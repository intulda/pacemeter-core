#!/usr/bin/env python3
"""
ACT 네트워크 로그에서 캐릭터명을 익명화한다.

용도:
- FFLogs parity 비교용 로그 공유
- 사용자 식별 정보 최소화

주의:
- 원본 로그는 보존하고, 제출용 복사본에만 사용한다.
- 완전한 개인정보 제거를 보장하지는 않으므로 결과는 한 번 검토한다.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def anonymize_lines(lines: list[str]) -> tuple[list[str], dict[str, str]]:
    alias_map: dict[str, str] = {}
    next_id = 1
    output: list[str] = []

    for line in lines:
        if "|" not in line:
            output.append(line)
            continue

        parts = line.rstrip("\n").split("|")
        line_type = parts[0]
        replaced = []
        for index, part in enumerate(parts):
            if should_anonymize_field(line_type, index, part):
                alias = alias_map.get(part)
                if alias is None:
                    alias = f"Player{next_id:02d}"
                    alias_map[part] = alias
                    next_id += 1
                replaced.append(alias)
            else:
                replaced.append(part)
        output.append("|".join(replaced))

    return output, alias_map


def is_probable_name(value: str) -> bool:
    if not value or len(value) < 2 or len(value) > 24:
        return False
    if value.isdigit():
        return False
    if all(ch in "0123456789ABCDEFabcdef" for ch in value):
        return False
    if any(ch in value for ch in ("=", "{", "}", "[", "]", "/", "\\")):
        return False
    if value.lower() in {"logline", "combatdata", "changeparty", "changezone", "dot", "hot"}:
        return False
    return True


def should_anonymize_field(line_type: str, index: int, value: str) -> bool:
    if not is_probable_name(value):
        return False

    name_fields_by_type = {
        "00": {4},
        "01": {3},
        "02": {3},
        "03": {3, 7},
        "04": {3, 7},
        "11": set(),
        "21": {3, 5, 7},
        "22": {3, 5, 7},
        "24": {3, 4, 18},
        "25": {3},
        "26": {3, 6, 8},
        "27": {3, 6, 8},
        "28": {3, 6, 8},
        "29": {3, 6, 8},
        "30": {3, 6, 8},
        "31": {3, 6, 8},
        "37": {3},
        "38": {3},
        "39": {3},
        "261": set(),
        "264": set(),
        "270": {3},
    }

    name_fields = name_fields_by_type.get(line_type)
    if name_fields is None:
        return False
    return index in name_fields


def main() -> None:
    parser = argparse.ArgumentParser(description="Anonymize ACT log names for sharing")
    parser.add_argument("input", help="input log file")
    parser.add_argument("output", help="output anonymized log file")
    parser.add_argument(
        "--mapping-output",
        help="optional JSON file to save original->alias mapping",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)

    lines = input_path.read_text(encoding="utf-8").splitlines()
    anonymized, alias_map = anonymize_lines(lines)

    output_path.write_text("\n".join(anonymized) + "\n", encoding="utf-8")

    if args.mapping_output:
        mapping_path = Path(args.mapping_output)
        mapping_path.write_text(
            json.dumps(alias_map, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    print(f"anonymized lines: {len(anonymized)}")
    print(f"mapped names: {len(alias_map)}")


if __name__ == "__main__":
    main()
