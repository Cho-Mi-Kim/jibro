CREATE TABLE collection_response_payload (
    sha256 varchar(64) PRIMARY KEY CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    body_json text NOT NULL CHECK (octet_length(body_json) <= 5000000),
    first_received_at timestamptz NOT NULL,
    CHECK (jsonb_typeof(body_json::jsonb) IN ('object', 'array'))
);

CREATE TABLE collection_response_attempt (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES collection_run(id) ON DELETE CASCADE,
    lease_token uuid NOT NULL,
    run_attempt integer NOT NULL CHECK (run_attempt > 0),
    provider varchar(20) NOT NULL DEFAULT 'LH',
    query_date date NOT NULL,
    page integer NOT NULL CHECK (page BETWEEN 1 AND 500),
    requested_at timestamptz NOT NULL,
    received_at timestamptz,
    completed_at timestamptz,
    http_status integer CHECK (http_status BETWEEN 100 AND 599),
    sha256 varchar(64) REFERENCES collection_response_payload(sha256),
    redacted boolean NOT NULL DEFAULT false,
    body_status varchar(30) NOT NULL DEFAULT 'NO_RESPONSE'
        CHECK (body_status IN ('NO_RESPONSE','JSON','INVALID_JSON','TOO_LARGE','UNAVAILABLE')),
    outcome varchar(30) NOT NULL DEFAULT 'REQUESTED'
        CHECK (outcome IN ('REQUESTED','ACCEPTED','REJECTED','TRANSPORT_FAILED')),
    UNIQUE (run_id, lease_token, page)
);
CREATE INDEX collection_response_attempt_run ON collection_response_attempt(run_id, requested_at, page);
COMMENT ON TABLE collection_response_payload IS 'Credential-redacted official API JSON, content-addressed; not document attachment storage.';
