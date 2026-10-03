# 계정과 개념 진단·추천 통합 — 2026-10-03

## 구현 범위

Backend main `8498a82`(계정·마이페이지, 서재·후기, 진단 근거 API)과 기존 로컬 개념 진단·추천 작업을
`feat/account-concept-integration`에 합쳤다. ML도 main `3dd5d0a`의 `/ml/reader-diagnostics`와
기존 `/ml/concepts/{topic_id}`, `/ml/learning-fit`, concept-abilities-v2를 함께 유지했다.
Frontend는 기존 Expo 브랜치를 기준으로 같은 이름의 기능 브랜치에서 작업했다.
기존 작업의 백업은 상위 `.integration-backups/20261003-150235/`에 있고 Backend/ML stash도 보존했다.

- Expo 앱: 회원가입·로그인·로그아웃, 내 계정 편집, 저장된 준비도와 진단 이력.
- 고정 사용자 ID를 제거하고 토큰의 사용자로 개인 요청을 보낸다. 진단/추천 복원 키와 화면 상태를 계정별로 분리한다.
- `/api/assessments/concepts` 생성의 body userId와 `/api/learning-recommendations` 생성/저장의 소유권을 검증한다.
  기존 인터셉터의 sessionId·runId 검사만으로 새 추천의 `{id}`가 보호되지 않아 서비스에서도 검사한다.
- `/api/topics/{topicId}/concept-map`은 공통 개념과 책 근거만 제공하므로 비회원 조회를 허용한다.
- 웹 프록시는 Authorization을 전달한다. 웹은 sessionStorage, 모바일은 SecureStore를 사용한다.
  이전 계정의 늦은 응답은 새 계정 화면에 적용하지 않는다. 현재 세션의 401은 로그인 해제, 403은 오류로 처리한다.
- HTTP 보안 설정을 Servlet 앱에만 적용하여 비웹 카탈로그/진단 bank import 실행도 가능하게 했다.

## DB 적용 경로

main에 이미 합쳐진 migration은 수정하지 않았다. 미병합 로컬 catalog migration 두 개를
`V20261003020000__record_local_catalog_import.sql`, `V20261003030000__record_discovery_catalog_members.sql`로 옮겨
main 계정 migration 뒤에 정상 순서로 적용한다.

새 DB 및 main의 `20261002090000`까지 적용한 DB에서 기존 계정·소개·암호 해시·세션이 보존되면서
새 개념 추천/카탈로그 테이블이 생성되는 것을 PostgreSQL 통합 검사로 확인했다.
이전 실험 이름의 migration을 이미 적용한 로컬 DB는 별도 보존해야 한다. Flyway history를 자동으로 고치거나
기존 DB를 삭제하지 않았다. 이번 브라우저 검증은 별도 DB를 사용했다.

## 실행과 재현

일반 설치는 [기본 실행](../README.md#로컬-실행)을 따른다. `local` 프로필은 `POSTGRES_*` 설정을 사용한다.
프로필 없이 JAR를 실행할 경우 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 사용한다.
ML은 이 브랜치와 configs를 함께 사용하고 Backend는 `ML_MODE=http`, `APP_AUTH_MODE=required`로 실행한다.

현재 로컬 검증 환경:

| 항목 | 주소/이름 |
| --- | --- |
| 웹 계정 | http://127.0.0.1:5183/account |
| Backend | http://127.0.0.1:8087 |
| ML | http://127.0.0.1:8011 |
| 별도 PostgreSQL | 127.0.0.1:5461 / bookpath |
| 컨테이너 | capstone-account-integration-20261003 |

위 DB는 루프백에만 공개한 일회성 검증용 설정이며 운영 배포 설정이 아니다.
기존 `linear-algebra-live-handoff-v1`의 catalog-manifest와 assessment-manifest로 실도서 3권,
자기평가 8개와 기존 승인 객관식 1개를 등록했다. 기존 다른 검증 DB의 471권 목록과 혼동하지 않는다.
새 후보 18문항은 사람 승인을 받지 않았으므로 활성 bank에 넣지 않았다.

Backend 전체 검사(저장소 루트에서 실행):

```sh
ML_CONTRACT_BASE_URL=http://127.0.0.1:8011 \
LA_HANDOFF_DIR="$PWD/../ML/data/output/linear-algebra-live-handoff-v1" \
DISCOVERY_HANDOFF_DIR="$PWD/../ML/data/output/discovery-catalog-live-v1" \
./gradlew clean build --no-daemon --console=plain
```

두 handoff 디렉터리는 별도로 생성하는 ignored 실데이터 산출물이다. 없다면 해당 환경변수를 생략하고
opt-in handoff 검사가 제외됐음을 기록한다. Docker와 Java 21이 필요하며 테스트는 별도 Testcontainers DB를 사용한다.
Frontend는 `npm run check`, ML은 `.venv/bin/pytest -q`와 `.venv/bin/ruff check .`로 확인한다.

## 검증과 다음 작업

Backend 전체 빌드와 201개 검사(실제 ML/두 handoff 검사 포함, 제외 0개)가 통과했다.
ML 333개 검사와 Ruff, Frontend 타입·린트 및 인증 7개/콘텐츠 5개 검사, 웹·iOS·Android 번들 생성이 통과했다.
실제 Backend/ML/PostgreSQL과 브라우저에서 두 합성 계정의 가입·프로필 편집·새로고침·진단 이어하기/완료·추천 저장/복원·
이력·로그아웃 및 폐기된 세션 복원을 확인했다. 타 계정 결과 조회와 사용자 ID 위조는 403이었다.
320/390/768/1280px에서 가로 넘침과 실행 오류가 없었다. 브라우저 결과와 화면은
`Frontend/native/.local-checks/account-check.json`, `account-*.png`에 있다.

네이티브 실기기 실행, 운영 배포, 추천 정확도는 이번 검증 범위가 아니다.
다음 순서는 생성 후보 18문항의 사람 검토·승인과 개념/능력별 bank coverage 확보다.
이어 도서 유형·실제 텍스트 근거의 품질을 정리하고, 기존 Backend 서재·후기 API를 Expo 화면에 연결한다.
