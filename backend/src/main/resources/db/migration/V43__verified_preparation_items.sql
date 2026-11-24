ALTER TABLE automatic_followup_review DROP CONSTRAINT automatic_followup_review_state_check;
ALTER TABLE automatic_followup_review ADD CONSTRAINT automatic_followup_review_state_check
    CHECK (state IN ('RUNNING','STAGED','PASS','PARTIAL','UNCHANGED','REVIEW_REQUIRED','INTAKE','ITEMS','FAILED','DEFERRED','SUPERSEDED'));
