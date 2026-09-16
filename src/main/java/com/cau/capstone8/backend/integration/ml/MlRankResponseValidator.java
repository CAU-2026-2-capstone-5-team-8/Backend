package com.cau.capstone8.backend.integration.ml;

import java.util.HashSet;
import java.util.Set;

public final class MlRankResponseValidator {
    private MlRankResponseValidator() {}

    public static MlRankResult validate(MlRankRequest request, MlRankResult result) {
        if (result == null
                || !request.requestId().equals(result.requestId())
                || !request.contractVersion().equals(result.contractVersion())
                || result.modelVersion() == null || result.modelVersion().isBlank()
                || result.items() == null) {
            throw invalidResponse();
        }

        Set<Long> requestedBookIds = new HashSet<>();
        for (MlRankRequest.Candidate candidate : request.candidates()) {
            requestedBookIds.add(candidate.bookId());
        }
        int expectedCount = Math.min(request.topK(), request.candidates().size());
        if (result.items().size() != expectedCount) {
            throw invalidResponse();
        }

        Set<Long> seenBookIds = new HashSet<>();
        Set<Integer> seenRanks = new HashSet<>();
        for (MlRankResult.Item item : result.items()) {
            if (!requestedBookIds.contains(item.bookId()) || !seenBookIds.add(item.bookId())) {
                throw invalidResponse();
            }
            if (item.rank() < 1 || item.rank() > expectedCount || !seenRanks.add(item.rank())) {
                throw invalidResponse();
            }
            if (!validScore(item.totalScore()) || !validScore(item.topicFit())
                    || !validScore(item.vocabularyFit()) || !validScore(item.knowledgeFit())
                    || !validScore(item.comprehensionFit())) {
                throw invalidResponse();
            }
            if (item.reasons() == null || item.reasons().size() < 2
                    || item.reasons().stream().anyMatch(reason -> reason == null || reason.isBlank())) {
                throw invalidResponse();
            }
        }
        return result;
    }

    private static boolean validScore(double score) {
        return Double.isFinite(score) && score >= 0 && score <= 1;
    }

    private static MlGatewayException invalidResponse() {
        return new MlGatewayException("ML_INVALID_RESPONSE", "ML 랭킹 응답이 계약과 일치하지 않습니다.");
    }
}
