package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.recommendation.ChallengeLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MlRankRequest(
        UUID requestId,
        String contractVersion,
        String profileVersion,
        double profileVocabulary,
        double profileBackgroundKnowledge,
        double profileComprehension,
        ChallengeLevel challengeLevel,
        int topK,
        List<Candidate> candidates) {

    public MlRankRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(challengeLevel, "challengeLevel");
        if (!"v1".equals(contractVersion)) {
            throw new IllegalArgumentException("contractVersion must be v1");
        }
        if (profileVersion == null || profileVersion.isBlank()
                || topK < 1 || topK > 20 || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("invalid ML rank request");
        }
        candidates = List.copyOf(candidates);
        var bookIds = new HashSet<Long>();
        for (Candidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!bookIds.add(candidate.bookId())) {
                throw new IllegalArgumentException("duplicate ML rank candidate bookId");
            }
        }
    }

    public record Candidate(
            long bookId,
            String featureVersion,
            double vocabulary,
            double knowledge,
            double comprehension,
            double topicRelevance) {

        public Candidate {
            if (bookId <= 0 || featureVersion == null || featureVersion.isBlank()) {
                throw new IllegalArgumentException("invalid ML rank candidate");
            }
        }
    }
}
