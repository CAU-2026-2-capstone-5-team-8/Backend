# 분야 요청과 책 준비

2026-10-06 로컬 통합 구현. 소비자 화면에는 기술 버전이나 해시를 노출하지 않는다.

## 구현 범위

분야 이름 입력 → 등록된 별칭/범위 확인 → 사용자 선택 → 출처별 책 수집 → Data-Pipeline 정제 → 도서 목록 공개까지 연결했다. 등록된 별칭 확인에는 Gemini를 호출하지 않는다. 새 분야의 실제 책 검색에는 각 출처 API를 사용한다.
새 확장 분야는 `computer-networks`다. 기존 6개 분야로 해석되면 이미 등록된 분야를 재사용한다.
이후 개념 초안 → 목차 근거 연결 → 문제 설계 → 문항 생성 → 수식 렌더링 검사 → AI 내용 검토 → ML 지원 확인 → 진단 활성화까지 같은 작업 큐에서 이어진다.
등록된 분야 별칭은 바로 연결한다. 넓거나 세부적인 입력은 후보를 제시한다. 기존 목록에 없는 입력은 지원되는 출처에서 책을 검색하고 실제 도서 분류와 예시를 보여준다. 분야를 선택하면 책을 공개하고, 목차 근거와 검토 조건을 충족한 경우에만 진단을 자동 활성화한다.

## 로컬 실행

Java 21, 기존 PostgreSQL, Data-Pipeline Python 환경과 양쪽 레포가 필요하다.
API 키는 `Data-Pipeline/.env`의 `YES24_API_KEY`, `Question-Generation/.env`의
`GEMINI_API_KEY`를 사용한다. 모델은 기존 `QUESTION_GENERATION_MODEL` 설정을 재사용한다.
키는 서버의 자식 프로세스에서 읽으며 프론트로 보내지 않는다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew bootJar
TOPIC_PREPARATION_ENABLED=true python3 scripts/run-local-backend.py
```

런처는 비공개 `.local/runtime.json`과 기존 활성 카탈로그를 사용한다.
`TOPIC_PREPARATION_ENABLED`는 runtime.json의 env에도 저장할 수 있다.
기본 서버 설정에서는 준비 작업을 실행하지 않는다. 실제 운영 배포 시 아래 설정과
Data-Pipeline 환경/파일·키 저장 방법을 배포 환경에 맞게 명시해야 한다.

- `topic-preparation.enabled=true`
- `topic-preparation.python`: Data-Pipeline 가상환경 Python 실행 파일
- `topic-preparation.script`: 이 레포의 `scripts/topic-preparation.py`
- `topic-preparation.workspace`: 작업 결과를 보존할 서버 디렉터리
- `topic-preparation.poll-ms`: 기본 3000
- `topic-preparation.initial-delay-ms`: 기본 5000
- `topic-content-preparation.enabled=true`: 개념 초안·목차 연결·문제 설계 실행
- `topic-question-preparation.enabled=true`: 문항 후보를 두 개씩 생성

로컬 런처의 추가 환경 설정은 `TOPIC_CONTENT_PREPARATION_ENABLED=true`,
`TOPIC_QUESTION_PREPARATION_ENABLED=true`다. 각 단계는 명시적으로 켜야 한다.
ML와 Question-Generation의 가상환경도 필요하며 각 레포에서 `uv sync`로 준비한다.
기존 `.env`의 언어 설정은 유지하고 이 새 문항 작업만
`TOPIC_QUESTION_GENERATION_LANGUAGE`(기본 `en-US`)를 사용한다.

## API와 상태

모든 분야 요청 API에는 로그인이 필요하고 자신의 요청만 읽거나 다시 시작할 수 있다.

| API | 동작 |
| --- | --- |
| POST `/api/topic-requests/resolve` | `{name}`을 로컬 확인. `MATCH/BROAD/RELATED/UNKNOWN`과 후보를 반환하며 요청·수집 작업을 만들지 않음 |
| POST `/api/topic-requests/discover` | `{name}`의 도서 검색을 백그라운드 접수. 대기/검색 중이면 202; 같은 사용자·검색어는 1시간 동안 재사용 |
| GET `/api/topic-requests/discover/{id}` | 자신의 검색 상태와 관측된 도서 분류·예시 조회 |
| POST `/api/topic-requests` | `{name,selectedSlug?}` 또는 `{name,discoveryId,providerCategory}`로 접수. 새 요청 201, 기존 요청/분야 200. 선택은 서버가 재계산한 후보 안에서만 허용. 예전 클라이언트의 선택적 `categoryId,scope`도 읽을 수 있음 |
| GET `/api/topic-requests` | 자신의 최근 요청 20개 |
| GET `/api/topic-requests/{id}` | 자신의 요청 상세 |
| POST `/api/topic-requests/{id}/retry` | 실패·확인 필요·접수 상태 재시작. `{name?,selectedSlug?}`으로 분야 이름 수정 또는 후보 선택; 기존 클라이언트의 `scope`도 읽을 수 있음 |
| POST `/api/topic-requests/{id}/content/retry` | 실패한 개념 준비만 재시작 |
| POST `/api/topic-requests/{id}/questions/retry` | 실패한 문항 생성만 재시작. 이미 저장한 문항 보존 |

## 개념과 문제 준비 단계

책 목록의 `BOOKS_READY`는 유지하고 요청 응답의 `content`에 별도 준비 상태를 표시한다.
개념 단계는 `QUEUED → PREPARING → CONCEPTS_READY / NEEDS_EVIDENCE / FAILED`다.
실제 목차 근거가 없는 개념과 목차가 없는 책을 제외됐다고 숨기지 않는다.
새 분야 준비만 한국어·영어 별칭을 로컬에서 연결하고 기존 영어 추천 정책은 유지한다.
Gemini에는 분야 이름만 보내며 책 원문·목차나 계정 정보를 보내지 않는다.
AI가 제안한 선수관계는 검증된 학습 순서와 구분해 보존한다.

`content.questions`는 `QUEUED → GENERATING → QUEUED ... → CANDIDATES_READY / FAILED`다.
생성 수·설계 수를 표시하고 준비 중에는 자동으로 조회한다. 한번에 두 문항씩 저장해
중단 시 완료된 문항을 보존한다. 이 단계에서 Gemini에 보내는 입력은 개념·학습 목표·
오개념 설계이며 책 원문이나 목차가 아니다.

책·개념·문제 작업은 동일한 DB 잠금과 단계별 기한 있는 실행 권한으로 중복 호출을 막는다.
원본 요청·카탈로그 스냅샷·개념 보고서·문제 설계·생성 파일의 해시를 연결하고,
기한이 지난 작업은 결과를 공개할 수 없다. 새 파일 묶음은 원본 수집 데이터를 덮어쓰지 않는다.
구조 검사와 빈 검토표 생성은 내용 승인으로 옮기지 않는다. 생성 단계는 후보만 저장하며,
별도의 AI 내용 검토와 활성화 검사를 통과한 뒤 문항 은행과 책 연결을 게시한다.

등록된 분야는 기존 Data-Pipeline 규칙을 사용한다. 목록 밖의 입력은 관측된 제공자 분류와 문자 그대로의 제목 검색어를 모두 만족하는 도서만 별도로 정제한다. 이 결과는 책 탐색용이며 임의의 모든 분야에 대한 개념·진단을 자동 확장한 것은 아니다.

새 요청 화면에는 분야 이름만 있다. 앱의 큰 분류와 데이터 제공자의 분류는 같은 체계로
취급하지 않는다. 이름만 접수한 요청은 `categoryId,categoryName=null`로 보관하고,
로컬 규칙이 등록된 분야를 확인하면 해당 정책의 domain으로 앱의 분류를 연결한다.
제공자별 카테고리·제목 필터는 Data-Pipeline의 별도 출처 정책을 그대로 사용한다.
모호한 이름은 후보에서 범위를 선택하거나 더 구체적인 이름을 다시 입력받는다. 설명 입력 칸은 없다.
이름을 수정하면 이전 분류/설명은 새 요청 내용에 사용하지 않고 다시 확인한다.

응답 Request에는 `id, categoryId, categoryName, name, scope, status, createdAt,
message, topicId, bookCount, content, candidates`가 포함된다. `BOOKS_READY`는 책 탐색 가능 상태다.
`/api/topics`의 별도 `conceptAssessmentReady`가 진단 가능 여부의 기준이다.

| 상태 | 의미 |
| --- | --- |
| NEEDS_REVIEW | 자동 준비가 꺼진 서버에서 접수만 된 요청 |
| QUEUED | 실행 대기 |
| CHECKING | 로컬 규칙으로 이름과 선택한 범위를 확인 |
| COLLECTING | 수집·정제 및 공개 자료 준비 |
| NEEDS_INPUT | 후보에서 범위 선택 또는 분야 이름 수정 필요 |
| FAILED | 중단/오류. 사용자가 다시 시도 가능 |
| BOOKS_READY | 관련 도서 공개 완료. 진단 완료를 뜻하지 않음 |

자동 준비가 켜지면 기존 NEEDS_REVIEW 요청도 실행 대기로 전환한다.
프론트는 대기·확인·수집 상태에서만 자신의 요청을 주기적으로 갱신한다.
이름만 접수할 때는 같은 계정/정규화 이름의 기존 요청을 재사용한다. 이전 클라이언트의
분류를 지정한 접수도 분류 미정 요청이 있으면 재사용한다. 이름 수정은 retry API를 사용한다.

## Gemini 없는 분야 확인 규칙

`src/main/resources/topic-name-resolution.json`이 로컬 이름 규칙의 기준이다.
기존 7개 분야의 이름 확인 기준이며, 어댑터 테스트가 Data-Pipeline의 slug·domain·이름과 일치하는지 확인한다. 목록 밖의 입력은 별도 제공자 검색 경로로 이어진다.
분야 추가 시 이름 규칙만 추가하는 것으로 수집·진단이 준비되지는 않는다. 출처 정책과 후속 콘텐츠 지원도 필요하다.

| 입력 예 | 동작 |
| --- | --- |
| `OS`, `운영체제`, `컴퓨터 통신망` | 등록된 별칭이면 해당 분야로 연결 |
| `수학`, `컴퓨터공학` | 지원하는 하위 분야 후보를 표시하고 선택 대기 |
| `TCP/IP`, `SQL`, `고유값` | 관련된 지원 분야를 제안. 세부 주제와 분야 전체를 같은 것으로 자동 확정하지 않음 |
| `OS와 DBMS` | 여러 후보를 보여주고 선택 대기 |
| `경제`, `인공지능` 등 목록 밖의 입력 | 별도 책 검색 → 실제 분류/예시 확인 → 분류 선택 → 책 탐색용 분야 등록 |
| 검색어에 맞는 책이 없는 입력 | 검색 결과 없음 안내. 관련 책을 찾았다는 이유만으로 학습 분야나 진단을 승인하지 않음 |

앱은 먼저 확인 API를 호출한다. `MATCH`는 바로 접수한다. `BROAD/RELATED`는 후보와 입력 이름으로 책을 찾는 버튼을 표시한다. `UNKNOWN`은 도서 검색을 자동 시작한다.
후보 선택은 원래 입력과 함께 `selectedSlug`로 전송하며 서버가 다시 검증한다.
직접 API를 호출하는 이전 클라이언트도 작업자가 로컬 규칙으로 재확인하여 `NEEDS_INPUT`에 남긴다.
원래 입력을 유지하므로 사용자 입력 `TCP/IP`와 실제 선택 `컴퓨터 네트워크`를 구분할 수 있다.
등록된 별칭 이외의 모든 자연어를 이해하는 분류기는 아니며, 키워드는 후보 제안에만 사용한다.

## 목록 밖의 입력: 제공자 검색과 선택

`topic_discovery`에 UUID·사용자·검색어·상태·분류 결과를 보관한다. 상태는
`QUEUED → SEARCHING → FOUND / NO_RESULTS / FAILED`이며 앱이 진행 중에 자동 조회한다.
계정당 시간당 신규 검색 10개, 후보 최대 100건, 등록 최대 20권으로 제한한다.
원문 응답은 `.local/topic-preparation/discoveries/{uuid}/`에 보존한다.
기존 큐와 동일한 DB 잠금·기한 있는 claim으로 제공자 호출을 직렬화한다.

분류 목록은 실제 `goodsSortNm` 전체 경로이며 생성한 경제학/재테크 분류가 아니다.
예시와 권수는 그 응답에서 제목·분류·ISBN을 확인한 고유 판본에 한정한다.
문자 그대로의 검색어와 선택한 정확한 분류가 모두 맞아야 canonical에 포함한다.
분류·ISBN 없는 항목과 제목이 맞지 않는 항목은 제외한다. 기존 분야의 필터는 바꾸지 않는다.

접수 시 검색 UUID의 소유권·원래 검색어·실제 반환한 분류인지 다시 검증한다.
선택은 `discovery_selection` JSON으로 요청에 보존한다. 정제는 같은 원문을 재사용하므로
분류 선택 후 다시 검색하여 결과가 바뀌지 않는다. 해시로 만든 `search-*` 식별자와
출처 분류별 `SRC-*` 부모는 원자적 공개 때만 생성한다. 준비 실패는 빈 분야를 공개하지 않는다.

별도 `book-search-*` 카탈로그는 책 탐색 가능 상태로만 등록한다. 현재 등록 분야의
`topic-request-*` 개념/문항 자동 작업과 구분하여 새 검색 결과가 잘못된 ML 정책이나
Gemini 문항 생성에 바로 들어가지 않도록 한다. 개념 설계와 진단 연결은 후속 단계다.

## 역할과 중간 결과

- Frontend: 분야 이름 입력, 실제 진행 상태, 이름 수정/재시도, 준비된 분야 이동.
- Backend: 로컬 별칭/분야 후보 확인, 선택 검증, 로그인/소유권, DB 큐와 실행 권한, 작업 순서, 원자적 공개.
- Data-Pipeline: 기존 YES24 수집·제목/도서 분류 필터·정제·원문 응답 보존.
- Gemini: 분야 확정 이후 개념 초안과 문항 후보 생성. 분야 이름의 해석·확정에는 사용하지 않는다.
- ML: 분야별 개념 초안 검증, 목차 근거 연결, 도서 개념 프로필과 문제 설계 생성.
- Question-Generation: 설계에 따른 문항 생성, 형식 검사, 빈 검토표 생성. 내용 승인과 진단 등록은 별도다.

`scripts/topic-preparation.py`는 JSON stdin/stdout으로 호출하는 서버 전용 어댑터다.
책 수집은 후보 최대 100건을 확인하고 통과한 최대 20권을 우선 등록한다.
0권이면 공개하지 않는다. 표지/본문/개념 증거가 없는 부분을 합성하지 않는다.

각 요청의 `.local/topic-preparation/request-{id}/`에는 제공자 원문 응답, 필터 집계, canonical 4개 JSONL, 별도 Backend 호환 handoff,
import/selection manifest, 완료 결과가 남는다. 기존 영어 분석 필드가 있으면
canonical에 보존하고 현재 Backend 입력 계약에 맞는 별도 투영만 전달한다.
수집 코드 revision과 실제 코드 hash를 저장한다. 재시도는 이미 확보한 원문 응답을 재사용한다. 개념/문항 단계의 AI 입력·모델·응답은 해당 단계 폴더에 보존한다. 이전 Gemini 이름 확인 파일은 보존하지만 더 이상 읽지 않는다.

## 중단·중복·공개 기준

- DB 잠금으로 준비 작업을 한 번에 하나씩 실행해 여러 서버의 제공자 호출도 직렬화한다.
- 실행 claim UUID와 15분 lease를 확인한다. 각 외부 단계는 180초 제한이다.
- lease가 만료된 작업은 실패로 표시하고 수동 재시도한다. 이전 프로세스가 뒤늦게 완료해도 공개할 수 없다.
- 카탈로그 import, 활성 선택, BOOKS_READY 전환은 한 트랜잭션이다.
- 분야, 책, 선택 결과, 범위가 불일치하면 전체 공개를 롤백한다.
- 기존 도서·진단·추천·계정 데이터를 지우지 않는다.

## 검증

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test --tests '*TopicRequestIntegrationTest'
../Data-Pipeline/.venv/bin/python -m unittest discover -s scripts/tests -p 'test_topic_preparation.py' -v
```

필터 검증은 Data-Pipeline의 `tests/test_network_topic.py`에 있다.
서버 통합 테스트는 로컬 별칭·큰 분야·관련 주제·미지원 이름, 후보 선택 위조 차단, 인증/소유권, 중복/한도, 실행 중 중복 claim,
이름 수정, 중단 후 재시도, 이전 claim 차단, 불완전 선택의 원자적 롤백,
책 공개와 진단 활성화 분리를 확인한다. 어댑터 테스트의 모형 응답은 AI 정확도 증명이 아니다.

로컬 실제 호출에서 요청 #2의 컴퓨터 네트워크가 도서 20권으로 공개됐고,
별칭 “컴퓨터 통신망”은 기존 분야를 재사용했다. “aaa”는 일치하는 지원 분야 없음으로 안내한다.
재시작 후 topic 9와 도서 목록이 유지됐다. UI 실기기/생산 배포 검증은 별도다.

## 다음 구현

1. 생성된 문항의 정답·해설·개념/능력 라벨 내용 검토와 수정 이력 계약.
2. AI가 제안한 개념·선수관계의 검토와 목차 근거 연결 품질 평가.
3. 새 분야의 ML 설정·지원 목록과 검토된 문항 은행 등록 연결.
4. 필요한 문제·추천 후보·ML 지원이 준비된 경우에만 진단 활성화.
5. 모호한 분야 확인과 미지원 분야 수집 정책 등록 흐름 확장.

실제 컴퓨터 네트워크 실행은 20권 중 16권을 6개 개념에 연결했고,
개념 × 능력 18개 설계에서 영어 문항 후보 18개를 생성했다. 앱 상태는
`CANDIDATES_READY`이며 모든 문항의 내용 판정은 검토 대기다. 문항 18개의
108개 표시 필드에 대해 앱 수식 렌더 검사를 통과했지만 정답 타당성 검증은 아니다.
작업 구현/입력 버전이 바뀌면 별도 실행 폴더에 생성하고 이전 문항을 보존한다.

문항 생성·형식 검증·AI 검토·진단 활성화를 구분한다. 수집 성공만으로 추천 품질을 주장하지 않는다.

## 2026-10-06 실제 제공자 검색 확인

경제 검색의 후보 최대 100건에서 경제 경영 15권·어린이 10권·고등학교 학습서
10권·대학교재 5권 등 8개 실제 분류가 확인됐다. 경제 경영을 선택해 15권을
`topic 11`에 등록했고 실제 `/api/books?topicId=11`에서 15권 조회를 확인했다.
원문과 canonical ISBN을 대조해 선택한 정확한 분류 및 제목 검색어가 모두 맞는지 확인했다.
같은 결과를 다시 선택하면 기존 분야를 재사용한다. 새 경제 분야의 진단은 비활성 상태다.

`aa`는 실제로 제목에 AA가 들어가는 라이트노벨/학습서 등 검색 결과가 나왔다.
따라서 이 문자열 자체를 항상 무의미하다고 단정하지 않는다. 이 경로의 결과는
서점 도서 검색이며 학습 분야의 유효성 승인이 아니다. 진단 준비 여부와 분리한다.
검색 결과가 없는 경우는 오프라인 및 서버 통합 테스트에서 확인했다.

Backend 전체 테스트 277개(실패 0, 오류 0, 제외 7), Data-Pipeline 전체 439개,
어댑터 오프라인 5개, Frontend 타입 검사·린트·웹 빌드를 통과했다.
실제 원문을 포함한 확인은 로컬 API 통합이며 브라우저 클릭·실기기·추천 품질 검증은 아니다.
검증용 계정과 요청만 삭제하고 사용자 테스트용 경제 카탈로그는 유지했다.

## 2026-10-06 공통 수집 조건과 해외 도서 연결

새 분야 선택은 YES24에서 관찰한 정확한 분류를 유지한다. 선택 후 Data-Pipeline의
`collection_scope`가 분야의 한영 대응, 책 용도, 독자 대상, 원래 분류를 분리해 저장한다.
작은 대응표 및 기존 분야 설정을 사용하며 이 경로에서는 Gemini를 호출하지 않는다.
영어 대응이나 세부 분류 제약을 매핑하지 못하면 국내 수집은 유지하고 해외 수집만 보류한다.

Open Library에서 최대 30개 후보를 검색하고, 원래 주제·분류·독자 대상과 유효한 영어 판
ISBN 근거를 확인한 뒤 최대 20권을 추가한다. 원문과 요청 조건, 포함/제외 사유, 출처별
결과를 별도로 보존한다. 외부 제공자 오류가 나도 국내 책 수집을 버리지 않는다.
ISBN 중복은 합치되 원래 출처는 보존하고, 같은 ISBN인데 제목·언어가 충돌하면 해외
후보를 보류한다. 번역본과 다른 판의 ISBN을 같은 책으로 합치지 않는다.

실제 경제 카탈로그는 국내 15권 + 해외 20권 = 35권으로 갱신했고,
웹 프록시의 `/api/books?topicId=11`에서 35권을 확인했다. 원래 경제 목록은 이전
불변 스냅샷에 남아 있다. 로컬 DB 백업과 전체 테이블 행 해시 비교로 기존 책·계정·진단
기록 보존을 확인했고, 경제의 현재 카탈로그 선택 포인터만 의도적으로 전환했다.
Backend의 Java 계약 변경 없이 어댑터와 자료 레포를 확장했다.

새 해외 책은 메타데이터 후보이므로 목차·개념 연결·문항 검토·진단 활성화는 별도 단계다.
현재 경제 분야의 `assessmentReady`와 `conceptAssessmentReady`는 둘 다 false다.
Google Books 새 분야 수집, 미지원 한영 이름 및 더 세밀한 분류 대응, 선택한 단계부터의
명시적 새로고침은 후속 작업이다. 완료된 부분 성공 체크포인트는 자동 재시도를 하지 않는다.
이번 검증은 로컬 수집→정제→가져오기→API 연결이며 브라우저 클릭이나 추천 품질 검증은 아니다.

## 2026-10-06 Google Books 추가 연결

공통 수집 조건을 Google Books에도 연결했다. Open Library와 다른 검색 문법을 사용하며
원래 volume 분류·언어·ISBN을 확인한 뒤 최대 20권을 정제한다. 같은 ISBN의 중복은
원래 출처와 설명 문서를 보존하며 정리하고, 분류 근거가 없는 책을 임의로 교재나
특정 독자 대상의 책으로 확정하지 않는다. Gemini를 호출하지 않는다.

제공자별 오류를 분리하고, 동일 요청의 구현 버전 변경 후에는 입력 조건이 일치하는
이전 원문을 재사용할 수 있게 했다. 이는 이전 응답의 재정제이며 최신 출처 재조회가 아니다.
각 실행의 `provider-report.json`에 원문 재사용 여부·포함 권수·추가 권수와 오류 상태를
남긴다. 실패한 제공자 때문에 다른 제공자의 성공 결과를 버리지 않는다.

전용 `GOOGLE_BOOKS_API_KEY`는 서버 환경 또는 Data-Pipeline의 비공개 `.env`에 설정할 수
있고 요청에만 전달된다. 저장된 검색 조건과 결과 보고서에는 포함되지 않는다. 로컬에는
전용 키가 없었고 실제 호출은 HTTP 429를 반환했다. 성공적인 Google Books 실수집을
주장하지 않으며, Books API 전용 키 및 해당 할당량 확인이 남아 있다.

실제 어댑터 실행 결과는 국내 15권 + 이전 Open Library 응답의 20권 = 35권을 유지했다.
Google Books 추가 권수는 0이다. 이전 네 개 canonical JSONL과 바이트 단위로 일치했고,
웹 프록시 API에서도 경제 35권을 확인했다. 새 데이터가 없어 DB 스냅샷을 추가 활성화하거나
기존 자료를 변경하지 않았다. Data-Pipeline 454개, Python 어댑터 9개 테스트와 스타일
검사를 통과했다. 화면 및 Java Backend 계약은 이번 단계에서 변경하지 않았다.

명시적 새로고침/제공자별 재시도, 세부 분류 대응, 목차·개념·문항 검토와 진단 활성화는
후속 단계다. 실제 API 제한과 모델 정확도를 혼동하지 않는다.

## Explicit catalog refresh (2026-10-06)

The first refresh slice supports provider-discovered `search-*` browsing fields with a matching private canonical artifact. It does not refresh curated diagnosis banks or activate diagnosis. `GET /api/topic-requests/catalog/{topicId}` and `POST /api/topic-requests/catalog/{topicId}/refresh` require authentication. POST accepts only `{ "mode": "ALL" }` or `{ "mode": "FAILED" }`. The latter derives provider failures from the active artifact; a client cannot submit commands, paths, provider parameters, or a new scope.

Refresh is shared per topic and durable in `topic_catalog_refresh`. Concurrent pending requests replay the shared state. A new job gets a UUID and an immutable `catalog-refresh/{uuid}` workspace, bypassing older provider-response replay for explicitly requested providers. Raw checkpoints inside the same job remain reusable after an interrupted normalization. There is one new refresh per topic per minute and ten per account per hour. Jobs participate in the existing preparation lock and busy checks.

The adapter authenticates the baseline against the database selection hash, source manifest hash, canonical bibliographic/evidence hashes, and the original discovery scope. Successful results supplement the previous canonical data. A failed source never removes existing books. Bibliographic identity conflicts are audited and excluded; absent cross-provider subject/category mappings remain explicit. Historical files remain unchanged. The active selection is switched only after both import and selection validation within one transaction, with a lease token and current-baseline fence. A removal of any previously visible book rolls back the transaction.

Public state exposes source names/status, visible count, added count and update time. It never exposes private artifact paths, raw hashes, request ownership or API keys. `PARTIAL` means at least one source failed; `FAILED` means no new selection was activated. This is an additive refresh: it does not reconcile deletions at source catalogs. Existing accounts, diagnostic answers, question banks and recommendation records stay separate.

## 2026-10-06 공통 분야 ID와 독립적인 다국어 수집

공통 분야 정의는 Data-Pipeline의 `configs/discovery-subjects.json` 한 곳에 둔다.
경제학·미시경제학·거시경제학을 먼저 지원한다. 별칭 → 공통 영문 ID → 한국어/영어
출처별 검색어로 연결하고, 부모 분류는 사이트의 국내도서 분류가 아닌 공통 분야의
도메인으로 정한다. 기존 `search-*` ID, 계정, 도서, 진단·문항 데이터는 유지한다.
새 경로의 ID는 `field-microeconomics`처럼 기존 진단 ID와 구분한다.

- `POST /api/topic-requests/discover`의 결과에 `fields`를 추가했다. 각 항목은 공통 ID,
  한국어/영어 표시 이름, 부모 이름, 중복 제거 후 권수, 출처별 상태·권수·예시를 제공한다.
  YES24·Open Library·Google Books를 독립적으로 병렬 검색하며 국내 결과가 없어도 해외
  결과를 확인한다. 실패한 출처의 내부 오류·키·파일 경로는 공개하지 않는다.
- `POST /api/topic-requests`는 `name`, `discoveryId`, `commonFieldId`로 선택한다.
  소유한 검색 결과에서 관측한 ID만 허용한다. 임의 ID, 다른 계정의 결과, 공통 분야와
  사이트 분류를 섞은 입력은 거부한다. 언어가 다른 별칭 요청도 같은 분야로 중복 방지한다.
- 선택 시 원문·정제 결과와 정의 해시를 확인하고 기존 검색 결과를 재사용하여 게시한다.
  API 요청은 큐에 들어가고 화면에서 준비 상태를 확인한다. 원문이나 분야 정의가 바뀌면
  자동으로 다른 범위의 데이터로 게시하지 않는다. canonical JSONL은 변경하지 않는다.
- 미등록 이름은 기존 출처 분류 탐색으로 이어진다. 임의 한글을 번역해서 무제한으로
  새 공통 분야를 확정하는 기능은 이번 구현에 포함하지 않는다. Gemini는 호출하지 않는다.
- 공통 분야의 목록 갱신도 기존 `catalog/{topicId}` 경로를 사용한다. 영어 입력으로 요청한
  분야라도 국내 출처는 등록된 한국어 검색어로 갱신한다. 기존 책을 보존하고 실패한
  출처만 다시 확인할 수 있다. 진단·문항 생성·추천 활성화는 별도 단계다.

로컬 실수집에서 `Microeconomics` 입력 후 분야 선택 → `미시경제학` 추가 → 책 40권 공개를
확인했다. YES24 한국어 20권, Open Library 영어 20권이며 Google Books는 HTTP 429로
0권이었다. 출처별 상한은 현재 20권이다. 검색어가 일치하는 문제집·해답집도 탐색 목록에는
남을 수 있으며, 수집 성공을 추천 후보의 품질 검증이나 문항 승인으로 표현하지 않는다.
새 분야의 `assessmentReady`, `conceptAssessmentReady`는 false다.

검증 범위: Data-Pipeline 456개 테스트, Python worker 16개 테스트, 백엔드 분야 관련
회귀 테스트, Frontend 타입 검사·린트·웹 export, 실제 로컬 HTTP/API 통합.
브라우저 클릭·네이티브 기기 동작·추천 품질 검증은 포함하지 않는다.


## 2026-10-06 자동 검토와 진단 활성화

이 절은 위의 수집·생성 단계별 기록에서 남겨 둔 진단 활성화 후속 작업을 구현한 내용이다.
책 목록 갱신으로 현재 source snapshot이 바뀌면 해당 버전의 개념·문항·검토도 다시 준비한다.
원래 요청이나 요청 계정이 삭제돼도 공유 분야 작업은 독립적인 workspace ID로 이어진다.

`CANDIDATES_READY → REVIEWING → REVIEW_PENDING … → ACTIVE / REVIEW_BLOCKED / FAILED`.
한 번에 최대 두 문항을 검토한다. 검토 결과는 생성 보고서와 분리하고 정확한 생성 ID·
설계·후보 파일 해시에 연결한다. 생성 형식 오류는 원본을 남긴 채 한 번만 재작성한다.
AI 내용 판정은 정답·난도 적합 여부와 목표·오답·해설 점수를 확인하며, 선수관계 후보도
별도로 검토한다. 같은 설정 모델을 별도 호출하는 방식이므로 독립된 모델들의 합의나
사람 승인, 실제 사용자 기반의 난도 보정으로 해석하지 않는다.

- 문항 후보 전체를 실제 Frontend Markdown/KaTeX 렌더러로 검사한다. 검토 어댑터 서버에는
  Node와 `Frontend/native` 의존성이 필요하다. 실행 파일은 `TOPIC_CONTENT_RENDER_NODE`
  (미설정 시 PATH의 node)를 사용한다. 렌더러가 없거나 수식 오류가 있으면 공개하지 않는다.
- 새 분야의 검토된 개념 그래프를 불변 파일로 만들고 `runtime-topics/{slug}.json` 포인터를
  원자적으로 교체한다. ML에는 `BOOKMATCH_ML_TOPIC_REGISTRY`를 같은 디렉터리로 설정한다.
  `/ml/concepts/{slug}`와 `concept-learning-v2`가 정확한 그래프 해시로 응답하는지 확인한다.
- 현재 수집 버전의 실제 목차 근거가 있는 책 연결과 검토 완료 문항 전체를 하나의 DB
  트랜잭션으로 게시한다. 부분 등록 실패는 전부 롤백한다. `ACTIVE`는 DB 게시 완료 후다.
- 파일 포인터와 DB는 분산 트랜잭션이 아니다. ML 확인이나 DB 게시가 실패하면 파일 포인터가
  남을 수 있지만 신규 진단·추천 발급은 현재 DB 준비 상태에서 차단한다. 재시도는 같은
  불변 자료를 확인한다. 이미 발급한 진단·기존 추천·질문 원본은 보존한다.
- 새로 수집한 목록이나 검토 중인 은행은 준비 완료로 표시하지 않는다. 정답 키·해설·
  검토 판정·파일 경로를 진단 발급 응답에 노출하지 않는다.

| API | 동작 |
| --- | --- |
| GET `/api/topics/{id}/preparation` | 공유 분야의 현재 생성·검토 단계와 건수 조회 |
| POST `/api/topics/{id}/preparation/retry` | 로그인 후 실패 단계 재시작. 생성 완료분은 다시 검토하며, 미완료 생성은 저장분부터 계속함 |

`REVIEW_BLOCKED`는 내용이나 선수관계 보완이 필요한 상태다. 선수관계가 통과하고
미승인 문항이 1~2개인 첫 검토라면 다음 작업에서 해당 문항만 한 차례 자동 재작성한다.
검토 의견과 이전 문항을 받아 새 생성 ID로 저장하며 통과 문항의 바이트와 ID는 유지한다.
이전 생성·검토 보고서 해시를 새 묶음에 연결한다. 새 묶음 전체를 다시 렌더링·내용 검토하며
이전 승인을 수정한 문항에 붙이지 않는다. 두 번째 보완 요청, 세 개 이상 문제, 선수관계
보완은 대기 상태로 남긴다. 자동 보완은 무한 반복이나 강제 승인이 아니다.
공개 `repairPending`이 true인 동안 화면 갱신을 유지한다. 외부 API 오류로 중단되면
저장분부터 재시도할 수 있다. 임의 모든 분야의 진단 성공을 보장하지 않는다.

반복 수집된 여러 출처의 목차를 한 트리로 합치면 순서가 중복될 수 있다. 원본 canonical
자료는 유지하고 각 책의 유효한 출처별 트리를 검사하여 가장 완전한 목차 하나를 분석에
사용한다. 선택한 출처와 제외한 잘못된 트리를 기록한다. 같은 목차 반복으로 개념 비중을
부풀리지 않는다. 이 근거는 다루는 개념을 보여주며 책 본문 난이도를 측정한 것은 아니다.

## 2026-10-06 한국어 문항 표시와 원문 전환

`question-translation.enabled=true`에서 검토된 활성 v5 영어 문항에 대한 번역을 준비한다.
로컬 실행 스크립트는 분야 자동화가 켜져 있으면 번역도 켜며 `QUESTION_TRANSLATION_ENABLED=false`로 끌 수 있다.
기존 Gemini 설정과 키를 사용한다. 사용자·응답·정답·해설은 번역 입력에 포함하지 않는다.
번역은 `question-translation.python`으로 지정한 Question-Generation 환경에서 실행한다.
로컬 실행 스크립트는 해당 레포의 `.venv/bin/python`을 지정한다. 수집용 Python과 분리한다.

원문을 수정하지 않고 `question_display_translation`에 문항 ID·원문 생성 해시·언어별로 저장한다.
숫자·수식은 자리표시자로 보호하고, 원래 필드에서 같은 내용이 중복·누락 없이 보존됐는지 확인한 뒤 복원한다.
한국어 어순에 따른 문장 내 수식 위치 변경은 허용하며, 조건과 관계의 의미 보존은 별도 검토한다. 보기 순서는 유지한다.
원문의 수사(zero, two 등)가 같은 값의 숫자로 표시되는 경우도 같은 필드 안의 원문 등장 횟수까지 허용한다.
숫자 값 변경·추가·중복·수식 변경은 거부하고, nonzero의 부정 의미 등은 의미 검토에서 함께 확인한다.
별도 Gemini 호출에서 의미·보기 순서·힌트 추가 여부를 검토하고 실제 Frontend 렌더러로 확인한다.
번역 작업 사이에는 기본 5초 간격을 두며, 제공자의 일시적 오류는 SDK에서 최대 두 번만 시도한다.
검토 실패는 FAILED로 남으며 무제한 호출하지 않는다. 운영자 재시도는 해당 FAILED 행을 QUEUED로 바꿀 수 있고,
동일 원문·모델·구현 버전의 저장된 생성/검토는 재사용한다. 의미 검토에서 거부된 결과를 강제 승인하지 않는다.
형식·의미 검사 실패 시 실제 오류와 검토 의견을 넣어 한 차례 새 번역을 만든 뒤 동일한 검사와 의미 검토를
다시 수행한다. 첫 번역과 검토는 별도 파일로 보존한다. 두 번째 결과도 실패하면 원문 표시를 유지한다.

발급 응답의 기존 `prompt`, `passage`, `choices`는 영어 스냅샷 그대로이며, `translation`을 선택 필드로 추가한다.
정확히 같은 원문 해시·제시문·문제·보기일 때만 번역을 연결한다. 번역은 정답 키나 해설을 공개하지 않는다.
완료된 기존 진단의 원문 스냅샷과 정답·프로필도 수정하지 않는다.
화면은 준비된 한국어를 기본 표시하며 원문/한국어 버튼으로 바꾼다. 번역이 없으면 원문으로 진행할 수 있다.
진행 중에는 번역만 갱신하고 선택·저장한 답은 유지한다. 번역 준비는 진단 내용 검토와 별도이며,
한국어가 항상 먼저 준비된 뒤 분야가 열리는 추가 게이트는 이번 단계에서 넣지 않았다.

배포에는 Node와 Frontend/native 의존성 및 `scripts/validate-display-translation.mjs`가 필요하다.
준비 작업에서 문항별 번역과 별도 검토를 저장하며 화면 전환·같은 문항 재발급에는 호출하지 않는다.
AI의 의미 검토는 사람의 번역 감수나 언어별 진단 동등성 실험을 대체하지 않는다.

2026-10-07 로컬 재처리 검증: 활성 v5 72문항의 번역이 READY이며 수식 렌더링 360개 필드에서
오류가 없었다. 원문 해시·내용과 숫자 값 보존을 확인했다. 선형대수·컴퓨터 네트워크·경제·미시경제·
거시경제에서 각 9문항(총 45문항)을 실제 API로 발급하고 답변 저장·재조회 및 원문 보존을 확인했다.
임시 계정만 정리한 뒤 기존 계정·문항·진단·답변·추천 테이블의 내용이 변경되지 않았음을 확인했다.
이는 로컬 번역 연동 검증이며 번역의 인간 감수나 진단·추천의 측정 타당성 검증은 아니다.


### Integration hardening (2026-10-08)

Catalog status and refresh requests read a public provider-status projection stored with the
selection snapshot. They do not launch Python processes while serving HTTP or holding user/topic
locks. Initial collection and refresh publication update the projection transactionally; older
snapshots without recorded provider status show `not_collected`. A full refresh remains available,
while failed-provider retry requires an observed failure. The worker still verifies the canonical
baseline, selection hash and immutable evidence before collecting or publishing.

Content preparation copies each source snapshot to its own directory and publishes that copy
atomically after integrity checks. A catalog refresh can therefore prepare a new snapshot without
overwriting or reusing the previous snapshot's evidence. Interrupted copies remain unpublished.

Display translation failures retry within the three-attempt budget. Publication checks passage
presence and nonblank text/choices against the stored source and fences stale claims; original
questions, answer keys and issued snapshots remain unchanged.
