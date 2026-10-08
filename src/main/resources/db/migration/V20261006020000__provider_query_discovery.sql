-- Observed provider scopes are separate from approved diagnostic topics.
CREATE TABLE topic_discovery (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    query varchar(120) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','SEARCHING','FOUND','NO_RESULTS','FAILED')),
    result jsonb,
    claim_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX topic_discovery_queue ON topic_discovery(status,created_at);
CREATE INDEX topic_discovery_user ON topic_discovery(user_id,created_at);
ALTER TABLE topic_request ADD COLUMN discovery_selection jsonb;
DROP INDEX topic_request_unclassified_name;
CREATE UNIQUE INDEX topic_request_unclassified_name
    ON topic_request(user_id,normalized_name) WHERE category_id IS NULL AND discovery_selection IS NULL;
CREATE UNIQUE INDEX topic_request_discovered_scope
    ON topic_request(user_id,(discovery_selection->>'slug')) WHERE discovery_selection IS NOT NULL;

ALTER TABLE topic_request DROP CONSTRAINT topic_request_user_id_category_id_normalized_name_key;
CREATE UNIQUE INDEX topic_request_classified_name
    ON topic_request(user_id,category_id,normalized_name) WHERE discovery_selection IS NULL;

ALTER TABLE topic_request DROP CONSTRAINT topic_request_classified_shape;
ALTER TABLE topic_request ADD CONSTRAINT topic_request_classified_shape CHECK (
    status NOT IN ('COLLECTING','BOOKS_READY') OR category_id IS NOT NULL
    OR (status='COLLECTING' AND discovery_selection IS NOT NULL)
);
