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


NAME_PATTERN = re.compile(r"(?<=\|)([^|]{2,24})\|")


def anonymize_lines(lines: list[str]) -> tuple[list[str], dict[str, str]]:
    alias_map: dict[str, str] = {}
    next_id = 1
    output: list[str] = []

    for line in lines:
        if "|" not in line:
            output.append(line)
            continue

        parts = line.rstrip("\n").split("|")
        replaced = []
        for part in parts:
            if is_probable_name(part):
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
    if value.startswith("0x"):
        return False
    if value.isdigit():
        return False
    if any(ch in value for ch in ("=", "{", "}", "[", "]", "/", "\\")):
        return False
    if value.lower() in {"logline", "combatdata", "changeparty", "changezone"}:
        return False
    return " " in value or NAME_PATTERN.fullmatch(f"|{value}|") is not None


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
