# Optional learning readiness v2

## Public contract

`POST /api/learning-recommendations?modelVersion=concept-learning-v2` uses the existing
request body and Idempotency-Key header. Omit the query parameter to keep v1. Reusing
the same key with a different policy returns 409. GET returns the original immutable
input/output-based result, with source evidence and checklist preserved.

Requires ML support for `concept-learning-v2` first. An older ML response is rejected,
not silently interpreted as v2. No frontend or database schema changes. No auto merge,
deployment or production catalog activation is performed by this PR.

V2 retains prerequisites appearing in the book: TOC presence is not proof of adequate
teaching. Book concept facts are separate from observed prior-knowledge responses.
`unmeasured` is not a zero score; `correct` is not calibrated mastery. The checklist
uses known graph dependencies; disconnected nodes are not ordered by real difficulty.

Before persistence, Backend checks summary and checklist states, actual observation
counts, roles, source artifact identity, exact source references, immediate dependency
consistency and prerequisite-derived status. ML owns the reviewed graph; Backend does
not maintain a second graph. Existing authorization/ownership and snapshots remain.

## Optional source evidence import

ML `scripts/prepare_learning_evidence.py` explicitly joins frozen candidate and source
mapping artifacts into a NEW artifact. Covered concepts can contain `evidence` arrays
in snake_case; required reference fields are checked and retained in JSONB. Legacy
candidates with no evidence remain accepted. Runtime ML requests use camelCase.
No source URL is fetched by the importer or recommendation endpoint.

Use a new local-catalog-import-v1 manifest/snapshot ID and the new candidate SHA-256
in a fresh test database. The enrichment changes feature_version to avoid immutable
identity collision. The existing importer rejects replacing an active projection;
explicit selected-book transitions are now available through the developer-only
[catalog maintenance command](catalog-maintenance.md); preview and confirmation are required. Existing
catalogs without enriched evidence return empty lists, not fabricated TOC sources.

Verification uses PostgreSQL/Testcontainers, WireMock ML contract responses, immutable
replay and rejection of tampered observations/summary/provenance. This is not a claim
of deployed end-to-end behavior or recommendation accuracy. See the dependent ML PR
for historical 50-book policy comparison and human-review CSV.

## Final verification (2026-10-04)

- WSL Ubuntu / Java 21 / PostgreSQL Testcontainers: `./gradlew clean build` passed.
- 216 tests discovered: 209 passed, 7 conditional external/live-data tests skipped,
  zero failures/errors. Live Python service/deployment was not exercised in this run.
- WSL source copy was compared with this checkout; source trees were identical.
- Independent review found two gaps, both reproduced RED and corrected: conflicting
  evidence identity import (including across concepts), and hidden prerequisites
  contradicting checklist dependencies. Re-review found no remaining Important issues.
- No live catalog or user records changed. Existing unfinished worktrees were preserved.
- ML dependency: https://github.com/CAU-2026-2-capstone-5-team-8/ML/pull/38
