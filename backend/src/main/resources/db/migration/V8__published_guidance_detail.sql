CREATE OR REPLACE FUNCTION notice_public_facts(n notice) RETURNS jsonb LANGUAGE sql AS $$
 SELECT jsonb_build_object('title',n.title,'agency',n.agency,'region',n.region,'type',n.support_type,
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
UPDATE published_notice_version v SET facts=notice_public_facts(n)
FROM notice n WHERE n.id=v.notice_id AND n.version=v.version AND n.revision=v.revision AND n.basic_review='PASS';
