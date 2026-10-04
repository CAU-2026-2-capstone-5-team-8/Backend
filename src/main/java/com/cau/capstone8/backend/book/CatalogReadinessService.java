package com.cau.capstone8.backend.book;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Internal data inventory, not a claim about learner measurement or recommendation quality. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogReadinessService {
    private final JdbcTemplate jdbc;
    private final CatalogSummaryService catalog;
    public CatalogReadinessService(JdbcTemplate jdbc, CatalogSummaryService catalog) { this.jdbc = jdbc; this.catalog = catalog; }
    public record Topic(long topicId, String name, long bookCount, long tocBookCount,
                        long tocCoverageKnownBookCount, long tocCoverageUnknownBookCount, long conceptBookCount,
                        long sourceLinkedConceptBookCount, long selfReportCount, long objectiveCount,
                        long humanApprovedObjectiveCount, long aiApprovedObjectiveCount, long priorKnowledgeQuestionCount,
                        boolean legacySessionAvailable, List<String> unassessedConcepts, List<String> missingConceptAbilities, List<String> warnings) {}
    public record Report(String contractVersion, List<Topic> topics) {}

    public Report report() {
        List<Topic> result = new ArrayList<>();
        for (var t : catalog.summary().topics()) {
            long tocKnown = jdbc.queryForObject("select count(distinct book_id) from backend.discovery_catalog_member where topic_id=?", Long.class, t.id());
            Map<String,Object> q = jdbc.queryForMap("""
                select count(*) filter(where answer_mode='SELF_REPORT') self_reports,
                    count(*) filter(where answer_mode='MULTIPLE_CHOICE') objective,
                    count(*) filter(where answer_mode='MULTIPLE_CHOICE' and upstream_provenance->'humanReview'->>'status'='approve') human,
                    count(*) filter(where answer_mode='MULTIPLE_CHOICE' and upstream_provenance->'aiReview'->>'status'='approve') ai,
                    count(*) filter(where answer_mode='MULTIPLE_CHOICE' and version='generated-question-v5'
                        and concept_id is not null and btrim(concept_id)<>'') prior
                from backend.question where topic_id=? and active
                """, t.id());
            long linked = jdbc.queryForObject("""
                select count(*) from backend.book_ranking_v2_projection p where topic_id=? and active
                  and exists (select 1 from jsonb_array_elements(covered_concepts) c
                              where jsonb_typeof(c->'evidence')='array' and jsonb_array_length(c->'evidence')>0)
                """, Long.class, t.id());
            List<String> gaps = jdbc.queryForList("""
                select distinct c->>'concept' from backend.book_ranking_v2_projection p,
                    lateral jsonb_array_elements(p.covered_concepts || p.prerequisite_concepts) c
                where p.topic_id=? and p.active and not exists (
                    select 1 from backend.question q where q.topic_id=p.topic_id and q.active
                      and q.answer_mode='MULTIPLE_CHOICE' and q.version='generated-question-v5'
                      and q.concept_id=c->>'concept') order by 1
                """, String.class, t.id());
            List<String> missingAbilities = jdbc.queryForList("""
                with concepts as (
                    select distinct c->>'concept' concept from backend.book_ranking_v2_projection p,
                        lateral jsonb_array_elements(p.covered_concepts || p.prerequisite_concepts) c
                    where p.topic_id=? and p.active
                ) select c.concept || ':' || a.ability from concepts c
                cross join (values ('meaning'),('application'),('reasoning')) a(ability)
                where not exists (select 1 from backend.question q where q.topic_id=? and q.active
                    and q.answer_mode='MULTIPLE_CHOICE' and q.version='generated-question-v5'
                    and q.concept_id=c.concept and q.upstream_provenance->>'ability'=a.ability)
                order by 1
                """, String.class, t.id(), t.id());
            List<String> warnings = new ArrayList<>();
            if (t.bookCount() == 0) warnings.add("NO_BOOKS");
            if (tocKnown < t.bookCount()) warnings.add("TOC_COVERAGE_NOT_IMPORTED");
            if (t.conceptBookCount() == 0) warnings.add("NO_CONCEPT_BOOKS");
            if (linked == 0) warnings.add("NO_SOURCE_LINKED_CONCEPT_BOOKS");
            if (number(q,"objective") == 0) warnings.add("NO_OBJECTIVE_QUESTIONS");
            if (number(q,"prior") == 0) warnings.add("NO_PRIOR_KNOWLEDGE_QUESTIONS");
            if (!gaps.isEmpty()) warnings.add("CONCEPT_QUESTION_GAPS");
            if (!missingAbilities.isEmpty()) warnings.add("CONCEPT_ABILITY_GAPS");
            result.add(new Topic(t.id(),t.name(),t.bookCount(),t.tocBookCount(),tocKnown,t.bookCount()-tocKnown,t.conceptBookCount(),linked,
                    number(q,"self_reports"),number(q,"objective"),number(q,"human"),number(q,"ai"),number(q,"prior"),
                    t.assessmentReady(),gaps,missingAbilities,List.copyOf(warnings)));
        }
        return new Report("catalog-readiness-v1", List.copyOf(result));
    }
    private static long number(Map<String,Object> values, String key) { return ((Number) values.get(key)).longValue(); }
}
