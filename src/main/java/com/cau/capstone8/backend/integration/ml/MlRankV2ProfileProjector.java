package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.profile.ReaderProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class MlRankV2ProfileProjector {
    private MlRankV2ProfileProjector() {
    }

    public static MlRankV2Request.Reader project(ReaderProfile profile, String mlTopicId) {
        Map<String, Object> evidence = profile.getEvidence();
        if (!"ml-http-reader-profile".equals(evidence.get("method"))) {
            throw new IllegalArgumentException("rank-v2 requires an HTTP ML reader profile");
        }
        String profileVersion = string(evidence.get("profileVersion"));
        String configVersion = string(evidence.get("configVersion"));
        String configHash = string(evidence.get("configHash"));
        if (!profile.getCalculationVersion().equals(profileVersion)
                || !MlRankV2Request.hash(configHash)) {
            throw new IllegalArgumentException("rank-v2 reader provenance is invalid");
        }
        Object raw = evidence.get("conceptReadiness");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("rank-v2 concept readiness is missing");
        }
        List<MlRankV2Request.ConceptReadiness> readiness = new ArrayList<>();
        var ids = new HashSet<String>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> item)) {
                throw new IllegalArgumentException("rank-v2 concept readiness is malformed");
            }
            String conceptId = string(item.get("conceptId"));
            if (!(item.get("score") instanceof Number number) || !ids.add(conceptId)) {
                throw new IllegalArgumentException("rank-v2 concept readiness is malformed");
            }
            readiness.add(new MlRankV2Request.ConceptReadiness(conceptId, number.doubleValue()));
        }
        return new MlRankV2Request.Reader(
                mlTopicId,
                profile.getVocabulary(),
                profile.getBackgroundKnowledge(),
                profile.getComprehension(),
                readiness,
                profileVersion,
                configVersion,
                configHash);
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("rank-v2 reader evidence is incomplete");
        }
        return text;
    }
}
