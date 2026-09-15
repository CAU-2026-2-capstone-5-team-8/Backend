-- ML(bookmatch-ml)이 개념 단위 추천 계산에 쓰는 concept_id를 문항에 추가한다.
-- 기존 문항과의 호환을 위해 nullable로 둔다 — 개념 태그가 없는 문항은 그 부분 점수만 빠질 뿐
-- 전체 파이프라인이 깨지지 않는다 (ML의 가중치 재정규화 방식과 일치).
ALTER TABLE question ADD COLUMN concept_id varchar(120);

-- 세션 재현성을 위해 발급 시점의 concept_id도 스냅샷으로 고정한다.
ALTER TABLE assessment_question ADD COLUMN concept_id_snapshot varchar(120);
