CREATE TABLE discovery_catalog_import (
    snapshot_id varchar(120) PRIMARY KEY CHECK (btrim(snapshot_id) <> ''),
    manifest_hash varchar(71) NOT NULL CHECK (manifest_hash ~ '^sha256:[0-9a-f]{64}$'),
    manifest jsonb NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Coverage and input provenance are immutable per snapshot. No book prose is copied here.
CREATE TABLE discovery_catalog_member (
    snapshot_id varchar(120) NOT NULL REFERENCES discovery_catalog_import(snapshot_id),
    book_id bigint NOT NULL,
    topic_id bigint NOT NULL,
    projection_id bigint REFERENCES book_ranking_v2_projection(id),
    toc_entry_count int NOT NULL CHECK (toc_entry_count >= 0),
    description_count int NOT NULL CHECK (description_count >= 0),
    source_count int NOT NULL CHECK (source_count >= 1),
    books_hash varchar(71) NOT NULL CHECK (books_hash ~ '^sha256:[0-9a-f]{64}$'),
    evidence_hash varchar(71) NOT NULL CHECK (evidence_hash ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (snapshot_id, book_id, topic_id),
    FOREIGN KEY (book_id, topic_id) REFERENCES book_topic(book_id, topic_id)
);
CREATE INDEX discovery_catalog_member_book_topic ON discovery_catalog_member(book_id, topic_id);
