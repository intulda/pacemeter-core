#!/usr/bin/env python3
"""
Build src/main/resources/dot-attribution-catalog.json from ACT job definitions.

This keeps unknown-status DoT attribution mapping reproducible across environments.
"""

from __future__ import annotations

import argparse
import json
import re
import urllib.request
from pathlib import Path

BASE_URL = "https://raw.githubusercontent.com/ravahn/FFXIV_ACT_Plugin/master/Definitions"

# Only jobs currently used by unknown-status DoT attribution.
JOB_DEFINITION_FILES = {
    19: "Paladin.json",
    22: "Dragoon.json",
    24: "WhiteMage.json",
    28: "Scholar.json",
    30: "Ninja.json",
    33: "Astrologian.json",
    34: "Samurai.json",
    37: "Gunbreaker.json",
    40: "Sage.json",
}

# Some expansions expose valid application actions that are not direct 1:1 name matches.
EXTRA_APPLICATION_ACTIONS = {
    40: {0x5EFA, 0x5EF8},  # Eukrasian Dosis III + Dosis III
}

# Explicit status->action overrides where ACT definitions are insufficient.
STATUS_TO_ACTION_OVERRIDES = {
    0x0F2B: 0x9094,  # Scholar baneful impaction
}

# Auto-attack ids must not be used as DoT attribution action ids.
INVALID_DOT_ACTION_IDS = {0x7, 0x17}


def normalize_name(value: str) -> str:
    lowered = value.lower().strip()
    # keep only alnum for robust matching against minor punctuation/spacing drift
    return re.sub(r"[^a-z0-9]+", "", lowered)


def parse_hex_id(raw_id: str) -> int:
    return int(raw_id, 16)


def fetch_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=20) as response:
        payload = response.read().decode("utf-8")
    return json.loads(payload)


def parse_actions(definition: dict) -> dict[int, str]:
    result: dict[int, str] = {}
    for action_entry in definition.get("actions", []):
        for raw_id, raw_name in action_entry.items():
            if raw_id in {"damage", "heal", "buff", "cost", "cooldown"}:
                continue
            if not isinstance(raw_id, str) or not isinstance(raw_name, str):
                continue
            if not re.fullmatch(r"[0-9A-Fa-f]+", raw_id):
                continue
            result[parse_hex_id(raw_id)] = raw_name
            break
    return result


def parse_dot_statuses(definition: dict) -> dict[int, str]:
    result: dict[int, str] = {}
    for status_entry in definition.get("statuseffects", []):
        status_id_hex = None
        status_name = None
        timeproc_type = None
        for key, value in status_entry.items():
            if key == "timeproc" and isinstance(value, dict):
                timeproc_type = str(value.get("type", "")).lower()
            elif isinstance(key, str) and re.fullmatch(r"[0-9A-Fa-f]+", key):
                status_id_hex = key
                status_name = value
        if status_id_hex is None or not isinstance(status_name, str):
            continue
        if timeproc_type != "dot":
            continue
        result[parse_hex_id(status_id_hex)] = status_name
    return result


def pick_action_for_status(status_id: int, status_name: str, actions: dict[int, str]) -> int | None:
    if status_id in STATUS_TO_ACTION_OVERRIDES:
        return STATUS_TO_ACTION_OVERRIDES[status_id]

    normalized_status_name = normalize_name(status_name)
    candidates = [
        action_id
        for action_id, action_name in actions.items()
        if normalize_name(action_name) == normalized_status_name
        and action_id not in INVALID_DOT_ACTION_IDS
    ]
    if not candidates:
        return None
    return max(candidates)


def build_catalog() -> list[dict]:
    entries: list[dict] = []
    for job_id, definition_file in sorted(JOB_DEFINITION_FILES.items()):
        definition = fetch_json(f"{BASE_URL}/{definition_file}")
        actions = parse_actions(definition)
        dot_statuses = parse_dot_statuses(definition)

        status_to_action: dict[int, int] = {}
        for status_id, status_name in dot_statuses.items():
            action_id = pick_action_for_status(status_id, status_name, actions)
            if action_id is None:
                continue
            status_to_action[status_id] = action_id

        application_action_ids = set(status_to_action.values())
        application_action_ids.update(EXTRA_APPLICATION_ACTIONS.get(job_id, set()))

        entries.append({
            "job_id": job_id,
            "application_action_ids": sorted(application_action_ids),
            "status_ids": sorted(status_to_action.keys()),
            "status_to_action": [
                {"status_id": status_id, "action_id": status_to_action[status_id]}
                for status_id in sorted(status_to_action.keys())
            ],
        })
    return entries


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("src/main/resources/dot-attribution-catalog.json"),
    )
    args = parser.parse_args()

    catalog = build_catalog()
    args.output.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
