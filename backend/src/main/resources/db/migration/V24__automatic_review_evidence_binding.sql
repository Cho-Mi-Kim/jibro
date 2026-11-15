ALTER TABLE automatic_notice_review ADD COLUMN extraction_fingerprint varchar(64);
DROP INDEX automatic_notice_review_guidance;
CREATE UNIQUE INDEX automatic_notice_review_guidance ON automatic_notice_review(extraction_id,extraction_fingerprint,policy) WHERE phase='GUIDANCE';
-- Explicit retries within one transaction must still have an unambiguous newest source snapshot.
ALTER TABLE notice_extraction ALTER COLUMN created_at SET DEFAULT clock_timestamp();
