ALTER TABLE notice_extraction ADD COLUMN retries_used integer NOT NULL DEFAULT 0 CHECK (retries_used BETWEEN 0 AND 2);
ALTER TABLE notice_extraction ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE notice_extraction ADD COLUMN claim_token uuid;
CREATE INDEX notice_extraction_due ON notice_extraction(next_attempt_at,created_at) WHERE status='QUEUED';
