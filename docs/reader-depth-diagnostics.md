# 완료한 진단의 난이도별 응답 근거 조회

`GET /api/assessments/{sessionId}/diagnostics`는 완료된 진단의 문항 스냅샷과 저장된
채점 결과를 Python ML의 `POST /ml/reader-diagnostics`에 전달한다.
ML PR #31의 `reader-depth-evidence-v1` 구현이 배포되어 있어야 한다.

## 실행 조건

- `ml.mode=http`, `ml.base-url`을 PR #31을 포함한 ML 서버 주소로 설정한다.
- 기존 진단을 생성하고 답변한 뒤 `/complete`로 완료한다.
- 해당 세션의 `/diagnostics`를 GET으로 조회한다.
- 기본 stub 모드에서는 가짜 진단 결과를 만들지 않고 503 `ML_UNAVAILABLE`을 반환한다.
- ML #31 배포 전 404 등 upstream 오류는 Backend에서 정제된 502로 반환한다.

## 응답과 계산 시점

최상위 `sessionId`, `diagnostics`, `responseSources`를 반환한다.
`diagnostics`는 개념별 문항 유형×난이도 9개 셀의 점수·응답 수·questionIds와
다음 확인 후보를 제공한다. `responseSources`는 ML questionId를 실제 발급 문항의
`assessmentQuestionId` 및 `SELF_REPORT`/`MULTIPLE_CHOICE`에 연결한다.
출처를 표시하는 정보이며 자기평가와 객관식의 점수를 재조정하지 않는다.

Backend 난이도는 기존 프로필과 동일하게 1~2→easy, 3→medium, 4~5→hard로 변환한다.
객관식은 저장된 서버 채점 결과, 자기평가는 저장된 안다/모른다 응답을 사용한다.
원본 문항이 변경되어도 발급 당시의 난이도·개념·영역 스냅샷을 사용한다.
정답 선택지, 해설, 지문, 원본 provenance는 이 응답에 포함하지 않는다.

조회 시점 ML 설정으로 다시 계산한 진단 근거다. 완료 당시 저장된 프로필을 갱신하거나
그때의 계산 결과를 재현한다고 보장하지 않는다. ML 응답의 profileVersion, configVersion,
configHash를 함께 확인한다. Frontend 연결은 이 PR에 포함하지 않는다.

## 오류와 상태 보존

| 조건 | HTTP |
|---|---:|
| 존재하지 않는 세션 | 404 |
| 미완료 세션 또는 저장 응답 누락 | 409 |
| stub 또는 ML 연결 불가 | 503 |
| ML 응답 시간 초과 | 504 |
| upstream 오류 또는 진단 계약 불일치 | 502 |

조회용 DB 트랜잭션 종료 후 ML을 호출한다. 성공·실패 모두 세션 상태와 reader_profile을
수정하지 않는다. 사용자·세션·분야·응답 수·버전·설정 해시와 개념 목록을 검증하고,
셀의 문항 ID·난이도·유형·점수·만점 수를 요청과 대조한다. 다음 확인 후보도 v1 규칙과
일치하는지 검증한다. 임의 JSON을 전달하지 않고 명시한 DTO 필드만 반환한다.

## 검증

- WireMock: Python에서 생성한 합성 fixture와 송신 DTO 대조, 잘못된 사용자/문항/점수/
  버전/누락 값 거부, 정제된 오류 응답.
- 서비스: 완료 세션 제한, 발급 스냅샷 재사용, 장애 시 상태 보존.
- PostgreSQL: 실제 진단 완료 후 stub 조회 실패에도 저장 프로필 유지.
- `ML_CONTRACT_BASE_URL`을 설정한 `*LiveContract*` 테스트: 실제 Python #31 서버와
  profile/rank/diagnostics HTTP 계약 확인.

문항 난이도별 관측 근거이며 교정된 능력 등급이나 책 자체 난이도가 아니다.
차기 문항을 실제 출제하려면 승인된 문항의 존재와 적합성을 추가 확인해야 한다.
