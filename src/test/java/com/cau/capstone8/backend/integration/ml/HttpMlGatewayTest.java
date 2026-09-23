package com.cau.capstone8.backend.integration.ml;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpMlGatewayTest {
    WireMockServer server;
    HttpMlGateway gateway;
    String baseUrl;
    final MlProfileRequest request = new MlProfileRequest(UUID.randomUUID(), "v1", 1,
            "assessment-1", "OS", Arrays.stream(MeasurementArea.values())
            .map(a -> new MlProfileRequest.Answer(a.name(), a, "concept-" + a, 2, true, 1)).toList());

    @BeforeEach void start() {
        server = new WireMockServer(0);
        server.start();
        baseUrl = server.baseUrl();
        gateway = new HttpMlGateway(baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }
    @AfterEach void stop() { server.stop(); }

    /** Shape captured from the Python service's POST /ml/reader-profile (bookmatch_ml main). */
    String validJson() {
        return """
                {"userId":1,"assessmentId":"assessment-1","topicId":"OS",
                 "vocabulary":1.0,"backgroundKnowledge":1.0,"comprehension":1.0,
                 "dimensionDetails":[
                   {"questionType":"vocabulary","score":1.0,"responseCount":1,"earnedWeight":1.0,"availableWeight":1.0},
                   {"questionType":"background_knowledge","score":1.0,"responseCount":1,"earnedWeight":1.0,"availableWeight":1.0},
                   {"questionType":"comprehension","score":1.0,"responseCount":1,"earnedWeight":1.0,"availableWeight":1.0}],
                 "conceptReadiness":[
                   {"conceptId":"concept-VOCABULARY","score":1.0,"responseCount":1,"earnedWeight":1.0,"availableWeight":1.0}],
                 "responseCount":3,"profileVersion":"reader-v1","configVersion":"reader-config-v1",
                 "configHash":"sha256:7dd4d891db7a9fbe7527142a6f23d0ade62b94193b18fcc9ce02789b89334219"}
                """.replaceAll("\\s+", "");
    }

    @Test void postsPythonWireFormatAndReturnsValidatedProfile() {
        server.stubFor(post("/ml/reader-profile").willReturn(okJson(validJson())));

        MlProfileResult result = gateway.calculateProfile(request);

        assertThat(result.vocabulary()).isEqualTo(1);
        assertThat(result.requestId()).isEqualTo(request.requestId());
        assertThat(result.calculationVersion()).isEqualTo("reader-v1");
        assertThat(result.dimensionCounts()).containsEntry(MeasurementArea.VOCABULARY, 1)
                .containsEntry(MeasurementArea.BACKGROUND_KNOWLEDGE, 1)
                .containsEntry(MeasurementArea.COMPREHENSION, 1);
        assertThat(result.evidence()).containsEntry("method", "ml-http-reader-profile")
                .containsEntry("configVersion", "reader-config-v1")
                .containsKeys("configHash", "dimensionDetails", "conceptReadiness");
        server.verify(1, postRequestedFor(urlEqualTo("/ml/reader-profile"))
                .withRequestBody(matchingJsonPath("$.assessmentId", equalTo("assessment-1")))
                .withRequestBody(matchingJsonPath("$.responses[0].questionType", equalTo("vocabulary")))
                .withRequestBody(matchingJsonPath("$.responses[0].topicId", equalTo("OS")))
                .withRequestBody(matchingJsonPath("$.responses[0].difficulty", equalTo("easy")))
                .withRequestBody(matchingJsonPath("$.responses[0].correct", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.responses[0].conceptId", equalTo("concept-VOCABULARY")))
                // The Python DTOs forbid unknown fields, so backend-only fields must not be sent.
                .withRequestBody(notContaining("requestId"))
                .withRequestBody(notContaining("contractVersion"))
                .withRequestBody(notContaining("knowsConcept"))
                .withRequestBody(notContaining("points")));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void mapsBackendDifficultyToPythonBands(int difficulty) {
        assertThat(MlProfileHttpContract.difficultyBand(difficulty))
                .isEqualTo(difficulty <= 2 ? "easy" : difficulty == 3 ? "medium" : "hard");
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "not-json", "null",
            "missing-score", "null-score", "bad-score", "wrong-assessment", "wrong-topic", "wrong-user",
            "bad-count", "bad-response-count", "unknown-dimension", "bad-hash", "missing-version",
            "missing-details"})
    void rejectsInvalidResponses(String kind) {
        String body = validJson();
        body = switch (kind) {
            case "missing-score" -> body.replace("\"vocabulary\":1.0,", "");
            case "null-score" -> body.replace("\"vocabulary\":1.0", "\"vocabulary\":null");
            case "bad-score" -> body.replace("\"vocabulary\":1.0", "\"vocabulary\":2.0");
            case "wrong-assessment" -> body.replace("\"assessment-1\"", "\"assessment-2\"");
            case "wrong-topic" -> body.replace("\"topicId\":\"OS\"", "\"topicId\":\"DB\"");
            case "wrong-user" -> body.replace("\"userId\":1", "\"userId\":2");
            case "bad-count" -> body.replaceFirst("\"responseCount\":1", "\"responseCount\":2");
            case "bad-response-count" -> body.replace("\"responseCount\":3", "\"responseCount\":4");
            case "unknown-dimension" -> body.replace("\"comprehension\",\"score\"", "\"reading\",\"score\"");
            case "bad-hash" -> body.replace("sha256:7dd4", "md5:7dd4");
            case "missing-version" -> body.replace("\"profileVersion\":\"reader-v1\",", "");
            case "missing-details" -> body.replaceAll(
                    "\"dimensionDetails\":\\[.*?\\],\"conceptReadiness\"", "\"conceptReadiness\"");
            default -> kind;
        };
        server.stubFor(post("/ml/reader-profile").willReturn(okJson(body)));
        assertFailure("ML_INVALID_RESPONSE");
    }

    @ParameterizedTest @ValueSource(ints = {400, 429, 500, 503})
    void rejectsHttpErrorsWithoutRetryOrFallback(int status) {
        server.stubFor(post("/ml/reader-profile").willReturn(aResponse().withStatus(status).withBody("secret upstream detail")));
        assertFailure("ML_UPSTREAM_ERROR");
        server.verify(1, postRequestedFor(urlEqualTo("/ml/reader-profile")));
    }

    @Test void timesOutWithoutRetry() {
        gateway = new HttpMlGateway(baseUrl, Duration.ofSeconds(2), Duration.ofMillis(100));
        server.stubFor(post("/ml/reader-profile").willReturn(okJson(validJson()).withFixedDelay(1000)));
        assertFailure("ML_TIMEOUT");
        server.verify(1, postRequestedFor(urlEqualTo("/ml/reader-profile")));
    }

    @Test void reportsConnectionFailure() {
        server.stop();
        assertFailure("ML_UNAVAILABLE");
    }

    @Test void selectsOnlyHttpGatewayWhenConfigured() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(HttpMlGateway.class, StubMlGateway.class)
                .withPropertyValues("ml.mode=http", "ml.base-url=" + baseUrl)
                .run(context -> assertThat(context).hasSingleBean(MlGateway.class)
                        .hasSingleBean(HttpMlGateway.class).doesNotHaveBean(StubMlGateway.class));
    }

    @Test void keepsStubAsDefault() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(HttpMlGateway.class, StubMlGateway.class)
                .run(context -> assertThat(context).hasSingleBean(StubMlGateway.class)
                        .doesNotHaveBean(HttpMlGateway.class));
    }

    @Test void reportsRankingUnavailableUntilHttpRankIsImplemented() {
        assertThatThrownBy(() -> gateway.rankBooks(null))
                .isInstanceOfSatisfying(MlGatewayException.class,
                        ex -> assertThat(ex.getFailureCode()).isEqualTo("ML_RANK_UNAVAILABLE"));
    }

    void assertFailure(String code) {
        assertThatThrownBy(() -> gateway.calculateProfile(request))
                .isInstanceOfSatisfying(MlGatewayException.class,
                        ex -> assertThat(ex.getFailureCode()).isEqualTo(code))
                .hasMessageNotContaining("secret").hasMessageNotContaining(baseUrl);
    }
}
