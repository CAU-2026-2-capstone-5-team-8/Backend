# Stage 2 implementation plan

Historical plan: the statements below describe stage 2 only. For current branch implementation (catalog plus assessment create/get/answer), see the repository README. This is not a current progress report.

Goal: a reproducible Java 21 backend that starts against PostgreSQL, applies Flyway migrations, publishes health/OpenAPI, and runs mandatory PostgreSQL integration tests in CI.

Spec: [Approved design](design.md). Only stage 2 is being implemented. No business endpoints or demo data yet.

## Work units

1. Verify official remote, clean initial checkout, and branch off current main. Use a separate clone in the approved Backend directory. Install Docker/Compose/JDK into the existing Ubuntu WSL runtime. Check Docker server, not only CLI presence.
2. Add official Gradle 8.14.3 Wrapper, verify its published SHA-256, pin the distribution SHA-256. Configure Boot 4.1.1 and managed JPA/validation/Flyway/PostgreSQL/Testcontainers dependencies in `build.gradle`.
3. Write `BootstrapIntegrationTest` first. It must run against an actual ephemeral PostgreSQL 17.11 container and assert migration/schema initialization, healthy HTTP status without DB details, OpenAPI identity, and no environment actuator exposure. Run before implementation; distinguish environment errors from expected missing behavior.
4. Implement `BackendApplication`, datasource/Flyway/JPA settings, schema initialization migration, Actuator health-only exposure, and springdoc metadata. Re-run the same tests. Do not skip tests when Docker is missing.
5. Add Compose with loopback-only PostgreSQL, required external password, persistent volume, and healthcheck. Add `.env.example` with no usable credentials, a `local` profile, and execution instructions that correctly load environment variables.
6. Add collaboration files, PR template, and GitHub Actions on PRs and pushes. CI uses Java 21 and Docker-equipped Ubuntu and runs `./gradlew clean build`; publish test reports even on failure. No remote operations in this task.
7. Validate Compose with a generated local-only password. Boot the packaged app against Compose, verify HTTP endpoints, restart against the existing DB, and check no pending migrations. Stop only task-owned application/Compose resources; preserve DB data.
8. Run full tests/build, inspect XML test counts and resolved dependencies, check diff/whitespace/ignored secrets/executable Wrapper. Scan staged text for credentials/private keys. Commit the verified stage locally and report exact limitations and next stage.

## Planned local commit

`chore: bootstrap backend with PostgreSQL integration checks`

## Acceptance checks

- `wsl -d Ubuntu -- docker info` succeeds in the tested local environment.
- `wsl -d Ubuntu -- bash ./gradlew clean build --no-daemon --console=plain` succeeds from the repository root.
- Bootstrap integration tests have no failures/errors/skips.
- Compose database and packaged app start, health reports `status: UP` without connection details, OpenAPI metadata is correct, Swagger loads.
- Database data survives Compose stop/start; Flyway validates an existing schema.
- Git is on the feature branch, clean after local commit, and nothing is pushed.

Future stages each add their behavior tests before implementation as described in the design. They are not part of this stage's completion claim.
