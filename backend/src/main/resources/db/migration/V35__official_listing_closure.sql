-- Explicit status from the same official collection fingerprint. Not an inferred closing date.
ALTER TABLE notice ADD COLUMN source_closed boolean NOT NULL DEFAULT false;
