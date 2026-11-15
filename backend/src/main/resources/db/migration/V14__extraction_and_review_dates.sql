ALTER TABLE notice ADD COLUMN basic_checked_at timestamptz;
ALTER TABLE notice ADD COLUMN guide_checked_at timestamptz;
ALTER TABLE notice ADD COLUMN collected_at timestamptz;
-- Only an audit for the current revision proves that the currently served data was reviewed.
UPDATE notice n SET basic_checked_at = a.created_at
FROM review_audit a WHERE a.notice_id=n.id AND a.revision=n.revision
AND a.basic_review='PASS' AND n.basic_review='PASS';
UPDATE notice n SET guide_checked_at = a.created_at
FROM review_audit a WHERE a.notice_id=n.id AND a.revision=n.revision
AND a.guide_review='PASS' AND n.guide_review='PASS';

CREATE TABLE notice_extraction (
 id uuid PRIMARY KEY, notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
 source_revision integer NOT NULL, source_fingerprint text,
 status varchar(20) NOT NULL CHECK(status IN ('QUEUED','RUNNING','READY','PARTIAL','FAILED','SUPERSEDED')),
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, finished_at timestamptz,
 message text NOT NULL DEFAULT '', sources jsonb NOT NULL DEFAULT '[]'::jsonb
);
CREATE UNIQUE INDEX notice_extraction_active ON notice_extraction(notice_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX notice_extraction_history ON notice_extraction(notice_id,created_at DESC);
