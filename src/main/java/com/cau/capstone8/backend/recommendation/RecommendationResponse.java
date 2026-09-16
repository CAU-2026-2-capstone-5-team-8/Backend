package com.cau.capstone8.backend.recommendation;

import java.util.List;

public record RecommendationResponse(
        long runId, long userId, long topicId, String status, long profileId, String profileVersion,
        String modelVersion, int candidateCount, int excludedCount, List<Item> items) {

    public record Item(long itemId, long bookId, int rank, double totalScore, double topicFit,
                        double vocabularyFit, double knowledgeFit, double comprehensionFit,
                        List<String> reasons) {}
}
