# Reviewed catalog covers

Catalog list/detail and reading-shelf responses expose additive `coverUrl: string | null`.
`null` means no usable reviewed image is stored. Clients must retain their coverless fallback
and handle failed image loading. The API never derives an image URL from an ISBN, fetches an
image on a request, or claims that a stored URL is still reachable.

## Data availability

The canonical pipeline `Book` contract currently has no cover/image field. Existing catalog
imports therefore leave covers absent. The additive migration does not populate any images,
including demo data. Do not insert invented provider URLs or copy a different edition's cover.

## Administrator review and import

There is no public cover-write endpoint. A database administrator may import a small reviewed
batch through a parameterized SQL client after applying the migration. For each row, keep a
review record with the canonical `ml_book_id`, ISBN, title, author, edition, exact image URL,
source page/API URL, retrieval/check timestamp, and display permission or rights basis. Verify
the source identifies the same edition and permits the intended image use. An absent or uncertain
match stays null. Source provenance alone does not grant display rights.

Only absolute HTTPS URLs with a DNS hostname, no credentials, no whitespace, no fragment, and
the default HTTPS port are eligible. Use the exact observed image URL; do not synthesize one
from its ISBN. Do not use expiring signed URLs or embed tokens. Review the target before import;
the backend performs syntax checks, not remote availability or licensing checks.

Use a transaction, bound parameters, and both immutable book identifiers. Preview the matched
row before updating; require exactly one affected row for each review. The following is a query
template, not a runnable example dataset:

```sql
BEGIN;
SELECT id, ml_book_id, isbn, title, author, cover_url, cover_source_url, cover_checked_at
FROM backend.book WHERE ml_book_id = :ml_book_id AND isbn = :isbn FOR UPDATE;

UPDATE backend.book
SET cover_url = :observed_https_image_url,
    cover_source_url = :reviewed_https_source_url,
    cover_checked_at = :checked_at
WHERE ml_book_id = :ml_book_id AND isbn = :isbn;
-- Check the row count, then COMMIT; otherwise ROLLBACK.
```

Retain the prior values in the review record to make replacement reversible. Clear an invalid,
withdrawn, or no-longer-permitted cover by setting all three fields to null in one update.
Catalog re-imports preserve these independently reviewed fields. After import, read
`GET /api/books/{bookId}`, a catalog list page, and the same book on the reading shelf; they
should show the identical `coverUrl`. Confirm the browser can display it under deployed CSP.

The source URL and check timestamp are administrative provenance and are not exposed by the
public book response. SQL constraints require the three values together; API projection also
rejects malformed or ineligible URLs defensively by returning null.
