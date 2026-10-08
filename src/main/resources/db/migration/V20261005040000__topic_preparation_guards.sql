-- Only active jobs may hold a claim; only published catalogs may expose a ready result.
ALTER TABLE topic_request ADD CONSTRAINT topic_request_claim_shape CHECK (
    (status IN ('CHECKING','COLLECTING') AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status NOT IN ('CHECKING','COLLECTING') AND claim_token IS NULL AND lease_until IS NULL)
);
ALTER TABLE topic_request ADD CONSTRAINT topic_request_ready_shape CHECK (
    (status='BOOKS_READY' AND topic_id IS NOT NULL AND book_count>0 AND resolved_slug IS NOT NULL)
    OR (status<>'BOOKS_READY' AND topic_id IS NULL AND book_count=0)
);
