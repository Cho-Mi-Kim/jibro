CREATE TABLE collection_provider_hold (
    provider varchar(40) PRIMARY KEY,
    hold_until timestamptz NOT NULL,
    source_run_id uuid REFERENCES collection_run(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
