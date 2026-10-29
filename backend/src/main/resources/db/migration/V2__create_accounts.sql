CREATE TABLE app_user (
 id uuid PRIMARY KEY, name varchar(30) NOT NULL, email varchar(254) NOT NULL UNIQUE,
 password_hash varchar(100), role varchar(10) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE login_identity (
 provider varchar(20) NOT NULL, subject varchar(255) NOT NULL,
 user_id uuid NOT NULL REFERENCES app_user(id), PRIMARY KEY(provider,subject)
);
CREATE INDEX login_identity_user_idx ON login_identity(user_id);
CREATE TABLE refresh_token (
 hash varchar(64) PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id),
 expires_at timestamptz NOT NULL, revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX refresh_token_user_idx ON refresh_token(user_id);
CREATE INDEX refresh_token_expiry_idx ON refresh_token(expires_at);
