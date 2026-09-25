-- Opt-in synthetic data only. Run transactionally after Flyway; never record in its history.
-- Stable keys permit replay without replacing edited data.
INSERT INTO backend.app_user(display_name,demo_key) VALUES ('데모 사용자','catalog-user-v1')
ON CONFLICT(demo_key) DO NOTHING;
INSERT INTO backend.topic(code,name) VALUES ('CS','컴퓨터공학') ON CONFLICT(code) DO NOTHING;
INSERT INTO backend.topic(code,name,parent_id,ml_topic_id)
SELECT 'OS','운영체제',id,'operating-systems' FROM backend.topic WHERE code='CS'
ON CONFLICT(code) DO NOTHING;
INSERT INTO backend.book(title,author,description,demo_key)
SELECT '운영체제 데모 도서 ' || n, '캡스톤 8조',
       '실제 출판물이 아닌 합성 도서입니다. 요구 능력 수치는 기능 검증용이며 추천 정확도를 검증하지 않았습니다.',
       'os-demo-' || n
FROM generate_series(1,5) n ON CONFLICT(demo_key) DO NOTHING;
INSERT INTO backend.book_topic(book_id,topic_id,is_primary,topic_weight)
SELECT b.id,t.id,true,1 FROM backend.book b CROSS JOIN backend.topic t
WHERE b.demo_key IN ('os-demo-1','os-demo-2','os-demo-3','os-demo-4','os-demo-5') AND t.code='OS'
ON CONFLICT(book_id,topic_id) DO NOTHING;
INSERT INTO backend.book_sample(book_id,location,checksum,provenance,synthetic)
SELECT id,'합성 샘플 v1',encode(sha256(convert_to(demo_key || ':sample-v1','UTF8')),'hex'),
       '팀이 기능 검증을 위해 만든 합성 메타데이터입니다. 원문 발췌 및 실제 출처 URL은 없습니다.',true
FROM backend.book WHERE demo_key IN ('os-demo-1','os-demo-2','os-demo-3','os-demo-4','os-demo-5')
ON CONFLICT(book_id,checksum) DO NOTHING;
INSERT INTO backend.book_feature(book_id,topic_id,version,active,vocabulary,knowledge,comprehension,topic_relevance)
SELECT b.id,t.id,'demo-feature-v1',true,n*0.15,n*0.15,n*0.15,1
FROM generate_series(1,5) n JOIN backend.book b ON b.demo_key='os-demo-' || n
CROSS JOIN backend.topic t WHERE t.code='OS'
ON CONFLICT DO NOTHING;
