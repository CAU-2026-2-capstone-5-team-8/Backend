# 3단계 검증 결과

검증일: 2026-09-12. 구현 범위: 분야·도서 조회, 카탈로그 DB, 선택적 합성 데모 데이터. 진단·추천·ML 계산은 아직 구현하지 않았다.

## 최종 테스트와 빌드

WSL Ubuntu에서 실제 PostgreSQL 17.11 Testcontainers를 사용했다.

```text
wsl -d Ubuntu -- bash ./gradlew clean build --no-daemon --console=plain
BUILD SUCCESSFUL in 1m 30s
7 actionable tasks: 7 executed
```

JUnit XML: BootstrapIntegrationTest 5개 + CatalogIntegrationTest 12개 = **17개, failures=0, errors=0, skipped=0**. H2 및 mock DB를 사용하지 않았다. OpenJDK class sharing 경고는 기존과 동일하며 빌드 실패는 없었다.

- 분야 목록과 부모 관계, 직접 연결된 분야 필터, ID 순 페이지네이션과 최대 int 페이지 번호
- 도서 상세, 비활성 특성, 같은 도서의 서로 다른 분야에서 특성 제공 여부 분리
- 잘못된 ID·페이지·문자열 입력 400과 존재하지 않는 리소스 404, 추적 가능한 오류 응답
- 점수 범위·NaN·Infinity 거부, 활성 특성 중복·잘못된 분야 참조·주 분야 중복 거부
- 분야 순환 및 고아 참조 거부, READ COMMITTED와 REPEATABLE READ에서 동시 부모 변경 직렬화
- 동시성 테스트는 pg_blocking_pids로 두 번째 작성자가 실제 잠금 대기하는 것을 확인한 뒤 첫 작성자를 커밋한다.
- 기본 실행에서는 데이터 없음, 데모 입력 재실행 후 중복 없음, 데모 해제 후 Flyway 검증 성공

## 실패 재현과 수정

처음 작성한 카탈로그 테스트 9개 중 8개가 API 404 또는 업무 테이블 미생성으로 실패했다. 구현 후 기존 테스트를 포함한 14개가 통과했다.

코드 리뷰에서 demo 프로필에만 Flyway repeatable seed 경로를 추가하면 demo 해제 후 이력 검증이 실패하는 문제를 발견했다. 재현 테스트가 실제 실패하는 것을 확인했다. 이후 seed를 Flyway 이력에서 분리했다. `DemoCatalogInitializer`는 demo 프로필에서만 실행하며, 동일 트랜잭션의 PostgreSQL advisory lock을 확보하고 `db/demo/catalog.sql`을 실행한다. 수정 후 재현 테스트가 통과했다.

후속 리뷰에서 남은 중요한 문제는 없었다. 검토 중 지적된 분야별 특성 검증과 동시 요청 겹침 검증도 테스트에 추가했다.

## 기존 DB 업그레이드와 실제 JAR

기존 Compose 볼륨의 초기 migration `20260912102447`에 `20260912123542`를 적용했다. 초기 migration은 변경하지 않았다.

1. `local,demo` 프로필로 빌드 JAR 실행 성공.
2. `/api/books?size=2`에서 도서 2권, totalElements=5, totalPages=3과 OS의 featureAvailable=true 확인.
3. 서버를 종료한 뒤 동일 DB에서 `local`만 활성화해 재시작 성공. Flyway는 No migration necessary를 기록했다.
4. demo 해제 후 `/api/books?size=1`에서도 totalElements=5 확인.
5. OpenAPI 3.1.0에 `/api/topics`, `/api/books`, `/api/books/{bookId}` 3개 경로 확인.
6. 검증용 서버와 Compose 컨테이너 종료. 기존 DB 볼륨과 데이터 보존.

JAR 실행 검증 후 OpenAPI의 설명을 3단계로 갱신하고 동시성 검사를 강화한 최종 전체 빌드가 다시 통과했다.

## 실행 안내와 다음 단계

README의 로컬 실행 절차를 따른다. demo 데이터를 새로 넣으려면 `--spring.profiles.active=local,demo`를 사용한다. demo 프로필 해제는 이미 저장한 데이터를 삭제하지 않는다. 합성 도서·특성은 기능 검증용이며 실제 출판물이나 검증된 추천 모델이 아니다.

Google Drive 진행 문서에 3단계 기록을 추가했다. 원격 push, PR, merge와 원격 CI 실행은 하지 않았다. 다음은 4단계 진단 세션, 문제 스냅샷, 답변 저장과 재조회다.
