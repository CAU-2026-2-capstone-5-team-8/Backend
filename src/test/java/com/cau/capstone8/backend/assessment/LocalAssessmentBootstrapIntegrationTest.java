package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cau.capstone8.backend.book.LocalCatalogImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Testcontainers
class LocalAssessmentBootstrapIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired LocalAssessmentBootstrap bootstrap;
    @Autowired AssessmentService assessments;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path dir;
    final JsonMapper json = new JsonMapper();
    Map<String,Object> manifest;
    List<Map<String,Object>> selfReports;

    @BeforeEach void prepare() throws Exception {
        // This class owns the disposable database; no application database is reset.
        jdbc.execute("truncate backend.app_user, backend.topic, backend.book cascade");
        jdbc.update("insert into backend.topic(code,name,ml_topic_id) values ('LA','선형대수','linear-algebra')");
        selfReports = new ArrayList<>();
        for (int i=0; i<8; i++) selfReports.add(new LinkedHashMap<>(Map.of(
                "demo_key","synthetic-la-"+i,"concept_id","concept-"+i,
                "measurement_area",i<3?"VOCABULARY":i<6?"BACKGROUND_KNOWLEDGE":"COMPREHENSION",
                "difficulty",2,"prompt","[자기평가] 검증용 문항 "+i,"version","synthetic-self-report-v1")));
        var approved = new LinkedHashMap<String,Object>();
        fixture(approved,"generated","generated-matrix-v4-rev1.json");
        fixture(approved,"reviews","reviews-linear-algebra-display-grounded.jsonl");
        fixture(approved,"grounding","grounding-matrix-v2.json");
        manifest = new LinkedHashMap<>(Map.of(
                "contract_version","local-assessment-bootstrap-v1","ml_topic_id","linear-algebra",
                "user_key","synthetic-la-user","user_name","Synthetic test user",
                "self_report_questions",selfReports,"approved_questions",List.of(approved)));
    }

    @Test void replaysSameBankAndIssuesNineMixedQuestionsWithoutAnswerLeak() throws Exception {
        var first = bootstrap.importManifest(write());
        var ids = jdbc.queryForList("select id from backend.question order by id",Long.class);
        assertThat(bootstrap.importManifest(write())).isEqualTo(first);
        assertThat(jdbc.queryForList("select id from backend.question order by id",Long.class)).isEqualTo(ids);
        assertThat(jdbc.queryForObject("select count(*) from backend.app_user",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from backend.question where answer_mode='SELF_REPORT' and upstream_provenance is null",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from backend.question where upstream_provenance#>>'{humanReview,status}'='approve'",Integer.class)).isEqualTo(1);
        var issued = assessments.create(first.userId(),first.topicId());
        assertThat(issued.questions()).hasSize(9);
        var mc = issued.questions().stream().filter(q->q.answerMode().equals("MULTIPLE_CHOICE")).findFirst().orElseThrow();
        assertThat(mc.passage()).isNotBlank();
        assertThat(mc.choices()).hasSize(4);
        assertThat(json.writeValueAsString(issued)).doesNotContain("correctChoiceIndex","explanation","upstreamProvenance");
    }

    @Test void changedSelfReportFailsInsteadOfOverwritingExistingBank() throws Exception {
        bootstrap.importManifest(write());
        selfReports.getFirst().put("prompt","[자기평가] Different prompt");
        assertThatThrownBy(()->bootstrap.importManifest(write())).hasMessageContaining("different immutable content");
        assertThat(jdbc.queryForObject("select prompt from backend.question where demo_key='synthetic-la-0'",String.class)).isEqualTo("[자기평가] 검증용 문항 0");
        assertThat(jdbc.queryForObject("select count(*) from backend.question",Integer.class)).isEqualTo(9);
    }

    @Test void corruptApprovedInputRollsBackUserAndSelfReports() throws Exception {
        Files.writeString(dir.resolve("generated-matrix-v4-rev1.json"),"{}");
        assertThatThrownBy(()->bootstrap.importManifest(write())).hasMessageContaining("SHA-256 mismatch");
        assertThat(jdbc.queryForObject("select count(*) from backend.app_user",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from backend.question",Integer.class)).isZero();
    }

    @Test void existingQuestionBankIsPreservedWhenNineQuestionPolicyCannotBeMet() throws Exception {
        jdbc.update("""
                insert into backend.question(topic_id,concept_id,measurement_area,difficulty,prompt,version,active,answer_mode)
                select id,'existing','VOCABULARY',2,'Existing question','existing-v1',true,'SELF_REPORT' from backend.topic where code='LA'
                """);
        assertThatThrownBy(()->bootstrap.importManifest(write())).hasMessageContaining("exactly these nine active questions");
        assertThat(jdbc.queryForObject("select count(*) from backend.question",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select prompt from backend.question",String.class)).isEqualTo("Existing question");
        assertThat(jdbc.queryForObject("select count(*) from backend.app_user",Integer.class)).isZero();
    }

    void fixture(Map<String,Object> approved,String key,String filename) throws Exception {
        byte[] bytes = new ClassPathResource("fixtures/question-handoff/"+filename).getContentAsByteArray();
        Files.write(dir.resolve(filename),bytes);
        approved.put(key+"_path",filename);
        approved.put(key+"_sha256",LocalCatalogImportService.hash(bytes));
    }
    Path write() throws Exception {
        Path path=dir.resolve("assessment.json"); Files.writeString(path,json.writeValueAsString(manifest));return path;
    }
}
