package com.cau.capstone8.backend.integration.ml;

import java.util.HashSet;
import java.util.Set;

public final class MlRankResponseValidator {
    private MlRankResponseValidator() {
    }

    public static MlRankResult validate(MlRankRequest request, MlRankResult result) {
        if (result == null || !request.requestId().equals(result.requestId())
                || !request.contractVersion().equals(result.contractVersion())
                || result.modelVersion() == null || result.modelVersion().isBlank()
                || result.modelVersion().length() > 80
                || result.items() == null) {
            throw invalidResponse();
        }

        int expectedCount = request.targetBookId() == null
                ? Math.min(request.limit(), request.candidates().size())
                : 1;
        if (result.items().size() != expectedCount) {
            throw invalidResponse();
        }
        Set<Long> requestedBooks = request.candidates().stream()
                .map(MlRankRequest.Candidate::bookId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> returnedBooks = new HashSet<>();
        for (int index = 0; index < result.items().size(); index++) {
            MlRankResult.Item item = result.items().get(index);
            if (item == null || item.rank() != index + 1
                    || !requestedBooks.contains(item.bookId())
                    || !returnedBooks.add(item.bookId())
                    || !validScore(item.score()) || !validScore(item.topicFit())
                    || !validScore(item.vocabularyFit()) || !validScore(item.knowledgeFit())
                    || !validScore(item.comprehensionFit()) || item.reasons() == null
                    || item.reasons().size() < 2
                    || item.reasons().stream().anyMatch(
                            reason -> reason == null || reason.isBlank())) {
                throw invalidResponse();
            }
        }
        if (request.targetBookId() != null
                && !request.targetBookId().equals(result.items().getFirst().bookId())) {
            throw invalidResponse();
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
