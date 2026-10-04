package com.cau.capstone8.backend.learning;

import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validate the new explanation against the exact sent snapshot; ML still owns graph inference. */
final class LearningReadinessValidator {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private LearningReadinessValidator() {}

    static void validate(Map<String, Object> input, Map<String, Object> output) {
        JsonNode request = JSON.valueToTree(input), response = JSON.valueToTree(output);
        Map<String, JsonNode> books = index(request.path("candidateBooks"), "bookId");
        Map<String, JsonNode> observations = new HashMap<>();
        for (var row : request.path("observations"))
            if (row.path("ability").equals(request.path("ability")))
                observations.put(row.path("conceptId").asString(), row);
        for (var item : response.path("items")) {
            var book = books.get(item.path("bookId").asString());
            require(book != null);
            Set<String> covered = strings(book.path("coveredConcepts"));
            Set<String> prerequisite = strings(item.path("inferredPrerequisites"));
            require(covered.equals(strings(item.path("coveredConcepts"))));
            require(book.path("sourceArtifactVersion").equals(item.path("sourceArtifactVersion"))
                    && book.path("sourceArtifactHash").equals(item.path("sourceArtifactHash")));
            require((prerequisite.isEmpty() ? "not-established" : "observed-graph-candidates")
                    .equals(item.path("foundationStatus").asString()));
            var internal = new TreeSet<>(prerequisite); internal.retainAll(covered);
            var external = new TreeSet<>(prerequisite); external.removeAll(covered);
            require(internal.equals(strings(item.path("internalPrerequisites")))
                    && external.equals(strings(item.path("externalPrerequisites"))));
            var checklist = item.path("readingChecklist");
            require("reading-checklist-v2".equals(checklist.path("version").asString())
                    && "observed_answers_not_calibrated_mastery".equals(checklist.path("interpretation").asString())
                    && "prerequisites-first-stable-concept-id".equals(checklist.path("orderPolicy").asString())
                    && checklist.path("limitations").isArray() && !checklist.path("limitations").isEmpty());
            Map<String, JsonNode> rows = index(checklist.path("concepts"), "conceptId");
            Set<String> union = new TreeSet<>(covered); union.addAll(prerequisite);
            require(rows.keySet().equals(union));
            validateSummary(item.path("foundation"), prerequisite, rows);
            validateSummary(item.path("targets"), covered, rows);
            Set<String> seen = new HashSet<>();
            boolean gap = false, unknown = false;
            int practice = 0, unmeasured = 0;
            for (var row : checklist.path("concepts")) {
                String concept = row.path("conceptId").asString();
                var observation = observations.get(concept);
                String state;
                if (observation == null) {
                    state = "unmeasured";
                    require(row.has("responseCount") && row.path("responseCount").isNull()
                            && row.has("correctCount") && row.path("correctCount").isNull());
                } else {
                    require(row.path("responseCount").isIntegralNumber() && row.path("correctCount").isIntegralNumber()
                            && row.path("responseCount").asLong() == observation.path("responseCount").asLong()
                            && row.path("correctCount").asLong() == observation.path("correctCount").asLong());
                    state = observation.path("correctCount").asLong() == observation.path("responseCount").asLong()
                            ? "correct" : "needs-practice";
                }
                require(state.equals(row.path("state").asString())
                        && row.path("isCovered").isBoolean() && row.path("isCovered").asBoolean() == covered.contains(concept)
                        && row.path("isPrerequisite").isBoolean() && row.path("isPrerequisite").asBoolean() == prerequisite.contains(concept)
                        && "unverified".equals(row.path("teachingSufficiency").asString()));
                String action = switch (state) {
                    case "unmeasured" -> "assess-concept";
                    case "needs-practice" -> "review-concept";
                    default -> "continue-learning";
                };
                require(action.equals(row.path("nextAction").asString()));
                Set<String> deps = strings(row.path("dependsOn"));
                require(seen.containsAll(deps));
                for (String dep : deps) require(strings(rows.get(dep).path("requiredFor")).contains(concept));
                for (String dependent : strings(row.path("requiredFor")))
                    require(rows.containsKey(dependent) && strings(rows.get(dependent).path("dependsOn")).contains(concept));
                seen.add(concept);
                List<JsonNode> expectedEvidence = new ArrayList<>();
                for (var e : book.path("conceptEvidence"))
                    if (concept.equals(e.path("conceptId").asString()) && !expectedEvidence.contains(e)) expectedEvidence.add(e);
                require(row.path("evidence").isArray() && row.path("evidence").size() == expectedEvidence.size());
                Set<JsonNode> actualEvidence = new HashSet<>();
                row.path("evidence").forEach(actualEvidence::add);
                require(actualEvidence.equals(new HashSet<>(expectedEvidence)));
                if (prerequisite.contains(concept)) {
                    gap |= state.equals("needs-practice"); unknown |= state.equals("unmeasured");
                }
                if (covered.contains(concept)) {
                    if (state.equals("needs-practice")) practice++;
                    if (state.equals("unmeasured")) unmeasured++;
                }
            }
            Set<String> derived = new TreeSet<>();
            Deque<String> pending = new ArrayDeque<>(covered);
            while (!pending.isEmpty()) {
                for (String parent : strings(rows.get(pending.removeFirst()).path("dependsOn")))
                    if (derived.add(parent)) pending.addLast(parent);
            }
            require(derived.equals(prerequisite));
            require((gap ? "foundation-gap" : unknown || prerequisite.isEmpty() ? "check-first" : "ready-to-explore")
                    .equals(item.path("status").asString()));
            require(item.path("practiceConceptCount").isIntegralNumber() && item.path("practiceConceptCount").asInt() == practice
                    && item.path("unmeasuredConceptCount").isIntegralNumber() && item.path("unmeasuredConceptCount").asInt() == unmeasured
                    && item.path("reviewOnly").isBoolean() && item.path("reviewOnly").asBoolean() == (practice + unmeasured == 0));
        }
    }

    private static void validateSummary(JsonNode summary, Set<String> expected, Map<String, JsonNode> checklist) {
        var rows = index(summary, "conceptId");
        require(rows.keySet().equals(expected));
        for (var entry : rows.entrySet())
            require(entry.getValue().path("state").equals(checklist.get(entry.getKey()).path("state")));
    }

    private static Map<String, JsonNode> index(JsonNode rows, String key) {
        require(rows.isArray());
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (var row : rows) {
            require(row.path(key).isString() && !row.path(key).asString().isBlank());
            require(result.putIfAbsent(row.path(key).asString(), row) == null);
        }
        return result;
    }
    private static Set<String> strings(JsonNode rows) {
        require(rows.isArray());
        Set<String> result = new TreeSet<>();
        for (var row : rows) require(row.isString() && !row.asString().isBlank() && result.add(row.asString()));
        return result;
    }
    private static void require(boolean condition) {
        if (!condition) throw new MlGatewayException("ML_INVALID_RESPONSE", "추천 체크리스트가 진단·도서 근거와 일치하지 않습니다.");
    }
}
