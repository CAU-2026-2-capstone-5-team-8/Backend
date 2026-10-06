-- Shared content survives the account/request which first collected it.
ALTER TABLE topic_content_preparation ALTER COLUMN source_request_id DROP NOT NULL;
ALTER TABLE topic_content_preparation DROP CONSTRAINT topic_content_preparation_source_request_id_fkey;
ALTER TABLE topic_content_preparation ADD FOREIGN KEY(source_request_id) REFERENCES topic_request(id) ON DELETE SET NULL;
-- Keep generation and review reports separate, including the exact candidate versions reviewed.
ALTER TABLE topic_question_preparation DROP CONSTRAINT topic_question_preparation_status_check;
ALTER TABLE topic_question_preparation DROP CONSTRAINT topic_question_preparation_check1;
ALTER TABLE topic_question_preparation ADD CONSTRAINT topic_question_preparation_status_check CHECK(status IN ('QUEUED','GENERATING','CANDIDATES_READY','REVIEWING','REVIEW_PENDING','REVIEW_BLOCKED','ACTIVE','FAILED'));
ALTER TABLE topic_question_preparation ADD CONSTRAINT topic_question_preparation_lease_check CHECK(
    (status IN ('GENERATING','REVIEWING') AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status NOT IN ('GENERATING','REVIEWING') AND claim_token IS NULL AND lease_until IS NULL));
ALTER TABLE topic_question_preparation ADD COLUMN review_report jsonb,
    ADD COLUMN review_report_hash varchar(71), ADD COLUMN review_artifact_path text;
ALTER TABLE topic_content_preparation ADD COLUMN workspace_id bigint;
UPDATE topic_content_preparation SET workspace_id=source_request_id WHERE source_request_id IS NOT NULL;
