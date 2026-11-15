-- Preserve the query that earlier queued/running attempts actually used.
ALTER TABLE collection_run ADD COLUMN category_codes varchar(20) NOT NULL DEFAULT '06';
ALTER TABLE collection_run ADD COLUMN scope_index integer NOT NULL DEFAULT 0 CHECK (scope_index BETWEEN 0 AND 2);
ALTER TABLE collection_run ADD COLUMN query_from date;
UPDATE collection_run SET query_from=(query_date - INTERVAL '3 months')::date;
ALTER TABLE collection_run ALTER COLUMN query_from SET NOT NULL;
ALTER TABLE collection_run ADD CONSTRAINT collection_query_dates_valid CHECK (query_from <= query_date);

ALTER TABLE collection_response_attempt ADD COLUMN category_code varchar(2) NOT NULL DEFAULT '06';
ALTER TABLE collection_response_attempt ADD COLUMN published_from date;
ALTER TABLE collection_response_attempt ADD COLUMN published_through date;
UPDATE collection_response_attempt SET published_from=(query_date - INTERVAL '3 months')::date, published_through=query_date;
ALTER TABLE collection_response_attempt ALTER COLUMN published_from SET NOT NULL;
ALTER TABLE collection_response_attempt ALTER COLUMN published_through SET NOT NULL;
ALTER TABLE collection_response_attempt DROP CONSTRAINT collection_response_attempt_run_id_lease_token_page_key;
ALTER TABLE collection_response_attempt ADD CONSTRAINT collection_response_scope_page_unique UNIQUE (run_id, lease_token, category_code, page);

CREATE TABLE collection_page_checkpoint (
    run_id uuid NOT NULL REFERENCES collection_run(id) ON DELETE CASCADE,
    scope_index integer NOT NULL CHECK (scope_index BETWEEN 0 AND 1),
    page integer NOT NULL CHECK (page BETWEEN 1 AND 500),
    item_signature varchar(64) NOT NULL CHECK (item_signature ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (run_id, scope_index, page),
    UNIQUE (run_id, scope_index, item_signature)
);
COMMENT ON TABLE collection_page_checkpoint IS 'Completed page identity per persisted collection scope; detects repeated provider pages without storing URLs or keys.';
