package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ml.mode", havingValue = "stub", matchIfMissing = true)
public class StubMlGateway implements MlGateway {
    private static final String CALCULATION_VERSION = "stub-profile-v1";
    private static final String RANK_MODEL_VERSION = "stub-rank-v1";

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

    private double ratio(
            Map<MeasurementArea, Integer> known,
            Map<MeasurementArea, Integer> totals,
            MeasurementArea area) {
        return (double) known.get(area) / totals.get(area);
    }

    @Override
    public MlRankResult rank(MlRankRequest request) {
        double targetVocabulary = clamp(request.profileVocabulary() + request.challengeLevel().offset());
        double targetKnowledge = clamp(request.profileBackgroundKnowledge() + request.challengeLevel().offset());
        double targetComprehension = clamp(request.profileComprehension() + request.challengeLevel().offset());

        record Scored(MlRankRequest.Candidate candidate, double topicFit, double vocabularyFit,
                       double knowledgeFit, double comprehensionFit, double total) {}

        List<Scored> scored = new ArrayList<>();
        for (MlRankRequest.Candidate candidate : request.candidates()) {
            double topicFit = candidate.topicRelevance();
            double vocabularyFit = 1 - Math.abs(candidate.vocabulary() - targetVocabulary);
            double knowledgeFit = 1 - Math.abs(candidate.knowledge() - targetKnowledge);
            double comprehensionFit = 1 - Math.abs(candidate.comprehension() - targetComprehension);
            double total = (topicFit + vocabularyFit + knowledgeFit + comprehensionFit) / 4.0;
            scored.add(new Scored(candidate, topicFit, vocabularyFit, knowledgeFit, comprehensionFit, total));
        }
        scored.sort(Comparator.<Scored>comparingDouble(s -> -s.total())
                .thenComparingLong(s -> s.candidate().bookId()));

        int count = Math.min(request.topK(), scored.size());
        List<MlRankResult.Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Scored s = scored.get(i);
            items.add(new MlRankResult.Item(
                    s.candidate().bookId(), i + 1, s.total(), s.topicFit(), s.vocabularyFit(),
                    s.knowledgeFit(), s.comprehensionFit(), reasons(s.topicFit(), s.vocabularyFit(),
                            s.knowledgeFit(), s.comprehensionFit())));
        }
        return new MlRankResult(request.requestId(), request.contractVersion(), RANK_MODEL_VERSION, items);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private List<String> reasons(double topicFit, double vocabularyFit, double knowledgeFit,
                                  double comprehensionFit) {
        record Component(String label, double value) {}
        List<Component> components = new ArrayList<>(List.of(
                new Component("분야 관련도", topicFit),
                new Component("어휘 적합도", vocabularyFit),
                new Component("배경지식 적합도", knowledgeFit),
                new Component("독해 적합도", comprehensionFit)));
        components.sort(Comparator.comparingDouble(Component::value).reversed());
        return components.stream().limit(2)
                .map(c -> String.format("%s가 %.2f로 높습니다.", c.label(), c.value()))
                .toList();
    }
}
