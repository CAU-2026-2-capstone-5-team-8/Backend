package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.recommendation.ChallengeLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MlRankRequest(
        UUID requestId,
        String contractVersion,
        Reader reader,
        ChallengeLevel challengeLevel,
        List<Candidate> candidates,
        Long targetBookId,
        int limit) {

    public MlRankRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(challengeLevel, "challengeLevel");
        if (!"v1".equals(contractVersion) || candidates == null || candidates.isEmpty()
                || limit < 1 || limit > 20) {
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
        if (targetBookId != null && !bookIds.contains(targetBookId)) {
            throw new IllegalArgumentException("target book must be present in ML rank candidates");
        }
    }

    public record Reader(
            long profileId,
            double vocabulary,
            double backgroundKnowledge,
            double comprehension,
            String profileVersion) {
        public Reader {
            if (profileId <= 0 || !validScore(vocabulary) || !validScore(backgroundKnowledge)
                    || !validScore(comprehension) || profileVersion == null
                    || profileVersion.isBlank()) {
                throw new IllegalArgumentException("invalid ML rank reader");
            }
        }
    }

    public record Candidate(
            long featureId,
            long bookId,
            String featureVersion,
            double vocabulary,
            double knowledge,
            double comprehension,
            double topicRelevance) {
        public Candidate {
            if (featureId <= 0 || bookId <= 0 || featureVersion == null
                    || featureVersion.isBlank() || !validScore(vocabulary)
                    || !validScore(knowledge) || !validScore(comprehension)
                    || !validScore(topicRelevance)) {
                throw new IllegalArgumentException("invalid ML rank candidate");
            }
        }
    }

    private static boolean validScore(double score) {
        return Double.isFinite(score) && score >= 0 && score <= 1;
    }
}
