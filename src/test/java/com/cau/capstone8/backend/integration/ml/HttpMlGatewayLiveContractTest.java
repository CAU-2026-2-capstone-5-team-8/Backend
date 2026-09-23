package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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
}
