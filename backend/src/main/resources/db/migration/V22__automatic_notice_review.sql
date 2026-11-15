ALTER TABLE notice ADD COLUMN automatic_review_blocked boolean NOT NULL DEFAULT false;
-- Existing hand-authored guidance must never be opted into machine replacement.
UPDATE notice SET automatic_review_blocked=true WHERE jsonb_array_length(content->'questions')>0;
CREATE TABLE automatic_notice_review (
    id bigserial PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    source_revision integer NOT NULL,
    applied_revision integer NOT NULL,
    extraction_id uuid REFERENCES notice_extraction(id),
    policy varchar(50) NOT NULL,
    phase varchar(20) NOT NULL CHECK (phase IN ('BASIC','GUIDANCE')),
    decision varchar(20) NOT NULL CHECK (decision IN ('PASS','HOLD')),
    reason text NOT NULL,
    source_fingerprint text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX automatic_notice_review_latest ON automatic_notice_review(notice_id,created_at DESC);
CREATE UNIQUE INDEX automatic_notice_review_basic ON automatic_notice_review(notice_id,source_revision,policy) WHERE phase='BASIC';
CREATE UNIQUE INDEX automatic_notice_review_guidance ON automatic_notice_review(extraction_id,policy) WHERE phase='GUIDANCE';
