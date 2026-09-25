package com.cau.capstone8.backend.integration.ml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/** HTTP transport for the Python profile and explicitly selected prerequisite-first v2 APIs. */
@Component
@ConditionalOnProperty(name = "ml.mode", havingValue = "http")
public class HttpMlGateway implements MlGateway {
    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public HttpMlGateway(@Value("${ml.base-url}") String baseUrl,
            @Value("${ml.connect-timeout:PT2S}") String connectTimeout,
            @Value("${ml.read-timeout:PT10S}") String readTimeout) {
        this(baseUrl, Duration.parse(connectTimeout), Duration.parse(readTimeout));
    }

    public HttpMlGateway(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("ML base URL must be an HTTP(S) URL without credentials/query/fragment");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("ML timeouts must be positive");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(readTimeout);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public MlProfileResult calculateProfile(MlProfileRequest request) {
        String body;
        try {
            body = client.post().uri("/ml/reader-profile").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(MlProfileHttpContract.toWire(request)))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, response) -> {
                        throw new MlGatewayException("ML_UPSTREAM_ERROR", "ML 서비스가 요청을 처리하지 못했습니다.");
                    }).body(String.class);
        } catch (ResourceAccessException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    throw new MlGatewayException("ML_TIMEOUT", "ML 서비스 응답 시간이 초과되었습니다.", ex);
                }
            }
            throw new MlGatewayException("ML_UNAVAILABLE", "ML 서비스에 연결할 수 없습니다.", ex);
        } catch (RestClientResponseException ex) {
            throw new MlGatewayException("ML_UPSTREAM_ERROR", "ML 서비스가 요청을 처리하지 못했습니다.", ex);
        }
        try {
            var tree = json.readTree(body);
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException();
            // Primitive double fields otherwise silently turn missing/null scores into zero.
            for (String score : new String[]{"vocabulary", "backgroundKnowledge", "comprehension"}) {
                if (!tree.path(score).isNumber()) throw new IllegalArgumentException();
            }
            var response = json.treeToValue(tree, MlProfileHttpContract.ProfileResponse.class);
            return MlProfileResponseValidator.validate(
                    request, MlProfileHttpContract.fromWire(request, response, json));
        } catch (MlGatewayException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MlGatewayException("ML_INVALID_RESPONSE", "ML 프로필 응답이 계약과 일치하지 않습니다.", ex);
        }
    }

    @Override
    public MlRankV2Result rankBooksV2(MlRankV2Request request) {
        String body;
        try {
            body = client.post().uri("/ml/rank").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(MlRankV2HttpContract.toWire(request)))
                    .retrieve()
                    .onStatus(status -> status.value() == 422, (req, response) -> {
                        throw new MlGatewayException(
                                "ML_RANK_TARGET_UNAVAILABLE",
                                "선택한 도서는 현재 프로필 근거로 개인화할 수 없습니다.");
                    })
                    .onStatus(status -> !status.is2xxSuccessful(), (req, response) -> {
                        throw new MlGatewayException(
                                "ML_UPSTREAM_ERROR", "ML 서비스가 요청을 처리하지 못했습니다.");
                    }).body(String.class);
        } catch (MlGatewayException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    throw new MlGatewayException("ML_TIMEOUT", "ML 서비스 응답 시간이 초과되었습니다.", ex);
                }
            }
            throw new MlGatewayException("ML_UNAVAILABLE", "ML 서비스에 연결할 수 없습니다.", ex);
        } catch (RestClientResponseException ex) {
            throw new MlGatewayException("ML_UPSTREAM_ERROR", "ML 서비스가 요청을 처리하지 못했습니다.", ex);
        }
        try {
            var tree = json.readTree(body);
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException();
            MlRankV2HttpContract.RankResponse response =
                    json.treeToValue(tree, MlRankV2HttpContract.RankResponse.class);
            return MlRankV2ResponseValidator.validate(
                    request, MlRankV2HttpContract.fromWire(response));
        } catch (MlGatewayException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MlGatewayException(
                    "ML_INVALID_RESPONSE", "ML 랭킹 응답이 v2 계약과 일치하지 않습니다.", ex);
        }
    }
}
