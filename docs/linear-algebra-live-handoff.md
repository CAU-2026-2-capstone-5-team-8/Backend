# 선형대수 실데이터 전체 연결 — 2026-10-01

2026-10-03: 이 문서는 당시 HTML/JavaScript 화면의 검증 기록입니다. 해당 프론트와 멘토링 fixture는 제거했으므로 아래의 이전 프론트 실행·화면 단계는 현재 사용할 수 없습니다. 서버·데이터 등록 절차는 계속 참고할 수 있으며, 현재 RN 앱의 실행은 [Frontend 문서](../../Frontend/README.md), 새 진단·추천 계약은 [개념 진단·추천 설계](concept-learning-v1.md)를 따릅니다.

선형대수 실도서 3권과 혼합 진단 9문항으로 브라우저 답변 → 서버 저장·채점 → Python ML 프로필 → 추천 → PostgreSQL 저장 → 화면 표시 → 새로고침 재조회를 확인했다. 서비스 연결 검증이며 진단의 측정 타당성·추천 정확도 검증은 아니다.

## 변경 범위

Backend, ML, Frontend 모두 `feat/linear-algebra-live-handoff` 브랜치에서 작업했다. Backend·ML은 당시 main에서, Frontend는 기존 `feat/mentor-demo-ranking-fixture`에서 분기했다. 커밋·push·PR 생성·병합은 하지 않았다. Data-Pipeline와 Question-Generation의 코드·원본 산출물은 수정하지 않았다.

- Backend: 명시적 로컬 도서 import, 혼합 문항 bootstrap, 등록 이력용 새 migration, PostgreSQL 회귀·실제 ML 연결 테스트.
- ML: SHA-256으로 고정한 파일의 handoff 생성, 실제 HTTP 검증 스크립트, REST Client 예제와 실행 응답. 기존 계산 알고리즘은 그대로 사용한다.
- Frontend: 세션 ID와 추천 ID 보관, 완료 진단과 저장 추천의 새로고침 복원, 자기평가 표시. 멘토링 fixture를 유지한다.

## 데이터와 선정 이유

스냅샷: `discovery-la-live-handoff-20260929-v1`. 원본 feature version은 `book-v1`, config version은 `features-v1`이며 등록 이름으로 바꾸지 않는다.

| canonical book_id | 도서 | 현재 개념 근거 |
| --- | --- | --- |
| isbn13:9788952117441 | 선형대수와 군 — 이인석 저 | basis, diagonalization, dimension, gaussian elimination, inner product, matrix, orthogonality, rank, vector, vector space의 목차 근거 |
| isbn13:9788961055680 | 현대 선형대수학 — 이상구,김덕선 공저 | rank의 목차 근거 |
| isbn13:9788970505329 | 인공지능 시대의 선형대수학 — 김대수,김경동 저 | rank의 목차 근거 |

당시 개인화 가능 6권에서 편입 문제집 2권을 제외했다. 나머지 `isbn13:9788964214428`은 현재 연결된 개념이 description 근거뿐이라 첫 검증에서 제외했다. 그래서 약 5권 목표보다 적은 3권으로 진행했다. 다른 시험용 도서도 이번 대상에 포함하지 않는다. 개인화 가능 여부는 독자 프로필의 평가 범위에 따라 달라진다. 새로운 난이도·개념 점수는 만들지 않았다. 원본의 scalar null과 빈 prerequisite 배열을 유지하고, 추천 알고리즘이 그래프에서 추론한 선수개념은 응답의 별도 필드로 확인한다.

고정 입력과 모든 SHA-256은 ML의 [pin manifest](../../ML/configs/experiments/linear-algebra-live-handoff-v1.json)에 있다. 준비 결과 `selection.json`은 개념별 목차 경로와 source-aware supporting evidence도 보존한다.

진단은 영역마다 3개씩 **자기평가 8개 + 승인된 v4 제시문 독해 1개**이다. 자기평가에는 `[자기평가]`를 표시하고 승인 provenance를 부여하지 않는다. v4의 기존 review·grounding·hash 검증을 그대로 통과시켜 등록한다. 생성 문항만으로 구성한 진단은 아직 아니다.

## 1. 고정된 로컬 handoff 준비

필요 환경은 Python 3.12 이상, ML의 `.venv`와 의존성, Java 21, 실행 중인 Docker, Node.js 22 이상이다. 이 PC에서는 `/opt/homebrew/opt/openjdk@21`을 사용했다. 아래는 캡스톤 폴더를 기준으로 한 **명시적 경로 예시**이며 프로그램이 형제 저장소를 자동 탐색하지 않는다.

```sh
cd /Users/noir1458/git/capstone/ML
.venv/bin/python scripts/prepare_linear_algebra_handoff.py \
  --canonical-dir ../Data-Pipeline/data/experiments/discovery-600-20260929/topics/linear-algebra/toc-only-processed \
  --pilot-dir data/output/discovery-la-ranking-pilot-20260929 \
  --generated-question ../Question-Generation/data/generated/la-matrix-comprehension-v4-rev1.json \
  --reviews ../Question-Generation/reviews/linear-algebra-display-grounded-comprehension-v1.jsonl \
  --grounding data/output/linear_algebra_matrix_grounding_v2.json \
  --output-dir data/output/linear-algebra-live-handoff-v1
```

출력 폴더는 새 폴더여야 한다. 재등록은 기존 manifest를 그대로 사용한다. 다시 준비할 때는 다른 출력 폴더를 지정한다. 기존 원본·출력 폴더를 덮어쓰지 않는다.

원본 데이터는 Git에서 제외되어 있으므로 새 clone만으로 위 명령이 성공하지 않는다. 기존 실험을 가진 팀원에게 pin manifest에 적힌 4개 canonical 파일·3개 pilot 파일 및 승인 문항·review·grounding 파일을 받아 명시한 위치에 두어야 한다. 없는 파일은 오류로 중단하며 재수집이나 임의 데이터로 대체하지 않는다. 다른 수집 버전은 hash 검증을 통과하지 못한다. 승인 문항 자료는 Backend의 기존 `src/test/resources/fixtures/question-handoff/`에도 동일 bytes로 보존되어 있다.

## 2. 격리된 DB 준비

기존 개발 DB나 볼륨 대신 새 이름·포트를 사용한다. 다음 명령은 Backend 폴더에서 실행한다. 동일 이름의 컨테이너가 이미 있으면 별도 이름과 빈 포트를 선택한다.

```sh
cd /Users/noir1458/git/capstone/Backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
export DB_URL=jdbc:postgresql://127.0.0.1:5457/backend_la_live
export DB_USERNAME=backend_local
export DB_PASSWORD="$POSTGRES_PASSWORD"
export ML_MODE=http
export ML_BASE_URL=http://127.0.0.1:8011
export SERVER_PORT=8087
docker run --name capstone-la-live --rm -d \
  -p 127.0.0.1:5457:5432 \
  -e POSTGRES_DB=backend_la_live -e POSTGRES_USER=backend_local \
  -e POSTGRES_PASSWORD postgres:17.11
docker exec capstone-la-live pg_isready -U backend_local -d backend_la_live
./gradlew bootJar --no-daemon --console=plain
```

`pg_isready`가 성공한 후 다음 단계로 간다. 이 컨테이너는 검증용이며 종료 시 데이터가 사라진다. 비밀번호는 출력·커밋하지 않는다. 다른 터미널에서 Backend를 실행한다면 같은 DB 환경변수를 전달한다.

## 3. 도서 등록과 재등록

같은 터미널에서 실행한다. `demo` 프로필은 함께 켜지 않는다.

```sh
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --spring.profiles.active=local-catalog-import \
  --catalog-import.manifest-path=/Users/noir1458/git/capstone/ML/data/output/linear-algebra-live-handoff-v1/catalog-manifest.json
```

`CATALOG_IMPORT_RESULT`에 topic ID, canonical ID별 book/projection ID와 `replayed`가 나온다. 같은 명령을 다시 실행하면 동일 ID와 `replayed:true`가 나온다. 일반 서버 시작은 데이터를 등록하지 않는다.

입력 `catalog-manifest.json`은 계약 버전, snapshot ID, books/candidates 경로와 SHA-256, 선택 book_id, 분야 code/name/parent/ml_topic_id를 모두 명시한다. canonical → book → book_topic → 활성 projection을 한 트랜잭션으로 등록한다. 기존 ISBN·ml_book_id·분야 매핑·원본 버전 내용이 다르면 전체 취소하고 실패한다. 기존 데이터를 병합·덮어쓰기·비활성화하지 않는다. 동일 snapshot ID는 manifest bytes도 같아야 한다.

`local_catalog_import`는 manifest와 도서·후보 hash를 기록한다. projection의 `source_artifact_version`은 snapshot ID, `source_artifact_hash`는 candidates hash이며 `version`은 원본 ML feature version이다. 다른 활성 버전이 있으면 명시적인 별도 전환 작업이 필요하다.

## 4. 사용자와 문항 bootstrap

```sh
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --spring.profiles.active=local-assessment-bootstrap \
  --assessment-bootstrap.manifest-path=/Users/noir1458/git/capstone/ML/data/output/linear-algebra-live-handoff-v1/assessment-manifest.json
```

`ASSESSMENT_BOOTSTRAP_RESULT`의 userId/topicId를 이후 API와 화면에 사용한다. 이번 빈 DB에서는 userId=1, topicId=2였다. 기존 DB에서는 ID를 가정하지 않는다. 동일 명령 재실행은 동일 사용자·문항 ID를 유지한다. 해당 분야에 다른 활성 문항이 있으면 실패하며 기존 bank를 보존한다. 9개·영역별 3개 정책을 완화하거나 기존 문항을 삭제하지 않는다.

## 5. 서버와 REST Client 실행

ML 폴더의 별도 터미널:

```sh
BOOKMATCH_ML_CONFIG_DIR="$PWD/configs" \
  .venv/bin/uvicorn bookmatch_ml.api:create_app --factory --host 127.0.0.1 --port 8011
```

DB 환경변수를 설정한 Backend 터미널:

```sh
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

Frontend 폴더의 별도 터미널:

```sh
BACKEND_URL=http://127.0.0.1:8087 PORT=5177 npm start
```

- ML 직접 호출: [linear-algebra-live.http](../../ML/examples/linear-algebra-live.http). `laProfile`을 먼저 실행하고 `laRank`를 실행한다. 프로필 점수·8개 readiness·config provenance는 실제 첫 응답에서 가져온다. 합성 답변만 입력이며 계산 결과를 하드코딩하지 않는다.
- Backend: [linear-algebra-live.http](../requests/linear-algebra-live.http). bootstrap 결과로 userId/topicId를 맞추고 위에서부터 실행한다. JSONPath가 섞인 발급 순서에서도 개념·영역·answer mode로 발급 ID를 찾는다. 자기평가는 `knowsConcept`, 객관식은 `selectedChoiceIndex`를 보낸다. 이후 응답의 세션·사용자·분야·추천 ID를 연결한다.
- 변경 답변 비교는 새 세션부터 시작한다. 완료 후 답변 수정은 409이다. 추천 요청을 반복하면 동일 결과를 조회하고, 같은 키로 topK를 바꾸면 409이다.

설치된 VS Code REST Client 0.25.1의 JSONPath 모듈을 사용해 두 예제의 모든 변수를 치환하고 실제 HTTP 요청을 실행했다. Backend 생성 201, 9개 답변·완료 200, 추천 201, 재조회 200을 확인했다. 확장 UI에서 버튼을 누르는 과정 자체는 자동화하지 않았다.

## 6. 브라우저 검증

<http://127.0.0.1:5177>에서 체험 계정 설정에 bootstrap 사용자 번호를 입력하고 선형대수를 선택한다. 9문항을 저장하고 프로필·추천을 확인한 뒤 새로고침한다.

브라우저는 진단 ID와 추천 run ID만 보관한다. 새로고침 시 진단과 완료 프로필을 Backend에서 읽고 `GET /api/recommendations/{runId}`로 저장 추천을 가져온다. 완료 API 재호출은 같은 프로필을 반환한다. 새로운 추천 POST를 보내지 않는다. 저장 추천의 사용자·분야·profile ID도 대조한다. 응답 본문·점수를 브라우저 저장소에 복사하지 않는다.

2026-10-01 Chrome 실검증에서 session=1, profile=1, run=1이었다. 자기평가 모두 true와 승인 객관식 index=3을 입력해 세 영역 1.0, 3권 추천, 부족 2권을 확인했다. 새로고침 전후 profile/run/item 행 수는 1/1/3으로 같았으며 9개 답변과 객관식 서버 채점 `correct=true`를 DB에서 확인했다. 현대 선형대수학·인공지능 시대의 선형대수학의 direct learning opportunity는 null이며 화면은 ‘평가 근거 없음’을 표시했다. 정답 key와 해설은 진단 API/화면에 노출되지 않았다.

화면과 DOM 기록은 로컬 `ML/data/output/linear-algebra-live-handoff-v1/browser/`에 있다. 새 브라우저에서는 새 진단을 시작하면 된다.

이후 동일 사용자로 비교·REST Client 진단을 추가 실행한 뒤에도 기존 브라우저의 run=1이 복원됨을 확인했다. 이때 새로고침 전후 profile/run/item 행 수는 5/5/15로 유지됐다. 최신 프로필로 이전 추천을 바꾸지 않고 해당 진단에 연결된 프로필·추천을 조회한다.

## 7. 자동 검증과 실제 응답

Backend 전체 148개 테스트 통과. 실도서 packet과 실제 Python 서버를 사용한 새 `LinearAlgebraLiveHandoffTest`도 실행했으며 skip은 0이었다.

```sh
ML_CONTRACT_BASE_URL=http://127.0.0.1:8011 \
LA_HANDOFF_DIR=/Users/noir1458/git/capstone/ML/data/output/linear-algebra-live-handoff-v1 \
  ./gradlew test --no-daemon --console=plain
```

새 Testcontainers DB에서 정상 import·재실행 ID 유지·충돌/잘못된 hash 거부·전체 rollback을 확인했다. 실제 ML 테스트는 객관식 오답 index=0의 서버 채점, comprehension=2/3, 원본 candidates와 실제 ML 요청의 **모든 필드 동일성**, config hash, 저장 profile과 추천의 연결, 재호출 시 ML 호출 1회 및 저장 행 수를 검증한다. 환경변수를 지정하지 않으면 실제 ML 테스트는 skip된다.

ML 전체 305개 테스트와 Ruff 검사/format, Frontend 19개 테스트와 구문 검사도 통과했다. 변경하지 않은 Data-Pipeline·Question-Generation의 전체 테스트는 이번 최종 검증에서 재실행하지 않았다.

ML 폴더에서 실제 HTTP 비교를 재실행한다. 결과 폴더는 새 경로를 선택한다.

```sh
.venv/bin/python scripts/verify_linear_algebra_live.py \
  --packet-dir data/output/linear-algebra-live-handoff-v1 \
  --output-dir data/output/linear-algebra-live-check-new \
  --ml-url http://127.0.0.1:8011 \
  --backend-url http://127.0.0.1:8087 --user-id 1 --topic-id 2
```

기록은 각 요청의 method/url/body/status/response와 `summary.json`이다. 실제 실행의 성공 기록은 로컬 `ML/data/output/linear-algebra-live-handoff-v1/verification-v2/` 및 `rest-client-v2/`에 있다. 합성 true/false 답변은 새로운 session 2/3에서 별도 계산했고 프로필·추천 입력값이 달랐다. 반복 계산은 같은 JSON 값, readiness 없는 일반 추천은 200·0권·부족 5권, 개인화 불가능한 특정 책 요청·범위를 벗어난 score·중복 candidate는 422였다. Backend 같은 키의 변경 입력과 완료 후 수정은 409였다. 순위 변경을 성공 조건으로 두지 않았다.

원문 없이 공유 가능한 실제 요청·응답 예시:

- [ML 프로필](../../ML/examples/linear-algebra-live/ml-profile.json), [ML 추천](../../ML/examples/linear-algebra-live/ml-rank.json), [평가 범위 없음](../../ML/examples/linear-algebra-live/ml-rank-unassessed.json), [잘못된 score](../../ML/examples/linear-algebra-live/ml-invalid-score.json).
- [Backend 완료](examples/linear-algebra-live/backend-complete-known.json), [추천](examples/linear-algebra-live/backend-recommend-known.json), [중복 키 충돌](examples/linear-algebra-live/backend-idempotency-conflict-known.json).

## 남은 작업과 종료

한국어 개념 매칭 개선, 승인 생성 문항 확장, 추천 품질 평가, 로그인·피드백 화면은 후속 작업이다. 이번 3권 중 2권은 현재 rank 근거만 연결되어 있으므로 개념 범위가 좁다. 8개 자기평가를 승인된 진단 문항으로 바꾸고 평가 범위를 넓혀야 한다. 완전한 projection 버전 전환·여러 데이터셋 병합은 이번 import에 포함하지 않았다.

검증 서버는 각 실행 터미널에서 Ctrl+C로 종료한다. 새로 만든 검증 DB만 종료하려면 `docker stop capstone-la-live`를 사용한다. 이번 실제 실행 컨테이너 이름은 `capstone-la-live-20261001`이다. `--rm` 컨테이너이므로 stop하면 그 검증 DB는 사라진다. 기존 컨테이너·볼륨은 건드리지 않았다.

## 후속 완료: 대량 수집 카탈로그 연결 (2026-10-01)

실제 실행 화면: `http://127.0.0.1:5177/?view=catalog`. 같은 화면에서 선형대수 진단을 시작할 수 있다. 기본 `/`는 저장한 진단·추천을 복원한다. Backend `8087`, Python ML `8011`, Frontend `5177`을 사용하는 기존 격리 DB를 확장했다. 운영 DB 배포는 하지 않았다.

600개 검색 항목을 471개 고유 도서로 정규화한 **2026-09-29 canonical processed 원본**을 고정했다. 목차가 있는 고유 도서는 439권이다. 최신 수집 PR이나 재수집 결과로 바꾸지 않았다.

| 분야 | 도서 | 목차 확보 | 이번에 등록한 rank-v2 후보 |
|---|---:|---:|---:|
| 선형대수 | 98 | 83 | 83 |
| 운영체제 | 54 | 49 | 0 |
| 알고리즘 | 70 | 69 | 0 |
| 데이터베이스 | 93 | 90 | 0 |
| 이산수학 | 92 | 84 | 0 |
| 확률과 통계 | 64 | 64 | 0 |
| 합계 | 471 | 439 | 83 |

`DiscoveryCatalogImportService`와 별도 opt-in profile `discovery-catalog-import`를 추가했다. manifest가 6분야의 canonical books SHA-256, 원본 4종 hash, 분야 매핑, coverage 파일 hash, 선택적으로 분석된 candidate 파일을 지정한다. `discovery_catalog_import/member`에 snapshot별 출처와 확보 수를 기록한다. 목차 본문/소개문을 이 DB에 복제하지 않았다. canonical books 메타데이터와 확보 수, 실제 TOC 매칭에서 만든 LA projection이 연결된다. 다른 5분야에는 빈 개념 배열로 가짜 projection을 만들지 않는다.

471권 등록은 기존 3권을 재사용하고 468권을 추가했다. LA projection은 기존 3개를 재사용하고 80개를 추가했다. 기존 projection의 값·ID·원본 출처는 그대로다. manifest에 명시한 기존 snapshot/hash를 import 이력과 대조하고 **모든 candidate 필드가 동일할 때만** 재사용한다. 같은 feature version의 값 충돌이나 묵시적 출처 교체는 거부한다. 기존 3권 importer의 strict 동작도 유지했다. 같은 bulk manifest 재실행은 `replayed=true`이고 행을 추가하지 않는다.

### 재현 순서

처음부터 시작할 때는 위 3권 packet 준비, catalog import, 9문항 bootstrap을 먼저 실행한다. 이후 ML 폴더에서 전체 dataset을 준비한다. output-dir는 새 경로여야 하며 원본 파일은 수정하지 않는다.

```sh
.venv/bin/python scripts/prepare_discovery_catalog_handoff.py \
  --canonical-topics-dir ../Data-Pipeline/data/experiments/discovery-600-20260929/topics \
  --la-pilot-dir data/output/discovery-la-ranking-pilot-20260929 \
  --la-packet-dir data/output/linear-algebra-live-handoff-v1 \
  --output-dir data/output/discovery-catalog-live-v1
```

Pin은 `ML/configs/experiments/discovery-catalog-live-v1.json`이며 모든 canonical hash와 원래 LA83 실험의 mapping/profile/candidate hash를 확인한다. source-aware adapter 재계산과 candidate의 동일성을 검사한다. 누락·변경·기존 output은 실패한다. 자동 재수집은 하지 않는다.

Backend 폴더에서 DB 환경변수를 설정하고 실행한다. 기존 user/profile/recommendation 테이블을 비우지 않는다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew bootJar --no-daemon --console=plain
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --spring.profiles.active=discovery-catalog-import \
  --discovery-catalog-import.manifest-path=/Users/noir1458/git/capstone/ML/data/output/discovery-catalog-live-v1/catalog-manifest.json
```

그다음 기존 설명대로 `ML_MODE=http` 백엔드와 FE를 실행한다. `GET /api/books/summary`가 전체·분야별 도서/목차/분석 후보/개념 연결 도서 수를 반환한다. 도서 목록·상세에는 `mlBookId`, 분야별 `tocEntryCount`(미확인=null, 미확보=0), `rankingCandidate`, `coveredConceptCount`(분석 전=null, 분석했으나 매칭 없음=0)를 추가했다. legacy `featureAvailable` 의미는 그대로다. `GET /api/topics`의 `assessmentReady`는 각 영역의 active 문항이 3개 이상일 때 true다. 현재 LA만 true이며 FE에서 다른 분야는 진단 준비중으로 표시된다.

### 실제 검증 기록

- Backend **153개**, ML **308개**, Frontend **22개** 테스트 통과. Backend 실제 ML 테스트 skip 0.
- `DiscoveryCatalogImportIntegrationTest`: 두 분야 메타데이터, 미분석 null, 확보 0, 페이지 경계, 재등록 ID 보존, 충돌/중복/고아 행 전체 rollback, 명시적 기존 출처 인정과 값 충돌 거부, 진단 문항 부족 상태를 검사한다.
- `DiscoveryCatalogLiveHandoffTest`: 새 Testcontainers DB에서 먼저 3권으로 실제 추천을 저장한 뒤 bulk import. 471권·439목차·83후보·11개념연결 확인, 기존 3개 projection 출처/추천 보존, 전 페이지·6필터 확인. 실제 ML로 전송한 83권의 ID와 모든 필드가 원본 wire와 동일하다. 후보 배열은 DB ID 순서여서 순서 대신 ID별 모든 값을 비교한다.
- 같은 로컬 DB 확장 전후 기존 5개 profile/5개 run/15개 item 그대로였다. import 재실행도 같은 값이다. 기존 추천 번호 1의 응답을 확장 후 다시 비교해 동일함을 확인했다.
- 실제 HTTP 검증에서 새 session/run **6·7** 생성. 응답에 후보83, 개인화가능6, concept-only5, 근거부족72, Top-5 5권이 기록됐다. 서로 다른 답변의 readiness/점수가 다르고, 동일 요청은 동일 결과다. 평가된 readiness가 없을 때 추천0권, 중복 candidate422, 완료 후 답변 수정409도 확인했다.
- Chrome에서 6분야 필터, 페이지 이동, 도서 상세, 다른 분야 진단 준비중, 9문항 답변 저장, 객관식 오답의 comprehension=2/3, 실제 Top-5를 확인했다. session/run **8**을 새로고침해 같은 추천 복원. 전후 profile8/run8/item30으로 행 수 불변.

실행 기록은 로컬 ignored `ML/data/output/discovery-catalog-live-verification-v1/`의 요청/응답/summary와 브라우저 기록에 있다. 화면 캡처는 `ML/data/output/discovery-catalog-live-v1/{catalog,recommendation}.jpg`다. 재검증은 이전 추천 GET 응답을 파일로 저장한 뒤 새 output-dir로 아래를 실행한다.

```sh
.venv/bin/python scripts/verify_discovery_catalog_live.py \
  --packet-dir data/output/discovery-catalog-live-v1 \
  --assessment-packet-dir data/output/linear-algebra-live-handoff-v1 \
  --output-dir data/output/discovery-catalog-live-check-new \
  --backend-base http://127.0.0.1:8087 --ml-base http://127.0.0.1:8011 \
  --user-id 1 --previous-run-json /path/to/previous-run.json
```

전체 Backend 검증에는 추가 환경변수를 설정한다.

```sh
ML_CONTRACT_BASE_URL=http://127.0.0.1:8011 \
LA_HANDOFF_DIR=/Users/noir1458/git/capstone/ML/data/output/linear-algebra-live-handoff-v1 \
DISCOVERY_HANDOFF_DIR=/Users/noir1458/git/capstone/ML/data/output/discovery-catalog-live-v1 \
  ./gradlew test --no-daemon --console=plain
```

### 후속 우선순위와 범위

대량 데이터의 메타데이터·확보 상태는 FE까지 연결됐고 LA83은 실제 추천에 연결됐다. 다른 5분야는 목록 조회까지다. 분야별 개념 사전/분석 projection·진단 문항을 준비하지 않아 추천 완료라고 할 수 없다. LA의 한국어 개념 매칭도 변경하지 않아 83권 중 개념 연결은 여전히11권, 이번 답변에서 개인화가능6권이다. 수집 목록은 선별 전이고 Top-5에 편입 문제집이 포함된다. 다음 우선순위는 **LA 한국어 개념 매칭 기준선 개선과 추천 대상 선별**, 이후 승인 진단 문항 확장과 다른 분야 연동이다.

이번 작업은 Backend·ML·Frontend의 `feat/discovery-catalog-live-handoff` 브랜치에서 진행했고 이전 단계 미커밋 변경을 함께 보존했다. 커밋·push·PR·병합·외부 배포는 하지 않았다. 앞서 만든 `capstone-la-live-20261001` 로컬 검증 DB를 계속 사용하며 실행 서버를 열어두었다. 이 DB는 기존 단계에서 만든 `--rm` 컨테이너로 중지 시 사라진다. 이번 도서·진단·추천 결과는 ignored `ML/data/output/discovery-catalog-live-v1/local-validation.sql`에 `pg_dump --schema=backend --no-owner --no-privileges`로 백업했다. 복원은 별도의 빈 검증 DB에만 수행한다.
