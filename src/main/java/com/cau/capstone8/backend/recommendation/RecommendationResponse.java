package com.cau.capstone8.backend.recommendation;

import java.time.OffsetDateTime;
import java.util.List;

public record RecommendationResponse(
        long id,
        long userId,
        long topicId,
        long profileId,
        RecommendationStatus status,
        ChallengeLevel challengeLevel,
        Long targetBookId,
        int requestedTopK,
        int eligibleCandidateCount,
        int excludedCandidateCount,
        String modelVersion,
        String failureCode,
        String failureMessage,
        OffsetDateTime completedAt,
        List<Item> items) {

    public record Item(
            long id,
            long bookId,
            int rank,
            double score,
            double topicFit,
            double vocabularyFit,
            double knowledgeFit,
            double comprehensionFit,
            String bookFeatureVersion,
            List<String> reasons) {
    }
}
