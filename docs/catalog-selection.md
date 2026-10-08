# 로컬 도서 목록의 선택 버전

Data-Pipeline에서 수집·정제한 파일은 Backend DB에 자동 복제되지 않는다.
필터 수정 전 자료를 추가 import하면 분야가 잘못 연결된 책도 그대로 노출된다.
브라우저 캐시를 지우거나 서버를 재시작하는 것으로 고쳐지지 않는다.

## 갱신과 실행

1. Data-Pipeline의 사용할 커밋과 raw 입력을 고정한다. 작업 중인 다른 변경과 섞지 않는다.
2. 새 도서라면 기존 `discovery-catalog-import`로 먼저 원본과 분석 자료를 등록한다.
3. `scripts/prepare-catalog-selection.py`로 **새 snapshot ID, 새 출력 폴더**에 선택 manifest를 만든다.
   이 명령은 Data-Pipeline의 필터를 호출하며 별도의 분야 판정 규칙을 만들지 않는다.
   원본 manifest/hash와 모든 분야·도서의 포함/제외 결정을 기록한다.
4. 검토한 manifest를 `.local/catalog/current-selection.json`으로 교체한다.
5. 아래 실행 명령을 사용한다. 시작할 때 선택 manifest를 검증하고 DB에 원자적으로 적용한다.
   잘못된 파일이면 시작이 실패하고 이전 선택은 유지된다. 동일 버전 재실행은 안전하다.

```bash
python3 scripts/run-local-backend.py
```

이 명령은 `.local/runtime.json`에 지정한 Java와 기존 PostgreSQL 연결 환경을 사용한다.
config 형식은 실행 스크립트 상단을 참고한다. `.local/`은 Git에서 제외한다.
ML과 Frontend 서버는 별도로 실행한다. DB 볼륨을 삭제하거나 다른 DB로 바꾸지 않는다.
일반 Java/Gradle 실행에도 `--spring.profiles.active=local`과
`--catalog-selection.manifest-path=/absolute/path/to/selection-manifest.json`을 전달할 수 있다.
경로 없이 시작해도 같은 DB에 마지막으로 활성화한 선택은 유지된다.

준비 명령 예시(경로·커밋·snapshot ID를 실제 입력으로 바꾼다):

```bash
python3 scripts/prepare-catalog-selection.py \
  --source-manifest /path/to/imported/catalog-manifest.json \
  --raw-root /path/to/raw \
  --pipeline-root /path/to/pinned/Data-Pipeline \
  --pipeline-python /path/to/Data-Pipeline/.venv/bin/python \
  --pipeline-revision FULL_40_CHARACTER_COMMIT_SHA \
  --snapshot-id catalog-selection-NEW_VERSION \
  --output .local/catalog/NEW_VERSION
```

2026-09-29 자료는 요청 후보 수가 현재 CLI 계획과 다른 이전 단일 요청 형식이다.
해당 자료에만 `--legacy-yes24`를 명시한다. 어댑터는 원본 응답 hash, 출처, 분야,
당시 단일 요청 파라미터를 검증한 뒤 **현재 Data-Pipeline normalizer**를 직접 호출한다.
원본 요청 메타데이터를 고쳐 쓰지 않는다. 새로운 수집 자료는 기본 CLI 경로를 쓴다.
새 책이 선택 결과에 있는데 원본 catalog에 없다면 조용히 누락하지 않고 실패한다.

## 적용 범위와 확인

- `GET /api/books/selection`: 분야별 현재 snapshot, 원본 정책 커밋, 포함/제외 수.
- `GET /api/books`, `/api/books/summary`: 선택된 목록과 동일한 페이지·집계 기준.
- 새 추천의 scalar/v2/learning 후보: 같은 분야별 선택을 적용한다.
- 원본 book, book_topic, projection과 사용자·진단·저장된 추천은 삭제하지 않는다.
  제외된 책도 과거 결과의 상세 링크로 조회할 수 있다.
- 이미 저장된 추천 또는 동일 Idempotency-Key로 재생하는 결과는 과거 결과 그대로다.
  최신 후보로 비교하려면 새 추천을 생성한다.
- 갱신 전부터 열어둔 Frontend 탭은 이전 목록을 메모리에 유지할 수 있다.
  적용 후 한 번 새로고침한다. 서버의 선택 버전과 브라우저 화면 상태는 별개다.
- 선택이 관리하는 분야에 새로 import한 책은 새 선택 버전에서 포함하기 전까지 숨긴다.
  새 수집 파일을 저장하거나 Git pull만 했다고 앱이 갱신된 것으로 간주하지 않는다.

2026-10-05 로컬 적용: 원본 471권 중 출처 필터 통과 380권
(선형대수 98, 운영체제 53, 알고리즘 44, 데이터베이스 91, 이산수학 88, 확률통계 6).
필터 통과는 **전공 교재 적합성 검토나 추천 정확도 검증이 아니다**.
되돌릴 때도 보관한 선택 manifest를 다시 활성화한다. 데이터 삭제로 되돌리지 않는다.
