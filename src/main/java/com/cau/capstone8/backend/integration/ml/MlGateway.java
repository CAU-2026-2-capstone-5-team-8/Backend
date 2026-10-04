package com.cau.capstone8.backend.integration.ml;

public interface MlGateway {
    MlProfileResult calculateProfile(MlProfileRequest request);

    default MlReaderDiagnostics readerDiagnostics(MlProfileRequest request) {
        throw new MlGatewayException("ML_UNAVAILABLE", "ML 진단 근거 조회를 사용할 수 없습니다.");
    }

    default java.util.Map<String, Object> learningFit(java.util.Map<String, Object> request) {
        throw new MlGatewayException("ML_RANK_UNAVAILABLE", "개념 추천을 준비하고 있습니다.");
    }

    default java.util.Map<String, Object> conceptGraph(String topicId) {
        throw new MlGatewayException("ML_CONCEPTS_UNAVAILABLE", "개념 지도를 준비하고 있습니다.");
    }

    default MlRankResult rankBooks(MlRankRequest request) {
        throw new MlGatewayException("ML_RANK_UNAVAILABLE", "ML 랭킹 계산을 사용할 수 없습니다.");
    }

    default MlRankV2Result rankBooksV2(MlRankV2Request request) {
        throw new MlGatewayException("ML_RANK_UNAVAILABLE", "ML 랭킹 v2 계산을 사용할 수 없습니다.");
    }
}
