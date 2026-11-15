CREATE TABLE evidence_location_resolution (
    file_id varchar(20) NOT NULL,
    baseline_sha char(64) NOT NULL,
    part_digest char(64) NOT NULL,
    current_sha char(64) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'MATCH', 'REVIEW')),
    current_part integer,
    current_location text,
    checked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(file_id, baseline_sha, part_digest),
    CHECK ((state = 'MATCH' AND current_part IS NOT NULL AND current_location IS NOT NULL)
        OR (state <> 'MATCH' AND current_part IS NULL AND current_location IS NULL))
);
CREATE INDEX evidence_location_resolution_pending ON evidence_location_resolution(state) WHERE state = 'PENDING';
