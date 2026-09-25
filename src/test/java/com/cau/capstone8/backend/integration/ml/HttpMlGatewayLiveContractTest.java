package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in check against a running Python service, e.g.
 * {@code uv run uvicorn --factory bookmatch_ml.api:create_app --port 8000} in the ML repo, then
 * {@code ML_CONTRACT_BASE_URL=http://127.0.0.1:8000 ./gradlew test --tests "*LiveContract*"}.
 */
@EnabledIfEnvironmentVariable(named = "ML_CONTRACT_BASE_URL", matches = "https?://.+")
class HttpMlGatewayLiveContractTest {

    @Test void pythonServiceAcceptsBackendProfileRequest() {
        var gateway = new HttpMlGateway(System.getenv("ML_CONTRACT_BASE_URL"),
                Duration.ofSeconds(2), Duration.ofSeconds(10));
        var request = new MlProfileRequest(UUID.randomUUID(), "v1", 1, "assessment-live", "OS", List.of(
                new MlProfileRequest.Answer("q1", MeasurementArea.VOCABULARY, "process", 1, true, 1),
                new MlProfileRequest.Answer("q2", MeasurementArea.VOCABULARY, null, 5, false, 1),
                new MlProfileRequest.Answer("q3", MeasurementArea.BACKGROUND_KNOWLEDGE, "thread", 3, true, 1),
                new MlProfileRequest.Answer("q4", MeasurementArea.COMPREHENSION, null, 4, false, 1)));

        MlProfileResult result = gateway.calculateProfile(request);

        // easy (1.0) known, hard (1.5) unknown -> 1.0 / 2.5 under reader-config-v1.
        assertThat(result.vocabulary()).isEqualTo(0.4);
        assertThat(result.backgroundKnowledge()).isEqualTo(1.0);
        assertThat(result.comprehension()).isEqualTo(0.0);
        assertThat(result.dimensionCounts()).containsEntry(MeasurementArea.VOCABULARY, 2);
        assertThat(result.evidence()).containsKeys("configHash", "conceptReadiness");
    }

    @Test void pythonServiceRanksRealScale50CandidateProjection() throws Exception {
        var gateway = new HttpMlGateway(System.getenv("ML_CONTRACT_BASE_URL"),
                Duration.ofSeconds(2), Duration.ofSeconds(10));
        String hash = "sha256:6230d12facf94e46c1daa11d9d83b3929322af2f69a36614d3f553684bfb21d6";
        var reader = new MlRankV2Request.Reader(
                "operating-systems", 0.7, 0.7, 0.7,
                List.of(
                        new MlRankV2Request.ConceptReadiness("computer architecture", 0.2),
                        new MlRankV2Request.ConceptReadiness("concurrency", 0.8),
                        new MlRankV2Request.ConceptReadiness("data structures", 1.0),
                        new MlRankV2Request.ConceptReadiness("memory management", 0.5),
                        new MlRankV2Request.ConceptReadiness("process", 1.0),
                        new MlRankV2Request.ConceptReadiness("scheduling", 1.0),
                        new MlRankV2Request.ConceptReadiness("synchronization", 0.8),
                        new MlRankV2Request.ConceptReadiness("virtual memory", 0.5)),
                "reader-v1", "reader-config-v1", hash);
        List<MlRankV2Request.Candidate> candidates = realCandidates();

        MlRankV2Result result = gateway.rankBooksV2(
                new MlRankV2Request(UUID.randomUUID(), 1, reader, candidates, null, 3));

        assertThat(result.modelVersion()).isEqualTo("rank-prerequisite-first-v2");
        assertThat(result.diagnostics().topicCandidateCount()).isEqualTo(3);
        assertThat(result.items()).isNotEmpty();
        assertThat(result.items()).allSatisfy(item ->
                assertThat(item.mlBookId()).startsWith("isbn13:"));
    }

    private List<MlRankV2Request.Candidate> realCandidates() throws Exception {
        var resource = getClass().getResource(
                "/fixtures/ml/scale-50-ranking-v2-candidates.jsonl");
        assertThat(resource).isNotNull();
        var json = JsonMapper.builder().build();
        List<MlRankV2Request.Candidate> candidates = new ArrayList<>();
        long id = 1;
        for (String line : Files.readAllLines(Path.of(resource.toURI()))) {
            var node = json.readTree(line);
            List<MlRankV2Request.Concept> covered = new ArrayList<>();
            node.path("covered_concepts").forEach(concept -> covered.add(
                    new MlRankV2Request.Concept(
                            concept.path("concept").asText(), concept.path("weight").asDouble())));
            List<MlRankV2Request.Concept> prerequisites = new ArrayList<>();
            node.path("prerequisite_concepts").forEach(concept -> prerequisites.add(
                    new MlRankV2Request.Concept(
                            concept.path("concept").asText(), concept.path("weight").asDouble())));
            candidates.add(new MlRankV2Request.Candidate(
                    id, id, node.path("book_id").asText(),
                    Map.of("operating-systems", 1.0), covered, prerequisites,
                    null, null, null, null, node.path("feature_version").asText(),
                    node.path("config_version").asText(), node.path("config_hash").asText()));
            id++;
        }
        return candidates;
    }
}
