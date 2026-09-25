package com.cau.capstone8.backend.integration.ml;

import java.util.List;
import java.util.Map;

/** Exact camel-case wire contract of ML POST /ml/rank for prerequisite-first v2. */
final class MlRankV2HttpContract {
    private MlRankV2HttpContract() {
    }

    record RankRequest(
            String rankingModel,
            ReaderProfile readerProfile,
            List<CandidateBook> candidateBooks,
            int limit,
            String bookId) {
    }

    record ReaderProfile(
            long userId,
            String topicId,
            double vocabulary,
            double backgroundKnowledge,
            double comprehension,
            List<ConceptReadiness> conceptReadiness,
            String profileVersion,
            String configVersion,
            String configHash) {
    }

    record ConceptReadiness(String conceptId, double score) {
    }

    record Concept(String concept, double weight) {
    }

    record CandidateBook(
            String bookId,
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
    }

    record RankResponse(
            Long userId,
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
    }

    record Item(
            String bookId,
            Integer rank,
            String availabilityStatus,
            Double prerequisiteReadiness,
            Integer prerequisiteAssessedCount,
            Integer prerequisiteTotalCount,
            Double prerequisiteCoverage,
            Double directLearningOpportunity,
            Integer directAssessedCount,
            Integer directTotalCount,
            Double directCoverage,
            List<String> coveredConcepts,
            List<String> inferredPrerequisites,
            List<String> reasons,
            String modelVersion,
            String bookFeatureVersion,
            String bookConfigVersion,
            String bookConfigHash) {
    }

    record Diagnostics(
            Integer requestedLimit,
            Integer returnedCount,
            Integer topicCandidateCount,
            Integer personalizableCount,
            Integer conceptOnlyCount,
            Integer evidenceUnavailableCount,
            Integer fallbackCount,
            Integer personalizedCandidateShortage) {
    }

    static RankRequest toWire(MlRankV2Request request) {
        var reader = request.reader();
        return new RankRequest(
                MlRankV2Request.MODEL,
                new ReaderProfile(
                        request.userId(), reader.topicId(), reader.vocabulary(),
                        reader.backgroundKnowledge(), reader.comprehension(),
                        reader.conceptReadiness().stream()
                                .map(item -> new ConceptReadiness(item.conceptId(), item.score()))
                                .toList(),
                        reader.profileVersion(), reader.configVersion(), reader.configHash()),
                request.candidates().stream().map(candidate -> new CandidateBook(
                        candidate.mlBookId(), candidate.topicDistribution(),
                        concepts(candidate.coveredConcepts()),
                        concepts(candidate.prerequisiteConcepts()),
                        candidate.lexicalDifficulty(), candidate.syntacticComplexity(),
                        candidate.conceptDensity(), candidate.prerequisiteDemand(),
                        candidate.featureVersion(), candidate.configVersion(), candidate.configHash()))
                        .toList(),
                request.limit(), request.targetBookId());
    }

    static MlRankV2Result fromWire(RankResponse response) {
        if (response == null || response.userId() == null || response.items() == null
                || response.diagnostics() == null) {
            throw new IllegalArgumentException("incomplete ML rank-v2 response");
        }
        List<MlRankV2Result.Item> items = response.items().stream().map(item -> {
            if (item == null || item.rank() == null || item.prerequisiteReadiness() == null
                    || item.prerequisiteAssessedCount() == null
                    || item.prerequisiteTotalCount() == null || item.prerequisiteCoverage() == null
                    || item.directAssessedCount() == null || item.directTotalCount() == null
                    || item.directCoverage() == null) {
                throw new IllegalArgumentException("incomplete ML rank-v2 item");
            }
            return new MlRankV2Result.Item(
                    item.bookId(), item.rank(), item.availabilityStatus(),
                    item.prerequisiteReadiness(), item.prerequisiteAssessedCount(),
                    item.prerequisiteTotalCount(), item.prerequisiteCoverage(),
                    item.directLearningOpportunity(), item.directAssessedCount(),
                    item.directTotalCount(), item.directCoverage(), item.coveredConcepts(),
                    item.inferredPrerequisites(), item.reasons(), item.modelVersion(),
                    item.bookFeatureVersion(), item.bookConfigVersion(), item.bookConfigHash());
        }).toList();
        Diagnostics diagnostics = response.diagnostics();
        if (diagnostics.requestedLimit() == null || diagnostics.returnedCount() == null
                || diagnostics.topicCandidateCount() == null
                || diagnostics.personalizableCount() == null
                || diagnostics.conceptOnlyCount() == null
                || diagnostics.evidenceUnavailableCount() == null
                || diagnostics.fallbackCount() == null
                || diagnostics.personalizedCandidateShortage() == null) {
            throw new IllegalArgumentException("incomplete ML rank-v2 diagnostics");
        }
        return new MlRankV2Result(
                response.userId(), response.topicId(), items,
                new MlRankV2Result.Diagnostics(
                        diagnostics.requestedLimit(), diagnostics.returnedCount(),
                        diagnostics.topicCandidateCount(), diagnostics.personalizableCount(),
                        diagnostics.conceptOnlyCount(), diagnostics.evidenceUnavailableCount(),
                        diagnostics.fallbackCount(), diagnostics.personalizedCandidateShortage()),
                response.modelVersion(), response.configVersion(), response.configHash(),
                response.conceptGraphVersion(), response.conceptGraphHash(),
                response.graphReviewVersion(), response.graphReviewHash(),
                response.readerProfileVersion(), response.readerConfigVersion(),
                response.readerConfigHash());
    }

    private static List<Concept> concepts(List<MlRankV2Request.Concept> concepts) {
        return concepts.stream().map(item -> new Concept(item.concept(), item.weight())).toList();
    }
}
