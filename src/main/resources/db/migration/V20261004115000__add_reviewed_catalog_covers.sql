ALTER TABLE book
    ADD COLUMN cover_url varchar(2048),
    ADD COLUMN cover_source_url varchar(2048),
    ADD COLUMN cover_checked_at timestamptz,
    ADD CONSTRAINT book_cover_provenance CHECK (
        (cover_url IS NULL AND cover_source_url IS NULL AND cover_checked_at IS NULL)
        OR
        (cover_url IS NOT NULL AND cover_source_url IS NOT NULL AND cover_checked_at IS NOT NULL
         AND cover_url ~ '^https://[^[:space:]]+$'
         AND cover_source_url ~ '^https://[^[:space:]]+$')
    );

COMMENT ON COLUMN book.cover_url IS 'Exact reviewed edition image URL; never synthesized from ISBN';
COMMENT ON COLUMN book.cover_source_url IS 'Page or API URL used to verify cover and edition identity';
COMMENT ON COLUMN book.cover_checked_at IS 'Timestamp of administrator source/edition review';
