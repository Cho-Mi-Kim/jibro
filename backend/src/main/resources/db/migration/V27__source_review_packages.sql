-- Source-bound editorial review is distinct from administrator and automatic rule decisions.
CREATE TABLE notice_source_review (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES notice(id),
    source_revision integer NOT NULL,
    result_revision integer NOT NULL,
    disposition varchar(20) NOT NULL CHECK (disposition IN ('PASS','HOLD','UNSUPPORTED')),
    package_fingerprint varchar(64) NOT NULL UNIQUE,
    reviewer_kind varchar(50) NOT NULL CHECK (reviewer_kind='AI_ASSISTED_SOURCE_REVIEW'),
    payload jsonb NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notice_source_review_notice ON notice_source_review(notice_id,reviewed_at DESC);
