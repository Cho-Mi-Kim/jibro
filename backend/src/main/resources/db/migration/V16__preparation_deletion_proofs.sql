CREATE TABLE preparation_deletion_proof (
    preparation_id uuid PRIMARY KEY REFERENCES preparation(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    revision integer NOT NULL,
    security_version integer NOT NULL,
    token_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);
CREATE INDEX preparation_deletion_proof_owner ON preparation_deletion_proof(user_id,created_at);
CREATE TABLE preparation_deletion_attempt (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    failures integer NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT now(),
    blocked_until timestamptz
);
