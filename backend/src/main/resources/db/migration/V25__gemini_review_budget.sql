CREATE TABLE gemini_review_cache (
    fingerprint varchar(64) PRIMARY KEY,
    model varchar(120) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'RUNNING',
    result_json text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE gemini_review_attempt (
    id bigserial PRIMARY KEY,
    fingerprint varchar(64) NOT NULL REFERENCES gemini_review_cache(fingerprint),
    attempted_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gemini_review_attempt_day ON gemini_review_attempt(attempted_at);
