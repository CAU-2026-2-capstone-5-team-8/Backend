-- A request starts with a name. App taxonomy is assigned after field resolution;
-- provider-specific catalog categories remain Data-Pipeline policy.
ALTER TABLE topic_request ALTER COLUMN category_id DROP NOT NULL;
CREATE UNIQUE INDEX topic_request_unclassified_name
    ON topic_request(user_id, normalized_name) WHERE category_id IS NULL;
ALTER TABLE topic_request ADD CONSTRAINT topic_request_classified_shape CHECK (
    status NOT IN ('COLLECTING','BOOKS_READY') OR category_id IS NOT NULL
);
