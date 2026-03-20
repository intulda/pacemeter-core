#!/usr/bin/env python3
"""
Build src/main/resources/action-name-catalog.json from ACT plugin definitions.

The catalog is used as a runtime fallback for action names not covered by
hand-maintained ActionNameLibrary mappings.
"""

from __future__ import annotations

import argparse
import json
import re
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

CONTENTS_URL = "https://api.github.com/repos/ravahn/FFXIV_ACT_Plugin/contents/Definitions"
RAW_BASE_URL = "https://raw.githubusercontent.com/ravahn/FFXIV_ACT_Plugin/master/Definitions"

SKIP_FILES = {
    "LanguageNamesDictionary.json",
}


def fetch_json(url: str):
    with urllib.request.urlopen(url, timeout=30) as response:
        payload = response.read().decode("utf-8")
    try:
        return json.loads(payload)
    except json.JSONDecodeError:
        # Some ACT definition files contain trailing commas.
        sanitized = re.sub(r",(\s*[}\]])", r"\1", payload)
        return json.loads(sanitized)


def is_hex_id(value: str) -> bool:
    return bool(re.fullmatch(r"[0-9A-Fa-f]+", value))


def normalize_name(name: str) -> str:
    return name.strip().lower()


def name_quality(name: str) -> tuple[int, int]:
    lowered = name.lower()
    if lowered.startswith("unknown_"):
        return (0, 0)
    if lowered.startswith("item_"):
        return (1, 0)
    return (2, len(name))


def list_definition_files() -> list[str]:
    payload = fetch_json(CONTENTS_URL)
    files = []
    for entry in payload:
        if entry.get("type") != "file":
            continue
        name = entry.get("name", "")
        if not name.endswith(".json") or name in SKIP_FILES:
            continue
        files.append(name)
    return sorted(files)


def extract_actions(definition: dict) -> list[tuple[int, str]]:
    results: list[tuple[int, str]] = []
    for action_entry in definition.get("actions", []):
        if not isinstance(action_entry, dict):
            continue
        for raw_id, raw_name in action_entry.items():
            if raw_id in {"damage", "heal", "buff", "cost", "cooldown"}:
                continue
            if not isinstance(raw_id, str) or not isinstance(raw_name, str):
                continue
            if not is_hex_id(raw_id):
                continue
            name = normalize_name(raw_name)
            if not name:
                continue
            results.append((int(raw_id, 16), name))
            break
    return results


def build_catalog() -> dict[str, str]:
    files = list_definition_files()
    name_votes: dict[int, Counter[str]] = defaultdict(Counter)

    for definition_file in files:
        definition = fetch_json(f"{RAW_BASE_URL}/{definition_file}")
        for action_id, action_name in extract_actions(definition):
            name_votes[action_id][action_name] += 1

    catalog: dict[str, str] = {}
    for action_id in sorted(name_votes):
        candidates = name_votes[action_id]
        chosen = sorted(
            candidates.items(),
            key=lambda item: (
                name_quality(item[0])[0],   # prefer non-unknown, non-item names
                item[1],                    # prefer frequently observed names
                name_quality(item[0])[1],   # slightly prefer descriptive names
            ),
            reverse=True,
        )[0][0]
        catalog[f"{action_id:04X}"] = chosen
    return catalog


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("src/main/resources/action-name-catalog.json"),
    )
    args = parser.parse_args()

    catalog = build_catalog()
    args.output.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {args.output} ({len(catalog)} actions)")


if __name__ == "__main__":
    main()
