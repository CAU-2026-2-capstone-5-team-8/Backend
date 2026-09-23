package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ml.mode", havingValue = "stub", matchIfMissing = true)
public class StubMlGateway implements MlGateway {
    private static final String CALCULATION_VERSION = "stub-profile-v1";

    @Override
    public MlProfileResult calculateProfile(MlProfileRequest request) {
        Map<MeasurementArea, Integer> totals = new EnumMap<>(MeasurementArea.class);
        Map<MeasurementArea, Integer> known = new EnumMap<>(MeasurementArea.class);
        for (MeasurementArea area : MeasurementArea.values()) {
            totals.put(area, 0);
            known.put(area, 0);
        }
        for (MlProfileRequest.Answer answer : request.answers()) {
            totals.compute(answer.measurementArea(), (ignored, count) -> count + 1);
            if (answer.knowsConcept()) {
                known.compute(answer.measurementArea(), (ignored, count) -> count + 1);
            }
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("method", "known_response_ratio");
        evidence.put("contractVersion", request.contractVersion());
        evidence.put("requestId", request.requestId().toString());
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (MeasurementArea area : MeasurementArea.values()) {
            dimensions.put(area.name(), Map.of("known", known.get(area), "total", totals.get(area)));
        }
        evidence.put("dimensions", dimensions);

        return new MlProfileResult(
                request.requestId(),
                request.contractVersion(),
                CALCULATION_VERSION,
                ratio(known, totals, MeasurementArea.VOCABULARY),
                ratio(known, totals, MeasurementArea.BACKGROUND_KNOWLEDGE),
                ratio(known, totals, MeasurementArea.COMPREHENSION),
                Map.copyOf(totals),
                Map.copyOf(evidence));
    }

    @Override
    public MlRankResult rankBooks(MlRankRequest request) {
        List<ScoredCandidate> ranked = request.candidates().stream()
                .filter(candidate -> request.targetBookId() == null
                        || request.targetBookId().equals(candidate.bookId()))
                .map(candidate -> score(request, candidate))
                .sorted((left, right) -> {
                    int scoreOrder = Double.compare(right.score(), left.score());
                    return scoreOrder != 0
                            ? scoreOrder
                            : Long.compare(left.candidate().bookId(), right.candidate().bookId());
                })
                .limit(request.targetBookId() == null ? request.limit() : 1)
                .toList();
        List<MlRankResult.Item> items = java.util.stream.IntStream.range(0, ranked.size())
                .mapToObj(index -> ranked.get(index).toResult(index + 1))
                .toList();
        return new MlRankResult(
                request.requestId(), request.contractVersion(), "stub-rank-v1", items);
    }

    private ScoredCandidate score(MlRankRequest request, MlRankRequest.Candidate candidate) {
        double offset = request.challengeLevel().getOffset();
        double vocabularyFit = fit(candidate.vocabulary(), request.reader().vocabulary(), offset);
        double knowledgeFit = fit(
                candidate.knowledge(), request.reader().backgroundKnowledge(), offset);
        double comprehensionFit = fit(
                candidate.comprehension(), request.reader().comprehension(), offset);
        double total = (candidate.topicRelevance() + vocabularyFit
                + knowledgeFit + comprehensionFit) / 4.0;
        List<String> reasons = List.of(
                String.format(Locale.ROOT, "주제 적합도는 %.2f입니다.", candidate.topicRelevance()),
                String.format(Locale.ROOT, "어휘 준비도 적합도는 %.2f입니다.", vocabularyFit),
                String.format(Locale.ROOT, "배경지식 적합도는 %.2f입니다.", knowledgeFit),
                String.format(Locale.ROOT, "독해 준비도 적합도는 %.2f입니다.", comprehensionFit));
        return new ScoredCandidate(
                candidate, total, vocabularyFit, knowledgeFit, comprehensionFit, reasons);
    }

    private double fit(double requirement, double readiness, double offset) {
        double target = Math.max(0, Math.min(1, readiness + offset));
        return 1 - Math.abs(requirement - target);
    }

    private double ratio(
            Map<MeasurementArea, Integer> known,
            Map<MeasurementArea, Integer> totals,
            MeasurementArea area) {
        return (double) known.get(area) / totals.get(area);
    }

    private record ScoredCandidate(
            MlRankRequest.Candidate candidate,
            double score,
            double vocabularyFit,
            double knowledgeFit,
            double comprehensionFit,
            List<String> reasons) {
        private MlRankResult.Item toResult(int rank) {
            return new MlRankResult.Item(
                    candidate.bookId(),
                    rank,
                    score,
                    candidate.topicRelevance(),
                    vocabularyFit,
                    knowledgeFit,
                    comprehensionFit,
                    reasons);
        }
    }
}
