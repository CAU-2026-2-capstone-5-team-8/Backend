package com.cau.capstone8.backend.recommendation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record RecommendationResponse(
        long id,
        long userId,
        long topicId,
        long profileId,
        RecommendationStatus status,
        RecommendationRankingMode rankingMode,
        ChallengeLevel challengeLevel,
        Long targetBookId,
        int requestedTopK,
        int eligibleCandidateCount,
        int excludedCandidateCount,
        String modelVersion,
        Provenance provenance,
        Map<String, Object> diagnostics,
        String failureCode,
        String failureMessage,
        OffsetDateTime completedAt,
        List<Item> items) {

    public record Provenance(
            String rankingConfigVersion,
            String rankingConfigHash,
            String conceptGraphVersion,
            String conceptGraphHash,
            String graphReviewVersion,
            String graphReviewHash,
            String readerProfileVersion,
            String readerConfigVersion,
            String readerConfigHash) {
    }

    public record Item(
            long id,
            long bookId,
            String mlBookId,
            String title,
            String author,
            RecommendationRankingMode rankingMode,
            int rank,
            Double score,
            Double topicFit,
            Double vocabularyFit,
            Double knowledgeFit,
            Double comprehensionFit,
            String bookFeatureVersion,
            Double prerequisiteReadiness,
            Integer prerequisiteAssessedCount,
            Integer prerequisiteTotalCount,
            Double prerequisiteCoverage,
            Double directLearningOpportunity,
            Integer directAssessedCount,
            Integer directTotalCount,
            Double directCoverage,
            String availabilityStatus,
            List<String> coveredConcepts,
            List<String> inferredPrerequisites,
            String bookConfigVersion,
            String bookConfigHash,
            List<String> reasons) {
    }
}
