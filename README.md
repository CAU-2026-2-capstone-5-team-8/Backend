# CAU 캡스톤디자인 8조 Backend

Spring Boot가 API와 PostgreSQL을 담당하고 Python ML이 계산을 담당하는 독서 진단·추천 프로토타입입니다.

현재 이 브랜치에는 **3단계 분야·도서 조회, 4단계 진단, 5단계 독자 프로필, 6단계 추천·피드백**이 구현되어 있습니다. 총 11개 업무 API를 제공하며 진단은 `knowsConcept`(안다/모른다) 자기평가 방식입니다. 기본 `stub` 모드는 영역별 안다고 답한 문항 비율로 프로필을 만들고, 활성 도서 특성과 준비도 사이의 거리를 사용해 추천을 계산합니다. `ML_MODE=http`는 **프로필 계산만** Python ML의 `/ml/reader-profile`로 연결하며 [설정·계약·한계](docs/ml-profile-http.md)를 참고하세요. `/ml/rank` HTTP 연결과 실제 Python 서버 E2E는 아직 구현 전입니다.

진단·프로필 API: `POST /api/assessments`, `GET /api/assessments/{sessionId}`, `PUT /api/assessments/{sessionId}/answers/{assessmentQuestionId}`, `POST /api/assessments/{sessionId}/complete`, `GET /api/users/{userId}/profiles/{topicId}`. 답변 요청은 `{"knowsConcept": true}` 또는 `{"knowsConcept": false}`이며, 미응답은 조회 결과에서 `null`입니다. [현재 작동 방식](<docs/current-implementation-overview(작동 방식).md>)을 참고하세요.

추천 API: `POST /api/recommendations` (`Idempotency-Key` 헤더 필수), `GET /api/recommendations/{runId}`, `POST /api/recommendations/{itemId}/feedback`. 추천 요청 예시는 `{"userId":1,"topicId":2,"challengeLevel":"BALANCED","topK":5}`이며, 특정 도서는 `targetBookId`를 추가하면 됩니다. 완료된 독자 프로필과 해당 분야의 활성 도서 특성이 필요합니다. 같은 사용자의 같은 키·같은 입력은 기존 결과를 재생하고, 같은 키·다른 입력이나 처리 중 중복 호출은 409입니다. 실패한 키는 정제된 오류를 재생하므로 새 계산에는 새 키를 사용하세요. 피드백은 `{"userId":1,"helpful":true,"comment":"도움이 됐어요"}` 형식으로 최초 201·재제출 200이며, 소유자가 다르면 409입니다.

## 요구 환경

- Java 21
- Docker Engine/Desktop과 Docker Compose v2
- Git
- Gradle 전역 설치 불필요: 검증된 Gradle 8.14.3 Wrapper 포함

Spring Boot 4.1.1, PostgreSQL 17.11, springdoc 3.1.1을 사용합니다. Flyway, JPA, JDBC, Testcontainers 버전은 Spring Boot BOM이 관리합니다. PostgreSQL용 Flyway 모듈을 별도로 포함합니다.

## 로컬 실행

다음 명령은 저장소 루트에서 실행합니다. `.env`는 Git에서 제외되며 실제 비밀번호를 커밋하지 않습니다.

```bash
cp .env.example .env
```

`.env`의 `POSTGRES_PASSWORD`에 로컬용 비밀번호를 넣으세요. 비밀번호는 `openssl rand -hex 24`로 생성할 수 있습니다. Java properties와 Compose가 같은 값을 읽도록 영문·숫자 값을 따옴표 없이 사용하세요.

```bash
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` 프로필은 저장소 루트의 `.env`를 Java properties 형식으로 읽습니다. Compose는 같은 파일을 자체적으로 읽습니다. `.env.example`은 실제 비밀번호를 포함하지 않으며, 값을 비워두면 Compose가 시작을 거부합니다.

| URL | 용도 |
| --- | --- |
| http://localhost:8080/actuator/health | 애플리케이션·DB 상태. 내부 상세정보 미노출 |
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |

실행 중인 개발 서버는 `Ctrl+C`로 종료합니다.

## 분야와 도서 조회

| API | 설명 |
| --- | --- |
| `GET /api/topics` | 분야 ID·코드·이름·부모 ID 목록 |
| `GET /api/books?topicId=...&page=0&size=20` | 도서 페이지. topicId는 선택 사항 |
| `GET /api/books/{bookId}` | 도서 상세와 분야별 활성 특성 제공 여부 |

분야·도서는 ID 오름차순으로 반환합니다. 도서 페이지 응답은 `content`, `page`, `size`, `totalElements`, `totalPages`입니다. `page`는 0 이상, `size`는 1~100, ID는 양수입니다. 잘못된 입력은 400, 존재하지 않는 분야·도서는 404이며 오류 응답은 `code`, 한국어 `message`, 서버 생성 `traceId`를 제공합니다.

분야 필터는 직접 연결된 도서만 조회합니다. CS 부모 분야에 OS 도서가 자동 포함되지 않습니다. 도서의 `topics`에는 `id`, `code`, `name`, `primary`, `weight`, `featureAvailable`이 포함됩니다. 특성이 없어도 도서 조회는 가능하며, 특성 제공 여부는 각 분야별 활성 버전을 기준으로 계산합니다.

기본 실행에는 초기 데이터가 없습니다. 합성 데모 도서 5권, CS·OS 분야, 데모 사용자 1명과 특성·출처 메타데이터를 넣으려면 다음처럼 실행합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local,demo'
```

데모 도서는 실제 출판물이 아니며 요구 능력 점수는 기능 검증용입니다. 실제 ISBN, 책 원문과 출처 URL은 만들지 않았습니다. 데모 입력은 같은 키가 있으면 덮어쓰지 않습니다. `demo`를 끈 뒤 재시작해도 이미 입력된 데이터는 보존됩니다. 데모 활성화 해제는 데이터 삭제가 아닙니다.

```bash
docker compose down
```

위 명령은 DB 볼륨을 보존합니다. 기존 DB의 비밀번호는 `.env`만 바꿔도 변경되지 않습니다. 데이터가 있는 볼륨을 임의로 삭제하지 마세요.

## 이 PC의 Windows / WSL 실행 방법

이 작업에서는 기존 Ubuntu WSL 2에 Docker Engine·Compose·OpenJDK 21을 설치했습니다. Windows Java와 WSL Java는 별개입니다. **검증 기준은 WSL 안에서 Docker와 Gradle을 함께 실행하는 방식**입니다.

Ubuntu가 systemd를 사용하지 않는 경우, 별도 터미널에서 Docker를 실행하고 유지합니다.

```powershell
wsl -d Ubuntu -u root -- dockerd --host=unix:///var/run/docker.sock
```

이미 `docker info`가 성공하면 데몬을 중복 실행하지 않습니다. systemd 환경에서는 해당 환경의 Docker 서비스 관리 방식을 사용합니다.

저장소 폴더의 PowerShell에서:

```powershell
Copy-Item .env.example .env
# .env에 로컬 POSTGRES_PASSWORD를 입력한 뒤 실행
wsl -d Ubuntu -- docker compose up -d --wait
wsl -d Ubuntu -- bash ./gradlew bootRun --args=--spring.profiles.active=local
```

Docker Desktop을 쓰는 Windows 환경에서는 Docker 서버 접근을 확인한 뒤 `gradlew.bat`을 사용할 수 있습니다. 현재 PC에서는 WSL 실행을 사용하세요.

이 PC에서는 Windows의 `localhost` 전달이 동작하지 않았습니다. Windows 브라우저에서 접속하려면 위 서버를 종료한 뒤 WSL 주소로 바인딩해 실행합니다.

```powershell
$wslAddress = ((wsl -d Ubuntu -- hostname -I).Trim() -split '\s+')[0]
Write-Output "Swagger: http://${wslAddress}:8080/swagger-ui.html"
wsl -d Ubuntu -- bash ./gradlew bootRun "--args=--spring.profiles.active=local --server.address=$wslAddress"
```

WSL 주소는 재시작 후 바뀔 수 있으므로 매번 조회합니다. 이 방식은 WSL 가상 네트워크 주소에 서버를 바인딩합니다. 기본 local 실행은 `127.0.0.1`을 사용합니다. 빌드된 JAR에도 `--server.address`를 동일하게 전달할 수 있습니다.

## 환경변수

| 변수 | local 기본값 / 용도 |
| --- | --- |
| POSTGRES_DB | backend |
| POSTGRES_USER | backend |
| POSTGRES_PASSWORD | 필수, 기본 비밀번호 없음 |
| POSTGRES_HOST | localhost |
| POSTGRES_PORT | 5432 |
| SERVER_PORT | 8080 |
| ML_MODE | `stub`(기본값)은 결정론적 프로필·추천 계산, `http`는 프로필 HTTP 계산만 지원 |
| ML_BASE_URL | `http` 모드의 ML 서버 기준 URL. 기본값 `http://127.0.0.1:8000` |
| ML_CONNECT_TIMEOUT | ML 연결 제한 시간. 기본값 `PT2S` |
| ML_READ_TIMEOUT | ML 응답 제한 시간. 기본값 `PT10S` |
| ASSESSMENT_PROCESSING_LEASE | 완료 처리 소유권 임대 시간. 기본값 `PT30S` |
| RECOMMENDATION_PROCESSING_LEASE | 추천 처리 소유권 임대 시간. 기본값 `PT30S` |

`local` 이외의 환경은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 접속 정보를 전달합니다. DB 포트는 Compose에서 `127.0.0.1`에만 바인딩합니다. MVP는 인증을 제공하지 않으며 외부 공개 배포는 범위에 포함되지 않습니다.

## 테스트와 빌드

```bash
./gradlew test
./gradlew clean build
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

이 PC의 PowerShell:

```powershell
wsl -d Ubuntu -- bash ./gradlew clean build --no-daemon --console=plain
```

테스트는 Testcontainers가 임시 PostgreSQL을 생성하므로 Compose DB나 `.env`가 필요 없습니다. **Docker가 없으면 실패하며 테스트를 건너뛰지 않습니다.** H2는 사용하지 않습니다. 테스트 종료 시 임시 컨테이너는 Testcontainers가 정리합니다.

결과: `build/reports/tests/test/index.html`, `build/test-results/test/`. 실행 JAR: `build/libs/backend-0.0.1-SNAPSHOT.jar`.

GitHub Actions는 PR과 push에서 Java 21·Ubuntu로 전체 테스트와 빌드를 수행합니다. Workflow 파일 추가만으로 main 보호가 설정되지는 않습니다. 원격 실행과 브랜치 보호는 별도 작업입니다.

## 프로젝트 구조와 DB 규칙

`com.cau.capstone8.backend` 아래에 기능별 패키지를 추가합니다: `user`, `topic`, `book`, `assessment`, `profile`, `recommendation`, `feedback`, `integration.ml`, `common`.

- 스키마는 `backend`, 생성과 변경은 Flyway가 담당합니다.
- JPA는 `ddl-auto=validate`, Open Session in View는 비활성화합니다.
- Migration 위치: `src/main/resources/db/migration`.
- Migration 버전: UTC `VyyyyMMddHHmmss__description.sql`.
- main에 반영된 migration은 수정하지 않습니다. 시간 이름만으로 순서·충돌 문제가 해결되지는 않으므로 팀원과 적용 순서를 조율합니다.
- 초기 migration 뒤에 카탈로그 migration을 추가했습니다. `app_user`, `topic`, `book`, `book_topic`, `book_sample`, `book_feature`를 생성합니다. 분야 순환 방지용 내부 guard 테이블도 사용합니다.
- 독자 프로필과 추천용 `reader_profile`, `recommendation_run`, `recommendation_item`, `feedback`을 후속 migration에서 생성합니다. 추천 시 입력과 도서 특성·점수·사유를 스냅샷합니다.
- 도서·분야별 활성 특성 최대 하나, 도서별 주 분야 최대 하나, FK·버전 고유성과 유한한 [0,1] 점수를 DB에서 검증합니다. 분야 변경은 DB에서 직렬화하며 수정 시각은 트리거로 갱신합니다.
- 데모 SQL은 스키마 migration과 분리되어 `demo` 프로필에서만 실행됩니다.

## 진단 완료와 ML 경계

`POST /api/assessments/{sessionId}/complete`는 9개 답변을 확인한 뒤 짧은 트랜잭션에서 UUID 처리 소유권과 기본 30초 임대를 확보합니다. 계산은 트랜잭션 밖에서 실행하고, 다시 소유권을 확인한 뒤 프로필 저장과 `COMPLETED` 전이를 함께 커밋합니다. 완료 요청을 재시도하면 기존 프로필을 반환하며, 진행 중인 중복 요청은 409입니다. ML 실패 시 내부 상세를 노출하지 않고 세션을 `IN_PROGRESS`로 되돌립니다.

`ml.mode=stub`용 `MlGateway`와 프로필·추천 요청/응답 검증은 구현되어 있습니다. 추천도 짧은 트랜잭션에서 요청/후보 스냅샷과 처리 임대를 저장하고, DB 트랜잭션 밖에서 계산한 뒤 소유권을 재확인하여 항목과 성공 상태를 함께 저장합니다. 응답이 늦어 임대가 만료되거나 ML 계산이 실패하면 내부 상세 없이 실패 상태만 남깁니다. `ml.mode=http`의 RestClient 기반 프로필 호출은 구현됐지만 `/ml/rank` 호출은 후속 단계입니다. HTTP 장애를 stub 성공으로 자동 전환하지 않습니다.

인증 추가 시 클라이언트가 보내는 userId를 신뢰하는 데모 방식을 인증된 `/api/me`로 바꿉니다.

## 협업과 다음 단계

현재 6단계 작업 브랜치: `feat/recommendations-feedback`. main 직접 커밋 금지, 승인 없는 merge/force push 금지. 작은 기능 단위로 커밋하고 동시에 작업하는 팀원은 별도 기능 브랜치를 사용합니다.

- [승인 설계](docs/design.md)
- [2단계 작업 계획](docs/implementation-plan.md)
- [2단계 검증 결과](docs/verification-stage2.md)
- [3단계 구현 계획](docs/superpowers/plans/2026-09-12-catalog.md)
- [3단계 검증 결과](docs/verification-stage3.md)

다음 단계는 `/ml/rank` HTTP 연동과 실제 Python ML 서버를 사용한 전체 E2E 검증입니다. [5단계 검증](docs/verification-stage5.md)과 [6단계 검증](docs/verification-stage6.md)을 참고하세요.
