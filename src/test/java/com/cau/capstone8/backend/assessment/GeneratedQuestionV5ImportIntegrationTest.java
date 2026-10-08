package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class GeneratedQuestionV5ImportIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired AssessmentService assessments;
    String generated;
    String id;

    @BeforeEach void setup() throws Exception {
        generated = Files.readString(Path.of("src/test/resources/fixtures/question-handoff/generated-concept-v5.json"));
        var artifact = importer.parseGeneratedQuestion(generated);
        id = artifact.generatedQuestionId();
        jdbc.update("delete from backend.question where generated_question_id=?", id);
        jdbc.update("insert into backend.topic(code,name,ml_topic_id) values ('LA-V5-TEST','Synthetic v5 contract','linear-algebra') on conflict (code) do nothing");
    }

    // This is a synthetic contract review, never a review of the live candidate bank.
    String review(String status) {
        return """
            {"generated_question_id":"%s","status":"%s","correct":true,
             "concept_alignment":5,"difficulty_appropriate":true,"distractor_quality":4,
             "explanation_quality":4,"notes":"synthetic contract fixture only"}
            """.formatted(id, status).replace("\n", "");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"concept-question-generation-config-v2","concept-question-generation-config-v3"})
    void latestWorkerContractImportsWithoutRelabelingLegacyArtifacts(String configVersion) {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var values=new java.util.TreeMap<String,Object>(json.readValue(generated,java.util.Map.class));
        values.put("prompt_version","concept-question-generation-prompt-v3");
        values.put("generation_config_version",configVersion);
        var identity=new java.util.TreeMap<>(values);identity.remove("generated_question_id");identity.remove("usage");
        String current="gq_"+ApprovedQuestionImportService.sha256(json.writeValueAsBytes(identity)).substring(7,39);
        values.put("generated_question_id",current);
        assertThat(importer.importApproved(json.writeValueAsString(values),review("approve").replace(id,current)).created()).isTrue();
        values.put("generation_config_version","concept-question-generation-config-v1");
        assertThatThrownBy(()->importer.importApproved(json.writeValueAsString(values),review("approve").replace(id,current))).isInstanceOf(QuestionImportException.class);
    }

    @Test void strictImportPreservesConceptAbilityAndContextWithoutPassage() {
        var first = importer.importApproved(generated, review("approve"));
        assertThat(first.created()).isTrue();
        assertThat(importer.importApproved(generated, review("approve")).created()).isFalse();
        var row = jdbc.queryForMap("select passage,answer_mode,upstream_provenance::text as provenance from backend.question where id=?", first.questionId());
        assertThat(row.get("passage")).isNull();
        assertThat(row.get("answer_mode")).isEqualTo("MULTIPLE_CHOICE");
        assertThat(row.get("provenance").toString()).contains("prior-knowledge", "application", "apply");
    }

    @Test void issuesV5SnapshotsWithoutAnswerKeysAndTracksAnsweredExposure() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        long user = jdbc.queryForObject("insert into backend.app_user(display_name) values ('synthetic v5 assessment') returning id", Long.class);
        long topic = jdbc.queryForObject("select id from backend.topic where code='LA-V5-TEST'", Long.class);
        for (int i=0; i<9; i++) {
            var values = new java.util.TreeMap<String, Object>(json.readValue(generated, java.util.Map.class));
            String ability = java.util.List.of("meaning", "application", "reasoning").get(i%3);
            values.put("ability", ability);
            values.put("cognitive_operation", java.util.List.of("recognize", "apply", "infer").get(i%3));
            values.put("stem", "Synthetic contract variant " + i + ": " + values.get("stem"));
            var identity = new java.util.TreeMap<>(values);
            identity.remove("generated_question_id"); identity.remove("usage");
            String variantId = "gq_" + ApprovedQuestionImportService.sha256(json.writeValueAsBytes(identity)).substring(7,39);
            values.put("generated_question_id", variantId);
            importer.importApproved(json.writeValueAsString(values), review("approve").replace(id, variantId));
        }
        var session = assessments.createConceptAssessment(user, topic);
        assertThat(session.questions()).hasSize(9).allMatch(q -> "prior-knowledge".equals(q.measurementContext()));
        assertThat(session.questions().stream().map(AssessmentResponse.IssuedQuestion::cognitiveOperation).distinct()).hasSize(3);
        assertThat(json.writeValueAsString(session)).doesNotContain("correctChoiceIndex", "explanation", "correct_choice_index");
        var q = session.questions().getFirst();
        var answer = new AssessmentAnswerRequest();
        answer.setSelectedChoiceIndex(0);
        assessments.answer(session.id(), q.id(), answer);
        long exposed = jdbc.queryForObject("select count(*) from backend.assessment_answer a join backend.assessment_question aq on aq.id=a.assessment_question_id where aq.session_id=?", Long.class, session.id());
        assertThat(exposed).isEqualTo(1);
        jdbc.update("update backend.question set version='generated-question-v4',passage='synthetic supplied passage' where id=(select question_id from backend.assessment_question where id=?)", q.id());
        assertThat(assessments.get(session.id()).questions().getFirst().measurementContext()).isEqualTo("prior-knowledge");
    }

    @Test void unapprovedCandidatesNeverEnterBank() {
        assertThatThrownBy(() -> importer.importApproved(generated, review("needs_revision")))
                .isInstanceOf(QuestionImportException.class);
        assertThat(jdbc.queryForObject("select count(*) from backend.question where generated_question_id=?", Long.class, id)).isZero();
    }

    @Test void acceptsVersionedMathContentAndPreservesItsBackslashes() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var values = new java.util.TreeMap<String, Object>(json.readValue(generated, java.util.Map.class));
        values.put("prompt_version", "concept-question-generation-prompt-v2");
        values.put("stem", "Find entry \\((1,1)\\) of \\(\\begin{bmatrix}1&2\\\\0&1\\end{bmatrix}\\).");
        var identity = new java.util.TreeMap<>(values);
        identity.remove("generated_question_id"); identity.remove("usage");
        values.put("generated_question_id", "gq_" + ApprovedQuestionImportService.sha256(json.writeValueAsBytes(identity)).substring(7,39));
        var parsed = (GeneratedQuestionV5Handoff) importer.parseGeneratedQuestion(json.writeValueAsString(values));
        assertThat(parsed.promptVersion()).isEqualTo("concept-question-generation-prompt-v2");
        assertThat(parsed.stem()).isEqualTo(values.get("stem"));
        assertThat(parsed.stem()).contains("\\begin{bmatrix}", "\\\\0&1");
    }

    @Test void editedOutputUnknownFieldsAndUnexpectedPassageAreRejected() {
        assertThatThrownBy(() -> importer.importApproved(generated.replace("1*1+2*0=1", "1*1+2*0=9"), review("approve")))
                .isInstanceOf(QuestionImportException.class);
        assertThatThrownBy(() -> importer.importApproved(generated.replace("\"stem\":", "\"passage\":\"teaching rule\",\"stem\":"), review("approve")))
                .isInstanceOf(QuestionImportException.class);
        assertThatThrownBy(() -> importer.importApproved(generated, review("approve"), "{}"))
                .isInstanceOf(QuestionImportException.class);
    }
}
