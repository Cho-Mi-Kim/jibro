-- Exact automatic publication proof permits reusing unchanged primary evidence after a version advances.
ALTER TABLE automatic_notice_review ADD COLUMN content_fingerprint varchar(64);
ALTER TABLE automatic_notice_review ADD CONSTRAINT automatic_review_content_hash
    CHECK (content_fingerprint IS NULL OR content_fingerprint ~ '^[0-9a-f]{64}$');

CREATE TABLE notice_followup_queue (
    notice_id uuid PRIMARY KEY REFERENCES notice(id) ON DELETE CASCADE,
    next_check_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    active_run_id uuid,
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 20),
    last_bundle_hash varchar(64),
    last_result_revision integer,
    last_content_fingerprint varchar(64),
    last_policy varchar(50),
    last_outcome varchar(30),
    last_checked_at timestamptz
);
CREATE INDEX notice_followup_due ON notice_followup_queue(next_check_at,created_at);

-- Every attempt is retained. Evidence/assessment fields are written once before publication.
CREATE TABLE automatic_followup_review (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    lease_token uuid NOT NULL,
    source_revision integer NOT NULL,
    source_fingerprint varchar(64) NOT NULL,
    before_content_fingerprint varchar(64) NOT NULL,
    before_content jsonb NOT NULL,
    before_start_date date,
    before_end_date date,
    policy varchar(50) NOT NULL,
    state varchar(30) NOT NULL DEFAULT 'RUNNING'
        CHECK (state IN ('RUNNING','STAGED','PASS','PARTIAL','UNCHANGED','REVIEW_REQUIRED','FAILED','DEFERRED','SUPERSEDED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    checked_at timestamptz,
    finished_at timestamptz,
    primary_extraction_id uuid REFERENCES notice_extraction(id),
    primary_fingerprint varchar(64),
    original_content jsonb,
    bundle_hash varchar(64),
    sources jsonb,
    assessment jsonb,
    result_revision integer,
    result_content_fingerprint varchar(64),
    reason varchar(2000) NOT NULL DEFAULT '',
    CHECK (bundle_hash IS NULL OR bundle_hash ~ '^[0-9a-f]{64}$'),
    CHECK (primary_fingerprint IS NULL OR primary_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX automatic_followup_notice ON automatic_followup_review(notice_id,created_at DESC);
