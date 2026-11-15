-- Keep the previously verified relationship when its notice revisions are explicitly rechecked.
ALTER TABLE notice_correction ADD COLUMN verification_history jsonb NOT NULL DEFAULT '[]'::jsonb
 CHECK (jsonb_typeof(verification_history) = 'array');
