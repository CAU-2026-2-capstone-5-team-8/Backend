# ML prerequisite-first ranking v2 integration

## Contract gap and decision

The existing Backend recommendation path is a legacy scalar contract. Its items require
`score`, `topic_fit`, `vocabulary_fit`, `knowledge_fit`, and `comprehension_fit`, and the
default `stub` gateway still produces that shape. ML `rank-prerequisite-first-v2` is a
lexicographic policy and intentionally has no arithmetic score. Its canonical result is
rank plus prerequisite readiness/coverage, direct learning opportunity/coverage, evidence
sets, and model/config/graph provenance.

Backend therefore keeps the legacy stub path intact and adds a separate v2 HTTP contract.
It never copies prerequisite readiness into `score` and never synthesizes covered concepts
from legacy scalar book features.

## Data flow

`ML_MODE=stub` continues to use `book_feature` and the v1 internal DTO. `ML_MODE=http`
requires:

1. `topic.ml_topic_id`, the explicit canonical topic mapping (for example `OS` to
   `operating-systems`);
2. `book.ml_book_id`, the explicit canonical Data-Pipeline/ML book identifier;
3. an active `book_ranking_v2_projection` row imported from the authoritative ML handoff;
4. `reader_profile.evidence` produced by the HTTP `/ml/reader-profile` path, including the
   original `conceptReadiness` and profile/config provenance.

The service projects those stored values to the versioned Python wire DTO and calls
`POST /ml/rank` with `rankingModel=rank-prerequisite-first-v2`. The server, not Backend,
infers accepted prerequisites.

## Persistence

`book_ranking_v2_projection` is separate from `book_feature`: the former is source-aware
concept evidence and provenance, while the latter remains the scalar stub feature model.
`recommendation_run.ranking_mode` distinguishes legacy and v2 runs. Legacy scalar item
columns become nullable, and a database check requires either the complete legacy scalar
shape or the complete canonical v2 shape. Run-level ranking config, graph, graph-review,
reader provenance, and diagnostics are stored for reproducibility.

## Scale-50 fixture and import boundary

`src/test/resources/fixtures/ml/scale-50-ranking-v2-candidates.jsonl` contains three real
Operating Systems projections copied byte-for-byte per JSON object from:

- source: `ML/data/output/scale-50-matching-candidates.jsonl`
- source SHA-256: `8a034f57a51c44ae5c3b0240b75812bb7654500d60d720da0eeae3389b3e74e0`
- checked-in three-row fixture SHA-256:
  `3638d14b4af92c18b2bf506fca0533b7f5c5f4a6886248b8004c8313ddb1825f`
- projection version: `book-v1` / `features-v1`
- copied identifiers: `isbn13:9780130319999`, `isbn13:9780132199087`,
  `isbn13:9780201498387`

The matching titles/authors come from Data-Pipeline's
`data/experiments/scale-50-v2/processed/books.jsonl`. Integration tests import the fixture
into PostgreSQL after inserting books with those canonical IDs. Production does not read a
sibling-repository path; deployment ingestion must upsert books/topics and then persist the
same projection fields through the Backend schema.

## Runtime behavior

- Ordinary personalized shortage, including zero personalizable books, is a successful
  recommendation with `items=[]` or fewer than requested and preserved diagnostics.
- ML HTTP 422 for a non-personalizable target maps to the safe Backend error
  `ML_RANK_TARGET_UNAVAILABLE` (HTTP 422); the upstream body is never exposed.
- `challengeLevel` remains in the public request for compatibility. V2 does not use it and
  does not claim that it affected ordering. A future product policy must decide whether to
  remove it or define a separate model.
- Frontend consumes only the Backend response. Items expose title/author, reasons, nullable
  legacy fields, and nullable v2 readiness/coverage fields. Run diagnostics expose v2
  provenance without leaking the Python DTO into the public API.

## Manual and live verification

Use `requests/recommendation-v2.http` with the VS Code REST Client. For the opt-in contract
test, run the current ML `main` server and then:

```bash
ML_CONTRACT_BASE_URL=http://127.0.0.1:8000 ./gradlew test --tests '*LiveContract*'
```

The v2 live test reads the checked-in real Scale-50 projection fixture. A full local E2E
also requires PostgreSQL, Backend with `ML_MODE=http`, and imported catalog/projection rows.
