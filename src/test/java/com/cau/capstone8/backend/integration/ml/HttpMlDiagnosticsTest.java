package com.cau.capstone8.backend.integration.ml;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpMlDiagnosticsTest {
    WireMockServer server;
    HttpMlGateway gateway;
    static MlProfileRequest request() {
        return new MlProfileRequest(UUID.randomUUID(), "v1", 1, "42", "operating-systems", List.of(
                new MlProfileRequest.Answer("q1", MeasurementArea.VOCABULARY, "process", 1, true, 1),
                new MlProfileRequest.Answer("q2", MeasurementArea.BACKGROUND_KNOWLEDGE, "process", 3, false, 1),
                new MlProfileRequest.Answer("q3", MeasurementArea.COMPREHENSION, null, 5, true, 1)));
    }
    @BeforeEach void start() {
        server = new WireMockServer(0);
        server.start();
        gateway = new HttpMlGateway(server.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(10));
    }
    @AfterEach void stop() { server.stop(); }
    String fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/ml/reader-diagnostics-v1.json")) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test void sendsExistingProfileContractAndValidatesPythonResponse() throws Exception {
        server.stubFor(post("/ml/reader-diagnostics").willReturn(okJson(fixture())));
        var result = gateway.readerDiagnostics(request());
        assertThat(result.assessmentId()).isEqualTo("42");
        assertThat(result.concepts()).hasSize(1);
        assertThat(result.concepts().getFirst().evidence()).hasSize(9);
        assertThat(result.concepts().getFirst().nextCheck().reason()).isEqualTo("review_observed_gap");
        assertThat(result.untaggedResponseCount()).isEqualTo(1);
        server.verify(postRequestedFor(urlEqualTo("/ml/reader-diagnostics"))
                .withRequestBody(matchingJsonPath("$.responses[1].difficulty", equalTo("medium")))
                .withRequestBody(notContaining("requestId")));
    }
    @ParameterizedTest
    @ValueSource(strings = {"wrong-user", "wrong-question", "bad-score", "missing-count", "bad-version", "{}", "null"})
    void rejectsMismatchedOrMalformedEvidence(String kind) throws Exception {
        String body = switch (kind) {
            case "wrong-user" -> fixture().replace("\"userId\": 1", "\"userId\": 2");
            case "wrong-question" -> fixture().replace("q1", "unissued");
            case "bad-score" -> fixture().replace("\"score\": 1.0", "\"score\": 0.0");
            case "missing-count" -> fixture().replace("\"responseCount\": 3", "\"responseCount\": null");
            case "bad-version" -> fixture().replace("reader-depth-evidence-v1", "unknown-v9");
            default -> kind;
        };
        server.stubFor(post("/ml/reader-diagnostics").willReturn(okJson(body)));
        assertThatThrownBy(() -> gateway.readerDiagnostics(request()))
                .isInstanceOfSatisfying(MlGatewayException.class,
                        ex -> assertThat(ex.getFailureCode()).isEqualTo("ML_INVALID_RESPONSE"));
    }
    @Test void upstreamFailureIsSanitized() {
        server.stubFor(post("/ml/reader-diagnostics")
                .willReturn(aResponse().withStatus(500).withBody("private upstream details")));
        assertThatThrownBy(() -> gateway.readerDiagnostics(request()))
                .isInstanceOfSatisfying(MlGatewayException.class, ex -> {
                    assertThat(ex.getFailureCode()).isEqualTo("ML_UPSTREAM_ERROR");
                    assertThat(ex.getMessage()).doesNotContain("private");
                });
    }

    @Test void timeoutDoesNotRetry() throws Exception {
        gateway = new HttpMlGateway(server.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(100));
        server.stubFor(post("/ml/reader-diagnostics").willReturn(okJson(fixture()).withFixedDelay(1000)));
        assertThatThrownBy(() -> gateway.readerDiagnostics(request()))
                .isInstanceOfSatisfying(MlGatewayException.class,
                        ex -> assertThat(ex.getFailureCode()).isEqualTo("ML_TIMEOUT"));
        server.verify(1, postRequestedFor(urlEqualTo("/ml/reader-diagnostics")));
    }

    @Test void unavailableConnectionDoesNotReturnSyntheticDiagnostics() {
        server.stop();
        assertThatThrownBy(() -> gateway.readerDiagnostics(request()))
                .isInstanceOfSatisfying(MlGatewayException.class,
                        ex -> assertThat(ex.getFailureCode()).isEqualTo("ML_UNAVAILABLE"));
    }
}
