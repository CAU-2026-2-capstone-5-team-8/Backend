# 2단계 검증 결과

검증일: 2026-09-12. 범위: 기반 구성만. 업무 API와 ML 계산 구현은 포함하지 않습니다.

## 환경

- Ubuntu 22.04 / WSL 2
- OpenJDK 21.0.12 (WSL)
- Docker Engine 29.1.3, Compose 2.40.3
- Gradle 8.14.3, Spring Boot 4.1.1
- PostgreSQL 17.11
- 실행 JAR에서 확인: Flyway 12.4.0, PostgreSQL JDBC 42.7.13, Hibernate 7.4.5.Final, springdoc 3.1.1

Windows에도 Temurin 21.0.11이 있지만 최종 테스트는 Docker가 설치된 WSL에서 수행했습니다. 초기 Windows 직접 테스트는 테스트 클래스 로딩 단계에서 실패했으며 Windows 네이티브 테스트 성공을 주장하지 않습니다.

## 테스트와 빌드

PowerShell에서 저장소 루트를 작업 디렉터리로 사용했습니다.

```powershell
wsl -d Ubuntu -- bash ./gradlew clean build --no-daemon --console=plain --warning-mode=all
```

실제 최종 출력:

```text
BootstrapIntegrationTest > publishesOpenApiDocumentWithProjectIdentity() PASSED
BootstrapIntegrationTest > doesNotExposeEnvironmentActuatorEndpoint() PASSED
BootstrapIntegrationTest > initializesBackendSchemaThroughFlywayOnAnEmptyPostgresDatabase() PASSED
BootstrapIntegrationTest > exposesDatabaseHealthWithoutConnectionDetails() PASSED
BUILD SUCCESSFUL in 1m 11s
7 actionable tasks: 7 executed
```

JUnit XML 집계: **tests=4, failures=0, errors=0, skipped=0**.

테스트는 실제 임시 PostgreSQL 17.11 컨테이너를 사용했습니다. Docker 부재 시 건너뛰는 설정은 없습니다. H2와 mock DB를 사용하지 않았습니다. 실행 중 OpenJDK의 class sharing 경고가 있었지만 테스트 실패는 없었습니다.

TDD 확인: 최소 진입점만 있는 상태에서 스키마 미생성과 health/OpenAPI의 404로 3개 테스트가 실패했습니다. Flyway·Actuator·springdoc 구성을 추가한 뒤 동일 테스트 4개가 모두 통과했습니다. 비공개 환경 Actuator 경로 테스트는 노출 제한을 위한 회귀 검사입니다.

## 실제 JAR 실행과 재시작

Git에서 제외한 `.env`에 무작위 로컬 비밀번호를 생성했습니다. 비밀번호가 비어 있는 `.env.example`로 Compose 설정 검증을 실행했을 때 시작을 거부하는 것을 확인했습니다.

`docker compose up -d --wait` 후 `java -jar build/libs/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local`로 앱을 실행했습니다. 새 DB에 version `20260912102447` migration 한 개가 적용됐습니다.

이 PC의 Windows localhost 전달이 동작하지 않아 WSL 주소에 `--server.address`를 지정해 앱을 재시작했습니다. 재시작 로그:

```text
Current version of schema "backend": 20260912102447
Schema "backend" is up to date. No migration necessary.
```

Windows에서 WSL 주소로 확인한 결과:

```text
Health: {"groups":["liveness","readiness"],"status":"UP"}
OpenAPI: 3.1.0, title: CAU Capstone 8 Backend
Business paths: 0
Swagger UI: 200
```

앱을 종료하고 Compose를 down/up한 뒤에도 `backend.flyway_schema_history`의 해당 버전과 `success=true`가 유지됐습니다. 마지막에는 앱과 Compose 컨테이너를 종료했습니다. 로컬 DB 볼륨은 보존했습니다.

## 검토와 범위 제한

- 별도 읽기 전용 코드 리뷰에서 수정이 필요한 문제가 발견되지 않았습니다.
- Gradle Wrapper JAR를 공식 SHA-256과 대조했습니다: `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`.
- Gradle 배포 ZIP의 SHA-256을 Wrapper 설정에 고정했습니다.
- GitHub Actions workflow는 작성했지만 원격 push와 CI 실행은 하지 않았습니다.
- main 보호 설정, 원격 PR 생성, merge는 수행하지 않았습니다.
- 3단계는 분야·도서·BookFeature 데이터와 조회 API입니다.
