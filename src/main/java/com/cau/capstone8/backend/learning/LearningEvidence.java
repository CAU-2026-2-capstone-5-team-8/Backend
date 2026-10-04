package com.cau.capstone8.backend.learning;

import java.net.URI;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Source references only. Never fetch a URL or treat a heading as proof of teaching. */
public record LearningEvidence(String conceptId, String evidenceId, String sourceId, String sourceUrl,
        String evidenceType, String editionRelation, List<String> tocPath, String matchingAlias,
        String matchMethod, String provenanceHash) {
    private static final JsonMapper STORED = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public LearningEvidence {
        for (String value : List.of(conceptId, evidenceId, sourceId, matchingAlias, matchMethod))
            if (value.isBlank()) throw new IllegalArgumentException("blank learning evidence field");
        if (!Set.of("toc_exact", "toc_same_work", "toc_public_web_exact", "toc_unspecified",
                "description", "document", "subject", "metadata_minimal").contains(evidenceType)
                || !Set.of("exact", "same_work", "canonical_record", "unspecified").contains(editionRelation)
                || provenanceHash == null || !provenanceHash.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid learning evidence provenance");
        if (sourceUrl != null) {
            URI uri = URI.create(sourceUrl);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("invalid evidence source URL");
        }
        if (tocPath != null) tocPath = List.copyOf(tocPath);
    }

    public static List<LearningEvidence> fromConcepts(List<Map<String, Object>> concepts) {
        List<LearningEvidence> result = new ArrayList<>();
        for (var concept : concepts) {
            if (!concept.containsKey("evidence")) continue;
            var rows = STORED.convertValue(concept.get("evidence"), new TypeReference<List<LearningEvidence>>() {});
            if (rows == null) throw new IllegalArgumentException("evidence must be an array");
            for (var row : rows) {
                if (row == null || !row.conceptId().equals(concept.get("concept")))
                    throw new IllegalArgumentException("evidence concept mismatch");
                if (!result.contains(row)) result.add(row);
            }
        }
        validateIdentities(result);
        return List.copyOf(result);
    }

    public static void validateIdentities(List<LearningEvidence> evidence) {
        Map<String, List<Object>> identities = new HashMap<>();
        for (var row : evidence) {
            // A single source may match multiple concepts/aliases, but its provenance cannot change.
            List<Object> identity = Arrays.asList(row.sourceId(), row.sourceUrl(), row.evidenceType(),
                    row.editionRelation(), row.tocPath(), row.provenanceHash());
            var previous = identities.putIfAbsent(row.evidenceId(), identity);
            if (previous != null && !previous.equals(identity))
                throw new IllegalArgumentException("conflicting evidence identity");
        }
    }
}
