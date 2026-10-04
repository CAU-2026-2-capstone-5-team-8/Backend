# 개념 문항의 AI 내용 검토 등록

사용자가 문항 판단을 AI에 위임한 경우 `generated-question-v5`에 별도의 AI 판정표를 사용할 수 있다.
기존 사람 판정 JSONL과 생성 문항 계약은 유지하며, v2/v4에는 기존 사람 검토 경로만 적용한다.
이 기능은 검토 주체를 보존하는 기능이다. 작성자 신원을 인증하거나 모델의 판단을 자동 검증하지 않는다.

## 입력 계약

각 JSONL 행은 아래 필수 필드를 가진다.

| 필드 | 값 |
| --- | --- |
| review_version | `ai-question-review-v1` |
| reviewer_type | `ai` |
| reviewer_name | 실제 검토 도구 이름. 빈 값·양끝 공백 금지 |
| validation_scope | `content-only` |
| review | 기존 판정 필드: generated_question_id, status, correct, concept_alignment, difficulty_appropriate, distractor_quality, explanation_quality, notes |

`review` 객체의 생성 ID가 현재 문항 ID와 일치하고 `status=approve`, `correct=true`여야 한다.
점수 범위·자료형·필수 필드·알 수 없는 필드를 엄격히 검사한다. 사람과 AI 행을 합쳐 동일 생성 ID가
두 번 등장해도 거부한다. 이전 생성 ID, 미완료 판정, 잘못된 버전·주체·검증 범위는 등록하지 않는다.
기존의 문항 해시, 변경 불가 콘텐츠, 멱등성, manifest의 전체 트랜잭션 처리는 유지한다.

## 저장 및 실행

기존 사람 판정은 `upstream_provenance.humanReview`, AI 판정은 `upstream_provenance.aiReview`에 저장한다.
AI 메타데이터의 `reviewVersion`, `reviewerType`, `reviewerName`, `validationScope`와 내용 판정을 함께
보존한다. 재등록은 기존 행을 덮어쓰지 않는다. 검토자의 점수와 설계 난도 평가는 내용상 편집 판단이며
실제 학습자 응답으로 확인한 난도·변별력·신뢰도는 아니다.

기존 `question-import` 프로필의 `question-import.manifest-path` 또는 단일 파일 옵션을 그대로 쓴다.
AI 검토라는 이유로 자동 등록하지 않는다. 등록 대상으로 지정한 manifest의 승인 문항만 등록하며,
발급 화면에는 정답·해설·검토 기록을 보내지 않는다. DB 마이그레이션과 API 변경은 없다.

## 검증

`AiQuestionReviewImportIntegrationTest`는 synthetic 판정으로 AI/사람 저장 구분, 기존 계약,
중복·미완료·잘못된 주체·검증 범위·이전 ID 거부와 멱등성을 검사한다.
`ActualConceptAiReviewHandoffTest`는 `ACTUAL_CONCEPT_REVIEW_DIR`을 지정했을 때 실제 생성물과
실제 AI 판정표로 격리 DB에서 등록·9문항 발급을 검사한다. 이 검사는 실행 중인 서비스 DB를 변경하지 않는다.

```sh
ACTUAL_CONCEPT_REVIEW_DIR=/absolute/path/to/concept-assessment-ai-reviewed-v4 \
  ./gradlew test --tests '*ActualConceptAiReviewHandoffTest'
```

2026-10-04 로컬 검증: 전체 205개 통과, 기존 외부 ML 검사 6개 제외. 실제 18문항과 AI 판정표를
명시적 manifest로 격리 DB에 일괄 등록·재등록하고, 6개 개념·3개 수행 목표의 9문항 발급과
정답·해설 비노출을 확인했다. 실행 파일 빌드도 통과했다. 기존 실행 서비스 DB는 변경하지 않았다.
