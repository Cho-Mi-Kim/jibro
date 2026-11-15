CREATE TABLE notice_supply_scope (
    notice_id uuid PRIMARY KEY REFERENCES notice(id) ON DELETE CASCADE,
    detail_url text NOT NULL,
    source_fingerprint text NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}',
    checked_at timestamptz,
    next_check_at timestamptz NOT NULL DEFAULT now(),
    token uuid
);
CREATE INDEX notice_supply_scope_due ON notice_supply_scope(next_check_at);

-- Use the same evidence map for publication and search. Each key occurs once per notice.
CREATE VIEW notice_supply_region AS
SELECT s.notice_id::text || '/' || r.region AS id, s.notice_id, s.source_fingerprint, r.region
FROM notice_supply_scope s CROSS JOIN LATERAL jsonb_object_keys(s.evidence) r(region)
WHERE s.evidence <> '{}'::jsonb;

INSERT INTO notice_supply_scope(notice_id,detail_url,source_fingerprint)
SELECT n.id,coalesce(q.official_url,n.content->'evidence'->>'url'),n.source_fingerprint
FROM notice n LEFT JOIN notice_correction_check q ON q.notice_id=n.id AND q.source_fingerprint=n.source_fingerprint
WHERE starts_with(n.source_key,'LH:') AND n.source_fingerprint IS NOT NULL
  AND starts_with(coalesce(q.official_url,n.content->'evidence'->>'url'),
                  'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?');
