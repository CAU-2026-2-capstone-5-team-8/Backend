package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire format of the Python service's {@code POST /ml/reader-profile} (bookmatch_ml
 * integration/schemas.py). The service rejects unknown fields, does not echo requestId or
 * contractVersion, grades difficulty as easy/medium/hard, and reports per-dimension response
 * counts inside dimensionDetails, so this class translates both directions and the shared
 * {@link MlProfileResponseValidator} still runs on the translated result.
 */
final class MlProfileHttpContract {
    static final String EVIDENCE_METHOD = "ml-http-reader-profile";
    private static final Pattern CONFIG_HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private MlProfileHttpContract() {
    }

    record ProfileRequest(long userId, String assessmentId, String topicId, List<Response> responses) {
    }

    record Response(
            String questionId,
            String topicId,
            String conceptId,
            String questionType,
            String difficulty,
            boolean correct) {
    }

    record ProfileResponse(
            Long userId,
            String assessmentId,
            String topicId,
            Double vocabulary,
            Double backgroundKnowledge,
            Double comprehension,
            List<DimensionDetail> dimensionDetails,
            List<ConceptReadiness> conceptReadiness,
            Integer responseCount,
            String profileVersion,
            String configVersion,
            String configHash) {
    }

    record DimensionDetail(
            String questionType,
            Double score,
            Integer responseCount,
            Double earnedWeight,
            Double availableWeight) {
    }

    record ConceptReadiness(
            String conceptId,
            Double score,
            Integer responseCount,
            Double earnedWeight,
            Double availableWeight) {
    }

    static ProfileRequest toWire(MlProfileRequest request) {
        List<Response> responses = request.answers().stream()
                .map(answer -> new Response(
                        answer.questionId(),
                        request.topicId(),
                        answer.conceptId(),
                        questionType(answer.measurementArea()),
                        difficultyBand(answer.difficulty()),
                        answer.knowsConcept()))
                .toList();
        return new ProfileRequest(
                request.userId(), request.assessmentId(), request.topicId(), responses);
    }

    /** Backend difficulty 1-5 to the ML reader config bands (1-2 easy, 3 medium, 4-5 hard). */
    static String difficultyBand(int difficulty) {
        return switch (difficulty) {
            case 1, 2 -> "easy";
            case 3 -> "medium";
            case 4, 5 -> "hard";
            default -> throw new IllegalArgumentException("difficulty must be 1 to 5");
        };
    }

    static String questionType(MeasurementArea area) {
        return area.name().toLowerCase(Locale.ROOT);
    }

    /** Translate a decoded response, throwing IllegalArgumentException on any contract mismatch. */
    static MlProfileResult fromWire(MlProfileRequest request, ProfileResponse response, JsonMapper json) {
        if (response == null
                || response.userId() == null || response.userId() != request.userId()
                || !request.assessmentId().equals(response.assessmentId())
                || !request.topicId().equals(response.topicId())
                || response.vocabulary() == null
                || response.backgroundKnowledge() == null
                || response.comprehension() == null
                || response.responseCount() == null
                || response.responseCount() != request.answers().size()
                || blank(response.profileVersion())
                || blank(response.configVersion())
                || response.configHash() == null
                || !CONFIG_HASH.matcher(response.configHash()).matches()
                || response.dimensionDetails() == null
                || response.conceptReadiness() == null) {
            throw new IllegalArgumentException("ML profile response does not match the contract");
        }

        Map<MeasurementArea, Integer> dimensionCounts = new EnumMap<>(MeasurementArea.class);
        List<Map<String, Object>> dimensionDetails = new ArrayList<>();
        for (DimensionDetail detail : response.dimensionDetails()) {
            if (detail == null || detail.questionType() == null || detail.responseCount() == null) {
                throw new IllegalArgumentException("ML profile dimension detail is incomplete");
            }
            MeasurementArea area = MeasurementArea.valueOf(
                    detail.questionType().toUpperCase(Locale.ROOT));
            if (dimensionCounts.put(area, detail.responseCount()) != null) {
                throw new IllegalArgumentException("duplicate ML profile dimension");
            }
            dimensionDetails.add(json.convertValue(detail, OBJECT_MAP));
        }
        List<Map<String, Object>> conceptReadiness = response.conceptReadiness().stream()
                .map(concept -> json.convertValue(concept, OBJECT_MAP))
                .toList();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("method", EVIDENCE_METHOD);
        evidence.put("profileVersion", response.profileVersion());
        evidence.put("configVersion", response.configVersion());
        evidence.put("configHash", response.configHash());
        evidence.put("dimensionDetails", dimensionDetails);
        evidence.put("conceptReadiness", conceptReadiness);

        return new MlProfileResult(
                request.requestId(),
                request.contractVersion(),
                response.profileVersion(),
                response.vocabulary(),
                response.backgroundKnowledge(),
                response.comprehension(),
                Map.copyOf(dimensionCounts),
                Map.copyOf(evidence));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
