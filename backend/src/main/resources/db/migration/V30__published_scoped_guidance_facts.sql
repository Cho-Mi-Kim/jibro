-- New public revisions retain uncertainty alongside the confirmed guidance.
-- Historical public facts and personal preparation snapshots are intentionally not rewritten.
CREATE OR REPLACE FUNCTION notice_public_facts(n notice) RETURNS jsonb LANGUAGE sql AS $$
 WITH scope AS (
   SELECT n.guide_review='PASS' AND NOT n.change_review AS available,
     coalesce(nullif(n.content->'reviewIssues','null'::jsonb),'[]'::jsonb) AS issues
 )
 SELECT jsonb_build_object('title',n.title,'agency',n.agency,'region',n.region,'type',n.support_type,
    'lifecycle',jsonb_build_object('state',n.lifecycle_state,'reason',n.lifecycle_reason,
        'evidence',n.lifecycle_evidence,'checkedAt',n.lifecycle_checked_at,'revision',n.lifecycle_revision),
    'publishedDate',n.published_date,'startDate',n.start_date,'endDate',n.end_date,
    'summary',n.content->>'summary','source',n.content->'evidence'->>'url',
    'guidance',CASE WHEN scope.available THEN
        CASE WHEN jsonb_array_length(scope.issues)>0 THEN '제공 · 일부 확인 필요' ELSE '제공' END
      ELSE '미제공 또는 검수 중' END,
    'questions',CASE WHEN scope.available THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',
        CASE WHEN q->>'reviewRequired'='true' OR EXISTS (
            SELECT 1 FROM jsonb_array_elements(scope.issues) issue WHERE issue->'questionIds' ? (q->>'id'))
          THEN '[확인 필요]' END,
        q->>'title',q->>'reason',q->'evidence'->>'label',
        (SELECT string_agg(o->>'label',', ') FROM jsonb_array_elements(coalesce(nullif(q->'options','null'),'[]')) o))),'[]')
       FROM jsonb_array_elements(n.content->'questions') q) ELSE '[]'::jsonb END,
    'documents',CASE WHEN scope.available THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',
        CASE WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(scope.issues) issue
          WHERE issue->'documentIds' ? (d->>'id') OR issue->'questionIds' ? (d->>'conditionQuestionId'))
          THEN '[확인 필요]' END,
        d->>'title',d->>'method',d->>'issuer',d->>'issueUrl',d->'evidence'->>'label',
        CASE WHEN d->>'conditionQuestionId' IS NOT NULL THEN concat('조건 ',d->>'conditionQuestionId',' ',d->>'conditionAnswerValues') END)),'[]')
       FROM jsonb_array_elements(n.content->'documents') d) ELSE '[]'::jsonb END,
    'schedules',CASE WHEN scope.available THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',s->>'title',s->>'date',s->'evidence'->>'label')),'[]')
       FROM jsonb_array_elements(n.content->'schedules') s) ELSE '[]'::jsonb END,
    'reviewIssues',CASE WHEN scope.available THEN
      (SELECT coalesce(jsonb_agg(concat_ws(' · ',issue->>'title',issue->>'detail',
        (SELECT string_agg(concat_ws(' · ',e->>'label',e->>'url',e->>'checkedAt'),' / ')
         FROM jsonb_array_elements(coalesce(issue->'evidence','[]')) e))),'[]')
       FROM jsonb_array_elements(scope.issues) issue) ELSE '[]'::jsonb END)
 FROM scope;
$$;
