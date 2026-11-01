CREATE TABLE notice (
 id uuid PRIMARY KEY, source_key varchar(255) UNIQUE, title varchar(300) NOT NULL,
 agency varchar(100) NOT NULL, region varchar(100) NOT NULL, support_type varchar(100) NOT NULL,
 start_date date, end_date date, published_date date,
 version integer NOT NULL DEFAULT 1, revision integer NOT NULL DEFAULT 1,
 basic_review varchar(10) NOT NULL DEFAULT 'HOLD' CHECK(basic_review IN ('PASS','HOLD')),
 guide_review varchar(20) NOT NULL DEFAULT 'HOLD' CHECK(guide_review IN ('PASS','HOLD','UNSUPPORTED')),
 change_review boolean NOT NULL DEFAULT false,
 reason text NOT NULL DEFAULT '', content jsonb NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notice_public_deadline_idx ON notice(basic_review,end_date,id);
CREATE INDEX notice_public_newest_idx ON notice(basic_review,published_date DESC,id);
CREATE INDEX notice_filters_idx ON notice(region,support_type);
CREATE TABLE preparation (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_user(id),
 notice_id uuid NOT NULL REFERENCES notice(id), notice_version integer NOT NULL,
 revision integer NOT NULL DEFAULT 1, snapshot jsonb NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,notice_id)
);
CREATE INDEX preparation_notice_idx ON preparation(notice_id);
CREATE TABLE review_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, notice_id uuid NOT NULL REFERENCES notice(id),
 reviewer_id uuid NOT NULL REFERENCES app_user(id), revision integer NOT NULL,
 basic_review varchar(10) NOT NULL, guide_review varchar(20) NOT NULL,
 reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX review_audit_notice_idx ON review_audit(notice_id,created_at DESC);
CREATE TABLE collection_run (
 id uuid PRIMARY KEY, status varchar(30) NOT NULL, started_at timestamptz NOT NULL DEFAULT now(),
 message text NOT NULL, actor_id uuid NOT NULL REFERENCES app_user(id)
);
