#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOG_PATH = ROOT / "src/main/resources/Network_30007_20260211.2026.02.11.log"
KNOWN_EFFECTS_PATH = ROOT / "src/main/resources/raid-buff-effects.json"
CLASSIFICATIONS_PATH = ROOT / "src/main/resources/status-classifications.json"
DEFAULT_OUTPUT_PATH = ROOT / "build/reports/status-candidates.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scan ACT network logs and report status effects that look like raid-buff candidates."
    )
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG_PATH)
    parser.add_argument("--known-effects", type=Path, default=KNOWN_EFFECTS_PATH)
    parser.add_argument("--classifications", type=Path, default=CLASSIFICATIONS_PATH)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH)
    parser.add_argument("--top", type=int, default=80)
    return parser.parse_args()


def load_known_effects(path: Path) -> tuple[set[int], set[str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    known_ids: set[int] = set()
    known_aliases: set[str] = set()
    for entry in payload:
        for status_id in entry.get("ids", []):
            known_ids.add(int(status_id))
        for alias in entry.get("aliases", []):
            known_aliases.add(alias.strip().lower())
    return known_ids, known_aliases


def load_classifications(path: Path) -> dict[tuple[int, str], dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    result: dict[tuple[int, str], dict] = {}
    for entry in payload:
        key = (int(entry["status_id"]), entry["status_name"])
        result[key] = entry
    return result


def hex_to_int(value: str) -> int:
    if not value:
        return 0
    try:
        return int(value, 16)
    except ValueError:
        return 0


def is_player_actor(actor_id: int) -> bool:
    return 0x10000000 <= actor_id < 0x20000000


def make_entry(status_id: int, status_name: str) -> dict:
    return {
        "status_id": status_id,
        "status_name": status_name,
        "apply_count": 0,
        "remove_count": 0,
        "self_apply_count": 0,
        "external_apply_count": 0,
        "player_source_apply_count": 0,
        "player_target_apply_count": 0,
        "unique_sources": set(),
        "unique_targets": set(),
        "source_examples": set(),
        "target_examples": set(),
    }


def score_candidate(entry: dict) -> int:
    score = 0
    score += min(entry["external_apply_count"], 20)
    score += min(len(entry["unique_targets"]) * 2, 20)
    score += min(entry["player_source_apply_count"], 20)
    if len(entry["unique_targets"]) >= 4:
        score += 10
    if entry["external_apply_count"] >= 4:
        score += 10
    return score


def summarize(entry: dict, known_ids: set[int], known_aliases: set[str], classifications: dict[tuple[int, str], dict]) -> dict:
    status_name = entry["status_name"]
    status_id = entry["status_id"]
    is_known = status_id in known_ids or status_name.lower() in known_aliases
    classification = classifications.get((status_id, status_name))
    unique_sources = sorted(entry["unique_sources"])
    unique_targets = sorted(entry["unique_targets"])

    return {
        "status_id": status_id,
        "status_hex": format(status_id, "X"),
        "status_name": status_name,
        "known_effect": is_known,
        "classification": classification.get("classification") if classification else "unclassified",
        "category": classification.get("category") if classification else None,
        "note": classification.get("note") if classification else None,
        "candidate_score": score_candidate(entry),
        "apply_count": entry["apply_count"],
        "remove_count": entry["remove_count"],
        "self_apply_count": entry["self_apply_count"],
        "external_apply_count": entry["external_apply_count"],
        "player_source_apply_count": entry["player_source_apply_count"],
        "player_target_apply_count": entry["player_target_apply_count"],
        "unique_source_count": len(unique_sources),
        "unique_target_count": len(unique_targets),
        "source_examples": sorted(entry["source_examples"])[:8],
        "target_examples": sorted(entry["target_examples"])[:8],
    }


def main() -> int:
    args = parse_args()
    known_ids, known_aliases = load_known_effects(args.known_effects)
    classifications = load_classifications(args.classifications)

    entries: dict[tuple[int, str], dict] = {}
    with args.log.open("r", encoding="utf-8", errors="ignore") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("|")
            if len(parts) < 9 or parts[0] not in {"26", "30"}:
                continue

            status_id = hex_to_int(parts[2])
            status_name = parts[3]
            source_id = hex_to_int(parts[5])
            source_name = parts[6]
            target_id = hex_to_int(parts[7])
            target_name = parts[8]
            key = (status_id, status_name)
            entry = entries.setdefault(key, make_entry(status_id, status_name))

            if parts[0] == "26":
                entry["apply_count"] += 1
                if source_id == target_id and source_id != 0:
                    entry["self_apply_count"] += 1
                else:
                    entry["external_apply_count"] += 1
                if is_player_actor(source_id):
                    entry["player_source_apply_count"] += 1
                if is_player_actor(target_id):
                    entry["player_target_apply_count"] += 1
            else:
                entry["remove_count"] += 1

            if source_id != 0:
                entry["unique_sources"].add(source_id)
            if target_id != 0:
                entry["unique_targets"].add(target_id)
            if source_name:
                entry["source_examples"].add(source_name)
            if target_name:
                entry["target_examples"].add(target_name)

    summarized = [
        summarize(entry, known_ids, known_aliases, classifications)
        for entry in entries.values()
        if entry["apply_count"] > 0
    ]
    summarized.sort(
        key=lambda row: (
            row["classification"] == "include",
            row["known_effect"],
            row["candidate_score"],
            row["external_apply_count"],
            row["unique_target_count"],
            row["apply_count"],
        ),
        reverse=True,
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summarized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"[status-candidates] wrote {len(summarized)} entries to {args.output}")
    print("Top candidates:")
    for row in summarized[: args.top]:
        print(
            f"{row['candidate_score']:>3} "
            f"{row['status_hex']:>5} "
            f"{row['status_name']:<20} "
            f"class={row['classification']:<12} "
            f"known={str(row['known_effect']).lower():<5} "
            f"apply={row['apply_count']:<4} "
            f"external={row['external_apply_count']:<4} "
            f"targets={row['unique_target_count']:<3} "
            f"sources={row['unique_source_count']:<3}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
