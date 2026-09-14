# 지금 구현된 것 설명 (2026-09-13 기준)

이 문서는 "지금 백엔드가 실제로 뭘 하는지"를 이해하기 위한 설명 문서다. 미래 계획은 [remaining-work-analysis.md](remaining-work-analysis.md), 전체 목표 계약은 [design.md](design.md) 참고.

## 1. 전체 구조 한눈에 보기

```
클라이언트 → Spring Boot (Controller → Service → Repository) → PostgreSQL
```

- **Spring Boot가 전부 담당한다.** API, 검증, DB 저장, 응답 생성까지 전부 Spring Boot 안에서 처리된다.
- **Python ML 서비스는 아직 코드 자체가 없다.** 설계상 나중에 "진단 점수 계산"과 "책 순위 매기기"만 담당할 예정이지만(스텁조차 아직 미구현), 지금은 관련 로직이 전혀 없다.
- **인증이 없다.** 로그인 없이 `userId`를 그냥 요청 값으로 받는다. 실제 서비스가 아니라 프로토타입이기 때문.
- 코드는 기능별 폴더로 나뉜다: `topic`(분야), `book`(도서), `assessment`(진단), `user`(사용자), `common.error`(에러 처리 공통)

## 2. 지금 실제로 동작하는 API 6개

### 2-1. 분야 목록 조회

```
GET /api/topics
```

DB에 등록된 분야(예: "컴퓨터공학" → "운영체제")를 부모-자식 관계와 함께 전부 반환한다. 응답 예시:

```json
[
  { "id": 1, "code": "CS", "name": "컴퓨터공학", "parentId": null },
  { "id": 2, "code": "OS", "name": "운영체제", "parentId": 1 }
]
```

### 2-2. 도서 목록 조회 (페이지네이션)

```
GET /api/books?topicId=2&page=0&size=20
```

- `topicId`를 주면 그 분야에 **직접** 연결된 도서만 필터링 (상위 분야로는 안 딸려옴)
- `page`/`size`로 페이지네이션, ID 순 정렬
- 각 도서가 속한 분야들과, 그 분야에 대한 "추천 계산용 특성(feature)"이 활성화돼 있는지 여부도 같이 보여준다

### 2-3. 도서 상세 조회

```
GET /api/books/{bookId}
```

존재하지 않는 ID면 404. 있으면 도서 정보 + 소속 분야별 feature 활성화 여부.

### 2-4. 진단 세션 생성 — 오늘 새로 만든 기능

```
POST /api/assessments
Body: { "userId": 1, "topicId": 2 }
```

**내부에서 실제로 일어나는 일 (순서대로):**

1. `userId`가 실제 존재하는 사용자인지 확인 → 없으면 404
2. `topicId`가 실제 존재하는 분야인지 확인 → 없으면 404
3. 그 분야의 문제 은행(`question` 테이블)에서 **측정 영역(어휘/배경지식/독해)마다 3문항씩, 총 9문항을 무작위로 뽑는다**
   - 만약 어느 한 영역이라도 활성 문항이 3개 미만이면 → 409 에러 (문제 은행이 준비 안 된 분야)
4. `assessment_session` 테이블에 새 세션 행을 만든다 (상태는 항상 `CREATED`로 시작)
5. 뽑은 9문항을 `assessment_question` 테이블에 "스냅샷"으로 복사해서 저장한다 — 나중에 원본 문제가 수정되거나 삭제돼도 이 세션에서 실제로 보여준 문제 내용은 그대로 보존된다는 뜻
6. 응답으로 세션 정보 + 9문항을 돌려준다

**문항 형식(2026-09-14 변경)**: 4지선다 객관식이 아니라 **"안다/모른다" 자기평가** 방식으로 바뀌었다. 그래서 문항에 보기(options)나 정답이 아예 없다 — 채점 대상이 아니라 사용자가 스스로 아는지 표시하는 방식이기 때문이다. 교수님 면담에서 나온 "시험처럼 느껴지면 피로도가 높아 접근성이 떨어진다"는 의견을 반영해 팀이 합의한 방향이다 (비교 근거는 [assessment-format-comparison.md](assessment-format-comparison.md)).

응답 예시:

```json
{
  "id": 5,
  "userId": 1,
  "topicId": 2,
  "status": "CREATED",
  "questions": [
    {
      "id": 41,
      "orderIndex": 0,
      "measurementArea": "VOCABULARY",
      "prompt": "\"교착 상태(deadlock)\"라는 용어의 뜻을 알고 있습니까?"
    }
  ]
}
```

### 2-5. 진단 세션 재조회

```
GET /api/assessments/{sessionId}
```

세션 상태, 9문항(측정 영역 포함), 문항별로 이미 저장된 답변(`knowsConcept`, 아직 답 안 했으면 `null`)을 반환한다. 존재하지 않는 sessionId면 404.

### 2-6. 진단 답변 저장

```
PUT /api/assessments/{sessionId}/answers/{assessmentQuestionId}
Body: { "knowsConcept": true }
```

1. `sessionId`가 없으면 404, `assessmentQuestionId`가 그 세션 소속이 아니면 404
2. 세션이 `PROCESSING`/`COMPLETED` 상태면 409 (더 이상 답변 수정 불가)
3. 첫 답변이면 세션 상태가 `CREATED → IN_PROGRESS`로 바뀜
4. 같은 문항에 다시 답하면 기존 값을 덮어씀(교체) — 행이 늘어나지 않음
5. 동시에 같은 세션에 답변/완료 요청이 들어오면 DB 행 잠금(`SELECT ... FOR UPDATE`)으로 순서를 보장함

이 API까지가 지금 실제로 되는 것이고, **진단 완료 처리(`POST .../complete`)는 아직 없다.**

## 3. DB에 실제로 만들어진 테이블

| 테이블 | 상태 | 비고 |
| --- | --- | --- |
| `app_user` | 데이터 있음 | 데모 사용자 1명 (demo 프로필 실행 시) |
| `topic` | 데이터 있음 | CS, OS 2개 (데모) |
| `book`, `book_topic`, `book_sample`, `book_feature` | 데이터 있음 | 합성 도서 5권 (실제 출판물 아님) |
| `question` | 데이터 있음 | OS 분야 9문항 (데모) |
| `assessment_session` | **오늘부터 실제로 행이 생김** | `POST /api/assessments` 호출할 때마다 |
| `assessment_question` | **오늘부터 실제로 행이 생김** | 세션당 9행 |
| `assessment_answer` | **오늘부터 실제로 행이 생김** | `PUT .../answers/...` 호출할 때마다 |
| `reader_profile`, `recommendation_run`, `recommendation_item`, `feedback` | **테이블 자체가 아직 없음** | 5~6단계에서 마이그레이션부터 추가해야 함 |

## 4. 아직 안 되는 것 (자주 헷갈리는 부분 정리)

- ❌ 진단 완료하고 프로필(점수) 계산받기 (`POST .../complete` 없음)
- ❌ 책 추천받기 (`POST /api/recommendations` 없음)
- ❌ 추천에 피드백 남기기 (`POST .../feedback` 없음)
- ❌ 실제 ML 서버와 통신 (설계만 있고 코드 없음)
- ❌ 로그인/인증 (userId를 그냥 숫자로 보냄)

## 5. 직접 실행해서 확인하는 법

```bash
cp .env.example .env               # POSTGRES_PASSWORD 설정
docker compose up -d --wait        # 로컬 PostgreSQL 실행
./gradlew bootRun --args='--spring.profiles.active=local,demo'
```

서버가 뜨면:

```bash
curl http://localhost:8080/api/topics
curl -X POST http://localhost:8080/api/assessments \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"topicId":2}'
```

Swagger UI: `http://localhost:8080/swagger-ui.html` (springdoc 자동 생성, 실제 요청도 이 화면에서 테스트 가능)
