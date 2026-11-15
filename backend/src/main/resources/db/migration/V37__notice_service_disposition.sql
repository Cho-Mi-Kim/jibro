-- Service decisions are distinct from LH status, official evidence and application dates.
ALTER TABLE notice ADD COLUMN service_closed boolean NOT NULL DEFAULT false;
ALTER TABLE notice ADD COLUMN service_excluded boolean NOT NULL DEFAULT false;
ALTER TABLE notice ADD COLUMN service_decision_reason text NOT NULL DEFAULT '';
ALTER TABLE notice ADD COLUMN service_decided_at timestamptz;
CREATE TABLE notice_service_decision (
    id bigserial PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES notice(id),
    revision integer NOT NULL,
    decision varchar(16) NOT NULL CHECK (decision IN ('CLOSE', 'EXCLUDE')),
    origin varchar(32) NOT NULL,
    reason text NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notice_service_decision_notice ON notice_service_decision(notice_id, id);
