package com.cau.capstone8.backend.integration.ml;

public interface MlGateway {
    MlProfileResult calculateProfile(MlProfileRequest request);

    default MlRankResult rankBooks(MlRankRequest request) {
        throw new MlGatewayException("ML_RANK_UNAVAILABLE", "ML 랭킹 계산을 사용할 수 없습니다.");
    }
}
