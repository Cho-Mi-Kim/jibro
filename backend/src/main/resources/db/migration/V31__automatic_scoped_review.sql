-- Automatic partial publication is available guidance, not a claim that every official fact is resolved.
ALTER TABLE automatic_notice_review DROP CONSTRAINT automatic_notice_review_decision_check;
ALTER TABLE automatic_notice_review ADD CONSTRAINT automatic_notice_review_decision_check
    CHECK (decision IN ('PASS','PARTIAL','HOLD'));
ALTER TABLE automatic_notice_review ADD COLUMN review_issues jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE automatic_notice_review ADD CONSTRAINT automatic_notice_review_scope_check
    CHECK (jsonb_typeof(review_issues) = 'array'
        AND ((decision = 'PARTIAL' AND phase = 'GUIDANCE' AND jsonb_array_length(review_issues) > 0)
            OR (decision <> 'PARTIAL' AND jsonb_array_length(review_issues) = 0)));
