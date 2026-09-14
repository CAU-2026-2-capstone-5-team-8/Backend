# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
cp .env.example .env               # set POSTGRES_PASSWORD before first run
docker compose up -d --wait        # local PostgreSQL (17.11)
./gradlew bootRun --args='--spring.profiles.active=local'

./gradlew clean build              # full build + tests
./gradlew test --tests "*BookIntegrationTest*"   # single test class
```

All integration tests use **Testcontainers against real PostgreSQL — never H2.** Docker must be running or the whole suite fails (it is not skipped). If Docker isn't available locally, push to a feature branch and let `.github/workflows/test.yml` (GitHub Actions, `on: push`/`pull_request`) run the same `./gradlew clean build`.

## Architecture

Spring Boot owns every public API, PostgreSQL, validation, and persistence. A separate Python service only computes reader-profile scores and rankings from data Spring sends it — it never touches the database, and clients never call it directly (`ml.mode=stub|http`, see `integration.ml`). Full contract, data model, and the assessment/recommendation state machines are specified in **`docs/design.md`** — read that before changing cross-cutting behavior; don't re-derive it from the code.

Packages are feature-based under `com.cau.capstone8.backend` (`topic`, `book`, `assessment`, `common.error`, ...), each typically holding `Entity + Repository (+Controller/Service)`. Repositories favor native SQL for anything beyond trivial lookups (pagination, joins, JSON containment checks) — see `BookRepository`/`QuestionRepository` for the pattern.

Flyway owns all DDL (`src/main/resources/db/migration`, `VyyyyMMddHHmmss__description.sql`, UTC); Hibernate only validates (`ddl-auto=validate`). **Never edit a migration already merged to `main`** — add a new one instead. Demo/seed data lives under `db/demo/*.sql`, is idempotent (`ON CONFLICT ... DO NOTHING` keyed by a `demo_key` column), and only runs under the `demo` Spring profile — it must stay decoupled from Flyway history.

## Current stage

Implemented: topic/book catalog APIs (stage 3) and the assessment/question-bank data model (stage 4, schema only — no session/answer endpoints yet). Stage plan, target API contract (11 endpoints total), and the ML request/response contract live in `docs/design.md`; `docs/implementation-plan.md` and `docs/verification-stage*.md` record what each completed stage actually verified. Don't assume an endpoint exists just because it's in the design doc's contract table — check what's actually implemented.

## Collaboration

Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`). Feature branches only, PR into `main`; no direct commits/pushes to `main`. Error responses are always `{code, message, traceId}` with a **Korean** `message` and no leaked internals (stack traces, SQL, upstream URLs) — `common.error.ApiExceptionHandler` is the one place that maps exceptions to this shape.

push 전 CI 결과는 항상 사용자 확인을 거친다 — PR을 올리고 CI(`.github/workflows/test.yml`)가 돌아간 뒤, 그 결과를 혼자 판단해서 다음 작업(추가 push, 머지 등)을 진행하지 않고 반드시 사용자에게 보여주고 확인받는다.

PR 설명은 다음 구조를 따른다: **요약**(무엇을, 왜 바꿨는지) / **검증**(실제로 확인한 항목 체크리스트 — 원격 CI처럼 아직 확인 못한 항목은 "별도 확인 대상"으로 솔직히 표기) / **DB 변경**(새로 추가한 Flyway 마이그레이션 파일명과 각각이 하는 일).

여러 명이 동시에 작업할 때는 마이그레이션 파일명이 겹치거나 순서가 꼬일 수 있다 — PR을 올리기 직전 `main` 기준 최신 마이그레이션 타임스탬프(`VyyyyMMddHHmmss`)를 확인하고, 겹치거나 `main`에 먼저 머지된 것보다 앞서면 파일명을 재조정한다.

브랜치·PR은 설계 문서의 단계(stage) 단위로 나눈다 — 같은 브랜치에 여러 단계를 계속 이어붙이지 않고, 한 단계(또는 그보다 작은 단위)가 끝나면 새 브랜치·새 PR로 시작한다. 리뷰어가 한 PR에서 무엇이 바뀌었는지 파악하기 쉽게 하기 위함이다.
