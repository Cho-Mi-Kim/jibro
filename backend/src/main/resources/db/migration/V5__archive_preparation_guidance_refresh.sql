CREATE TABLE preparation_history (
    id bigserial PRIMARY KEY,
    preparation_id uuid NOT NULL REFERENCES preparation(id) ON DELETE CASCADE,
    notice_version integer NOT NULL,
    snapshot jsonb NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (preparation_id, notice_version)
);
