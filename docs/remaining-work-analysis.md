# 남은 구현 단계 분석 (4~8단계)

작성일: 2026-09-13. 이 문서는 계획 문서이며 아직 구현되지 않았다. 근거는 [설계 문서](design.md)의 데이터 모델·공개 계약·ML 계약·진단 상태 전이 절이다. 코드를 먼저 확인하지 않고 이 문서만으로 구현 여부를 판단하지 말 것 — `CLAUDE.md`의 "Current stage" 절 참고.

## 현재 상태 (기준: 커밋 `c11983c`)

- 완료: 3단계(분야·도서 카탈로그), 4단계 중 `POST /api/assessments`(세션 생성 + 9문항 스냅샷 발급)
- 미완료: 4단계 나머지(조회, 답변 저장), 5~8단계 전체

## 4단계 나머지 — 답변 저장·재조회

### GET /api/assessments/{sessionId}
- `AssessmentSessionRepository`/`AssessmentQuestionRepository`/`AssessmentAnswerRepository`를 조합해 세션 상태, 발급된 9문항(정답 키 제외), 문항별 저장된 답변(있는 경우)을 반환
- 존재하지 않는 sessionId → 404
- 응답 구조는 `POST /api/assessments`의 `AssessmentResponse`를 재사용하거나 확장 (저장된 `selectedOptionId` 필드 추가)

### PUT /api/assessments/{sessionId}/answers/{assessmentQuestionId}
- 요청: `selectedOptionId` (문항 스냅샷의 옵션 목록에 실제로 존재하는 값인지 검증 — 없으면 400)
- `assessmentQuestionId`가 해당 `sessionId`에 속하는지 검증 (다른 세션 소속이면 404 또는 400)
- **세션 상태 전이**: 첫 답변 저장 시 `CREATED → IN_PROGRESS` (설계 문서 "진단 상태 전이" 절)
- **처리 중/완료 상태에서는 답변 수정 불가** — `PROCESSING`/`COMPLETED` 상태에서 PUT 요청 시 409
- 같은 문항에 대한 재제출은 UPSERT(교체) — DB에 이미 `assessment_answer.assessment_question_id UNIQUE` 제약이 있으므로 서비스 계층에서 존재 여부 확인 후 update/insert 분기 필요
- **동시성**: 설계 문서는 "답변 저장과 완료 요청은 같은 세션 행을 짧은 트랜잭션으로 잠근다"고 명시 — `SELECT ... FOR UPDATE` 또는 낙관적 락 검토 필요

### 이번 4단계에서 아직 안 건드린 것
- 상태 전이 로직 자체 (지금은 세션이 항상 `CREATED`로 생성되고 끝)
- 답변 유효성 검증 (옵션 존재 여부)

## 5단계 — 진단 완료·프로필 계산

가장 복잡한 상태 머신이 여기 있다 (설계 문서 "진단 상태 전이" 전체를 다시 읽을 것).

### POST /api/assessments/{sessionId}/complete
- 9개 답변이 모두 존재하는지 확인 (부족하면 409 또는 400 — 설계 문서에 명시된 코드 없음, 결정 필요)
- 입력을 얼려(freeze) UUID `attemptId` 발급 + 30초 lease 설정, 커밋 후 ML 호출 (`MlGateway.profile`)
- 두 번째 짧은 트랜잭션에서 현재 attempt가 아직 세션의 소유인지 재확인한 뒤 `reader_profile` 원자적 저장 + `COMPLETED` 전이
- **재시도 시맨틱**: 이미 완료된 세션에 다시 호출하면 기존 프로필을 그대로 반환 (200)
- **동시 완료 요청**: 이미 처리 중인 세션에 또 완료 요청 → 409
- **만료된 attempt**: 30초 지나면 다음 완료 요청이 attempt를 교체할 수 있고, 늦게 도착한 이전 attempt의 응답은 새 attempt를 덮어쓰면 안 됨
- **ML 실패 시**: 정제된 실패 사유를 `last_failure_code`/`last_failure_message`에 저장하고, 저장 시점에 여전히 그 attempt가 세션 소유자라면 `IN_PROGRESS`로 복구
- **DB 자체가 끊긴 경우**: 복구는 lease 만료에 맡기고 별도 보정 로직은 만들지 않음 (설계 문서 명시)

### GET /api/users/{userId}/profiles/{topicId}
- 완료 시각 내림차순, 동률이면 ID로 최신 프로필 1건 조회
- 프로필 없음 → 404 (설계 문서: 추천 쪽에서는 "no profile → 409"이지만 이 조회 자체는 단순 조회이므로 404가 일관적 — 결정 필요)

### 필요한 새 구성 요소
- `reader_profile` 테이블 마이그레이션 (세션 UNIQUE, 능력 3종, 계산 버전, evidence JSONB)
- `MlGateway` 인터페이스 + `ml.mode=stub` 구현체 (설계 문서의 stub 프로필 공식: 정답 수/발급 수, 문항당 1점)
- 세션 잠금을 위한 `SELECT ... FOR UPDATE` 또는 비관적 락 전략 확정

## 6단계 — 추천·피드백

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
- `userId`가 추천 소유자와 일치하는지 검증 (불일치 시 처리 방식 결정 필요 — 403 없음, 설계 문서 오류 코드 표에 없음)
- 재제출 시 UNIQUE(user, item) 제약으로 교체(200) vs 최초 제출(201)

### 필요한 새 구성 요소
- `recommendation_run`, `recommendation_item`, `feedback` 테이블 마이그레이션
- `MlGateway.rank` stub 구현 (설계 문서의 stub 랭킹 공식: dimension fit, topic fit, 산술 평균, 동점 시 ID 오름차순, reason 최소 2개)

## 7단계 — ML HTTP 연동

- `ml.mode=http`일 때 `RestClient` 기반 어댑터 구현 (connect timeout 2s, response timeout 10s)
- stub과 동일한 응답 검증 로직 공유 (필수 필드, 유한/범위 값, 중복·미요청 도서, 결과 개수/순위, 버전 호환성 거부)
- **fallback 없음**: HTTP 실패를 stub 성공으로 대체하지 않음, 자동 재시도도 없음
- WireMock standalone 3.13.2로 계약 테스트 (실제 Python 서비스 없이 검증)

## 8단계 — 전체 E2E 및 인수인계

- 분야 조회 → 진단 생성 → 답변 → 완료 → 프로필 조회 → 추천 → 피드백 전체 흐름 통합 테스트
- README/설계 문서/`docs/verification-stage*.md` 최종 정리
- 팀 인수인계용 요약 (Google Drive 진행 기록 문서와 동기화)

## 결정이 필요한 모호한 지점 (구현 전에 팀 논의 권장)

1. 완료 시 9개 답변 미충족 상태 코드 (400 vs 409)
2. 프로필 없음 조회의 상태 코드 (404 vs 추천 쪽 409와의 일관성)
3. 피드백 소유자 불일치 시 상태 코드 (403 부재)
4. 세션 잠금 구현 방식 (`SELECT FOR UPDATE` vs 낙관적 락 + 재시도)

## 다른 레포·팀 진행 상황 (백엔드 관련) — 2026-09-14 확인

팀 조직(`CAU-2026-2-capstone-5-team-8`)에는 Backend 외에 Frontend, ML, Data-Pipeline 레포가 있다.

- **Frontend / ML**: 아직 미착수 (각각 Initial commit만 존재)
- **Data-Pipeline**: 활발히 진행 중 (PR 9개 중 8개 머지, 1개 진행 중). 공개 API/출판사 페이지에서 실제 도서 메타데이터·목차·본문 일부를 수집해 `books.jsonl`/`documents.jsonl`/`toc.jsonl`/`sources.jsonl`로 저장한다. README에 "concept extraction, difficulty scoring, or recommendation은 하지 않는다"고 명시 — `book_feature` 계산은 이 레포 책임이 아니다.

### 백엔드가 신경 써야 할 것

1. **스코프 확인 필요**: Data-Pipeline은 이미 OS 5권 + Linear Algebra(선형대수) 5권을 수집했는데, `design.md`의 MVP 범위는 "한 개 분야(운영체제)"만 명시돼 있다. 실제로 여러 분야를 지원할 계획이면 `design.md` 스코프 문구부터 갱신해야 한다.
2. **실데이터 ingestion 경로 없음**: Data-Pipeline의 JSONL 스키마(`Book`/`Document`/`TocEntry`/`Source`, ISBN 기반 `book_id`)를 Backend의 `book`/`book_topic`/`book_sample` 테이블로 옮기는 임포트 스크립트가 아직 없다. 지금은 손으로 만든 데모 도서 5권뿐.
3. **`book_feature`(난이도 점수) 채울 주체 미정**: ML 레포가 아직 미착수라, 6단계(추천) 구현 시점까지 이 데이터가 준비될지 불확실 — stub 데이터로 6단계를 먼저 검증하고 실제 feature는 나중에 교체하는 방향이 현실적일 수 있음.
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
