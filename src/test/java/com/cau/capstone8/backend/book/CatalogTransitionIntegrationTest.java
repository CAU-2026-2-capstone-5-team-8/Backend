package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class CatalogTransitionIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalCatalogImportService importer;
    @Autowired CatalogTransitionService transitions;
    @Autowired CatalogReadinessService readiness;
    @TempDir Path dir;
    LocalCatalogImportIntegrationTest fixture;
    LocalCatalogImportService.Result original;

    @BeforeEach void setup() throws Exception {
        fixture = new LocalCatalogImportIntegrationTest();
        fixture.jdbc = jdbc; fixture.dir = dir; fixture.prepare();
        original = importer.importManifest(fixture.writeManifest());
        Files.writeString(dir.resolve("candidates.jsonl"), fixture.candidate(fixture.idA)
                .replace("book-v1", "book-v2").replace("\"matrix\"", "\"vector\"") + "\n"
                + fixture.candidate(fixture.idB).replace("book-v1", "book-v2") + "\n");
        fixture.manifest.put("snapshot_id", "synthetic-transition-v2");
        fixture.manifest.put("candidates_sha256", fixture.hash("candidates.jsonl"));
    }

    @Test void previewDoesNotPersistApplyPreservesIdsAndRollbackRestoresOnlyAnalysis() throws Exception {
        long user = jdbc.queryForObject("insert into backend.app_user(display_name) values ('Synthetic reader') returning id", Long.class);
        long session = jdbc.queryForObject("insert into backend.assessment_session(user_id,topic_id,status,completed_at) values (?,?,'COMPLETED',now()) returning id", Long.class,user,original.topicId());
        long profile = jdbc.queryForObject("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,comprehension,calculation_version,evidence) values (?,1,1,1,'fixture','{}') returning id", Long.class,session);
        jdbc.update("insert into backend.learning_recommendation(user_id,topic_id,profile_id,request_key,request_hash,input_snapshot,result) values (?,?,?,'saved',?,'{\"version\":\"book-v1\"}','{\"fixture\":true}')",user,original.topicId(),profile,"a".repeat(64));
        jdbc.update("insert into backend.reading_shelf(user_id,book_id,status,note) values (?,?,'READING','keep this note')",user,original.bookIds().get(fixture.idA));
        var saved = jdbc.queryForMap("select to_jsonb(r)::text content from backend.learning_recommendation r");
        Path path = fixture.writeManifest();
        var preview = transitions.preview(path);
        assertThat(preview.before()).extracting(CatalogTransitionService.Entry::version).containsOnly("book-v1");
        assertThat(preview.after()).extracting(CatalogTransitionService.Entry::version).containsOnly("book-v2");
        assertThat(activeVersions()).containsOnly("book-v1");
        assertThat(count("book_ranking_v2_projection")).isEqualTo(2);
        assertThat(count("catalog_transition")).isZero();
        var applied = transitions.apply(path, preview.token(), "new verified concept matches");
        assertThat(activeVersions()).containsOnly("book-v2");
        assertThat(count("book")).isEqualTo(2);
        assertThat(count("book_ranking_v2_projection")).isEqualTo(4);
        assertThat(transitions.apply(path, preview.token(), "retry").transitionId()).isEqualTo(applied.transitionId());
        transitions.rollback(applied.transitionId(), "restore previous analysis");
        assertThat(activeVersions()).containsOnly("book-v1");
        assertThat(jdbc.queryForList("select id from backend.book order by id", Long.class))
                .containsExactlyElementsOf(original.bookIds().values());
        assertThat(count("book_ranking_v2_projection")).isEqualTo(4);
        assertThat(count("local_catalog_import")).isEqualTo(2);
        assertThat(jdbc.queryForMap("select to_jsonb(r)::text content from backend.learning_recommendation r")).isEqualTo(saved);
        assertThat(jdbc.queryForObject("select note from backend.reading_shelf",String.class)).isEqualTo("keep this note");
        assertThatThrownBy(() -> transitions.apply(path, preview.token(), "retry after rollback"))
                .hasMessageContaining("rolled back");
    }

    @Test void stalePreviewAndChangedArtifactCannotActivate() throws Exception {
        Path path = fixture.writeManifest();
        var preview = transitions.preview(path);
        jdbc.update("update backend.book_ranking_v2_projection set covered_concepts='[]' where id=?", original.projectionIds().get(fixture.idA));
        assertThatThrownBy(() -> transitions.apply(path, preview.token(), "stale preview"))
                .hasMessageContaining("preview is stale");
        Files.writeString(dir.resolve("candidates.jsonl"), "{}");
        assertThatThrownBy(() -> transitions.preview(path)).hasMessageContaining("SHA-256 mismatch");
        assertThat(activeVersions()).containsOnly("book-v1");
        assertThat(count("catalog_transition")).isZero();
    }

    @Test void rollbackCannotOverwriteNewerTransitionOrTamperedOldVersion() throws Exception {
        Path path = fixture.writeManifest();
        var applied = transitions.apply(path, transitions.preview(path).token(), "second version");
        jdbc.update("update backend.book_ranking_v2_projection set covered_concepts='[]' where id=?", original.projectionIds().get(fixture.idA));
        assertThatThrownBy(() -> transitions.rollback(applied.transitionId(), "unsafe rollback"))
                .hasMessageContaining("previous projection changed");
        assertThat(activeVersions()).containsOnly("book-v2");
        jdbc.update("update backend.book_ranking_v2_projection set active=false where active");
        assertThatThrownBy(() -> transitions.rollback(applied.transitionId(), "stale rollback"))
                .hasMessageContaining("active catalog changed");
    }

    @Test void invalidSecondBookRollsBackAllDeactivationAndNewRows() throws Exception {
        Files.writeString(dir.resolve("candidates.jsonl"), fixture.candidate(fixture.idA).replace("book-v1", "book-v2")
                + "\n" + fixture.candidate(fixture.idB).replace("book-v1", "book-v2").replace("1.0", "2.0"));
        fixture.manifest.put("candidates_sha256", fixture.hash("candidates.jsonl"));
        assertThatThrownBy(() -> transitions.preview(fixture.writeManifest())).hasMessageContaining("invalid score");
        assertThat(activeVersions()).containsOnly("book-v1");
        assertThat(count("book_ranking_v2_projection")).isEqualTo(2);
    }

    @Test void selectedSubsetDoesNotDeactivateOtherBooks() throws Exception {
        fixture.manifest.put("selected_book_ids", List.of(fixture.idA));
        Path path = fixture.writeManifest();
        transitions.apply(path, transitions.preview(path).token(), "one book only");
        assertThat(activeVersions()).containsExactly("book-v2", "book-v1");
    }

    @Test void concurrentApplyUsesOneTransitionAndDoesNotDuplicateVersions() throws Exception {
        Path path = fixture.writeManifest();
        String token = transitions.preview(path).token();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transitions.apply(path, token, "concurrent first"));
            var second = pool.submit(() -> transitions.apply(path, token, "concurrent second"));
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).transitionId())
                    .isEqualTo(second.get(15, java.util.concurrent.TimeUnit.SECONDS).transitionId());
        }
        assertThat(count("catalog_transition")).isEqualTo(1);
        assertThat(count("book_ranking_v2_projection")).isEqualTo(4);
    }

    @Test void transitionRejectsNewBookOrTopicInsteadOfLeavingCatalogAdditionsOnRollback() throws Exception {
        Path path = fixture.writeManifest();
        jdbc.update("delete from backend.book_ranking_v2_projection where book_id=?", original.bookIds().get(fixture.idA));
        jdbc.update("delete from backend.book_topic where book_id=?", original.bookIds().get(fixture.idA));
        jdbc.update("delete from backend.book where id=?", original.bookIds().get(fixture.idA));
        assertThatThrownBy(() -> transitions.preview(path)).hasMessageContaining("existing book-topic mapping required");
        assertThat(count("book")).isEqualTo(1);
        fixture.manifest.put("selected_book_ids",List.of(fixture.idB));
        fixture.manifest.put("topic",Map.of("code","NEW","name","New topic","parent_code","MAT", "parent_name","Synthetic Math","ml_topic_id","new-topic"));
        Files.writeString(dir.resolve("books.jsonl"),fixture.book(fixture.idB,"9788961055680").replace("linear-algebra","new-topic"));
        Files.writeString(dir.resolve("candidates.jsonl"),fixture.candidate(fixture.idB).replace("linear-algebra","new-topic").replace("book-v1","book-v2"));
        fixture.manifest.put("books_sha256",fixture.hash("books.jsonl"));
        fixture.manifest.put("candidates_sha256",fixture.hash("candidates.jsonl"));
        assertThatThrownBy(() -> transitions.preview(fixture.writeManifest())).hasMessageContaining("existing book-topic mapping required");
        assertThat(count("topic")).isEqualTo(2);
    }

    @Test void firstAnalysisForExistingBookCanRollBackToNoActiveAnalysis() throws Exception {
        jdbc.update("update backend.book_ranking_v2_projection set active=false");
        Path path = fixture.writeManifest();
        var applied = transitions.apply(path,transitions.preview(path).token(),"first active analysis");
        assertThat(applied.before()).allMatch(e -> e.projectionId()==null);
        transitions.rollback(applied.transitionId(),"remove first active analysis");
        assertThat(activeVersions()).isEmpty();
        assertThat(count("book")).isEqualTo(2);
    }

    @Test void laterTransitionMustBeRolledBackBeforeEarlierOne() throws Exception {
        Path path = fixture.writeManifest();
        var second = transitions.apply(path,transitions.preview(path).token(),"second version");
        Files.writeString(dir.resolve("candidates.jsonl"),Files.readString(dir.resolve("candidates.jsonl")).replace("book-v2","book-v3"));
        fixture.manifest.put("snapshot_id","synthetic-transition-v3");
        fixture.manifest.put("candidates_sha256",fixture.hash("candidates.jsonl"));
        path = fixture.writeManifest();
        var third = transitions.apply(path,transitions.preview(path).token(),"third version");
        assertThatThrownBy(() -> transitions.rollback(second.transitionId(),"out of order")).hasMessageContaining("active catalog changed");
        transitions.rollback(third.transitionId(),"undo third");
        transitions.rollback(second.transitionId(),"undo second");
        assertThat(activeVersions()).containsOnly("book-v1");
    }

    @Test void readinessSeparatesReviewSourcesAndShowsMissingAbilities() {
        objective(1,"matrix","meaning","recognize","humanReview",true);
        objective(2,"matrix","application","apply","aiReview",true);
        objective(3,"matrix","reasoning","infer","humanReview",false);
        var topic = readiness.report().topics().getFirst();
        assertThat(topic.objectiveCount()).isEqualTo(2);
        assertThat(topic.humanApprovedObjectiveCount()).isEqualTo(1);
        assertThat(topic.aiApprovedObjectiveCount()).isEqualTo(1);
        assertThat(topic.priorKnowledgeQuestionCount()).isEqualTo(2);
        assertThat(topic.unassessedConcepts()).isEmpty();
        assertThat(topic.missingConceptAbilities()).containsExactly("matrix:reasoning");
    }

    void objective(int id, String concept, String ability, String operation, String reviewer, boolean active) {
        jdbc.update("""
            insert into backend.question(topic_id,measurement_area,difficulty,prompt,version,active,concept_id,
                answer_mode,generated_question_id,question_spec_id,choices,correct_choice_index,explanation,generated_content_hash,upstream_provenance)
            values (?,'BACKGROUND_KNOWLEDGE',1,'Synthetic objective','generated-question-v5',?,?,'MULTIPLE_CHOICE',?,?,'["a","b","c","d"]',0,'Synthetic explanation',?,cast(? as jsonb))
            """, original.topicId(),active,concept,"gq_"+String.format("%032d",id),"q_"+String.format("%020d",id),"sha256:"+"a".repeat(64),
                "{\"measurementContext\":\"prior-knowledge\",\"questionSpecVersion\":\"concept-question-spec-v2\",\"ability\":\""+ability+"\",\"cognitiveOperation\":\""+operation+"\",\""+reviewer+"\":{\"status\":\"approve\"}}");
    }

    @Test void readinessDoesNotMistakeSelfReportsForObjectiveCoverage() {
        for (String area : List.of("VOCABULARY", "BACKGROUND_KNOWLEDGE", "COMPREHENSION"))
            for (int i = 0; i < 3; i++) jdbc.update("""
                insert into backend.question(topic_id,measurement_area,difficulty,prompt,version,active,concept_id)
                values (?,?,1,'Synthetic self-report','fixture',true,'matrix')
                """, original.topicId(), area);
        var topic = readiness.report().topics().getFirst();
        assertThat(topic.conceptBookCount()).isEqualTo(2);
        assertThat(topic.sourceLinkedConceptBookCount()).isZero();
        assertThat(topic.tocCoverageKnownBookCount()).isZero();
        assertThat(topic.tocCoverageUnknownBookCount()).isEqualTo(2);
        assertThat(topic.selfReportCount()).isEqualTo(9);
        assertThat(topic.objectiveCount()).isZero();
        assertThat(topic.legacySessionAvailable()).isTrue();
        assertThat(topic.unassessedConcepts()).containsExactly("matrix");
        assertThat(topic.warnings()).contains("NO_OBJECTIVE_QUESTIONS", "NO_SOURCE_LINKED_CONCEPT_BOOKS");
    }

    List<String> activeVersions() { return jdbc.queryForList("select version from backend.book_ranking_v2_projection where active order by book_id", String.class); }
    int count(String table) { return jdbc.queryForObject("select count(*) from backend." + table, Integer.class); }
}
