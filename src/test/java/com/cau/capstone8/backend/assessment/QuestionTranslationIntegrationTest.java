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
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class QuestionTranslationIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @Autowired QuestionTranslationService translations;
    @Autowired ApprovedQuestionImportService importer;
    @Autowired QuestionRepository questions;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    Question question;
    @BeforeEach void setup() throws Exception {
        jdbc.update("insert into backend.topic(code,name,ml_topic_id) values ('LA-TRANSLATION-TEST','Synthetic translation topic','linear-algebra') on conflict (code) do nothing");
        String generated=Files.readString(Path.of("src/test/resources/fixtures/question-handoff/generated-concept-v5.json"));
        String id=importer.parseGeneratedQuestion(generated).generatedQuestionId();
        String review="""
            {"generated_question_id":"%s","status":"approve","correct":true,"concept_alignment":5,
            "difficulty_appropriate":true,"distractor_quality":4,"explanation_quality":4,"notes":"synthetic translation fixture only"}
            """.formatted(id).replace("\n", "");
        long questionId=importer.importApproved(generated,review).questionId();
        question=questions.findById(questionId).orElseThrow();
        jdbc.update("delete from backend.question_display_translation");
    }
    AssessmentQuestion snapshot(String hash) {
        return new AssessmentQuestion(1L,question.getId(),0,question.getMeasurementArea(),question.getPassage(),question.getPrompt(),
            question.getConceptId(),question.getVersion(),question.getDifficulty(),question.getAnswerMode(),question.getGeneratedQuestionId(),
            question.getQuestionSpecId(),question.getChoices(),question.getCorrectChoiceIndex(),question.getExplanation(),hash,question.getUpstreamProvenance());
    }
    tools.jackson.databind.JsonNode ready(QuestionTranslationService.Job job) {
        var result=json.createObjectNode();result.put("status","READY");result.put("language","ko");
        result.put("version","question-translation-ko-v1");result.put("model","synthetic-test");result.put("sourceHash",job.sourceHash());
        result.putNull("passage");result.put("prompt","합성 한국어 문항입니다.");result.set("choices",json.valueToTree(question.getChoices()));return result;
    }
    @Test void attachesTranslationToExactSnapshotAndRetainsOriginalAndAnswerKey() {
        String before=jdbc.queryForObject("select row_to_json(q)::text from backend.question q where id=?",String.class,question.getId());
        var job=translations.claim();assertThat(job.input()).doesNotContainKeys("correctChoiceIndex","explanation","userId");
        translations.publish(job,ready(job));
        var display=translations.display(snapshot(question.getGeneratedContentHash()));
        assertThat(display.prompt()).isEqualTo("합성 한국어 문항입니다.");assertThat(display.choices()).isEqualTo(question.getChoices());
        assertThat(translations.display(snapshot("sha256:"+"a".repeat(64)))).isNull();
        assertThat(translations.claim()).isNull();
        assertThat(jdbc.queryForObject("select row_to_json(q)::text from backend.question q where id=?",String.class,question.getId())).isEqualTo(before);
    }
    @Test void staleClaimCannotPublishAndFailedTranslationStaysOriginal() {
        var job=translations.claim();translations.fail(job);translations.publish(job,ready(job));
        assertThat(translations.display(snapshot(question.getGeneratedContentHash()))).isNull();
        var retry=translations.claim();assertThat(retry).isNotNull();
        translations.fail(retry);
        var last=translations.claim();assertThat(last).isNotNull();translations.fail(last);
        assertThat(translations.claim()).isNull();
        assertThat(jdbc.queryForObject("select attempts from backend.question_display_translation where question_id=?",Integer.class,job.questionId())).isEqualTo(3);
    }
    @Test void invalidShapeNeverBecomesReady() {
        var job=translations.claim();
        var missing=(tools.jackson.databind.node.ObjectNode)ready(job);missing.remove("passage");
        assertThatThrownBy(()->translations.publish(job,missing)).isInstanceOf(IllegalArgumentException.class);
        var blank=(tools.jackson.databind.node.ObjectNode)ready(job);blank.set("choices",json.valueToTree(java.util.List.of("a"," ","c","d")));
        assertThatThrownBy(()->translations.publish(job,blank)).isInstanceOf(IllegalArgumentException.class);
        var numeric=(tools.jackson.databind.node.ObjectNode)ready(job);numeric.set("choices",json.valueToTree(java.util.List.of(1,2,3,4)));
        assertThatThrownBy(()->translations.publish(job,numeric)).isInstanceOf(IllegalArgumentException.class);
        var added=(tools.jackson.databind.node.ObjectNode)ready(job);added.put("passage","Invented passage");
        assertThatThrownBy(()->translations.publish(job,added)).isInstanceOf(IllegalArgumentException.class);
        assertThat(translations.display(snapshot(question.getGeneratedContentHash()))).isNull();
    }
}
