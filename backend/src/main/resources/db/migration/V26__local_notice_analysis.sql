CREATE TABLE notice_local_analysis (
    extraction_id uuid NOT NULL REFERENCES notice_extraction(id) ON DELETE CASCADE,
    extraction_fingerprint varchar(64) NOT NULL,
    policy varchar(100) NOT NULL,
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    source_revision integer NOT NULL,
    rule_book jsonb NOT NULL,
    packet_plan jsonb NOT NULL,
    coverage_issues jsonb NOT NULL,
    api_results jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (extraction_id, extraction_fingerprint, policy)
);
CREATE INDEX notice_local_analysis_notice ON notice_local_analysis(notice_id, created_at DESC);
