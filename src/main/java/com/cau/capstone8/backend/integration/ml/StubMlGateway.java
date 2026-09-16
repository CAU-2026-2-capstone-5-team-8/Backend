package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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

    private double ratio(
            Map<MeasurementArea, Integer> known,
            Map<MeasurementArea, Integer> totals,
            MeasurementArea area) {
        return (double) known.get(area) / totals.get(area);
    }
}
