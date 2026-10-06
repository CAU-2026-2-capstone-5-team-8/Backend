-- Book publication and diagnostic activation are deliberately separate stages.
ALTER TABLE topic_request DROP CONSTRAINT topic_request_scope_check;
ALTER TABLE topic_request ADD CONSTRAINT topic_request_scope_check
    CHECK (char_length(scope) <= 1000);
ALTER TABLE topic_request DROP CONSTRAINT topic_request_status_check;
ALTER TABLE topic_request ADD CONSTRAINT topic_request_status_check
    CHECK (status IN ('NEEDS_REVIEW','QUEUED','CHECKING','COLLECTING','NEEDS_INPUT','FAILED','BOOKS_READY'));
ALTER TABLE topic_request
    ADD COLUMN message varchar(500),
    ADD COLUMN resolved_slug varchar(120),
    ADD COLUMN topic_id bigint REFERENCES topic(id),
    ADD COLUMN book_count integer NOT NULL DEFAULT 0 CHECK (book_count >= 0),
    ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN claim_token uuid,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX topic_request_queue ON topic_request(status,id);
