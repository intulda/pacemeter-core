#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EFFECTS_PATH = ROOT / "src/main/resources/raid-buff-effects.json"
OUTPUT_PATH = ROOT / "src/main/resources/raid-buff-catalog.json"
XIVAPI_SHEET_URL = "https://v2.xivapi.com/api/sheet/Status/{status_id}?fields=Name,Description&language=en"
USER_AGENT = "paceMeter/0.1 (raid-buff-catalog-builder)"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build raid-buff-catalog.json from the local effect map and optional XIVAPI metadata."
    )
    parser.add_argument("--effects", type=Path, default=EFFECTS_PATH)
    parser.add_argument("--output", type=Path, default=OUTPUT_PATH)
    parser.add_argument(
        "--skip-xivapi",
        action="store_true",
        help="Do not fetch metadata from XIVAPI. Only copy the local effect map.",
    )
    return parser.parse_args()


def load_json(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def fetch_status_metadata(status_id: int) -> dict | None:
    url = XIVAPI_SHEET_URL.format(status_id=status_id)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=10) as response:
        payload = json.load(response)

    fields = payload.get("fields") or {}
    if not fields:
        return None

    return {
        "id": payload.get("row_id", status_id),
        "name_en": fields.get("Name"),
        "description_en": fields.get("Description"),
        "source": url,
    }


def enrich_entry(entry: dict, skip_xivapi: bool) -> dict:
    ids = entry.get("ids") or []
    metadata = []
    if not skip_xivapi:
        for status_id in ids:
            try:
                status = fetch_status_metadata(status_id)
            except Exception as exc:  # pragma: no cover - network path
                print(f"[raid-buff-catalog] failed to fetch XIVAPI status {status_id}: {exc}", file=sys.stderr)
                status = None
            if status:
                metadata.append(status)

    enriched = dict(entry)
    if metadata:
        aliases = {alias.lower() for alias in entry.get("aliases", [])}
        for row in metadata:
            name_en = (row.get("name_en") or "").lower()
            if name_en and name_en not in aliases:
                print(
                    f"[raid-buff-catalog] warning: metadata name '{row['name_en']}' "
                    f"does not match aliases {entry.get('aliases', [])} for ids {ids}",
                    file=sys.stderr,
                )
        enriched["metadata"] = {"xivapi": metadata}
        enriched["source"] = "manual-effect-map+xivapi"
    return enriched


def main() -> int:
    args = parse_args()
    entries = load_json(args.effects)
    enriched = [enrich_entry(entry, args.skip_xivapi) for entry in entries]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        json.dump(enriched, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"[raid-buff-catalog] wrote {len(enriched)} entries to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
