-- Collected evidence and historical recommendation IDs remain immutable.
CREATE TABLE catalog_selection_snapshot (
    snapshot_id varchar(120) PRIMARY KEY,
    manifest_hash varchar(71) NOT NULL,
    manifest jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE catalog_selection_topic (
    snapshot_id varchar(120) REFERENCES catalog_selection_snapshot(snapshot_id),
    topic_id bigint REFERENCES topic(id),
    PRIMARY KEY(snapshot_id, topic_id)
);
CREATE TABLE catalog_selection_member (
    snapshot_id varchar(120) NOT NULL,
    topic_id bigint NOT NULL,
    book_id bigint NOT NULL,
    included boolean NOT NULL,
    reason varchar(120) NOT NULL,
    PRIMARY KEY(snapshot_id, topic_id, book_id),
    FOREIGN KEY(snapshot_id, topic_id) REFERENCES catalog_selection_topic(snapshot_id, topic_id),
    FOREIGN KEY(book_id, topic_id) REFERENCES book_topic(book_id, topic_id)
);
CREATE TABLE catalog_selection_current (
    topic_id bigint PRIMARY KEY,
    snapshot_id varchar(120) NOT NULL,
    FOREIGN KEY(snapshot_id, topic_id) REFERENCES catalog_selection_topic(snapshot_id, topic_id)
);
CREATE VIEW catalog_visible_book_topic AS
SELECT bt.* FROM book_topic bt
LEFT JOIN catalog_selection_current c ON c.topic_id=bt.topic_id
WHERE c.topic_id IS NULL OR EXISTS (
    SELECT 1 FROM catalog_selection_member m
    WHERE m.snapshot_id=c.snapshot_id AND m.topic_id=bt.topic_id
      AND m.book_id=bt.book_id AND m.included
);

CREATE VIEW catalog_visible_book AS
SELECT b.* FROM book b
WHERE NOT EXISTS (SELECT 1 FROM book_topic bt WHERE bt.book_id=b.id)
   OR EXISTS (SELECT 1 FROM catalog_visible_book_topic bt WHERE bt.book_id=b.id);
