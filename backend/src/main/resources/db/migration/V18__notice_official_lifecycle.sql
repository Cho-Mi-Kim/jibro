-- Explicit official lifecycle never overwrites date-derived or publication status.
ALTER TABLE notice
 ADD COLUMN lifecycle_state varchar(20) NOT NULL DEFAULT 'NORMAL'
   CHECK (lifecycle_state IN ('NORMAL','EARLY_CLOSED','CANCELLED')),
 ADD COLUMN lifecycle_reason text NOT NULL DEFAULT '',
 ADD COLUMN lifecycle_revision integer NOT NULL DEFAULT 0 CHECK (lifecycle_revision >= 0),
 ADD COLUMN lifecycle_evidence jsonb,
 ADD COLUMN lifecycle_checked_at timestamptz;

CREATE TABLE notice_lifecycle_audit (
 id bigserial PRIMARY KEY,
 notice_id uuid NOT NULL REFERENCES notice(id),
 actor_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
 notice_revision integer NOT NULL,
 lifecycle_revision integer NOT NULL,
 previous_state varchar(20) NOT NULL,
 state varchar(20) NOT NULL,
 reason text NOT NULL,
 evidence jsonb NOT NULL,
 changed_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(notice_id, lifecycle_revision)
);

CREATE OR REPLACE FUNCTION notice_public_facts(n notice) RETURNS jsonb LANGUAGE sql AS $$
 SELECT jsonb_build_object('title',n.title,'agency',n.agency,'region',n.region,'type',n.support_type,
    'lifecycle',jsonb_build_object('state',n.lifecycle_state,'reason',n.lifecycle_reason,
        'evidence',n.lifecycle_evidence,'checkedAt',n.lifecycle_checked_at,'revision',n.lifecycle_revision),
    'publishedDate',n.published_date,'startDate',n.start_date,'endDate',n.end_date,
    'summary',n.content->>'summary','source',n.content->'evidence'->>'url',
    'guidance',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN '제공' ELSE '미제공 또는 검수 중' END,
    'questions',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',q->>'title',q->>'reason',q->'evidence'->>'label',
        (SELECT string_agg(o->>'label',', ') FROM jsonb_array_elements(coalesce(nullif(q->'options','null'),'[]')) o))),'[]')
       FROM jsonb_array_elements(n.content->'questions') q) ELSE '[]'::jsonb END,
    'documents',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',d->>'title',d->>'method',d->>'issuer',d->>'issueUrl',d->'evidence'->>'label',
         CASE WHEN d->>'conditionQuestionId' IS NOT NULL THEN concat('조건 ',d->>'conditionQuestionId',' ',d->>'conditionAnswerValues') END)),'[]')
       FROM jsonb_array_elements(n.content->'documents') d) ELSE '[]'::jsonb END,
    'schedules',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',s->>'title',s->>'date',s->'evidence'->>'label')),'[]') FROM jsonb_array_elements(n.content->'schedules') s)
      ELSE '[]'::jsonb END);
$$;
