CREATE TABLE topic_question_preparation (
    topic_id bigint NOT NULL,
    source_snapshot_id varchar(120) NOT NULL,
    content_report_hash varchar(71) NOT NULL CHECK(content_report_hash ~ '^sha256:[0-9a-f]{64}$'),
    status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','GENERATING','CANDIDATES_READY','FAILED')),
    generated_count int NOT NULL DEFAULT 0,
    planned_count int NOT NULL CHECK(planned_count BETWEEN 9 AND 18),
    claim_token uuid,
    lease_until timestamptz,
    attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0),
    report_hash varchar(71) CHECK(report_hash ~ '^sha256:[0-9a-f]{64}$'),
    artifact_path text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(topic_id,source_snapshot_id,content_report_hash),
    FOREIGN KEY(topic_id,source_snapshot_id) REFERENCES topic_content_preparation(topic_id,source_snapshot_id),
    CHECK(generated_count BETWEEN 0 AND planned_count),
    CHECK((status='GENERATING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'GENERATING' AND claim_token IS NULL AND lease_until IS NULL)),
    CHECK(status<>'CANDIDATES_READY' OR (generated_count=planned_count AND report_hash IS NOT NULL AND artifact_path IS NOT NULL))
);
