# 지금 구현된 것 설명 (2026-09-15 기준)

이 문서는 "지금 백엔드가 실제로 뭘 하는지"를 이해하기 위한 설명 문서다. 미래 계획은 [remaining-work-analysis.md](remaining-work-analysis.md), 전체 목표 계약은 [design.md](design.md) 참고.

## 1. 전체 구조 한눈에 보기

```
클라이언트 → Spring Boot (Controller → Service → Repository) → PostgreSQL
```

- **Spring Boot가 전부 담당한다.** API, 검증, DB 저장, 응답 생성까지 전부 Spring Boot 안에서 처리된다.
- **Python ML 서비스와의 HTTP 통신은 아직 없다.** 대신 같은 계약을 구현한 결정론적 `stub`이 영역별 자기평가 비율로 독자 프로필을 계산한다.
- **인증이 없다.** 로그인 없이 `userId`를 그냥 요청 값으로 받는다. 실제 서비스가 아니라 프로토타입이기 때문.
- 코드는 기능별 폴더로 나뉜다: `topic`(분야), `book`(도서), `assessment`(진단), `profile`(독자 프로필), `integration.ml`(계산 경계), `user`(사용자), `common.error`(에러 처리 공통)

## 2. 지금 실제로 동작하는 API 8개

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

### 2-4. 진단 세션 생성

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
5. 뽑은 9문항을 `assessment_question` 테이블에 "스냅샷"으로 복사해서 저장한다 — 나중에 원본 문제가 수정돼도 이 세션에서 실제로 보여준 문제 내용은 그대로 보존된다는 뜻
6. 응답으로 세션 정보 + 9문항을 돌려준다

**문항 형식(2026-09-14 변경)**: 4지선다 객관식이 아니라 **"안다/모른다" 자기평가** 방식으로 바뀌었다. 그래서 문항에 보기(options)나 정답이 아예 없다 — 채점 대상이 아니라 사용자가 스스로 아는지 표시하는 방식이기 때문이다. 교수님 면담에서 나온 "시험처럼 느껴지면 피로도가 높아 접근성이 떨어진다"는 의견을 반영해 팀이 합의한 방향이다 (비교 근거는 [문항 방식 비교](<assessment-format-comparison(문제 선다 방식).md>)).

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
5. 답변 저장은 세션 행 잠금(`SELECT ... FOR UPDATE`)을 사용함. 완료 요청도 같은 잠금을 사용해 답변 변경과 경쟁하지 않음

### 2-7. 진단 완료와 프로필 생성

```
POST /api/assessments/{sessionId}/complete
```

1. 세션을 잠그고 발급한 9문항에 답변이 모두 있는지 확인한다. 부족하면 409를 반환한다.
2. UUID `attemptId`와 기본 30초 처리 임대를 저장한 뒤 트랜잭션을 끝낸다.
3. DB 트랜잭션 밖에서 `MlGateway`를 호출한다. 현재 기본 `stub`은 측정 영역별 `안다 수 / 3`을 `[0,1]` 점수로 계산한다.
4. 다시 세션을 잠그고 같은 attempt가 여전히 소유자인지 확인한 뒤 `reader_profile` 저장과 `COMPLETED` 전이를 한 트랜잭션으로 처리한다.
5. 같은 완료 요청을 다시 보내면 새 계산 없이 저장된 프로필을 200으로 반환한다. 진행 중인 중복 요청은 409이며, 만료된 attempt는 새 요청이 교체할 수 있다.
6. 계산 실패 시 외부 상세를 응답이나 DB에 남기지 않고 정제된 실패 코드·메시지만 저장한 뒤 `IN_PROGRESS`로 복구한다.

### 2-8. 최신 독자 프로필 조회

```
GET /api/users/{userId}/profiles/{topicId}
```

해당 사용자·분야의 완료된 프로필 중 완료 시각 내림차순, 동률이면 프로필 ID 내림차순으로 1건을 반환한다. 프로필이 없으면 404다.

## 3. DB에 실제로 만들어진 테이블

| 테이블 | 상태 | 비고 |
| --- | --- | --- |
| `app_user` | 데이터 있음 | 데모 사용자 1명 (demo 프로필 실행 시) |
| `topic` | 데이터 있음 | CS, OS 2개 (데모) |
| `book`, `book_topic`, `book_sample`, `book_feature` | 데이터 있음 | 합성 도서 5권 (실제 출판물 아님) |
| `question` | 데이터 있음 | OS 분야 9문항 (데모) |
| `assessment_session` | 호출 시 저장 | `POST /api/assessments` 호출할 때마다 |
| `assessment_question` | 호출 시 저장 | 세션당 9행, 영역·문구·버전·난이도 스냅샷 |
| `assessment_answer` | 호출 시 저장 | `PUT .../answers/...` 호출할 때마다 |
| `reader_profile` | 완료 시 저장 | 세션당 최대 1행, 점수 3종·계산 버전·근거 JSON |
| `recommendation_run`, `recommendation_item`, `feedback` | **테이블 자체가 아직 없음** | 6단계에서 마이그레이션부터 추가해야 함 |

## 4. 아직 안 되는 것 (자주 헷갈리는 부분 정리)

- ❌ 책 추천받기 (`POST /api/recommendations` 없음)
- ❌ 추천에 피드백 남기기 (`POST .../feedback` 없음)
- ❌ 실제 Python ML 서버와 HTTP 통신 (`stub` 계산만 구현됨)
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
