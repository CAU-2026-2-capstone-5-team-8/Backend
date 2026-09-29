# Approved Generated Question handoff v1

> 이 역사적 v2-only 계약은 [generated question handoff v2](generated-question-handoff-v2.md)에서 additive하게 확장되었다.

이 문서는 ML의 `QuestionSpec`, Question-Generation의 `GeneratedQuestion`·`HumanQuestionReview`, Backend의 진단 lifecycle 사이 경계를 정의한다. Backend는 승인된 생성 문항을 제공하기 위한 저장·발급·채점 시스템이며 question generator가 아니다.

## 1. Repository별 책임

| 구간 | 입력/출력 | 책임 |
| --- | --- | --- |
| ML | `QuestionSpec` | topic, question type, cognitive operation, concept, difficulty와 근거를 정의한다. |
| Question-Generation | `GeneratedQuestion` | QuestionSpec을 4지선다 문항으로 생성하고 generation·source provenance를 남긴다. |
| Human QA | `HumanQuestionReview` | 생성 문항을 `approve`, `reject`, `needs_revision` 중 하나로 판정한다. |
| Backend | approved handoff | 승인 확인, 영속화, 세션 스냅샷, 답변 저장, 서버 채점, ML profile handoff를 담당한다. |

Backend는 Gemini를 호출하지 않고 QuestionSpec을 생성하지 않는다. Human QA가 승인한 품질을 Backend가 별도 점수 threshold로 재판정하지도 않는다. Question generation과 serving은 runtime에서 분리한다.

## 2. Handoff 입력과 승인 조건

한 번의 import 입력은 다음 두 파일이다.

1. 완전한 `GeneratedQuestion` JSON 한 건
2. 그 `generated_question_id`와 정확히 일치하는 `HumanQuestionReview`가 한 건 포함된 JSONL

review만으로는 stem, choices, answer key를 복구할 수 없으므로 두 입력 모두 필수다. Backend는 sibling repository를 scan하거나 Python module을 import하지 않으며, GitHub API·Gemini·로컬 절대 경로에 의존하지 않는다.

현재 boundary가 수용하는 생성 계약은 다음과 같다.

- `generated_question_version == generated-question-v2`
- `question_spec_version == question-spec-v1`
- `target_difficulty == 1`
- `vocabulary / recognize` 또는 `background_knowledge / recall`
- 공백이 아닌 ID, concept, stem, explanation, version·config·hash provenance
- 중복 없는 4개 choices와 `0..3` 범위의 `correct_choice_index`
- 현재 v2의 범위에 맞는 빈 `related_concepts`와 `source_document_ids`
- 존재하는 usage object와, 값이 있을 때 non-negative인 token counts
- Backend `topic.ml_topic_id`와 일치하는 `topic_id`

unknown field, unknown version/type, malformed array/index/hash는 fail closed 한다. matching review는 반드시 정확히 한 건이어야 하며 `status == approve`와 `correct == true`를 모두 만족해야 한다. `reject`, `needs_revision`, missing/mismatched/duplicate review는 import하지 않는다. review 점수는 schema 범위 `1..5`만 확인하고 `concept_alignment == 5` 같은 추가 품질 기준을 만들지 않는다.

## 3. Identity, idempotency, provenance

`generated_question_id`는 upstream immutable identity이고 Backend numeric `question.id`와 구분한다.

- 같은 ID + semantic-equivalent immutable content: 기존 row를 반환하는 no-op
- 같은 ID + 다른 immutable content: conflict로 fail closed

Semantic hash는 strict schema로 decode한 GeneratedQuestion을 canonical JSON으로 다시 직렬화해 SHA-256으로 계산한다. 사용량은 생성 문항의 의미 내용이 아니므로 hash에서 제외하지만 audit provenance에는 필요한 생성·입력·config 정보가 보존된다. DB unique partial index가 같은 upstream ID의 중복 row도 차단한다.

자주 조회하거나 제약에 쓰는 generated ID, QuestionSpec ID, choices, correct index, explanation, content hash는 column으로 저장한다. generation model, prompt/config versions, source IDs와 matching human review는 `jsonb` provenance로 보존한다. delimiter 기반 문자열 저장은 사용하지 않는다.

## 4. Controlled import path

Import는 `question-import` profile에서만 실행되는 명시적 `ApplicationRunner`를 사용한다. 한 실행은 GeneratedQuestion 한 건을 import하며, reviews 파일은 JSONL 전체를 받을 수 있다. 일반 서버 시작에서는 자동 scan하거나 import하지 않는다.

```bash
./gradlew bootRun --args='--spring.main.web-application-type=none --spring.profiles.active=local,question-import --question-import.generated-path=/path/to/generated-question.json --question-import.reviews-path=/path/to/human-reviews.jsonl'
```

대상 `topic.ml_topic_id`는 먼저 존재해야 한다. 개발용 `demo,question-import` 조합에서는 demo catalog가 먼저 적재되도록 runner 순서를 고정했다. 성공 log의 `created=false`는 같은 내용이 이미 import되어 no-op 되었음을 뜻한다.

## 5. Persistence와 assessment snapshot

`question.answer_mode`는 기존 `SELF_REPORT`와 생성형 `MULTIPLE_CHOICE`를 구분한다. 생성 문항은 승인 import 직후 active question bank 후보가 된다. DB constraint는 각 mode에서 허용되는 필드 조합, generated/spec ID 형식, 4개 choices, correct index, hash와 object provenance를 검증한다.

세션을 만들 때 다음 값을 `assessment_question`에 함께 snapshot한다.

- Backend question ID, generated question ID, QuestionSpec ID
- measurement area, concept, difficulty
- stem, choices, correct choice index, explanation
- question version, semantic hash, provenance

원본 bank row가 수정되거나 inactive가 되어도 이미 발급한 질문과 채점 기준은 바뀌지 않는다.

## 6. Client와 answer contract

생성·조회 응답에는 `answerMode`, stem과 choices를 포함한다. `correctChoiceIndex`, correctness, explanation은 포함하지 않는다. Entity를 직접 API에 serialize하지 않는다.

기존 self-report request는 그대로 유지한다.

```json
{"knowsConcept": true}
```

Multiple-choice request는 선택 index만 받는다.

```json
{"selectedChoiceIndex": 2}
```

두 필드를 동시에 보내거나 mode와 다른 필드를 보내거나, `correct`처럼 정의되지 않은 필드를 보내거나, index가 `0..3` 밖이면 400으로 거부한다. Backend가 issued snapshot의 answer key와 선택을 비교해 `assessment_answer.correct`를 계산한다. 클라이언트는 correctness를 결정할 수 없다.

DB도 self-report는 `knows_concept`만, multiple-choice는 `selected_choice_index`와 server-calculated `correct`만 존재하도록 mode shape를 제한한다.

## 7. ML ReaderProfile handoff

완료 시 Backend는 mode별 결과를 하나의 ML 의미로 변환한다.

- legacy `SELF_REPORT`: 기존 `knowsConcept` 값을 보존해 ML `correct`에 전달
- generated `MULTIPLE_CHOICE`: Backend가 계산한 `correct`를 ML `correct`에 전달

Multiple-choice의 raw selected index와 answer key는 ML에 보내지 않는다. ML wire의 `questionId`는 생성 문항이면 `generated_question_id`, legacy 문항이면 issued assessment question ID를 사용한다. question type은 `MeasurementArea`의 기존 mapping을, Level 1은 기존 difficulty `1`과 HTTP adapter의 `easy` mapping을 그대로 사용한다.

## 8. 현재 coverage limitation

Backend의 기존 정책은 `vocabulary`, `background_knowledge`, `comprehension` 각 3문항, 총 9문항과 9개 답변을 요구한다. ML ReaderProfile도 세 question type을 모두 요구한다. 이 정책과 ML config는 이번 handoff에서 변경하지 않는다.

Question-Generation v1/v2 production 범위는 현재 Level 1 `vocabulary / recognize`와 `background_knowledge / recall`이며 comprehension generation은 fail closed다. 현재 human-approved Linear Algebra 문항도 vocabulary 4개, background knowledge 1개, comprehension 0개다.

따라서 현재 상태는 **approved generated questions available, but full assessment bank coverage incomplete**이다. Linear Algebra generated-only full assessment 또는 E2E가 완료된 상태가 아니다. Backend는 comprehension을 임의 생성하지 않고, LA 5문항을 복제하지 않으며, 누락 점수를 0이나 평균으로 합성하지 않고, 9문항/3영역 정책이나 ML minimum을 낮추지 않는다.

## 9. Contract fixture provenance

Backend fixture `src/test/resources/fixtures/question-handoff/`는 Question-Generation commit `d38e170532086a6fac5b58d8450cf8b3657cffd4`의 다음 실제 승인 artifact를 복제한 contract-test 전용 자료다.

- `data/generated/reviewed-os-human-qa/process.json`
- `reviews/os-reviewed-question-generation-v1.jsonl`의 matching review

fixture에는 secret이나 raw book content가 없으며 production question bank라고 주장하지 않는다. 테스트는 sibling repository 경로에 의존하지 않는다. 이 representative approved OS 문항을 기존 legacy bank와 섞어 lifecycle을 검증하는 것은 generated-only LA coverage를 가장하지 않는다.
