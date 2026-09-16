package com.cau.capstone8.backend.recommendation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RecommendationRequest(
        @NotNull @Positive Long userId,
        @NotNull @Positive Long topicId,
        @Positive Long targetBookId,
        @NotNull ChallengeLevel challengeLevel,
        @Min(1) @Max(20) Integer topK) {

    public RecommendationRequest {
        if (topK == null) topK = 5;
    }
}
