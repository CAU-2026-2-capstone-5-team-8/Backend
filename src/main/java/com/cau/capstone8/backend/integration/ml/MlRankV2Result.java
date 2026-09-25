package com.cau.capstone8.backend.integration.ml;

import java.util.List;

public record MlRankV2Result(
        long userId,
        String topicId,
        List<Item> items,
        Diagnostics diagnostics,
        String modelVersion,
        String configVersion,
        String configHash,
        String conceptGraphVersion,
        String conceptGraphHash,
        String graphReviewVersion,
        String graphReviewHash,
        String readerProfileVersion,
        String readerConfigVersion,
        String readerConfigHash) {

    public record Item(
            String mlBookId,
            int rank,
            String availabilityStatus,
            double prerequisiteReadiness,
            int prerequisiteAssessedCount,
            int prerequisiteTotalCount,
            double prerequisiteCoverage,
            Double directLearningOpportunity,
            int directAssessedCount,
            int directTotalCount,
            double directCoverage,
            List<String> coveredConcepts,
            List<String> inferredPrerequisites,
            List<String> reasons,
            String modelVersion,
            String bookFeatureVersion,
            String bookConfigVersion,
            String bookConfigHash) {
    }

    public record Diagnostics(
            int requestedLimit,
            int returnedCount,
            int topicCandidateCount,
            int personalizableCount,
            int conceptOnlyCount,
            int evidenceUnavailableCount,
            int fallbackCount,
            int personalizedCandidateShortage) {
    }
}
