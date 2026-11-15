-- Preserve unknown historical publication times instead of using the migration time.
ALTER TABLE published_notice_version ADD COLUMN published_at timestamptz;
ALTER TABLE published_notice_version ALTER COLUMN published_at SET DEFAULT statement_timestamp();
UPDATE published_notice_version v
SET published_at = a.first_pass
FROM (
    SELECT notice_id, revision, min(created_at) AS first_pass
    FROM review_audit WHERE basic_review = 'PASS' GROUP BY notice_id, revision
) a
WHERE v.notice_id = a.notice_id AND v.revision = a.revision;

UPDATE published_notice_version v
SET published_at = n.basic_checked_at
FROM notice n
WHERE v.published_at IS NULL AND n.basic_review = 'PASS' AND n.basic_checked_at IS NOT NULL
  AND v.notice_id = n.id AND v.version = n.version AND v.revision = n.revision;
