package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.book.LocalCatalogImportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in local validation bank: SELF_REPORT is never relabeled as approved generated content. */
@Service
public class LocalAssessmentBootstrap {
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final JdbcTemplate jdbc;
    private final ApprovedQuestionImportService importer;
    private final QuestionRepository questions;
    public LocalAssessmentBootstrap(JdbcTemplate jdbc, ApprovedQuestionImportService importer,
                                    QuestionRepository questions) {
        this.jdbc = jdbc; this.importer = importer; this.questions = questions;
    }
    public record SelfReport(String demoKey, String conceptId, String measurementArea,
                             int difficulty, String prompt, String version) {}
    public record Approved(String generatedPath, String generatedSha256, String reviewsPath,
                           String reviewsSha256, String groundingPath, String groundingSha256) {}
    public record Manifest(String contractVersion, String mlTopicId, String userKey,
                           String userName, List<SelfReport> selfReportQuestions,
                           List<Approved> approvedQuestions) {}
    public record Result(long userId, long topicId, int selfReportCount, int approvedCount) {}

    @Transactional
    public Result importManifest(Path path) {
        try {
            Manifest m = JSON.readValue(Files.readAllBytes(path), Manifest.class);
            require("local-assessment-bootstrap-v1".equals(m.contractVersion()), "unsupported bootstrap version");
            text(m.mlTopicId(), 120); text(m.userKey(), 80); text(m.userName(), 120);
            require(m.selfReportQuestions() != null && m.approvedQuestions() != null,
                    "explicit question lists required");
            require(!m.approvedQuestions().isEmpty(), "at least one approved question is required");
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('local-assessment-bootstrap-v1'))", Object.class);
            var topics = jdbc.queryForList("select id from backend.topic where ml_topic_id=?", m.mlTopicId());
            require(topics.size() == 1, "import catalog topic before bootstrapping assessment");
            long topicId = ((Number) topics.getFirst().get("id")).longValue();
            var users = jdbc.queryForList("select id,display_name from backend.app_user where demo_key=?", m.userKey());
            long userId;
            if (users.isEmpty()) userId = jdbc.queryForObject(
                    "insert into backend.app_user(display_name,demo_key) values (?,?) returning id",
                    Long.class, m.userName(), m.userKey());
            else {
                require(m.userName().equals(users.getFirst().get("display_name")), "existing test user differs");
                userId = ((Number) users.getFirst().get("id")).longValue();
            }
            var keys = new HashSet<String>();
            var issuedIds = new HashSet<Long>();
            for (SelfReport q : m.selfReportQuestions()) {
                text(q.demoKey(), 80); text(q.conceptId(), 120); text(q.prompt(), 4000); text(q.version(), 80);
                require(keys.add(q.demoKey()), "duplicate self-report key");
                MeasurementArea.valueOf(q.measurementArea());
                require(q.difficulty() >= 1 && q.difficulty() <= 5, "invalid question difficulty");
                require(q.prompt().contains("자기평가"), "self-report prompt must identify itself");
                var rows = jdbc.queryForList("""
                        select id,topic_id,concept_id,measurement_area,difficulty,prompt,version,active,answer_mode
                        from backend.question where demo_key=?
                        """, q.demoKey());
                long questionId;
                if (rows.isEmpty()) questionId = jdbc.queryForObject("""
                        insert into backend.question(topic_id,concept_id,measurement_area,difficulty,prompt,
                            version,active,demo_key,answer_mode) values (?,?,?,?,?,?,true,?,'SELF_REPORT') returning id
                        """, Long.class, topicId, q.conceptId(), q.measurementArea(), q.difficulty(), q.prompt(),
                        q.version(), q.demoKey());
                else {
                    var r = rows.getFirst();
                    require(((Number) r.get("topic_id")).longValue() == topicId
                            && q.conceptId().equals(r.get("concept_id"))
                            && q.measurementArea().equals(r.get("measurement_area"))
                            && q.difficulty() == ((Number) r.get("difficulty")).intValue()
                            && q.prompt().equals(r.get("prompt")) && q.version().equals(r.get("version"))
                            && Boolean.TRUE.equals(r.get("active")) && "SELF_REPORT".equals(r.get("answer_mode")),
                            "existing self-report key has different immutable content");
                    questionId = ((Number) r.get("id")).longValue();
                }
                issuedIds.add(questionId);
            }
            Path base = path.toAbsolutePath().getParent();
            for (Approved q : m.approvedQuestions()) {
                Path generated = checked(base, q.generatedPath(), q.generatedSha256());
                Path reviews = checked(base, q.reviewsPath(), q.reviewsSha256());
                var generatedJson = JSON.readTree(Files.readAllBytes(generated));
                require(m.mlTopicId().equals(generatedJson.path("topic_id").asString()), "approved topic mismatch");
                ApprovedQuestionImportService.ImportResult result;
                if (q.groundingPath() == null) {
                    require(q.groundingSha256() == null, "unexpected grounding hash");
                    result = importer.importApproved(generated, reviews);
                } else result = importer.importApproved(generated, reviews,
                        checked(base, q.groundingPath(), q.groundingSha256()));
                require(issuedIds.add(result.questionId()), "duplicate approved question");
            }
            questions.flush();
            var active = jdbc.queryForList("select id,measurement_area from backend.question where topic_id=? and active", topicId);
            require(active.size() == 9 && active.stream().allMatch(r -> issuedIds.contains(((Number) r.get("id")).longValue())),
                    "local bank must contain exactly these nine active questions; existing bank is preserved on failure");
            for (MeasurementArea area : MeasurementArea.values()) require(active.stream()
                    .filter(r -> area.name().equals(r.get("measurement_area"))).count() == 3,
                    "three questions per measurement area required");
            return new Result(userId, topicId, m.selfReportQuestions().size(), m.approvedQuestions().size());
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("local assessment handoff could not be read", e); }
    }
    private static Path checked(Path base, String value, String hash) throws Exception {
        text(value, 4096);
        Path p = base.resolve(value);
        require(Files.isRegularFile(p) && Files.size(p) <= 2 * 1024 * 1024, "missing or oversized question input: " + p);
        require(LocalCatalogImportService.hash(Files.readAllBytes(p)).equals(hash), "question input SHA-256 mismatch: " + p);
        return p;
    }
    private static void text(String s, int max) { require(s != null && !s.isBlank() && s.length() <= max, "invalid bootstrap text"); }
    private static void require(boolean ok, String msg) { if (!ok) throw new IllegalArgumentException(msg); }
}
