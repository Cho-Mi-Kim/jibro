CREATE TABLE auth_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX auth_session_user ON auth_session(user_id);
CREATE INDEX auth_session_expiry ON auth_session(expires_at);

ALTER TABLE refresh_token ADD COLUMN session_id uuid REFERENCES auth_session(id) ON DELETE CASCADE;
CREATE INDEX refresh_token_session ON refresh_token(session_id);
-- Earlier tokens have no session binding and cannot support immediate logout.
UPDATE refresh_token SET revoked=true WHERE session_id IS NULL;

CREATE TABLE login_attempt (
    id uuid PRIMARY KEY,
    account_key char(64) NOT NULL,
    address_key char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    successful boolean
);
CREATE INDEX login_attempt_account ON login_attempt(account_key,created_at);
CREATE INDEX login_attempt_address ON login_attempt(address_key,created_at);
CREATE INDEX login_attempt_expiry ON login_attempt(created_at);
