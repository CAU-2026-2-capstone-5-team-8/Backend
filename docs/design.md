# Backend prototype design

Approved in the task on 2026-09-12. This repository is implemented in stages; this document describes the target, not a claim that all features exist.

## Scope and boundaries

One assessed topic (operating systems), nine single-choice questions (three per dimension), five demo books, one demo user. Computer science is the classification parent. Spring Boot owns all public APIs, PostgreSQL, snapshots, validation, and persistence. Python only calculates a reader profile and rankings from supplied inputs. Clients never access Python directly. Stub mode must run without Python. Authentication, OCR, real ML training, Redis, Kafka, cloud deployment, and adaptive questions are excluded.

Use Java 21, Spring Boot 4.1.1 / MVC, Gradle 8.14.3, PostgreSQL 17.11, JPA, Flyway, RestClient, springdoc 3.1.1. Use Boot's dependency management for its managed libraries and WireMock standalone 3.13.2 when the HTTP adapter is implemented. Feature packages live under `com.cau.capstone8.backend`.

## Data model

All core tables have generated IDs and UTC-compatible `timestamptz` creation/update times. Flyway owns DDL; Hibernate validates. Use a dedicated `backend` schema.

| Table | Important fields and constraints |
| --- | --- |
| app_user | display name; demo data only |
| topic | unique code, name, parent_id; no parent cycles |
| book | title, author, description, optional unique ISBN |
| book_topic | unique book/topic, is_primary, topic_weight; at most one primary topic per book |
| book_sample | book, source URL, location, checksum, provenance; identify synthetic data |
| book_feature | book/topic/version unique, active flag, three required abilities, topic relevance; at most one active feature per book/topic |
| question | topic, dimension, prompt, version, active flag (self-report: no options/answer key) |
| assessment_session | user, topic, status, attempt ID, processing expiry, last failure |
| assessment_question | session, source question, order, immutable prompt/dimension/version snapshot; unique session/question and session/order |
| assessment_answer | unique assessment_question, self-reported known/unknown response |
| reader_profile | unique session, three abilities, calculation version, evidence; user/topic derived from session |
| recommendation_run | user, topic, profile, status, request key/hash, input snapshot, model version; unique user/request key |
| recommendation_item | run, book, feature, rank, scores, reasons; unique run/book and run/rank |
| feedback | user, recommendation item, helpful, comment; unique user/item |

Scores and weights must be finite and in [0,1]. Store searchable IDs, states, and scores in columns. Use JSONB for snapshots, options, evidence, and reasons. Preserve used feature versions and assessment snapshots. No deletion API is in scope. Index topic/book lookup, user/topic/session completion lookup, and run/rank lookup.

## Assessment consistency

CREATED -> IN_PROGRESS on first answer; IN_PROGRESS -> PROCESSING when completing; PROCESSING -> COMPLETED on success or IN_PROGRESS on failure.

Answer writes and complete claims lock the same session row in a short transaction. Require all nine valid answers, freeze input, create a UUID attempt ID and 30-second lease, then commit before calling ML. A second short transaction verifies the current attempt before atomically storing the profile and completing the session. A completed retry returns the existing profile. An active concurrent completion returns 409. Disallow answer edits while processing or completed. An expired attempt can be replaced by the next completion request; late responses cannot overwrite the new attempt. On ML failure, persist a sanitized last failure and restore IN_PROGRESS only if the attempt still owns the session. If the DB is unavailable, log the failure and recover through lease expiry after it returns.

## Public contract

| Method/path | Request | Success |
| --- | --- | --- |
| GET /api/topics | none | 200 topic IDs, names, parent IDs |
| GET /api/books | topicId, page=0, size=20 | 200 page of books |
| GET /api/books/{bookId} | positive ID | 200 book/topics/feature availability |
| POST /api/assessments | userId, topicId | 201 session and nine issued questions |
| GET /api/assessments/{sessionId} | positive ID | 200 state/questions/saved answers |
| PUT /api/assessments/{sessionId}/answers/{assessmentQuestionId} | selectedOptionId | 200 saved answer |
| POST /api/assessments/{sessionId}/complete | no body | 200 completed session/profile, including retries |
| GET /api/users/{userId}/profiles/{topicId} | positive IDs | 200 latest completed profile |
| POST /api/recommendations | userId, topicId, targetBookId?, challengeLevel, topK=5; Idempotency-Key | 201 new success, 200 previous success |
| GET /api/recommendations/{runId} | positive ID | 200 status/results or sanitized failure |
| POST /api/recommendations/{itemId}/feedback | userId, helpful, comment? | 201 new, 200 replacement |

IDs are positive; page >= 0; size 1..100; topK 1..20; comment <= 1000 characters. Never expose answer keys or grading criteria. MVP user IDs are not authentication; document migration to authenticated `/api/me`. Feedback user must match the recommendation owner.

Latest profile is selected by completion time then ID. Top-K uses books directly assigned to the topic and compatible active features. A target book must belong to the requested topic; target mode ignores topK and returns one result. Exclude featureless Top-K candidates and record counts. Return fewer results when fewer are available. No eligible candidates / missing target features -> 422; no profile -> 409. Ranking snapshots fix the selected profile, feature values and versions, configuration, and ML mode/model version. Same key/body returns the same run; different body -> 409; processing -> 409; failed -> replay saved failure. A new key starts a new calculation. Expired processing runs become FAILED on access/replay; late results cannot finalize them. Results and success state commit together; persist failures independently of rolled-back result inserts.

Errors contain `code`, Korean `message`, and server-generated `traceId`. 400 invalid inputs; 404 missing resources; 409 state/idempotency conflicts; 422 unusable recommendation inputs; 502 invalid ML response/upstream failure; 503 connection failure; 504 timeout. Never expose upstream URLs or raw exceptions.

## ML contract and stub

`MlGateway` has profile and rank operations. `ml.mode=stub` selects deterministic in-process behavior; `http` selects RestClient. Both implementations share response validation. No automatic fallback from HTTP to stub and no automatic HTTP retries. Connect timeout 2s; response timeout 10s; attempt lease 30s.

Both internal endpoints use `contractVersion=v1` and echo a UUID requestId. `/ml/reader-profile` receives topic and issued question dimension/self-reported known-or-not response/points; returns calculation version, three ability scores, dimension counts, and evidence. `/ml/rank` receives the fixed profile and version, candidate book features and versions, challenge level, and topK; returns model version, ranked book IDs, total/component scores, and multiple reasons. Reject missing fields, non-finite/out-of-range numbers, duplicate/unrequested books, wrong result counts/ranks, and incompatible versions.

Stub profile = known-response count / issued count per dimension. All questions have one point. Target ability = clamp(profile ability + challenge offset, 0, 1), with COMFORTABLE=-0.2, BALANCED=0, CHALLENGING=+0.2. Dimension fit = 1 - abs(book requirement - target ability). Topic fit = feature topic relevance. Total = arithmetic mean of four fits. Sort descending score then ascending book ID. Return at least two reasons grounded in component scores. Versions are `stub-profile-v1` and `stub-rank-v1`. These are demo rules, not scientifically validated measurements.

## Delivery and collaboration

Stage 2 bootstraps runtime/config/migration/tests/CI. Stage 3 catalog. Stage 4 assessment answers. Stage 5 completion/profile. Stage 6 recommendations/feedback. Stage 7 HTTP ML. Stage 8 full E2E and handoff. Demo seed data is opt-in and separate from schema migrations. Migration names use UTC timestamps to the second; coordinate ordering, and never modify a migration merged to main.

Work on `feat/backend-prototype`; no direct main commits, push, PR, force push, or merge without explicit authorization. Split future concurrent team work into feature branches. One BE owner can handle assessment/profile, the other catalog/recommendation/feedback; both own the ML contract and migration order.
