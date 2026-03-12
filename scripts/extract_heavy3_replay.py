#!/usr/bin/env python3
from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path


INPUT_PATH = Path("src/main/resources/Network_30007_20260211.2026.02.11.log")
OUTPUT_DIR = Path("src/test/resources/replay/raw")
WINDOW_START = "2026-02-11T20:01:24"
WINDOW_END = "2026-02-11T20:50:01"
IDLE_SECONDS = 30
SETUP_SECONDS = 20
PULL_INDEX = 1

FULL_TYPES = {"01", "02", "03", "11", "21", "22", "25", "26", "30"}


def parse_ts(value: str) -> datetime:
    head, rest = value.split(".", 1)
    frac, tz = rest.split("+", 1)
    frac = (frac[:6]).ljust(6, "0")
    return datetime.strptime(f"{head}.{frac}+{tz}", "%Y-%m-%dT%H:%M:%S.%f%z")


@dataclass
class PullWindow:
    start: datetime
    end: datetime
    damage_events: int


def load_lines(path: Path) -> list[str]:
    with path.open("r", encoding="utf-8", errors="ignore") as handle:
        return [line.rstrip("\n") for line in handle]


def detect_pulls(lines: list[str]) -> list[PullWindow]:
    pulls: list[PullWindow] = []
    last_damage_ts: datetime | None = None
    current_start: datetime | None = None
    current_events = 0

    for line in lines:
        parts = line.split("|")
        if len(parts) < 10:
            continue
        ts = parts[1]
        if not (WINDOW_START <= ts <= WINDOW_END):
            continue
        if parts[0] not in {"21", "22"}:
            continue

        try:
            actor_id = int(parts[2], 16)
            damage = int(parts[9], 16)
        except ValueError:
            continue

        if not (0x10000000 <= actor_id < 0x20000000):
            continue
        if damage <= 0:
            continue

        dt = parse_ts(ts)
        if last_damage_ts is None or (dt - last_damage_ts).total_seconds() > IDLE_SECONDS:
            if current_start is not None and last_damage_ts is not None:
                pulls.append(PullWindow(current_start, last_damage_ts, current_events))
            current_start = dt
            current_events = 0

        current_events += 1
        last_damage_ts = dt

    if current_start is not None and last_damage_ts is not None:
        pulls.append(PullWindow(current_start, last_damage_ts, current_events))

    return pulls


def build_actor_sets(lines: list[str], start: datetime, end: datetime) -> tuple[set[int], set[int], set[int], set[int]]:
    players: set[int] = set()
    pets: set[int] = set()
    bosses: set[int] = set()
    party_members: set[int] = set()

    for line in lines:
        parts = line.split("|")
        if len(parts) < 2:
            continue
        ts = parts[1]
        if not (start.isoformat()[:19] <= ts <= end.isoformat()[:19]):
            continue

        if parts[0] == "11":
            for member in parts[3:11]:
                if member:
                    party_members.add(int(member, 16))

        if parts[0] != "03" or len(parts) < 12:
            continue

        actor_id = int(parts[2], 16)
        owner_id = int(parts[6], 16) if parts[6] else 0
        max_hp = int(parts[11]) if parts[11].isdigit() else 0

        if 0x10000000 <= actor_id < 0x20000000:
            players.add(actor_id)
        elif owner_id in players or owner_id in party_members:
            pets.add(actor_id)
        elif max_hp >= 50_000_000:
            bosses.add(actor_id)

    players.update(party_members)
    return players, pets, bosses, party_members


def keep_full(parts: list[str]) -> bool:
    return parts[0] in FULL_TYPES


def keep_minimal(parts: list[str], players: set[int], pets: set[int], bosses: set[int]) -> bool:
    line_type = parts[0]
    if line_type in {"01", "02", "11"}:
        return True

    if line_type == "03" and len(parts) >= 12:
        actor_id = int(parts[2], 16)
        owner_id = int(parts[6], 16) if parts[6] else 0
        max_hp = int(parts[11]) if parts[11].isdigit() else 0
        return actor_id in players or owner_id in players or actor_id in pets or max_hp >= 50_000_000

    if line_type in {"21", "22"} and len(parts) > 9:
        actor_id = int(parts[2], 16)
        target_id = int(parts[6], 16)
        try:
            damage = int(parts[9], 16)
        except ValueError:
            return False
        return damage > 0 and (actor_id in players or actor_id in pets) and (target_id in bosses or target_id in players)

    if line_type == "25" and len(parts) > 2:
        return int(parts[2], 16) in players

    return False


def filter_lines(lines: list[str], start: datetime, end: datetime, mode: str, players: set[int], pets: set[int], bosses: set[int]) -> list[str]:
    result: list[str] = []
    start_key = start.isoformat()[:19]
    end_key = end.isoformat()[:19]
    for line in lines:
        parts = line.split("|")
        if len(parts) < 2:
            continue
        ts = parts[1]
        if not (start_key <= ts <= end_key):
            continue
        if mode == "full":
            if keep_full(parts):
                result.append(line)
        else:
            if keep_minimal(parts, players, pets, bosses):
                result.append(line)
    return result


def write_lines(path: Path, lines: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    lines = load_lines(INPUT_PATH)
    pulls = detect_pulls(lines)
    pull = pulls[PULL_INDEX - 1]
    setup_start = pull.start - timedelta(seconds=SETUP_SECONDS)
    players, pets, bosses, party_members = build_actor_sets(lines, setup_start, pull.end)

    full_lines = filter_lines(lines, setup_start, pull.end, "full", players, pets, bosses)
    minimal_lines = filter_lines(lines, setup_start, pull.end, "minimal", players, pets, bosses)

    base_name = f"heavy3_pull{PULL_INDEX}"
    full_path = OUTPUT_DIR / f"{base_name}_full.log"
    minimal_path = OUTPUT_DIR / f"{base_name}_minimal.log"
    summary_path = OUTPUT_DIR / f"{base_name}_summary.json"

    write_lines(full_path, full_lines)
    write_lines(minimal_path, minimal_lines)

    summary = {
        "source": str(INPUT_PATH),
        "pullIndex": PULL_INDEX,
        "windowStart": pull.start.isoformat(),
        "windowEnd": pull.end.isoformat(),
        "setupStart": setup_start.isoformat(),
        "durationSeconds": round((pull.end - pull.start).total_seconds(), 3),
        "damageEvents": pull.damage_events,
        "players": len(players),
        "pets": len(pets),
        "bosses": len(bosses),
        "partyMembers": len(party_members),
        "full": {
            "path": str(full_path),
            "lines": len(full_lines),
            "chars": sum(len(line) for line in full_lines),
            "types": dict(sorted(Counter(line.split("|", 1)[0] for line in full_lines).items())),
        },
        "minimal": {
            "path": str(minimal_path),
            "lines": len(minimal_lines),
            "chars": sum(len(line) for line in minimal_lines),
            "types": dict(sorted(Counter(line.split("|", 1)[0] for line in minimal_lines).items())),
        },
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
