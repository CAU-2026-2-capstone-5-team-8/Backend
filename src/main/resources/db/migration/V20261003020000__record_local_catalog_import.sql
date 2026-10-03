-- Import identity is separate from the original ML feature version.
CREATE TABLE local_catalog_import (
    snapshot_id varchar(120) PRIMARY KEY CHECK (btrim(snapshot_id) <> ''),
    manifest_hash varchar(71) NOT NULL,
    books_hash varchar(71) NOT NULL,
    candidates_hash varchar(71) NOT NULL,
    topic_id bigint NOT NULL REFERENCES topic(id),
    selected_book_ids jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
