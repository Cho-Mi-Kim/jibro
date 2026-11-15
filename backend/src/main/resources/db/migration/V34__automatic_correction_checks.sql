-- Official history can connect several earlier versions directly to the latest correction.
ALTER TABLE notice_correction DROP CONSTRAINT notice_correction_corrected_notice_id_key;
CREATE INDEX notice_correction_target_idx ON notice_correction(corrected_notice_id);

CREATE TABLE notice_correction_check (
 notice_id uuid PRIMARY KEY REFERENCES notice(id) ON DELETE CASCADE,
 official_url text NOT NULL,
 identity_key text NOT NULL,
 source_fingerprint text NOT NULL,
 state varchar(20) NOT NULL DEFAULT 'PENDING',
 token uuid,
 lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 checked_at timestamptz,
 attempts integer NOT NULL DEFAULT 0,
 result_code varchar(60) NOT NULL DEFAULT 'COLLECTED',
 CHECK (state IN ('PENDING','RUNNING','LINKED','UNCHANGED','DEFERRED'))
);
CREATE INDEX notice_correction_check_due_idx ON notice_correction_check(next_attempt_at);
CREATE INDEX notice_correction_check_identity_idx ON notice_correction_check(identity_key);
