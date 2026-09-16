package com.cau.capstone8.backend.integration.ml;

import java.util.List;
import java.util.UUID;

public record MlRankResult(
        UUID requestId,
        String contractVersion,
        String modelVersion,
        List<Item> items) {

    public record Item(
            long bookId,
            int rank,
            double totalScore,
            double topicFit,
            double vocabularyFit,
            double knowledgeFit,
            double comprehensionFit,
            List<String> reasons) {
    }
}
