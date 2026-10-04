-- Immutable analysis versions stay in place. Only their active selection changes.
CREATE TABLE catalog_transition (
    id uuid PRIMARY KEY,
    topic_id bigint NOT NULL REFERENCES topic(id),
    snapshot_id varchar(120) NOT NULL REFERENCES local_catalog_import(snapshot_id),
    manifest_hash varchar(71) NOT NULL,
    plan_token varchar(71) NOT NULL UNIQUE,
    before_state jsonb NOT NULL CHECK (jsonb_typeof(before_state) = 'array'),
    after_state jsonb NOT NULL CHECK (jsonb_typeof(after_state) = 'array'),
    reason text NOT NULL CHECK (btrim(reason) <> ''),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    rolled_back_at timestamptz,
    rollback_reason text,
    CHECK ((rolled_back_at IS NULL) = (rollback_reason IS NULL))
);
