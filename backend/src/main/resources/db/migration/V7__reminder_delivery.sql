ALTER TABLE alert_preferences ADD COLUMN email_enabled boolean NOT NULL DEFAULT false;
CREATE TABLE push_device (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 endpoint text NOT NULL UNIQUE, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX push_device_owner ON push_device(user_id);
CREATE TABLE alert_delivery (
 user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,event_key text NOT NULL,
 channel text NOT NULL, attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL,
 delivered boolean NOT NULL DEFAULT false, PRIMARY KEY(user_id,event_key,channel)
);
