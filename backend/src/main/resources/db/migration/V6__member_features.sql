CREATE TABLE saved_notice (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    saved_version integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, notice_id)
);
CREATE TABLE alert_preferences (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    enabled boolean NOT NULL, lead_days integer NOT NULL CHECK (lead_days IN (1,3,7)),
    include_saved boolean NOT NULL
);
CREATE TABLE alert_read (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    event_key text NOT NULL, PRIMARY KEY (user_id,event_key)
);
ALTER TABLE preparation ADD COLUMN archived_at timestamptz;
ALTER TABLE preparation ADD COLUMN takeaway varchar(500) NOT NULL DEFAULT '';
CREATE TABLE experience_reference (
    target_id uuid NOT NULL REFERENCES preparation(id) ON DELETE CASCADE,
    source_id uuid NOT NULL REFERENCES preparation(id) ON DELETE CASCADE,
    PRIMARY KEY(target_id,source_id), CHECK(target_id<>source_id)
);
ALTER TABLE app_user ADD COLUMN security_version integer NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN disabled boolean NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
ALTER TABLE app_user ADD COLUMN interest_region varchar(50) NOT NULL DEFAULT '';
CREATE TABLE account_ticket (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    purpose varchar(12) NOT NULL, token_hash char(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    PRIMARY KEY(user_id,purpose)
);

CREATE TABLE published_notice_version (
    notice_id uuid NOT NULL REFERENCES notice(id) ON DELETE CASCADE,
    version integer NOT NULL, revision integer NOT NULL, facts jsonb NOT NULL,
    PRIMARY KEY(notice_id,version,revision)
);
CREATE FUNCTION notice_public_facts(n notice) RETURNS jsonb LANGUAGE sql AS $$
  SELECT jsonb_build_object('title',n.title,'agency',n.agency,'region',n.region,'type',n.support_type,
    'publishedDate',n.published_date,'startDate',n.start_date,'endDate',n.end_date,
    'summary',n.content->>'summary','source',n.content->'evidence'->>'url',
    'guidance',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN '제공' ELSE '미제공 또는 검수 중' END,
    'questions',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN (SELECT coalesce(jsonb_agg(q->>'title'),'[]') FROM jsonb_array_elements(n.content->'questions') q) ELSE '[]'::jsonb END,
    'documents',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN (SELECT coalesce(jsonb_agg(d->>'title'),'[]') FROM jsonb_array_elements(n.content->'documents') d) ELSE '[]'::jsonb END,
    'schedules',CASE WHEN n.guide_review='PASS' AND NOT n.change_review THEN (SELECT coalesce(jsonb_agg(concat(s->>'title',' · ',s->>'date')),'[]') FROM jsonb_array_elements(n.content->'schedules') s) ELSE '[]'::jsonb END);
$$;
INSERT INTO published_notice_version SELECT id,version,revision,notice_public_facts(n) FROM notice n WHERE basic_review='PASS';
CREATE FUNCTION record_public_notice_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.basic_review='PASS' THEN
        INSERT INTO published_notice_version VALUES (NEW.id,NEW.version,NEW.revision,notice_public_facts(NEW))
        ON CONFLICT(notice_id,version,revision) DO UPDATE SET facts=EXCLUDED.facts;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER record_published_notice AFTER INSERT OR UPDATE ON notice FOR EACH ROW EXECUTE FUNCTION record_public_notice_version();
