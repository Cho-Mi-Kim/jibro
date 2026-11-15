CREATE UNIQUE INDEX login_identity_user_provider_unique ON login_identity(user_id, provider);

CREATE TABLE account_connection_proof (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL,
    security_version integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    failures integer NOT NULL DEFAULT 0
);

CREATE TABLE account_connection_intent (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider varchar(20) NOT NULL CHECK (provider IN ('google','kakao')),
    binding_hash char(64) NOT NULL,
    state_hash char(64),
    security_version integer NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX account_connection_intent_user_idx ON account_connection_intent(user_id);

CREATE TABLE account_connection_attempt (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    failures integer NOT NULL DEFAULT 0,
    blocked_until timestamptz
);
