-- Content is shared by a topic and its immutable catalog source, not by an account.
CREATE TABLE topic_content_preparation (
    topic_id bigint NOT NULL REFERENCES topic(id),
    source_snapshot_id varchar(120) NOT NULL REFERENCES discovery_catalog_import(snapshot_id),
    source_request_id bigint NOT NULL REFERENCES topic_request(id),
    status varchar(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','PREPARING','CONCEPTS_READY','NEEDS_EVIDENCE','FAILED')),
    claim_token uuid,
    lease_until timestamptz,
    attempts int NOT NULL DEFAULT 0 CHECK (attempts>=0),
    report jsonb,
    report_hash varchar(71) CHECK (report_hash ~ '^sha256:[0-9a-f]{64}$'),
    artifact_path text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(topic_id,source_snapshot_id),
    CHECK ((status='PREPARING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PREPARING' AND claim_token IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('CONCEPTS_READY','NEEDS_EVIDENCE') AND report IS NOT NULL AND report_hash IS NOT NULL AND artifact_path IS NOT NULL)
        OR (status NOT IN ('CONCEPTS_READY','NEEDS_EVIDENCE') AND report IS NULL AND report_hash IS NULL AND artifact_path IS NULL))
);
