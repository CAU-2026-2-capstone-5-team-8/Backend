package com.cau.capstone8.backend.integration.ml;

public interface MlGateway {
    MlProfileResult calculateProfile(MlProfileRequest request);
    MlRankResult rank(MlRankRequest request);
}
