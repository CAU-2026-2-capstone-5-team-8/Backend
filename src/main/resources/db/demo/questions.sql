-- Opt-in synthetic question bank, mirrors catalog.sql's idempotent demo_key pattern.
-- Self-report format: each prompt asks whether the user knows the concept (no options/answer key).
INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'VOCABULARY', 2,
       '"교착 상태(deadlock)"라는 용어의 뜻을 알고 있습니까?',
       'demo-question-v2', true, 'os-vocab-1'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'VOCABULARY', 3,
       '"세마포어(semaphore)"라는 용어의 뜻을 알고 있습니까?',
       'demo-question-v2', true, 'os-vocab-2'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'VOCABULARY', 1,
       '"가상 메모리(virtual memory)"라는 용어의 뜻을 알고 있습니까?',
       'demo-question-v2', true, 'os-vocab-3'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'BACKGROUND_KNOWLEDGE', 3,
       '가상 메모리에서 페이지 폴트(page fault)가 발생하는 상황을 알고 있습니까?',
       'demo-question-v2', true, 'os-knowledge-1'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'BACKGROUND_KNOWLEDGE', 4,
       '라운드 로빈(Round Robin) 스케줄링 방식을 알고 있습니까?',
       'demo-question-v2', true, 'os-knowledge-2'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'BACKGROUND_KNOWLEDGE', 2,
       '뮤텍스(mutex)가 왜 필요한지 알고 있습니까?',
       'demo-question-v2', true, 'os-knowledge-3'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'COMPREHENSION', 3,
       '스케줄링 정책이 응답 시간과 처리량에 어떤 영향을 미치는지 이해하고 있습니까?',
       'demo-question-v2', true, 'os-comprehension-1'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'COMPREHENSION', 2,
       '캐시 적중률과 평균 메모리 접근 시간의 관계를 이해하고 있습니까?',
       'demo-question-v2', true, 'os-comprehension-2'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, version, active, demo_key)
SELECT t.id, 'COMPREHENSION', 4,
       '우선순위 기반 스케줄링에서 기아 상태(starvation)가 왜 발생하는지 이해하고 있습니까?',
       'demo-question-v2', true, 'os-comprehension-3'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;
