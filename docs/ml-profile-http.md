# ML 프로필 HTTP 연결 (7단계 일부)

2026-09-16 회의 문서와 main의 진단 완료 구현을 기준으로 통신 부분을 구현했다. 문제 유형·난이도 3단계는 결정 대상이므로 기존 자기평가 계약을 변경하지 않는다.

## 실행

기본값은 기존 `ML_MODE=stub`이다. 실제 프로필 서버가 준비된 환경에서 실행 프로세스에 다음 환경변수를 지정한다.

```text
ML_MODE=http
ML_BASE_URL=http://127.0.0.1:8000
ML_CONNECT_TIMEOUT=PT2S
ML_READ_TIMEOUT=PT10S
```

`ML_BASE_URL`은 개발자가 관리하는 서버 주소다. 사용자가 입력한 URL을 전달하지 않는다. `.env`에 적는 것만으로 자동 로딩된다고 가정하지 않는다.

기존 `MlGateway.calculateProfile` 호출이 `POST /ml/reader-profile` JSON 요청으로 전송된다. 백엔드 내부 DTO(`MlProfileRequest`/`MlProfileResult`)는 그대로 두고, `MlProfileHttpContract`가 Python 서비스(`bookmatch_ml/integration/schemas.py`)의 실제 형식으로 양방향 변환한다. Python DTO는 모르는 필드를 거부(`extra=forbid`)하므로 백엔드 전용 필드는 보내지 않는다.

요청 변환:

| 백엔드 | Python 서비스 |
| --- | --- |
| `answers[]` | `responses[]` |
| `measurementArea` `VOCABULARY` | `questionType` `vocabulary` (소문자) |
| `difficulty` 1~5 | `difficulty` 1·2 → `easy`, 3 → `medium`, 4·5 → `hard` (ML `reader-config-v1` 가중치 1.0/1.25/1.5) |
| `knowsConcept` | `correct` |
| `topicId` | 요청과 각 응답의 `topicId` |
| `requestId`, `contractVersion`, `points` | 보내지 않음. Python은 문항 배점 대신 난이도 가중치를 쓰고, 현재 백엔드 배점은 항상 1이다 |

응답 변환: Python 응답에는 requestId·contractVersion·calculationVersion·dimensionCounts가 없다. userId·assessmentId·topicId·responseCount가 요청과 일치하는지, 세 점수가 null이 아닌지, profileVersion·configVersion이 비어 있지 않은지, configHash가 `sha256:` 형식인지 확인한다. 그다음 requestId·contractVersion은 요청 값으로, calculationVersion은 `profileVersion`(예: `reader-v1`)으로, dimensionCounts는 `dimensionDetails[].responseCount`로 채운다. 이렇게 만든 결과를 기존 공통 validator가 다시 검증한다(점수 범위, 영역별 문항 수). evidence에는 `method=ml-http-reader-profile`, profileVersion, configVersion, configHash, dimensionDetails, conceptReadiness를 저장한다. 추천 HTTP 연결 때 ML의 `readerProfile`(configHash·conceptReadiness 포함)을 다시 만들 때 쓴다.

## 실패 처리

- 연결 실패: ML_UNAVAILABLE / 503
- 연결·응답 시간 초과: ML_TIMEOUT / 504
- 비정상 HTTP 상태: ML_UPSTREAM_ERROR / 502
- 잘못된 JSON 또는 계약 불일치: ML_INVALID_RESPONSE / 502

애플리케이션 자동 재시도, redirect 추적, stub 자동 대체를 하지 않는다. 외부 응답 본문·URL은 공개 API 오류 메시지에 넣지 않는다. 기존 완료 서비스가 실패 시 세션을 IN_PROGRESS로 복구하며, 저장 시 attemptId 소유권을 확인한다.

## 검증과 한계

WireMock 테스트는 Python 서비스에서 실제로 받은 응답 형식을 정상 응답으로 사용한다. 여기에 요청 본문의 필드 이름과 값(소문자 questionType, 난이도 구간, 백엔드 전용 필드를 보내지 않는지), 누락/null/범위 밖 점수, assessmentId·topicId·userId 불일치, 영역·전체 문항 수 불일치, 알 수 없는 영역, 잘못된 configHash, 누락된 버전과 상세 정보, 오류 상태, 지연, 연결 실패를 검사한다.

`HttpMlGatewayLiveContractTest`는 `ML_CONTRACT_BASE_URL`을 설정했을 때만 실행되며, 실행 중인 Python 서비스로 실제 요청을 보낸다. 2026-09-23에 ML `main`(3b6027a)을 `uvicorn --factory bookmatch_ml.api:create_app`으로 띄워 통과를 확인했다(어휘 easy 정답 + hard 오답 → 0.4). 변경 전 형식은 같은 서버에서 422(`responses` 누락, `requestId`·`answers` 등 extra_forbidden)로 거부되는 것도 확인했다.

책/문제 난이도 모델·문제 생성은 구현하지 않았다. `/ml/rank`는 아직 HTTP로 연결되지 않았으므로 `ML_MODE=http`에서 추천 생성은 `ML_RANK_UNAVAILABLE`로 실패한다. 추천 연결에는 계약 차이가 더 크다. Python `candidateBooks`는 주제 분포·개념·난이도 지표·configHash를 요구하지만 `book_feature`에는 세 수치만 있고, `challengeLevel`에 해당하는 필드가 Python에 없으며, 백엔드 주제 코드(`OS`)와 ML·Data-Pipeline 주제 ID(`operating-systems`)가 다르다. 그래서 별도 PR로 진행한다.

## 다음 진행 순서

1. ~~프로필 HTTP 계약을 ML 팀의 실제 출력과 대조한다.~~ (이 문서의 변환으로 완료)
2. ML이 만든 책 후보 프로필(`candidateBooks` 항목)을 `book_feature`에 저장하고, 그 값으로 rank HTTP 어댑터를 구현한다.
3. 진단 → 프로필 → 추천 → 피드백 전체 E2E를 추가한다.
4. 문제 유형과 난이도 기준을 팀이 확정하면 계약 버전을 올려 확장한다.
