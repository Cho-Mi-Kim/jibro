-- A correction joins distinct official notices; it never rewrites their content or private preparations.
CREATE TABLE notice_correction (
 original_notice_id uuid PRIMARY KEY REFERENCES notice(id),
 corrected_notice_id uuid NOT NULL UNIQUE REFERENCES notice(id),
 original_revision integer NOT NULL CHECK (original_revision > 0),
 corrected_revision integer NOT NULL CHECK (corrected_revision > 0),
 reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
 evidence jsonb NOT NULL,
 checked_at timestamptz NOT NULL,
 CHECK (original_notice_id <> corrected_notice_id)
);
