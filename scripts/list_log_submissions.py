#!/usr/bin/env python3
"""
SQLite 카탈로그에 등록된 로그 제출 목록을 출력한다.
"""

from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_DB_PATH = ROOT_DIR / "data" / "catalog" / "submissions.db"


def main() -> None:
    parser = argparse.ArgumentParser(description="List registered log submissions")
    parser.add_argument("--db", default=str(DEFAULT_DB_PATH), help="sqlite catalog path")
    parser.add_argument("--limit", type=int, default=20, help="max rows to print")
    args = parser.parse_args()

    connection = sqlite3.connect(Path(args.db))
    try:
        rows = connection.execute(
            """
            SELECT submission_id, submitted_at, region, client_language, zone_id, encounter_name, difficulty
            FROM submissions
            ORDER BY submitted_at DESC
            LIMIT ?
            """,
            (args.limit,),
        ).fetchall()
    finally:
        connection.close()

    for row in rows:
        print(" | ".join(str(value) for value in row))


if __name__ == "__main__":
    main()
