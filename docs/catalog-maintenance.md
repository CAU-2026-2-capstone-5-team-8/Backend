# 카탈로그 분석 교체와 데이터 현황 검사

캡스톤 개발팀용 명령이다. 계정·공개 배포·관리자 화면을 추가하지 않는다.
`local-catalog-import-v1`의 검증과 원본 해시를 재사용한다. 새 수집이나 난이도 추정은 하지 않는다.
공개 API 및 기존 가져오기의 덮어쓰기 거부 정책은 그대로다.

## 교체 범위

- 기존 책의 **선택한 분야별 rank-v2/learning 분석 버전**만 원자적으로 교체한다.
- 책 ID·제목·책장·리뷰·진단·사용자·저장된 추천 JSON과 이전 분석은 보존한다.
- 새 책/분야/연결은 기존 importer로 먼저 등록한다. 교체 명령은 생성하지 않는다.
- 선택하지 않은 책은 그대로 둔다. 전체 카탈로그 동기화/삭제 명령이 아니다.
- 기존 책에 처음 분석을 연결하는 것도 가능하다. 복구하면 활성 분석이 없는 상태로 돌아간다.
- 새 `snapshot_id`와 새 `feature_version`이 필요하다. 같은 버전의 내용/출처를 덮어쓰지 않는다.
- discovery 목차 수집 집계, 책 메타데이터, 문항 은행, ML 그래프는 교체 대상이 아니다.

## 실행

Java 21 및 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 필요하다. 먼저 별도 검증 DB에서 실행한다.
Spring 시작 시 Flyway가 마이그레이션을 적용하므로 기존 DB 사용 전 백업한다.
ML 서버나 웹 서버는 필요 없다. 아래 `JAR`는 빌드 산출물 경로다.

```sh
./gradlew clean build
JAR=build/libs/backend-0.0.1-SNAPSHOT.jar
java -jar "$JAR" --spring.main.web-application-type=none \
  --spring.profiles.active=catalog-maintenance --catalog-maintenance.action=report
```

출력의 `CATALOG_MAINTENANCE_RESULT=` 뒤가 JSON이다. 일반 앱 화면에는 노출하지 않는다.
다른 import/demo/local/bootstrap 프로필과 함께 실행하면 DB 연결·seed 실행 전 거부한다.

### 미리보기 → 적용 → 복구

```sh
java -jar "$JAR" --spring.main.web-application-type=none \
  --spring.profiles.active=catalog-maintenance --catalog-maintenance.action=preview \
  --catalog-maintenance.manifest-path=/absolute/path/backend-manifest.json

java -jar "$JAR" --spring.main.web-application-type=none \
  --spring.profiles.active=catalog-maintenance --catalog-maintenance.action=apply \
  --catalog-maintenance.manifest-path=/absolute/path/backend-manifest.json \
  --catalog-maintenance.token=sha256:PREVIEW_OUTPUT_TOKEN \
  '--catalog-maintenance.reason=검토한 목차 매칭 결과 반영'

java -jar "$JAR" --spring.main.web-application-type=none \
  --spring.profiles.active=catalog-maintenance --catalog-maintenance.action=rollback \
  --catalog-maintenance.transition-id=UUID_FROM_APPLY \
  '--catalog-maintenance.reason=검토 결과 이전 분석으로 복구'
```

`token`과 `transition-id`는 앞 단계 출력값으로 대체한다.

미리보기는 기존 importer를 트랜잭션 안에서 실제 실행하고 롤백한다. 파일 누락/해시 불일치,
개념 중복, 잘못된 점수, ISBN 충돌도 같은 검증을 받는다. 행 변경은 남지 않지만 PostgreSQL
시퀀스 번호는 소비될 수 있다. 미리보기의 새 projection ID는 임시 값이며 적용 시 달라진다.
`before`/`after`의 버전, 개념·선수개념·출처 연결 개념 수를 비교한다.

확인값은 manifest의 정확한 바이트(참조 파일 해시 포함)와 선택한 책의 현재 활성 분석 내용에
묶인다. 적용 시 참조 파일 해시도 검사한다. 실패하면 비활성화/신규 분석/감사 기록을 모두 롤백한다.
두 importer와 공통 DB writer lock을 사용한다. 같은 확인값의 동시 적용·재시도는 하나의 ID로
수렴한다. 이미 성공한 명령의 재시도는 저장된 이력을 반환하고 파일을 다시 가져오지 않는다.
적용 후 다른 버전이 활성화됐거나 복구한 명령은 재시도로 덮어쓰지 않는다.

복구는 현재 활성 분석이 해당 적용 결과 그대로이고 이전 분석 내용도 보존돼 있을 때만 가능하다.
같은 책을 후속 교체했다면 후속 교체부터 복구한다. 새 분석·manifest·감사 이력은 남는다.
DB 전체 복원은 아니다. 직접 SQL은 advisory lock 규약을 우회하므로 명령과 동시에 실행하지 않는다.
이력은 `backend.catalog_transition`의 ID·snapshot·사유·시각·전후 상태로 확인한다.
새 추천만 새 분석을 사용하며 저장된 추천은 생성 당시 결과를 유지한다.

## 현황 해석

`catalog-readiness-v1`은 DB 재고 검사이지 정확도 평가가 아니다.

- `bookCount`, `conceptBookCount`: 분야 도서 수, 활성 분석에 개념이 있는 도서 수.
- `sourceLinkedConceptBookCount`: 개념에 출처 참조가 저장된 도서 수. 원격 자료를 다시 가져오거나
  해당 출처/매칭의 타당성을 재검토했다는 뜻은 아니다.
- `tocBookCount`: discovery import의 최신 기록에 목차가 있는 도서 수.
  `tocCoverageKnownBookCount`/`tocCoverageUnknownBookCount`와 함께 본다. local import만 했다면
  집계가 없을 수 있으므로 0을 '실제 목차 없음'으로 해석하지 않는다.
- `selfReportCount`/`objectiveCount`: 활성 자기보고/객관식 수를 분리한다.
- `humanApprovedObjectiveCount`/`aiApprovedObjectiveCount`: 저장된 승인 출처를 구분한다.
  AI 승인은 사람의 검토를 뜻하지 않으며 둘 다 측정 정확도 인증은 아니다.
- `priorKnowledgeQuestionCount`: 개념 ID가 있는 활성 v5 객관식 수.
- `unassessedConcepts`: 다루는 개념/명시된 선수개념 중 v5 객관식이 전혀 없는 개념.
- `missingConceptAbilities`: 개념에서 빠진 `meaning`, `application`, `reasoning` 문항 칸.
  한 문항의 존재는 측정의 충분성을 보장하지 않는다. ML 그래프의 추가 추론 선수개념까지
  펼치는 검사가 아니며 legacy v2/v4 문제를 prior-knowledge v5로 간주하지 않는다.
- `legacySessionAvailable`: 기존 영역별 최소 3문항 조건 충족 여부이지 추천 품질 인증이 아니다.

경고는 누락 작업을 알려주며 자동으로 자료를 만들어 채우거나 교체를 승인하지 않는다.
개념 ID의 현재 ML 그래프 호환성과 추천 품질은 별도 ML 연동·사람 평가가 필요하다.

## 검증

실제 PostgreSQL 테스트로 미리보기 무변경, 선택 범위, 실패 원자성, 동시 적용,
stale token, 복구 순서, 이전 내용 변조 거부, 기존 책장/추천 JSON 보존 및 문항 분류를 검사한다.
실제 수집 파일을 새 Testcontainers DB에 넣는 선택적 검사는 다음과 같다.

```sh
BOOKMATCH_CATALOG_MANIFEST=/absolute/path/backend-manifest.json \
  ./gradlew test --tests '*CatalogReadinessLiveHandoffTest' --rerun-tasks
```

환경변수가 없으면 이 검사는 제외된다. 기존 사용자 DB나 사람의 추천 적합성 평가를 대신하지 않는다.

### 2026-10-05 검증 기록

- WSL Ubuntu / Java 21 / PostgreSQL 17.11 Testcontainers: `clean build` 성공.
  실제 handoff 검사를 활성화한 실행에서 269개 중 262개 통과, 기존 외부 연동 조건부 7개 제외,
  실패/오류 0개. CI에는 로컬 handoff가 없으므로 해당 검사도 추가 제외된다.
- 기존 수집본 `actual-os25-sept23-learning-evidence-20261004`를 새 임시 DB에 import:
  도서 25권, 개념 연결 7권, 출처 참조 연결 7권. manifest의 candidate SHA-256은
  `d4f4dbf4195db6aa07d06dac582097108734c94ddd7dcc7607d7cb3047d17cab`.
- 이 검사는 책 분석만 import했다. discovery 수집 집계는 미수입(25권 unknown), 문항 은행은
  미수입(0문항)이다. 운영 DB나 다른 검증 환경의 문항 수가 0이라는 주장이 아니다.
- 개념/명시적 선수개념 16개 및 개념별 의미·적용·추론 총 48개 문항 칸의 부족이 출력됐다.
  자료 생성이나 추천 정확도 개선을 주장하지 않는다.
- 독립 리뷰에서 신규 책까지 생성할 수 있는 범위 누출을 발견했다. 재현 테스트가 실제 실패하는
  것을 확인한 뒤 기존 책/분야 연결만 허용하도록 수정했다. 재검토에서 Important/Critical 잔여 없음.
