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
import tools.jackson.databind.json.JsonMapper;

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

    String validJson() {
        return JsonMapper.builder().build().writeValueAsString(new StubMlGateway().calculateProfile(request));
    }

    @Test void postsSnapshotAndReturnsValidatedProfile() {
        server.stubFor(post("/ml/reader-profile").willReturn(okJson(validJson())));
        assertThat(gateway.calculateProfile(request).vocabulary()).isEqualTo(1);
        server.verify(1, postRequestedFor(urlEqualTo("/ml/reader-profile"))
                .withRequestBody(matchingJsonPath("$.requestId", equalTo(request.requestId().toString())))
                .withRequestBody(matchingJsonPath("$.answers[0].conceptId", equalTo("concept-VOCABULARY"))));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "not-json", "null",
            "missing-score", "null-score", "wrong-id", "wrong-version", "bad-count", "bad-score"})
    void rejectsInvalidResponses(String kind) {
        String body = validJson();
        body = switch (kind) {
            case "missing-score" -> body.replace("\"vocabulary\":1.0,", "");
            case "null-score" -> body.replace("\"vocabulary\":1.0", "\"vocabulary\":null");
            case "wrong-id" -> body.replace(request.requestId().toString(), UUID.randomUUID().toString());
            case "wrong-version" -> body.replace("\"v1\"", "\"v2\"");
            case "bad-count" -> body.replace("\"VOCABULARY\":1", "\"VOCABULARY\":99");
            case "bad-score" -> body.replace("\"vocabulary\":1.0", "\"vocabulary\":2.0");
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
