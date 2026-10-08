# 1차 멘토링 후속 구현 점검

2026-10-02 기준. 멘토링 보고서의 계획과 실제 구현/열린 PR을 구별한다. 보고서의 수집 수치와 모델 정확도를 이번 작업에서 다시 측정한 것은 아니다.

| 멘토링 항목 | 확인한 상태 | 후속 처리 |
| --- | --- | --- |
| 사용자가 부족한 지식과 다음 확인 항목 표시 | ML #31, Backend #27, Frontend #5 구현 PR이 열려 있음 | 중복 구현 없이 기존 PR 검토/통합 필요 |
| 진단을 도서 추천에 연결 | main에 rank-v2 HTTP 연동과 Frontend 추천 표시 존재 | 정확도 검증용 사람 평가 데이터는 별도 필요 |
| 단일 서버 로컬 캐시 | main에는 미구현 | 이번 브랜치에서 도서 상세 기본 정보 Caffeine 캐시 구현 |
| 2지선다로 설문 부담 감소 | 자기평가는 2지선다, 생성 객관식은 4지선다 계약 | 선택지를 임의로 삭제하지 않음. 생성·승인·정답 인덱스·Backend·Frontend 계약을 함께 바꿔야 함 |
| 문항 신뢰성 검증 | 생성 검증 및 human-review/import 경로 존재, Backend #26 일괄 import PR 있음 | 승인되지 않은 문항을 정식 문제로 추가하지 않음 |
| 도서 선정/수집 품질 | Data-Pipeline #40/#45/#46 등 별도 PR 진행 중 | 이번 작업에서 변경/머지하지 않음 |
| 독서 기록·미션·커뮤니티, 대상 분야 확장 | 보고서에서 후속 방향으로 제시 | 핵심 진단·추천 검증 이후 별도 범위로 진행 |

## 이번 구현: 도서 상세 기본 정보 캐시

2026-10-05 갱신: `GET /api/books/{bookId}`의 ID, 제목, 저자, 설명, ISBN, ML 도서 ID, 검수된 표지 URL을 불변 record로 캐시한다. 표지는 기존 `CoverUrl.reviewed` 검사를 거친 값만 담는다. JPA 엔티티와 사용자 프로필, 추천 결과, 분야 관계, `featureAvailable`, `tocEntryCount`, `rankingCandidate`, `coveredConceptCount`는 캐시하지 않는다. 목록 조회/페이지 수/추천 후보 쿼리는 기존 DB 경로를 유지한다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `CATALOG_METADATA_CACHE_ENABLED` | `true` | `false`로 캐시 우회 |
| `CATALOG_METADATA_CACHE_MAXIMUM_SIZE` | `2000` | 저장 메타데이터 버전 수 상한(바이트 상한 아님) |
| `CATALOG_METADATA_CACHE_TTL` | `PT5M` | 적재 시점부터 만료까지의 기간 |

양수 크기/기간만 허용한다. 같은 `(id, updated_at)` 버전을 동시에 조회하면 Caffeine의 원자적 적재를 사용하며, 404나 DB 예외는 캐시하지 않는다. 빈도 높은 조회도 TTL을 연장하지 않는다. 만료 후 다음 요청에서 다시 적재하며 백그라운드 외부 API 갱신 작업은 아니다.

매 상세 조회에서 DB의 `updated_at`을 확인하고 해당 버전만 조회한다. 기존 `book_updated` 트리거가 SQL/JdbcTemplate 수정에도 시간을 갱신하므로 JPA 이벤트에 의존하지 않는다. 행이 삭제되면 캐시에 이전 값이 있어도 404를 반환한다. 도서 상세의 기존 REPEATABLE_READ 트랜잭션 안에서 버전과 본문을 읽으며, 이미 시작된 조회는 자기 DB 스냅샷을 유지할 수 있다. 커밋 이후 시작된 조회는 새 버전을 사용한다. 이전 버전은 TTL/크기 제한으로 제거되며 여러 서버도 각자 DB 버전을 확인한다. 이 방식은 기존 PostgreSQL timestamp 정밀도에 의존한다.

적중 시 전체 메타데이터 적재를 생략하지만 버전 확인 쿼리는 실행한다. 쿼리 수 감소, 응답 시간 개선율, 추천 정확도 향상은 실측하거나 보장하지 않는다. DB 스키마 변경은 없다.

구현 참고: [Caffeine 원자적 적재](https://github.com/ben-manes/caffeine/wiki/Population), [크기 및 시간 만료 정책](https://github.com/ben-manes/caffeine/wiki/Eviction).

## 검증 결과

2026-10-05 최신 main(`554badc`) 병합 후 WSL Ubuntu Java 21/PostgreSQL 17.11 Testcontainers에서 `./gradlew clean build --no-daemon --console=plain` 성공. 총 234개 중 227개 통과, 실패/오류 0개, 실제 외부 ML/승인 아티팩트가 필요한 선택 테스트 7개 skip. SQL 메타데이터 수정·삭제·표지 추가 테스트 3개가 기존 캐시에서 실제 실패한 뒤 수정 후 통과했다. 롤백, 표지 제거, 버전별 스냅샷, DB 실패와 기존 분야·feature 최신성까지 카탈로그/캐시 테스트 26개를 확인했다. 종료 중 닫힌 Testcontainers 연결에 대한 Hikari 경고와 기존 컴파일 경고가 있었지만 빌드는 성공했다. 원격 CI는 별도 확인 대상이다.
