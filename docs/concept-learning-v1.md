# 공통 개념·수행 능력·React Native 연결 — 2026-10-03

Frontend의 기본 앱을 `Frontend/native`의 React Native + Expo로 전환했다. 책 목차를 하나의 공통 목차로 합치지 않고, 분야별 공통 개념 ID에 책의 내용과 독자 평가를 각각 연결한다. 책의 설명 깊이와 본문 독해 난이도는 목차로 추정하지 않는다.

## 계약

| API | 용도 |
|---|---|
| `POST /api/assessments/concepts` | 개념·능력 중복을 줄여 9문항 발급 |
| `GET /api/assessments/{id}` | 발급 문항·저장 답변 복원 |
| `PUT /api/assessments/{id}/answers/{questionId}` | 답변 저장·서버 채점 |
| `POST /api/assessments/{id}/complete` | 프로필 계산·저장 |
| `GET /api/topics/{topicId}/concept-map?bookId={id}` | 공통 지도와 선택한 책의 개념 범위 |
| `POST /api/learning-recommendations` | 능력별 추천 계산·저장 |
| `GET /api/learning-recommendations/{id}` | 계산 당시 저장 결과 재조회 |

`GET /api/topics`의 `conceptAssessmentReady`가 새 선택 정책에서 9문항을 구성할 수 있는지 나타낸다. 기존 `assessmentReady`는 이전 정책의 의미를 유지한다.

새 추천은 `Idempotency-Key`가 필수이고 요청은 다음과 같다. 사용자·분야·프로필 ID는 실제 완료 결과를 사용한다.

```json
{"userId":1,"topicId":2,"profileId":3,"ability":"application","topK":5}
```

`ability`는 `meaning`, `application`, `reasoning`이다. 같은 사용자의 같은 키·입력은 같은 저장 결과를 반환하고 다른 입력은 409이다. 최신 프로필과 요청 `profileId`가 다르면 409로 막는다. 저장 결과 GET은 새 계산을 호출하지 않는다. 새 추천의 항목 ID는 기존 feedback API의 recommendation item ID와 호환되지 않는다.

## 문항과 프로필

발급 snapshot의 `cognitiveOperation`을 응답과 ML 요청까지 전달한다. 생성 문항 handoff에 이미 검토된 메타데이터가 있으므로 별도 값이나 정답을 추측하지 않는다.

- `recognize`, `recall` → 뜻·성질 (`meaning`).
- `apply` → 계산·적용 (`application`).
- `compare`, `relate`, `integrate`, `infer` → 설명·추론 (`reasoning`).

이는 초기 화면용 분류이며 검증된 심리측정 척도가 아니다. 문항의 **주 개념**에만 평가를 붙이며 관련 개념 전체로 점수를 복제하지 않는다. `SELF_REPORT`는 별도 `selfReports`로 보관한다. objective mode나 operation이 없는 이전 응답은 `unclassifiedResponseCount`이며 확인된 능력으로 바꾸지 않는다.

프로필의 `evidence.conceptProfile`에는 개념·능력별 `responseCount`, `correctCount`, `questionIds`, `operations`, 관찰 점수가 있다. 데이터가 없는 칸은 미평가이며 0점으로 채우지 않는다. 새 추천은 가중 점수 대신 정답 수와 문항 수를 사용한다.

새 선택 정책은 능력 메타데이터가 있는 활성 객관식과 주 개념이 있는 자기평가를 대상으로 중복된 개념·능력 칸보다 새 칸을 먼저 고른다. 같은 중복 수준에서는 객관식과 아직 적게 평가한 개념을 우선한다. 기존 v1 프로필 저장 필드를 계산하기 위해 이전 영역마다 **최소 1개**는 남기지만 영역별 3개 할당은 새 정책에서 사용하지 않는다. 회당 9문항과 기존 3개 필드는 이행기 호환 제약이다. 이후 저장 스키마를 바꾸면 이 제약을 제거할 수 있다.

## 지도와 추천

ML의 `GET /ml/concepts/{topicId}`는 안정된 개념 ID, 한국어 표시 이름, 검토 승인된 선수관계와 config hash를 반환한다. 목차 순서로 선수관계를 만들지 않는다. 현재 공통 목록은 선형대수 20개, 운영체제 17개이며 전체 분야를 완전하게 대표한다는 뜻은 아니다.

새 ML 계산은 `POST /ml/learning-fit`, 모델 버전은 `concept-learning-v1`이다. Backend는 최신 프로필의 objective 관찰 수와 활성 book projection의 내용 개념·출처를 전달한다. 자기평가·세 가지 총점·본문 scalar difficulty를 보내지 않는다.

책 내용의 그래프상 선행 개념 중 책 내용에 포함되지 않은 개념을 선수개념 **후보**로 분리한다. 이는 실제 책의 요구사항을 확정한 값이 아니며, 책 안에서 선수개념을 어느 시점과 깊이로 설명하는지도 판단하지 않는다.

- 해당 능력에서 모든 평가 응답이 정답이면 `correct`, 오답이 있으면 `needs-practice`, 평가가 없으면 `unmeasured`.
- 선수개념 후보에 오답이 있으면 `foundation-gap`, 미평가가 있거나 선수관계가 확인되지 않았으면 `check-first`.
- 선수개념 후보가 실제로 존재하고 모두 정답인 경우에만 `ready-to-explore`.
- 선수관계가 비어 있으면 `foundationStatus=not-established`. 요구사항 없음이나 준비 완료를 뜻하지 않는다.
- 공통 개념 매칭이 비어 있는 책은 추천에서 제외하고 미연결 후보 수에 포함한다.

정렬 기준은 새 학습 후보 → 선수개념 관찰 범주 → 선수관계 확인 여부 → 직접 연습할 개념이 있는지 → 안정된 도서 ID 순이다. 모든 책 내용이 현재 선택한 능력에서 정답 확인된 책은 복습 후보로 뒤에 둔다. 목차 항목 수나 개념 수가 많다는 이유로 더 높은 순위를 주지 않는다. 단일 cosine 유사도나 종합 난이도 점수는 만들지 않는다. 이 규칙은 설명 가능한 기준선이며 학습 성과가 검증된 최적 추천은 아니다.

`learning_recommendation`에 입력 snapshot, 출력, 사용자·분야·프로필, 요청 키/해시를 저장한다. 출력에는 모델·그래프/config hash·review 버전과 도서 artifact version/hash가 있다. 기존 `recommendation_run/item`과 다른 테이블이며 이전 결과를 다시 계산하거나 덮어쓰지 않는다.

v1/v2 모두 짧은 트랜잭션에서 사용자 행을 잠그고 입력 snapshot과 `PROCESSING` 시도 UUID·임대를 저장한 뒤 커밋한다. ML HTTP 호출과 응답 검증은 트랜잭션 밖에서 수행한다. 완료 시 다시 추천 행을 잠가 현재 시도와 임대 유효성을 확인하고 결과와 `SUCCEEDED` 상태를 함께 저장한다. 임대는 `recommendation.processing-lease`(기본 30초)를 사용한다.

처리 중인 같은 키의 중복 요청은 대기하지 않고 409를 반환한다. 성공한 요청은 최초 201, 같은 키·입력의 재요청은 기존 JSON 그대로 200을 반환한다. 다른 입력이나 모델로 성공한 키를 재사용하면 409다. 저장 전 ML·검증·DB 오류가 발생하면 해당 시도가 소유한 처리 행만 삭제하므로 같은 키로 재시도할 수 있다. 복구 시 DB를 사용할 수 없으면 임대가 만료된 뒤 다음 POST가 처리 행을 교체한다. 늦게 도착한 응답·오류는 새 시도나 성공 결과를 수정하지 못한다. 처리 중이거나 만료된 행의 GET은 409이며 새 계산은 POST로 요청한다.

`V20261005000000__learning_recommendation_processing_lease.sql`은 상태·시도 UUID·만료 시각을 추가한다. 기존 저장 결과는 `SUCCEEDED`로 보존하고 요청 해시 계산과 성공 응답 형식은 유지한다.

## 실행과 검증

[기존 실데이터 등록 문서](linear-algebra-live-handoff.md)의 3권 catalog import → 혼합 bank bootstrap → 471권 bulk import 순서와 동일하다. `ML_MODE=http`, ML `8011`, Backend `8087`을 사용한다. Frontend 폴더에서 `npm --prefix native ci`, `npm run web`을 실행하면 `5183`에서 새 앱을 확인한다.

이번에는 기존 DB를 사용하지 않고 `capstone-concepts-20261003`, 로컬 `5459` 포트의 별도 PostgreSQL을 만들었다. `bookpath` DB/사용자는 이 검증용이며 포트는 loopback에만 공개한다. 이 컨테이너는 `--rm`이고 중지하면 데이터가 사라진다. 검증 응답·화면 기록·`pg_dump` 백업은 ignored `ML/data/output/concept-learning-live-v1/`에 보관했다. 일반 개발 DB에는 이 로컬 trust 설정을 적용하지 않는다.

검증: Backend 157개(실제 ML 연결 포함, skip 0), ML 320개, 기존 프론트 22개 통과. RN 타입 검사·린트·Expo 진단 21/21, 웹·iOS·Android 번들 생성 성공. 실제 브라우저에서 진단 중간 새로고침/이어하기, objective 오답과 자기평가 분리, 20개 공통 개념, 도서 테두리 비교, 추천 저장/재조회와 새로고침을 확인했다. 390px 화면과 전체 페이지 가로 넘침도 확인했다. 네이티브 실기기 실행과 배포는 아직 검증하지 않았다.

## 현재 데이터 한계

실제 bank는 자기평가 8개와 행렬 계산·적용 객관식 1개다. 모든 개념·능력이 측정된 상태가 아니다. 기존 문항을 승인 없이 새 객관식으로 바꾸지 않았다. 세 축을 채우려면 검토된 정의·적용·추론 문항이 추가로 필요하다.

목록 471권 중 선형대수 projection 83권, 공통 개념 연결 11권이다. 나머지 72권은 판단 보류다. 현재 한국어 목차 매칭과 선수관계가 부분적이며 수집 목록에는 편입 문제집도 포함된다. 다음 품질 작업은 목차 매칭 범위·도서 유형 선별, 선수관계 검토 확대, 승인 문항 확장, 전문가/실사용자 추천 평가다. 그 전에는 책의 학년·설명 깊이·독해 난이도나 정확한 개인 숙련도를 표시하지 않는다.


## 개념 진단 v2 후속 — 2026-10-03

새 승인 문항 `generated-question-v5`는 사전 지식 문항이며 concept-question-spec-v2의 개념·수행 목표를 명시합니다.
이전 v2/v4 import 계약은 보존됩니다. 새로운 immutable content ID를 검증하고 HumanQuestionReview
approve/correct=true가 없으면 저장하지 않습니다. `V20261003010000`은 과거 문항과 세션을 수정하지 않고
v5 및 해당 provenance의 사전 지식/수행 목표 조건을 DB 제약에 추가합니다.

새 v5 문제은행이 준비되면 개념 진단은 v5 후보를 대상으로 개념·수행 목표의 중복을 줄입니다. 같은 사용자·분야에서
이미 답한 문항의 노출 횟수를 조회해 동일한 다양성 조건에서 미응답 후보를 우선합니다. 회당 9문항은 이번 작은
파일럿의 범위이며 완전한 적응형 검사나 20개 개념 전체의 평가를 뜻하지 않습니다.

발급/답변 API의 `measurementContext`는 snapshot provenance에서 읽습니다. v2는 prior-knowledge, v4는
provided-information 계약이며 SELF_REPORT나 출처 없는 이전 문항은 null입니다. ML은 지문 후 수행·조건 불명의
응답을 사전 지식과 분리합니다. 새 추천은 concept-abilities-v2의 prior-knowledge 관찰만 사용합니다.
이전 프로필은 다시 진단해야 하며 기존 저장 추천은 그대로 재조회할 수 있습니다. 새 추천에는
`conceptProfileVersion=concept-abilities-v2`를 기록합니다.

로컬 검토 화면은 기본 비활성입니다. `local` profile과 `assessment.preview-dir`을 명시해야 다음 읽기 전용 API가
열립니다. 서버는 검토할 때 loopback에 바인딩합니다.

- `GET /api/assessments/concept-preview/summary`: 후보 수와 분야 ID.
- `GET /api/assessments/concept-preview`: 질문·보기·목표·정답·해설. RN의 `/question-review`가 사용합니다.

이 화면의 선택은 서버 채점·사용자 프로필·추천에 저장하지 않습니다. 로컬 원본 초안 18개는 아직 사람 승인 전이며
실제 진단 bank에 등록하지 않았습니다. Gemini 외부 생성은 별도 전송 승인을 기다립니다.
## 수식 본문 형식 후속

`generated-question-v5`는 생성 프롬프트 v1과 v2를 모두 허용한다. v2는 동일 JSON 텍스트 필드에
Markdown·LaTeX를 사용하며 문자열의 역슬래시와 행렬 줄 구분자를 그대로 보존한다.
본문 변경은 새 생성 ID를 요구하며 과거 발급·응답 스냅샷은 유지한다. 표시 변환은 RN 프론트의 공통 컴포넌트가 담당한다.
로컬 검토의 현재 초안 경로는 `Question-Generation/data/generated/concept-assessment-math-v2`다.
수식이 정상 표시된다는 사실은 사람 승인이나 정답의 타당성을 의미하지 않는다.
