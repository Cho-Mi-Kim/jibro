ALTER TABLE app_user ADD COLUMN trial_expires_at timestamptz;
CREATE INDEX app_user_trial_expiry_idx ON app_user(trial_expires_at) WHERE trial_expires_at IS NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT trial_account_has_no_credentials
    CHECK (trial_expires_at IS NULL OR (password_hash IS NULL AND role = 'USER' AND NOT email_verified));
