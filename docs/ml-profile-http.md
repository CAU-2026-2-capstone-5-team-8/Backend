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

기존 `MlGateway.calculateProfile` 호출이 `POST /ml/reader-profile` JSON 요청으로 전송된다. requestId, contractVersion=v1, userId, assessmentId, topicId, answers를 사용한다. answers는 questionId, measurementArea, conceptId(선택), difficulty(기존 1~5), knowsConcept, points를 보존한다. 외부 ML 팀과 이 DTO 계약을 확인한 후 사용해야 한다.

응답은 requestId, contractVersion, calculationVersion, vocabulary, backgroundKnowledge, comprehension, dimensionCounts, evidence다. 점수는 유한한 0~1 숫자이며 누락/null을 0으로 취급하지 않는다. 요청 ID·버전·영역별 문항 수는 기존 공통 validator로 검증한다.

## 실패 처리

- 연결 실패: ML_UNAVAILABLE / 503
- 연결·응답 시간 초과: ML_TIMEOUT / 504
- 비정상 HTTP 상태: ML_UPSTREAM_ERROR / 502
- 잘못된 JSON 또는 계약 불일치: ML_INVALID_RESPONSE / 502

애플리케이션 자동 재시도, redirect 추적, stub 자동 대체를 하지 않는다. 외부 응답 본문·URL은 공개 API 오류 메시지에 넣지 않는다. 기존 완료 서비스가 실패 시 세션을 IN_PROGRESS로 복구하며, 저장 시 attemptId 소유권을 확인한다.

## 검증과 한계

WireMock으로 실제 로컬 HTTP 요청을 보내 정상 응답, 누락/null 점수, ID/버전/문항 수/점수 불일치, 오류 상태, 지연, 연결 실패를 검사한다. 기본 stub과 HTTP 모드의 빈 선택도 검증한다.

실제 Python ML 서버와 E2E 연결을 검증한 것은 아니다. 책/문제 난이도 모델·문제 생성은 구현하지 않았다. `/ml/rank`는 추천 PR과 계약을 맞춘 다음 별도 구현한다. 이 변경만으로 이슈 #12 전체가 완료되지는 않는다.

## 다음 진행 순서

1. 프로필 HTTP 계약을 ML 팀의 실제 출력과 대조한다.
2. 추천 PR #18/#19의 중복·계약을 정리한 후 rank 어댑터를 구현한다.
3. 진단 → 프로필 → 추천 → 피드백 전체 E2E를 추가한다.
4. 문제 유형과 난이도 기준을 팀이 확정하면 계약 버전을 올려 확장한다.
