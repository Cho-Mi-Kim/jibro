-- Published partial guidance keeps unresolved facts in the content and a distinct audit disposition.
ALTER TABLE notice_source_review DROP CONSTRAINT notice_source_review_disposition_check;
ALTER TABLE notice_source_review ADD CONSTRAINT notice_source_review_disposition_check
    CHECK (disposition IN ('PASS','PARTIAL','HOLD','UNSUPPORTED'));
