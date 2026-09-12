-- Opt-in synthetic question bank, mirrors catalog.sql's idempotent demo_key pattern.
INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'VOCABULARY', 2,
       '문맥상 밑줄 친 단어와 의미가 가장 가까운 것은?',
       '[{"id":"a","text":"완화하다"},{"id":"b","text":"악화시키다"},{"id":"c","text":"무시하다"},{"id":"d","text":"보류하다"}]'::jsonb,
       'a', 'demo-question-v1', true, 'os-vocab-1'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'VOCABULARY', 3,
       '다음 중 프로세스와 스레드의 차이를 가장 정확히 설명한 것은?',
       '[{"id":"a","text":"차이가 없다"},{"id":"b","text":"프로세스는 메모리를 공유하지 않지만 스레드는 공유한다"},{"id":"c","text":"스레드는 항상 독립된 주소 공간을 가진다"},{"id":"d","text":"프로세스는 커널 객체가 아니다"}]'::jsonb,
       'b', 'demo-question-v1', true, 'os-vocab-2'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'VOCABULARY', 1,
       '"교착 상태(deadlock)"의 의미로 가장 알맞은 것은?',
       '[{"id":"a","text":"프로세스가 자원을 무한정 기다리며 멈춘 상태"},{"id":"b","text":"프로세스가 정상 종료된 상태"},{"id":"c","text":"CPU가 유휴 상태인 것"},{"id":"d","text":"메모리가 해제된 상태"}]'::jsonb,
       'a', 'demo-question-v1', true, 'os-vocab-3'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'BACKGROUND_KNOWLEDGE', 3,
       '가상 메모리에서 페이지 폴트가 발생하는 상황으로 가장 알맞은 것은?',
       '[{"id":"a","text":"CPU 캐시가 가득 찬 경우"},{"id":"b","text":"참조한 페이지가 물리 메모리에 적재되어 있지 않은 경우"},{"id":"c","text":"디스크 용량이 부족한 경우"},{"id":"d","text":"네트워크 지연이 발생한 경우"}]'::jsonb,
       'b', 'demo-question-v1', true, 'os-knowledge-1'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'BACKGROUND_KNOWLEDGE', 4,
       '라운드 로빈(Round Robin) 스케줄링의 특징으로 가장 알맞은 것은?',
       '[{"id":"a","text":"우선순위가 가장 높은 프로세스만 실행"},{"id":"b","text":"각 프로세스에 동일한 타임 슬라이스를 순환 할당"},{"id":"c","text":"짧은 작업을 먼저 실행"},{"id":"d","text":"선점이 불가능하다"}]'::jsonb,
       'b', 'demo-question-v1', true, 'os-knowledge-2'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'BACKGROUND_KNOWLEDGE', 2,
       '뮤텍스(mutex)를 사용하는 주된 목적은?',
       '[{"id":"a","text":"임계 구역에 대한 상호 배제 보장"},{"id":"b","text":"프로세스 생성 속도 향상"},{"id":"c","text":"디스크 입출력 최적화"},{"id":"d","text":"네트워크 패킷 순서 보장"}]'::jsonb,
       'a', 'demo-question-v1', true, 'os-knowledge-3'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'COMPREHENSION', 3,
       '다음 글의 중심 내용으로 가장 알맞은 것은: "운영체제는 한정된 자원을 여러 프로세스에 배분하기 위해 스케줄링 정책을 사용하며, 정책에 따라 응답 시간과 처리량이 달라진다."',
       '[{"id":"a","text":"운영체제의 정의"},{"id":"b","text":"스케줄링 정책이 성능 지표에 미치는 영향"},{"id":"c","text":"프로세스의 생성 절차"},{"id":"d","text":"메모리 계층 구조"}]'::jsonb,
       'b', 'demo-question-v1', true, 'os-comprehension-1'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'COMPREHENSION', 2,
       '다음 글에서 추론할 수 있는 내용은: "캐시 적중률이 높을수록 평균 메모리 접근 시간은 짧아진다."',
       '[{"id":"a","text":"캐시 적중률과 접근 시간은 무관하다"},{"id":"b","text":"캐시 적중률이 낮아지면 평균 접근 시간이 길어질 수 있다"},{"id":"c","text":"캐시는 항상 100% 적중한다"},{"id":"d","text":"메모리 접근 시간은 항상 일정하다"}]'::jsonb,
       'b', 'demo-question-v1', true, 'os-comprehension-2'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;

INSERT INTO backend.question(topic_id, measurement_area, difficulty, prompt, options, correct_option_id, version, active, demo_key)
SELECT t.id, 'COMPREHENSION', 4,
       '다음 글의 필자가 우려하는 문제는: "우선순위 기반 스케줄링에서 낮은 우선순위 프로세스는 계속 뒤로 밀려 실행 기회를 얻지 못할 수 있다."',
       '[{"id":"a","text":"기아 상태(starvation)"},{"id":"b","text":"메모리 누수"},{"id":"c","text":"교착 상태"},{"id":"d","text":"캐시 미스"}]'::jsonb,
       'a', 'demo-question-v1', true, 'os-comprehension-3'
FROM backend.topic t WHERE t.code='OS' ON CONFLICT(demo_key) DO NOTHING;
