-- Keep the first observed closure of the current intake, independently of subsequent reviews.
ALTER TABLE notice ADD COLUMN intake_closed_at timestamptz;

CREATE FUNCTION notice_has_closure(n notice) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT n.source_closed OR n.service_closed OR n.lifecycle_state IN ('EARLY_CLOSED','CANCELLED')
        OR (n.content->>'applicationClosure') IS NOT NULL
$$;

CREATE FUNCTION record_notice_intake_closure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT notice_has_closure(NEW) THEN
        NEW.intake_closed_at := NULL;
    ELSIF TG_OP = 'INSERT' THEN
        NEW.intake_closed_at := statement_timestamp();
    ELSIF NOT notice_has_closure(OLD) THEN
        NEW.intake_closed_at := statement_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER notice_intake_closure BEFORE INSERT OR UPDATE OF source_closed, service_closed, lifecycle_state, content
    ON notice FOR EACH ROW EXECUTE FUNCTION record_notice_intake_closure();
