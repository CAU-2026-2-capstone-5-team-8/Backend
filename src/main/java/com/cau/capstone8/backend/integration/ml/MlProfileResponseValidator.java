package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.EnumMap;
import java.util.Map;

public final class MlProfileResponseValidator {
    private MlProfileResponseValidator() {
    }

    public static MlProfileResult validate(MlProfileRequest request, MlProfileResult result) {
        if (result == null
                || !request.requestId().equals(result.requestId())
                || !request.contractVersion().equals(result.contractVersion())
                || result.calculationVersion() == null
                || result.calculationVersion().isBlank()
                || !validScore(result.vocabulary())
                || !validScore(result.backgroundKnowledge())
                || !validScore(result.comprehension())
                || result.dimensionCounts() == null
                || result.evidence() == null) {
            throw invalidResponse();
        }

        Map<MeasurementArea, Integer> expectedCounts = new EnumMap<>(MeasurementArea.class);
        for (MeasurementArea area : MeasurementArea.values()) {
            expectedCounts.put(area, 0);
        }
        for (MlProfileRequest.Answer answer : request.answers()) {
            expectedCounts.compute(answer.measurementArea(), (ignored, count) -> count + 1);
        }
        if (!expectedCounts.equals(result.dimensionCounts())) {
            throw invalidResponse();
        }
        return result;
    }

    private static boolean validScore(double score) {
        return Double.isFinite(score) && score >= 0 && score <= 1;
    }

    private static MlGatewayException invalidResponse() {
        return new MlGatewayException("ML_INVALID_RESPONSE", "ML 프로필 응답이 계약과 일치하지 않습니다.");
    }
}
