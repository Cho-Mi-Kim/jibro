CREATE TABLE guide_source_version (
    version_key varchar(64) PRIMARY KEY,
    snapshot_json jsonb NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE guide_source_state (
    source_url varchar(2048) PRIMARY KEY,
    current_version varchar(64) REFERENCES guide_source_version(version_key),
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    next_check_at timestamptz NOT NULL,
    failure_count integer NOT NULL DEFAULT 0,
    error_code varchar(120),
    CHECK (status IN ('PENDING', 'AVAILABLE', 'UNREACHABLE', 'UNREADABLE'))
);

CREATE TABLE guide_entry_state (
    guide_id varchar(120) PRIMARY KEY,
    baseline_hash varchar(64) NOT NULL,
    published_json jsonb NOT NULL,
    verified_sources jsonb NOT NULL DEFAULT '{}'::jsonb,
    revision bigint NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'CURRENT',
    last_verified_at timestamptz,
    last_attempt_at timestamptz,
    last_review_fingerprint varchar(64),
    last_outcome varchar(64),
    next_review_at timestamptz,
    CHECK (status IN ('CURRENT', 'RECHECK', 'UNREACHABLE'))
);

CREATE TABLE guide_entry_history (
    id uuid PRIMARY KEY,
    guide_id varchar(120) NOT NULL,
    revision bigint NOT NULL,
    baseline_hash varchar(64) NOT NULL,
    before_json jsonb NOT NULL,
    after_json jsonb NOT NULL,
    source_versions jsonb NOT NULL,
    reason varchar(120) NOT NULL,
    proof_json jsonb NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX guide_entry_history_guide_idx ON guide_entry_history(guide_id, created_at DESC);

CREATE TABLE guide_refresh_lease (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    owner uuid,
    lease_until timestamptz,
    last_completed_at timestamptz,
    last_result jsonb
);
INSERT INTO guide_refresh_lease(singleton) VALUES(true);
