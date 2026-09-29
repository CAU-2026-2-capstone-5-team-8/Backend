# Approved Generated Question handoff v2

이 문서는 v1의 `generated-question-v2` import를 보존하면서, human-approved `generated-question-v4` display-grounded comprehension을 Backend 진단 lifecycle로 전달하는 additive 계약이다. Backend는 생성기나 grounding 생산자가 아니며 Gemini나 sibling repository를 runtime에 호출하지 않는다.

## 1. 지원 버전과 입력

Backend는 `generated_question_version` discriminator를 먼저 읽은 뒤 각 버전을 서로 다른 strict schema로 decode한다.

- `generated-question-v2`: 기존 GeneratedQuestion JSON + HumanQuestionReview JSONL. grounding 입력은 없고, 제공하면 거부한다.
- `generated-question-v4`: GeneratedQuestion JSON + HumanQuestionReview JSONL + exact `generation-grounding-v2` JSON bytes.
- historical `generated-question-v3`와 future version은 거부한다.

두 schema 모두 unknown/missing field와 scalar coercion을 허용하지 않는다. v2에 v4 field를 섞거나 v2 shape의 version만 v4로 바꾸는 것도 거부한다. matching review는 정확히 한 건이어야 하고 `status=approve`, `correct=true`여야 한다.

v4 runner는 기존 `question-import` profile에 선택적인 세 번째 path를 추가한다.

```bash
./gradlew bootRun --args='--spring.main.web-application-type=none --spring.profiles.active=local,question-import --question-import.generated-path=/path/to/generated-v4.json --question-import.reviews-path=/path/to/reviews.jsonl --question-import.grounding-path=/path/to/generation-grounding-v2.json'
```

## 2. Grounding 검증

Backend-local `generation-grounding-v2` contract는 ML Python module을 import하지 않고 다음을 fail closed 검증한다.

- schema/version, comprehension/apply/Level 2 single-source target
- QuestionSpec, topic, concept, source document identity
- raw source passage와 display passage 각각의 UTF-8 SHA-256
- `first-concept-sentence-window-v1` extraction policy
- `pdf-display-normalization-v1` display policy
- canonical input file hashes와 source/license provenance
- GeneratedQuestion `passage`와 grounding `display_passage_text`의 exact equality
- GeneratedQuestion `input_artifact_hash`와 exact grounding artifact bytes의 SHA-256 equality

Backend는 passage를 trim, reflow, 번역하거나 다시 normalize하지 않는다. 안전한 source/license metadata는 audit용 JSONB provenance에 보존하지만 assessment API에는 노출하지 않는다.

## 3. Persistence, semantic identity, snapshot

`question.passage`는 다음 shape를 가진다.

- `SELF_REPORT`: null
- `generated-question-v2`: null
- `generated-question-v4`: nonblank deterministic display passage

v4 `stem`은 passage를 포함하지 않는 question-only prompt로 저장한다. semantic content hash는 strict decoded artifact의 canonical JSON에서 `usage`만 제외해 계산하므로 passage, stem, choices, answer, explanation과 grounding hashes/policies/input artifact hash가 포함된다. 동일 generated ID의 idempotent replay는 no-op이고 immutable content 충돌은 거부한다.

세션 발급 때 `passage_snapshot`도 prompt, choices, answer key, explanation, version, provenance와 함께 고정한다. 이후 question bank row가 바뀌어도 기존 세션의 passage, 질문, choices와 서버 채점 기준은 변하지 않는다.

## 4. API와 채점/ML handoff

issued question 응답은 nullable `passage`를 포함한다. v4에는 display passage, question-only `prompt`, 네 `choices`, `answerMode=MULTIPLE_CHOICE`가 전달된다. raw source passage, source/license provenance, `correctChoiceIndex`, `correct`, `explanation`은 노출하지 않는다.

클라이언트는 기존과 동일하게 `selectedChoiceIndex`만 제출한다. Backend가 issued snapshot answer key로 correctness를 계산한다. 완료 시 v4 응답은 generated question ID, `COMPREHENSION`, concept `matrix`, difficulty `2`, 서버 계산 correctness로 기존 ReaderProfile flow에 전달된다. 기존 difficulty band 정책 `1,2 -> easy`는 변경하지 않는다.

## 5. 실제 contract fixture provenance

fixture는 production auto-sync가 아니라 repository 경계를 고정하는 test-only copy다. 복사 과정에서 내용을 수정하지 않았다.

Question-Generation main `ccb16cc4b803d36496775573e35885ebd278fbd9`:

- revised approved ID `gq_66f3360464d1336ec1612715ebbdd3ed`
- revised artifact SHA-256 `ea384ab3f3098018003a4c12cd79397ece4f35b169bd7a269bde17ff33b291dd`
- first-pass ID `gq_c0e6f6c774e467bcb8ab2c10a4cd9379` (`needs_revision`)
- first-pass artifact SHA-256 `6be7844a38001452fd1137b68bc19872f9be6f90035e826ee6283cee3765b363`
- review JSONL SHA-256 `8688969e3d81d528b2cba65af3111922c63486329d9c6e47fa7ff43c1d601159`

Matching ML `generation-grounding-v2`:

- exact artifact SHA-256 `a9d7f5f1b1487040c6aff681fbfe8591800a274aa0b5e2231cf784ea8c1fe2a6`
- source passage hash `sha256:7220ee5501766f97edabc506e871470356fa2aeb40ec92acfd6f7e44d9ce4ce0`
- display passage hash `sha256:c5126e3650a7c2329f2a4281efc774465bb9954e19091808d7162938926850a4`
- display policy `pdf-display-normalization-v1`

## 6. 현재 coverage limitation

현재 upstream human-approved Linear Algebra generated coverage는 기존 Level 1 문항 5개(vocabulary 4, background knowledge 1)와 grounded comprehension v4 1개뿐이다. 영역별 3개, 총 9개를 요구하는 Backend 정책을 충족하는 generated-only LA bank가 아니며 문항 복제, 합성, policy 완화는 하지 않는다.

Frontend는 아직 연결되지 않았고 multi-source grounding, `integrate`, Level 3는 지원하지 않는다.
