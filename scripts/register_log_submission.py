#!/usr/bin/env python3
"""
FFLogs parity 비교용 로그 제출 디렉토리를 SQLite 카탈로그에 등록한다.

원칙:
- 대용량 로그 본문은 파일로 보관
- 검색/비교용 메타데이터만 SQLite에 저장
"""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_DB_PATH = ROOT_DIR / "data" / "catalog" / "submissions.db"
DEFAULT_SCHEMA_PATH = ROOT_DIR / "db" / "submission_catalog.sql"

REQUIRED_FIELDS = {
    "submissionId",
    "submittedAt",
    "region",
    "clientLanguage",
    "zoneId",
    "encounterName",
    "difficulty",
    "partyJobs",
    "hasDotTicks",
}


def load_metadata(metadata_path: Path) -> dict:
    data = json.loads(metadata_path.read_text(encoding="utf-8"))
    missing = sorted(field for field in REQUIRED_FIELDS if field not in data)
    if missing:
        raise ValueError(f"metadata missing required fields: {', '.join(missing)}")
    if not isinstance(data["partyJobs"], list) or not data["partyJobs"]:
        raise ValueError("metadata.partyJobs must be a non-empty list")
    if not isinstance(data["zoneId"], int):
        raise ValueError("metadata.zoneId must be an integer")
    if not isinstance(data["hasDotTicks"], bool):
        raise ValueError("metadata.hasDotTicks must be a boolean")
    return data


def init_db(connection: sqlite3.Connection, schema_path: Path) -> None:
    schema_sql = schema_path.read_text(encoding="utf-8")
    connection.executescript(schema_sql)


def resolve_submission_paths(submission_dir: Path) -> tuple[Path, Path, Path | None]:
    combat_log_path = submission_dir / "combat.log"
    metadata_path = submission_dir / "metadata.json"
    mapping_path = submission_dir / "mapping.json"

    if not combat_log_path.exists():
        raise FileNotFoundError(f"combat log not found: {combat_log_path}")
    if not metadata_path.exists():
        raise FileNotFoundError(f"metadata not found: {metadata_path}")
    return combat_log_path, metadata_path, mapping_path if mapping_path.exists() else None


def upsert_submission(
    connection: sqlite3.Connection,
    metadata: dict,
    submission_dir: Path,
    combat_log_path: Path,
    metadata_path: Path,
    mapping_path: Path | None,
) -> None:
    connection.execute(
        """
        INSERT INTO submissions (
            submission_id,
            submitted_at,
            region,
            client_language,
            zone_id,
            encounter_name,
            difficulty,
            party_jobs_json,
            fflogs_report_url,
            fflogs_fight_id,
            pull_start_approx,
            has_dot_ticks,
            notes,
            storage_dir,
            combat_log_path,
            metadata_path,
            mapping_path,
            updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT(submission_id) DO UPDATE SET
            submitted_at = excluded.submitted_at,
            region = excluded.region,
            client_language = excluded.client_language,
            zone_id = excluded.zone_id,
            encounter_name = excluded.encounter_name,
            difficulty = excluded.difficulty,
            party_jobs_json = excluded.party_jobs_json,
            fflogs_report_url = excluded.fflogs_report_url,
            fflogs_fight_id = excluded.fflogs_fight_id,
            pull_start_approx = excluded.pull_start_approx,
            has_dot_ticks = excluded.has_dot_ticks,
            notes = excluded.notes,
            storage_dir = excluded.storage_dir,
            combat_log_path = excluded.combat_log_path,
            metadata_path = excluded.metadata_path,
            mapping_path = excluded.mapping_path,
            updated_at = CURRENT_TIMESTAMP
        """,
        (
            metadata["submissionId"],
            metadata["submittedAt"],
            metadata["region"],
            metadata["clientLanguage"],
            metadata["zoneId"],
            metadata["encounterName"],
            metadata["difficulty"],
            json.dumps(metadata["partyJobs"], ensure_ascii=False),
            metadata.get("fflogsReportUrl"),
            metadata.get("fflogsFightId"),
            metadata.get("pullStartApprox"),
            1 if metadata["hasDotTicks"] else 0,
            metadata.get("notes"),
            str(submission_dir.resolve()),
            str(combat_log_path.resolve()),
            str(metadata_path.resolve()),
            str(mapping_path.resolve()) if mapping_path else None,
        ),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Register an anonymized log submission in SQLite catalog")
    parser.add_argument("submission_dir", help="directory containing combat.log and metadata.json")
    parser.add_argument("--db", default=str(DEFAULT_DB_PATH), help="sqlite catalog path")
    parser.add_argument("--schema", default=str(DEFAULT_SCHEMA_PATH), help="schema SQL path")
    args = parser.parse_args()

    submission_dir = Path(args.submission_dir)
    db_path = Path(args.db)
    schema_path = Path(args.schema)

    combat_log_path, metadata_path, mapping_path = resolve_submission_paths(submission_dir)
    metadata = load_metadata(metadata_path)

    db_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(db_path)
    try:
        init_db(connection, schema_path)
        upsert_submission(connection, metadata, submission_dir, combat_log_path, metadata_path, mapping_path)
        connection.commit()
    finally:
        connection.close()

    print(f"registered submission: {metadata['submissionId']}")
    print(f"catalog: {db_path}")


if __name__ == "__main__":
    main()
