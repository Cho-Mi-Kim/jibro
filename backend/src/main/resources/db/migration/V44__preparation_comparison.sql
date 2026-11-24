-- Retain the public structured fields for later item-by-item comparison, including editorial releases.
ALTER TABLE published_notice_version ADD COLUMN preparation jsonb;
CREATE FUNCTION keep_published_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.facts->>'guidance' IN ('제공','제공 · 일부 확인 필요') THEN
        SELECT jsonb_build_object('questions',n.content->'questions','documents',n.content->'documents','reviewIssues',n.content->'reviewIssues')
          INTO NEW.preparation FROM notice n WHERE n.id=NEW.notice_id AND n.revision=NEW.revision;
    ELSIF TG_OP='UPDATE' THEN
        NEW.preparation := OLD.preparation;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER retain_preparation BEFORE INSERT OR UPDATE OF facts ON published_notice_version
    FOR EACH ROW EXECUTE FUNCTION keep_published_preparation();
UPDATE published_notice_version v SET preparation=jsonb_build_object('questions',n.content->'questions','documents',n.content->'documents','reviewIssues',n.content->'reviewIssues')
FROM notice n WHERE v.notice_id=n.id AND n.basic_review='PASS' AND n.guide_review='PASS' AND NOT n.change_review AND v.revision=n.revision;
-- A link can arrive after the new notice was already processed. Revisit the latest notice promptly.
CREATE FUNCTION revisit_corrected_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE notice_followup_queue SET next_check_at=clock_timestamp(),last_bundle_hash=NULL WHERE notice_id=NEW.corrected_notice_id;
    RETURN NEW;
END $$;
CREATE TRIGGER revisit_corrected_items AFTER INSERT OR UPDATE OF corrected_notice_id ON notice_correction
    FOR EACH ROW EXECUTE FUNCTION revisit_corrected_preparation();

