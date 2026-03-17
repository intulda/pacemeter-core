CREATE TABLE IF NOT EXISTS submissions (
    submission_id TEXT PRIMARY KEY,
    submitted_at TEXT NOT NULL,
    region TEXT NOT NULL,
    client_language TEXT NOT NULL,
    zone_id INTEGER NOT NULL,
    encounter_name TEXT NOT NULL,
    difficulty TEXT NOT NULL,
    party_jobs_json TEXT NOT NULL,
    fflogs_report_url TEXT,
    fflogs_fight_id INTEGER,
    pull_start_approx TEXT,
    has_dot_ticks INTEGER NOT NULL DEFAULT 0,
    notes TEXT,
    storage_dir TEXT NOT NULL,
    combat_log_path TEXT NOT NULL,
    metadata_path TEXT NOT NULL,
    mapping_path TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_submissions_zone_id ON submissions(zone_id);
CREATE INDEX IF NOT EXISTS idx_submissions_region ON submissions(region);
CREATE INDEX IF NOT EXISTS idx_submissions_language ON submissions(client_language);
CREATE INDEX IF NOT EXISTS idx_submissions_difficulty ON submissions(difficulty);
CREATE INDEX IF NOT EXISTS idx_submissions_submitted_at ON submissions(submitted_at);
