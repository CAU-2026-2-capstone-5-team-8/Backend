package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.Map;
import java.util.UUID;

public record MlProfileResult(
        UUID requestId,
        String contractVersion,
        String calculationVersion,
        double vocabulary,
        double backgroundKnowledge,
        double comprehension,
        Map<MeasurementArea, Integer> dimensionCounts,
        Map<String, Object> evidence) {
}
