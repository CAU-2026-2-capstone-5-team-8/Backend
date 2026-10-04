# 남은 작업과 현재 상태

확인일: 2026-10-05 (Asia/Seoul). 원격 main과 열린 PR, 로컬 통합 검사를 대조했다.
아래 과거 기록의 “ML HTTP 미구현”·“실데이터 ingestion 없음”·“인증 없음”은 현재 상태가 아니다.

## 완료된 구현

| 범위 | 현재 확인한 근거 |
| --- | --- |
| 계정·소유권, 진단·프로필·추천 저장/복원, 서재·후기 | Backend main 및 Frontend main |
| 개념·능력별 진단 및 AI 검토 이력을 분리한 18문항 import | Backend #32, Question-Generation #7 |
| 책 안/밖 선수개념과 출처를 보존하는 준비도 v2 | ML #38, Backend #33 |
| Expo 서점형 화면, v2 요청/재조회, 표지 메타데이터 | Frontend #9, Backend #34 |
| 기존 웹 릴리스 통합 검증 | Frontend `docs/release-verification-2026-10-04.md`의 해당 환경 기록 |

원격 main 기준점: Backend `554badc`, Frontend `5d15406`, ML `88b0177`.
이는 코드 상태이며 현재 실행 중인 모든 서비스나 DB가 같은 버전이라는 뜻은 아니다.
AI 검토는 사람 승인 또는 실증적 문항 타당성을 대신하지 않는다.

## 우선순위 1 — 안정화 변경 마무리

- [Backend #35](https://github.com/CAU-2026-2-capstone-5-team-8/Backend/pull/35):
  ML 계산을 DB 트랜잭션 밖으로 이동. 처리 중 중복은 409, 성공 결과는 재조회,
  실패/lease 만료 후 재시도와 늦은 응답의 덮어쓰기 방지.
- [Backend #36](https://github.com/CAU-2026-2-capstone-5-team-8/Backend/pull/36)와
  [Frontend #10](https://github.com/CAU-2026-2-capstone-5-team-8/Frontend/pull/10):
  명시적으로 신뢰한 프록시의 접속자 주소만 사용하고 IP·계정별 로그인 제한 적용.
  운영 프로필에서는 stub/demo와 전달 헤더 자동 재작성을 차단.
- 세 PR은 확인 시점에 OPEN이며 각각 원격 CI가 성공했다. 로컬 통합 브랜치
  `integrate/stability-20261005`에서는 충돌 없이 결합했다. main 병합/배포는 별도 상태다.

### 2026-10-05 통합 검증

Backend #35 `1db1e35`와 #36 `e2177f5`를 main 위에 결합한 상태에서
Java 21 + PostgreSQL Testcontainers `clean build`: **245개 중 238통과,
7개 조건부 검사 제외, 실패/오류 0**. 제외 검사는 실 ML 또는 로컬 handoff 입력이 필요하다.

실제 Node 웹 프록시 → production 프로필 Backend → 별도 PostgreSQL에서도
45개 잘못된 로그인 요청을 검사했다. 서로 다른 12개 주소는 각각 401,
같은 계정으로 IP를 바꾼 11번째 요청은 429, 위조 체인과 비신뢰 헤더로
IP 제한을 우회하려는 11번째 요청도 429였고 Retry-After가 전달됐다.
계정 행은 0개로 유지됐으며 검사 서버와 일회성 DB를 종료했다.
ML 계산은 이 로그인 검사에서 호출하지 않았다.

Frontend의 두 실제 접속자 테스트는 macOS에 없는 `127.0.0.2/3` 대신
IPv4/IPv6 루프백을 사용하도록 보완했다. 운영 안내에는 production 프로필과
각 프록시 구간의 좁은 신뢰 주소 설정을 추가했다.

## 우선순위 2 — 콘텐츠와 평가 범위 확장

1. 사용할 DB/분야/문제은행 스냅샷을 고정하고 개념·능력별 문항 수를 확인한다.
   과거 LA 18개 AI 검토 문항 환경과 OS 자기평가 8개+객관식 1개 환경을 혼동하지 않는다.
2. 사람 검토와 실제 응답 데이터로 문항 정답·오개념·난이도를 확인한다.
   준비도 관찰 수를 숙달도나 심리측정 타당성으로 해석하지 않는다.
3. 도서 유형, 원문/영문 목차의 개념 매칭, 선수관계 및 본문 근거를 검토한다.
   미매칭/본문 없음/다른 판본은 명시적으로 미확인 상태를 유지한다.
4. 동일한 고정 자료에 독립적인 사람 평가를 붙여 추천 품질을 비교한다.
   synthetic 응답과 저장/화면 테스트는 추천 정확도 평가가 아니다.

## 이후 기능·운영 범위

- 이메일 인증과 비밀번호 재설정: 미구현. 공개 운영 대상과 계정 복구 요구에 따라 별도 설계.
- 책장 사진 인식: 계획만 있음. 카탈로그 식별과 사용자 확인부터 범위를 정하고
  OCR 결과를 개념 이해도나 책 난이도 근거로 사용하지 않는다.
- 공개 운영: 도메인/TLS, 비밀값, ingress 주소/접근제어, DB 백업/복구, 확정된
  데이터 import 및 적용 순서를 환경에 맞춰 확인해야 한다.
- 네이티브: 번들 생성 기록과 iOS/Android 실기기 검증은 별개다.
- 기존 Data-Pipeline 작업트리의 미커밋 영문 분석 변경은 보존했다.

## 보존한 과거 기록 — 2026-09-16

아래는 당시 구현 계획이다. 현재 미완료 작업 목록으로 사용하지 않는다.

---

# 남은 구현 단계 분석 (7~8단계)

갱신일: 2026-09-16. 현재 구현과 앞으로의 계획을 구분한다. 6단계 변경은 `feat/recommendations-feedback` 브랜치 기준이며, main 병합 여부와는 별개다.

## 현재 상태

- 구현됨: 카탈로그 조회 3개, 진단 생성·재조회·답변 저장 3개, 완료·최신 프로필 2개, 추천·재조회·피드백 3개, 총 11개 API.
- 검증됨: 5단계 전체 빌드와 6단계 대상 PostgreSQL 통합 테스트·ML 랭킹 계약 테스트 통과. 6단계 전체 빌드 결과는 `verification-stage6.md`에 기록한다.
- 미구현: 7~8단계의 실제 HTTP ML 연동, 전체 E2E.

## 4단계 — 구현된 답변 저장·재조회와 남은 검증

### GET /api/assessments/{sessionId}
- 세션 상태와 발급 문항, 저장된 `knowsConcept`를 반환한다. 미응답은 `null`이다.
- 존재하지 않는 세션은 404다.

### PUT /api/assessments/{sessionId}/answers/{assessmentQuestionId}
- 요청은 `{"knowsConcept": true}` 또는 `{"knowsConcept": false}`다. 누락/null은 400이다.
- 다른 세션 소속 문항은 404, 처리 중·완료된 세션은 409다.
- 첫 답변 저장 시 CREATED → IN_PROGRESS로 전환하고, 재제출은 기존 답변을 교체한다.
- 세션 행에 비관적 쓰기 잠금을 사용한다. 완료 API도 같은 잠금을 사용한다.

### 아직 보강할 검증
- 서로 다른 연결에서 같은 문항을 동시에 제출해 중복 없이 저장되는지 확인.
- 문항 원본 변경 후 기존 세션의 문항·영역·버전 스냅샷이 유지되는지 확인.
- 답변 저장과 완료 요청의 실제 동시 경쟁 검증.

## 5단계 — 구현된 진단 완료·프로필 계산

아래 상태 머신과 API는 `feat/assessment-completion-profile` 브랜치에 구현했다.

### POST /api/assessments/{sessionId}/complete
- 9개 답변이 모두 존재하는지 확인하고 부족하면 상태 충돌 409 반환
- 입력을 얼려(freeze) UUID `attemptId` 발급 + 30초 lease 설정, 커밋 후 ML 호출 (`MlGateway.profile`)
- 두 번째 짧은 트랜잭션에서 현재 attempt가 아직 세션의 소유인지 재확인한 뒤 `reader_profile` 원자적 저장 + `COMPLETED` 전이
- **재시도 시맨틱**: 이미 완료된 세션에 다시 호출하면 기존 프로필을 그대로 반환 (200)
- **동시 완료 요청**: 이미 처리 중인 세션에 또 완료 요청 → 409
- **만료된 attempt**: 30초 지나면 다음 완료 요청이 attempt를 교체할 수 있고, 늦게 도착한 이전 attempt의 응답은 새 attempt를 덮어쓰면 안 됨
- **ML 실패 시**: 정제된 실패 사유를 `last_failure_code`/`last_failure_message`에 저장하고, 저장 시점에 여전히 그 attempt가 세션 소유자라면 `IN_PROGRESS`로 복구
- **DB 자체가 끊긴 경우**: 복구는 lease 만료에 맡기고 별도 보정 로직은 만들지 않음 (설계 문서 명시)

### GET /api/users/{userId}/profiles/{topicId}
- 완료 시각 내림차순, 동률이면 ID로 최신 프로필 1건 조회
- 프로필 없음 → 404 (추천 생성의 프로필 없음은 별도 상태 충돌이므로 409)

### 필요한 새 구성 요소
- `reader_profile` 테이블 마이그레이션 (세션 UNIQUE, 능력 3종, 계산 버전, evidence JSONB)
- `MlGateway` 인터페이스 + `ml.mode=stub` 구현체 (설계 문서의 stub 프로필 공식: 영역별 안다 응답 수/발급 수, 문항당 1점)
- 기존 `findByIdForUpdate` 비관적 잠금을 완료 처리에도 재사용

## 6단계 — 구현된 추천·피드백

### POST /api/recommendations
- 요청: `userId`, `topicId`, `targetBookId?`, `challengeLevel`, `topK=5`, `Idempotency-Key` 헤더
- **Idempotency-Key 처리**: 같은 키+같은 바디 → 기존 run 그대로 반환(200); 같은 키+다른 바디 → 409; 처리 중 → 409; 실패 기록 재생
- Top-K 모드 vs 특정 도서(target) 모드 분기 — target 모드는 topK 무시하고 1건만 반환
- 후보 도서: 분야에 직접 연결 + 활성 feature 보유 도서만 (feature 없는 후보는 제외하고 개수 기록)
- 후보 없음/target feature 없음 → 422
- 프로필 없음 → 409
- 랭킹 스냅샷: 선택된 프로필, feature 값/버전, 설정, ML 모드/모델 버전을 고정 저장

### GET /api/recommendations/{runId}
- 상태별 응답 분기 (처리 중/완료/실패) — 실패는 정제된 사유만 노출

### POST /api/recommendations/{itemId}/feedback
- `userId`가 추천 소유자와 일치하는지 검증 (불일치 시 상태 충돌 409; MVP에 인증/403 계약 없음)
- 재제출 시 UNIQUE(user, item) 제약으로 교체(200) vs 최초 제출(201)

### 추가한 구성 요소
- `recommendation_run`, `recommendation_item`, `feedback` 테이블 마이그레이션
- `MlGateway.rankBooks` stub 구현 (설계 문서의 stub 랭킹 공식: dimension fit, topic fit, 산술 평균, 동점 시 ID 오름차순, reason 최소 2개)
- 사용자 행 잠금과 추천 처리 임대·시도 ID로 동시 중복 호출과 늦게 끝난 계산을 방어; 실패 사유는 정제해서 저장

## 7단계 — ML HTTP 연동

- `ml.mode=http`일 때 `RestClient` 기반 어댑터 구현 (connect timeout 2s, response timeout 10s)
- stub과 동일한 응답 검증 로직 공유 (필수 필드, 유한/범위 값, 중복·미요청 도서, 결과 개수/순위, 버전 호환성 거부)
- **fallback 없음**: HTTP 실패를 stub 성공으로 대체하지 않음, 자동 재시도도 없음
- WireMock standalone 3.13.2로 계약 테스트 (실제 Python 서비스 없이 검증)

## 8단계 — 전체 E2E 및 인수인계

- 분야 조회 → 진단 생성 → 답변 → 완료 → 프로필 조회 → 추천 → 피드백 전체 흐름 통합 테스트
- README/설계 문서/`docs/verification-stage*.md` 최종 정리
- 팀 인수인계용 요약 (Google Drive 진행 기록 문서와 동기화)

## 남은 결정 사항

1. 현재 세션 비관적 잠금과 완료 처리의 실제 동시 경쟁 테스트

5단계에서는 9개 답변 미충족을 상태 충돌 409, 최신 프로필 조회 결과 없음을 404로 결정했다. 6단계에서는 인증이 없는 MVP의 피드백 소유자 불일치를 409로 결정했다.

## 다른 레포·팀 진행 상황 (백엔드 관련) — 2026-09-16 확인

팀 조직(`CAU-2026-2-capstone-5-team-8`)에는 Backend 외에 Frontend, ML, Data-Pipeline 레포가 있다.

- **ML**: canonical handoff, 도서/독자 프로필, 랭킹, 평가, Spring용 `/ml/reader-profile`·`/ml/rank` 계약까지 main에 구현됨. 실제 HTTP 연결은 Backend 7단계에서 계약을 다시 대조해야 한다.
- **Frontend**: 이 문서 갱신에서는 별도 재확인하지 않음.
- **Data-Pipeline**: 활발히 진행 중 (PR 9개 중 8개 머지, 1개 진행 중). 공개 API/출판사 페이지에서 실제 도서 메타데이터·목차·본문 일부를 수집해 `books.jsonl`/`documents.jsonl`/`toc.jsonl`/`sources.jsonl`로 저장한다. README에 "concept extraction, difficulty scoring, or recommendation은 하지 않는다"고 명시 — `book_feature` 계산은 이 레포 책임이 아니다.

### 백엔드가 신경 써야 할 것

1. **스코프 확인 필요**: Data-Pipeline은 이미 OS 5권 + Linear Algebra(선형대수) 5권을 수집했는데, `design.md`의 MVP 범위는 "한 개 분야(운영체제)"만 명시돼 있다. 실제로 여러 분야를 지원할 계획이면 `design.md` 스코프 문구부터 갱신해야 한다.
2. **실데이터 ingestion 경로 없음**: Data-Pipeline의 JSONL 스키마(`Book`/`Document`/`TocEntry`/`Source`, ISBN 기반 `book_id`)를 Backend의 `book`/`book_topic`/`book_sample` 테이블로 옮기는 임포트 스크립트가 아직 없다. 지금은 손으로 만든 데모 도서 5권뿐.
3. **ML↔Backend 영속 모델 매핑 필요**: ML은 풍부한 책 프로필과 버전·해시를 출력하지만 Backend의 현재 `book_feature`는 데모용 세 값과 topic relevance만 저장한다. 6단계는 현재 stub feature로 추천 영속화·멱등성을 먼저 검증하고, 7단계에서 실제 ML 후보 DTO와 저장 전략을 확정한다.
4. **license/provenance 개념 재사용 가능**: `sources.jsonl`의 `license`/`rights_note`가 Backend `book_sample.provenance`/`synthetic`과 개념이 겹침 — 나중에 매핑 시 참고.

## 토픽(분야)이 운영체제 하나로 고정돼 있는가?

**아니다 — 코드/스키마는 분야 범용(topic-agnostic)이다.** 확인 결과:

- Java 소스 전체에서 `"OS"`나 `"운영체제"`를 하드코딩한 곳은 없다 (전체 grep으로 확인)
- `topic` 테이블은 임의의 분야를 부모-자식 관계로 저장할 수 있고, `POST /api/assessments`도 `topicId`를 그냥 파라미터로 받아 그 분야의 활성 문항을 샘플링할 뿐이다
- 지금 "운영체제 하나만" 되는 이유는 **코드 제약이 아니라 콘텐츠(데모 데이터) 제약**이다: `db/demo/catalog.sql`이 CS→OS 분야만 넣고, `db/demo/questions.sql`이 OS 문항 9개만 넣어놨을 뿐이다. `design.md`도 이걸 의도적인 "MVP 범위" 결정으로 명시해뒀다(운영체제 1개 분야, 도서 5권으로 축소).

**자료구조·선형대수·알고리즘 등을 추가하려면 코드 수정이 필요 없고:**

1. `topic` 테이블에 해당 분야 행 추가 (이미 범용 INSERT로 가능)
2. 분야별로 측정 영역(어휘/배경지식/독해)당 3문항씩, 총 9문항 콘텐츠 작성 — 코드가 아니라 콘텐츠 제작 작업
3. (추천까지 지원하려면) 그 분야에 속한 도서와 `book_feature`도 준비돼야 함 — 위 Data-Pipeline/ML 이슈와 직결

즉 버그가 아니라, **팀이 여러 분야로 확장하기로 결정하면 콘텐츠(문항·도서)만 채우면 되는 구조**다. 다만 `design.md`의 스코프 문구는 여전히 "1개 분야"로 적혀있으니, 확장하기로 했다면 그 문서부터 갱신해야 한다.

## 참고 문서

- 전체 계약: [design.md](design.md)
- 완료 단계 기록: [verification-stage2.md](verification-stage2.md), [verification-stage3.md](verification-stage3.md)
- 팀 공유 Google Drive 진행 기록: "Backend 프로토타입 구현 설계 및 진행 기록" (캡스톤디자인(1) → 03. 개발 기록 → 공통 기록 → BE)
