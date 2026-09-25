package com.cau.capstone8.backend.integration.ml;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MlRankV2ResponseValidator {
    private MlRankV2ResponseValidator() {
    }

    public static MlRankV2Result validate(MlRankV2Request request, MlRankV2Result result) {
        if (result == null || result.userId() != request.userId()
                || !request.reader().topicId().equals(result.topicId())
                || !MlRankV2Request.MODEL.equals(result.modelVersion())
                || !"ranking-v2-config-v1".equals(result.configVersion())
                || !MlRankV2Request.hash(result.configHash())
                || MlRankV2Request.blank(result.conceptGraphVersion())
                || !MlRankV2Request.hash(result.conceptGraphHash())
                || MlRankV2Request.blank(result.graphReviewVersion())
                || !MlRankV2Request.hash(result.graphReviewHash())
                || !request.reader().profileVersion().equals(result.readerProfileVersion())
                || !request.reader().configVersion().equals(result.readerConfigVersion())
                || !request.reader().configHash().equals(result.readerConfigHash())
                || result.items() == null || result.diagnostics() == null
                || result.items().size() > request.limit()) {
            throw invalid();
        }

        MlRankV2Result.Diagnostics d = result.diagnostics();
        int expectedLimit = request.targetBookId() == null ? request.limit() : 1;
        if (d.requestedLimit() != expectedLimit || d.returnedCount() != result.items().size()
                || d.topicCandidateCount() != request.candidates().size()
                || negative(d.personalizableCount(), d.conceptOnlyCount(),
                        d.evidenceUnavailableCount(), d.fallbackCount(),
                        d.personalizedCandidateShortage())
                || d.personalizableCount() + d.conceptOnlyCount()
                        + d.evidenceUnavailableCount() != d.topicCandidateCount()
                || d.fallbackCount() != d.conceptOnlyCount() + d.evidenceUnavailableCount()
                || d.personalizedCandidateShortage() != Math.max(0, expectedLimit - d.returnedCount())
                || (request.targetBookId() == null
                        && d.returnedCount() != Math.min(request.limit(), d.personalizableCount()))) {
            throw invalid();
        }

        Map<String, MlRankV2Request.Candidate> candidates = new LinkedHashMap<>();
        request.candidates().forEach(candidate -> candidates.put(candidate.mlBookId(), candidate));
        var ids = new HashSet<String>();
        var ranks = new HashSet<Integer>();
        for (int index = 0; index < result.items().size(); index++) {
            MlRankV2Result.Item item = result.items().get(index);
            MlRankV2Request.Candidate candidate = item == null ? null : candidates.get(item.mlBookId());
            if (candidate == null || !ids.add(item.mlBookId()) || item.rank() < 1
                    || !ranks.add(item.rank())
                    || (request.targetBookId() == null && item.rank() != index + 1)
                    || (request.targetBookId() != null
                            && (!request.targetBookId().equals(item.mlBookId())
                                    || item.rank() > d.personalizableCount()))
                    || !"personalizable".equals(item.availabilityStatus())
                    || !MlRankV2Request.score(item.prerequisiteReadiness())
                    || !counts(item.prerequisiteAssessedCount(), item.prerequisiteTotalCount(),
                            item.prerequisiteCoverage(), true)
                    || !MlRankV2Request.nullableScore(item.directLearningOpportunity())
                    || !counts(item.directAssessedCount(), item.directTotalCount(),
                            item.directCoverage(), false)
                    || (item.directAssessedCount() == 0) != (item.directLearningOpportunity() == null)
                    || item.coveredConcepts() == null || item.inferredPrerequisites() == null
                    || item.reasons() == null || item.reasons().size() < 2
                    || item.reasons().stream().anyMatch(MlRankV2Request::blank)
                    || !MlRankV2Request.MODEL.equals(item.modelVersion())
                    || !candidate.featureVersion().equals(item.bookFeatureVersion())
                    || !candidate.configVersion().equals(item.bookConfigVersion())
                    || !candidate.configHash().equals(item.bookConfigHash())) {
                throw invalid();
            }
            List<String> expectedCovered = candidate.coveredConcepts().stream()
                    .map(MlRankV2Request.Concept::concept).sorted().toList();
            if (!expectedCovered.equals(item.coveredConcepts())) {
                throw new MlGatewayException("ML_INVALID_RESPONSE",
                        "ML rank-v2 covered concepts mismatch for " + item.mlBookId()
                                + ": expected=" + expectedCovered + ", actual=" + item.coveredConcepts());
            }
        }
        return result;
    }

    private static boolean counts(int assessed, int total, double coverage, boolean requiresAssessed) {
        return total >= 1 && assessed >= (requiresAssessed ? 1 : 0) && assessed <= total
                && MlRankV2Request.score(coverage)
                && Math.abs(coverage - (double) assessed / total) < 1e-9;
    }

    private static boolean negative(int... values) {
        for (int value : values) if (value < 0) return true;
        return false;
    }

    private static MlGatewayException invalid() {
        return new MlGatewayException(
                "ML_INVALID_RESPONSE", "ML 랭킹 응답이 v2 계약과 일치하지 않습니다.");
    }
}
