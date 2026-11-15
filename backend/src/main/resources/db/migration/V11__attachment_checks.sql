CREATE TABLE attachment_check_run (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    actor_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
    notice_revision integer NOT NULL,
    status varchar(20) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    message varchar(1000) NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX attachment_check_one_running ON attachment_check_run(notice_id) WHERE status='RUNNING';
CREATE INDEX attachment_check_recent ON attachment_check_run(notice_id, started_at DESC);

CREATE TABLE attachment_fingerprint (
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    url_key char(64) NOT NULL,
    url varchar(2000) NOT NULL,
    sha256 char(64),
    byte_size bigint,
    media_type varchar(100),
    checked_at timestamptz,
    attempted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (notice_id, url_key)
);

CREATE TABLE attachment_check_result (
    run_id uuid NOT NULL REFERENCES attachment_check_run(id) ON DELETE CASCADE,
    ordinal integer NOT NULL,
    label varchar(300) NOT NULL,
    url varchar(2000) NOT NULL,
    status varchar(20) NOT NULL,
    message varchar(300) NOT NULL,
    previous_hash char(64),
    current_hash char(64),
    byte_size bigint,
    PRIMARY KEY (run_id, ordinal)
);
