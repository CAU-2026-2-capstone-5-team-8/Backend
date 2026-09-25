package com.cau.capstone8.backend.integration.ml;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MlRankV2Request(
        UUID requestId,
        long userId,
        Reader reader,
        List<Candidate> candidates,
        String targetBookId,
        int limit) {
    public static final String MODEL = "rank-prerequisite-first-v2";

    public MlRankV2Request {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reader, "reader");
        if (userId <= 0 || candidates == null || candidates.isEmpty() || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("invalid ML rank-v2 request");
        }
        candidates = List.copyOf(candidates);
        var ids = new HashSet<String>();
        for (Candidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!ids.add(candidate.mlBookId())) {
                throw new IllegalArgumentException("duplicate ML rank-v2 candidate bookId");
            }
        }
        if (targetBookId != null && !ids.contains(targetBookId)) {
            throw new IllegalArgumentException("rank-v2 target must be a candidate");
        }
    }

    public record Reader(
            String topicId,
            double vocabulary,
            double backgroundKnowledge,
            double comprehension,
            List<ConceptReadiness> conceptReadiness,
            String profileVersion,
            String configVersion,
            String configHash) {
        public Reader {
            if (blank(topicId) || !score(vocabulary) || !score(backgroundKnowledge)
                    || !score(comprehension) || conceptReadiness == null
                    || blank(profileVersion) || blank(configVersion) || !hash(configHash)) {
                throw new IllegalArgumentException("invalid ML rank-v2 reader");
            }
            conceptReadiness = List.copyOf(conceptReadiness);
            var ids = new HashSet<String>();
            for (ConceptReadiness readiness : conceptReadiness) {
                if (readiness == null || !ids.add(readiness.conceptId())) {
                    throw new IllegalArgumentException("invalid ML rank-v2 concept readiness");
                }
            }
        }
    }

    public record ConceptReadiness(String conceptId, double score) {
        public ConceptReadiness {
            if (blank(conceptId) || !MlRankV2Request.score(score)) {
                throw new IllegalArgumentException("invalid concept readiness");
            }
        }
    }

    public record Concept(String concept, double weight) {
        public Concept {
            if (blank(concept) || !score(weight)) {
                throw new IllegalArgumentException("invalid book concept");
            }
        }
    }

    public record Candidate(
            long projectionId,
            long backendBookId,
            String mlBookId,
            Map<String, Double> topicDistribution,
            List<Concept> coveredConcepts,
            List<Concept> prerequisiteConcepts,
            Double lexicalDifficulty,
            Double syntacticComplexity,
            Double conceptDensity,
            Double prerequisiteDemand,
            String featureVersion,
            String configVersion,
            String configHash) {
        public Candidate {
            if (projectionId <= 0 || backendBookId <= 0 || blank(mlBookId)
                    || topicDistribution == null || topicDistribution.isEmpty()
                    || coveredConcepts == null || prerequisiteConcepts == null
                    || !nullableScore(lexicalDifficulty) || !nullableScore(syntacticComplexity)
                    || !nullableScore(conceptDensity) || !nullableScore(prerequisiteDemand)
                    || blank(featureVersion) || blank(configVersion) || !hash(configHash)) {
                throw new IllegalArgumentException("invalid ML rank-v2 candidate");
            }
            topicDistribution = Map.copyOf(topicDistribution);
            coveredConcepts = List.copyOf(coveredConcepts);
            prerequisiteConcepts = List.copyOf(prerequisiteConcepts);
        }
    }

    static boolean score(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    static boolean nullableScore(Double value) {
        return value == null || score(value);
    }

    static boolean hash(String value) {
        return value != null && value.matches("^sha256:[0-9a-f]{64}$");
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
