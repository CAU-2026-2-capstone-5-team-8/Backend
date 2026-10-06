-- Refresh is separate from diagnosis preparation; existing catalog stays active until publication.
CREATE TABLE topic_catalog_refresh (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    topic_id bigint NOT NULL REFERENCES topic(id) ON DELETE CASCADE,
    baseline_selection varchar(120) NOT NULL REFERENCES catalog_selection_snapshot(snapshot_id),
    baseline_hash varchar(71) NOT NULL,
    providers jsonb NOT NULL CHECK (jsonb_typeof(providers)='array'),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETE','PARTIAL','FAILED')),
    claim_token uuid,
    lease_until timestamptz,
    report jsonb,
    added_book_count integer NOT NULL DEFAULT 0 CHECK (added_book_count>=0),
    published_selection varchar(120) REFERENCES catalog_selection_snapshot(snapshot_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status='RUNNING') = (claim_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE UNIQUE INDEX topic_catalog_refresh_active ON topic_catalog_refresh(topic_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX topic_catalog_refresh_recent ON topic_catalog_refresh(topic_id, created_at DESC);
