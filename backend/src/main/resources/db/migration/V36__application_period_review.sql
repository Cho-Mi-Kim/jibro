CREATE TABLE application_period_review (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES notice(id),
    extraction_id uuid NOT NULL REFERENCES notice_extraction(id),
    source_revision integer NOT NULL,
    result_revision integer NOT NULL,
    evidence_fingerprint text NOT NULL,
    policy text NOT NULL,
    decision text NOT NULL CHECK (decision IN ('APPLIED','HOLD','UNCHANGED')),
    result jsonb NOT NULL,
    checked_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(extraction_id,evidence_fingerprint,policy)
);
CREATE INDEX application_period_review_notice ON application_period_review(notice_id,checked_at DESC);
