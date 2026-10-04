-- Existing saved recommendations are completed results. Failed calculations remain retryable.
ALTER TABLE learning_recommendation
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'SUCCEEDED',
    ADD COLUMN attempt_id uuid,
    ADD COLUMN processing_expires_at timestamptz,
    ADD CONSTRAINT learning_recommendation_processing_state CHECK (
        (status = 'SUCCEEDED' AND attempt_id IS NULL AND processing_expires_at IS NULL)
        OR (status = 'PROCESSING' AND attempt_id IS NOT NULL AND processing_expires_at IS NOT NULL)
    );
