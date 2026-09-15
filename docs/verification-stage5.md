# 5단계 검증 결과

검증일: 2026-09-16. 구현 범위: 진단 완료 상태 머신, 결정론적 프로필 stub, 독자 프로필 저장·최신 조회, ML 프로필 내부 계약 검증.

## 최종 테스트와 빌드

macOS에서 OpenJDK 21과 실제 PostgreSQL 17.11 Testcontainers를 사용했다.

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew clean build --no-daemon --console=plain
BUILD SUCCESSFUL in 30s
7 actionable tasks: 7 executed
```

JUnit XML 합계는 **52개, failures=0, errors=0, skipped=0**이다. H2와 외부 ML 서버는 사용하지 않았다. 실행 JAR `build/libs/backend-0.0.1-SNAPSHOT.jar`도 생성됐다.

## 확인한 동작

- 발급한 9문항에 모두 답변해야 완료할 수 있으며, 미충족은 409다.
- 완료 claim은 세션 행을 잠근 짧은 트랜잭션에서 UUID attempt와 30초 lease를 저장한다.
- 프로필 계산은 DB 트랜잭션 밖에서 실행하고, 두 번째 트랜잭션에서 attempt 소유권을 다시 확인한 뒤 프로필 저장과 `COMPLETED` 전이를 함께 처리한다.
- 완료 호출 재시도는 세션당 유일한 기존 프로필을 그대로 반환한다.
- 활성 lease의 중복 완료는 409이고, 만료 lease는 새 attempt가 교체할 수 있다.
- ML 호출 실패 시 세션을 `IN_PROGRESS`로 복구하고 attempt/lease를 비우며, 응답과 DB에는 정제된 실패 정보만 남긴다.
- 완료 이후 답변 변경을 거부한다.
- 최신 프로필은 완료 시각 내림차순, 동률이면 프로필 ID 내림차순으로 선택하며 결과 없음은 404다.
- ML 요청은 중복 문항 ID와 누락된 측정 영역을 거부하고, 응답의 상관 ID·계약 버전·영역별 응답 수·유한한 `[0,1]` 점수를 검증한다.
- DB는 처리 상태와 attempt/lease의 동시 존재, 완료 상태와 완료 시각의 동시 존재, 난이도 스냅샷 1~5, 프로필 세션 유일성, 점수 범위와 JSON 객체 evidence를 제약으로 검증한다.
- 이전 Flyway 버전까지만 적용한 DB에 기존 `COMPLETED` 세션과 발급 문항을 넣은 뒤 최신 migration으로 올려, 완료 시각과 난이도 스냅샷이 보존되는 업그레이드 경로를 검증한다.

## 마이그레이션

기존 migration은 수정하지 않았다. 새 `V20260915083500__create_reader_profile.sql`이 기존 발급 문항의 난이도를 원본 문항에서 backfill한 뒤 `difficulty_snapshot`을 필수화하고, 세션 완료 시각·상태 제약·최신 조회 인덱스와 `reader_profile` 테이블을 추가한다.

## 현재 제한과 다음 단계

현재 `ml.mode=stub`만 실행 가능하다. 이 stub은 설계에 따라 영역별 `안다 응답 수 / 발급 수`를 계산하며 과학적으로 검증된 진단 모델이 아니다. 추천·피드백은 6단계, 실제 Python 호출은 7단계 범위다.

ML 저장소 main에는 이미 `/ml/reader-profile`과 `/ml/rank`가 있다. 다만 현재 Backend 내부 계약과 실제 ML wire 계약은 상관 ID/계약 버전, 정수 난이도와 `easy|medium|hard`, 프로필·설정 버전 및 concept readiness 표현이 다르다. HTTP adapter를 구현하기 전에 어느 계약을 기준으로 할지 합의하고, 숫자 난이도 매핑을 설정으로 버전화해야 한다. 자동 HTTP 재시도나 HTTP 실패 시 stub fallback은 추가하지 않는다.

원격 push, PR, CI, merge는 이 검증 시점에는 실행하지 않았다.
