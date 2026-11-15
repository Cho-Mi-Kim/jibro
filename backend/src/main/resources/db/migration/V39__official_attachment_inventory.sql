CREATE TABLE notice_file_inventory (
    notice_id uuid PRIMARY KEY REFERENCES notice(id) ON DELETE CASCADE,
    detail_url text NOT NULL,
    source_fingerprint text NOT NULL,
    manifest jsonb,
    next_check_at timestamptz NOT NULL DEFAULT now(),
    token uuid
);
CREATE INDEX notice_file_inventory_due ON notice_file_inventory(next_check_at);

INSERT INTO notice_file_inventory (notice_id, detail_url, source_fingerprint)
SELECT q.notice_id, q.official_url, n.source_fingerprint
FROM notice_correction_check q JOIN notice n ON n.id = q.notice_id
WHERE q.source_fingerprint = n.source_fingerprint;
