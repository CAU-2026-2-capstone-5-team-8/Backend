package com.cau.capstone8.backend.integration.ml;

import java.util.*;

/** Explicit allowlist of the reader-depth-evidence-v1 response; never proxies arbitrary JSON. */
public record MlReaderDiagnostics(
        Long userId, String assessmentId, String topicId, Integer responseCount,
        Integer untaggedResponseCount, List<Concept> concepts, String diagnosticVersion,
        String profileVersion, String configVersion, String configHash, List<String> limitations) {
    public record Concept(String conceptId, Double observedScore, Integer responseCount,
            List<Evidence> evidence, Boolean fullyObserved, NextCheck nextCheck) {}
    public record Evidence(String questionType, String difficulty, Integer responseCount,
            Double score, Integer fullCreditCount, List<String> questionIds) {}
    public record NextCheck(String questionType, String difficulty, String reason) {}

    static MlReaderDiagnostics validate(MlProfileRequest request, MlReaderDiagnostics result) {
        require(result != null);
        require(Objects.equals(result.userId, request.userId())
                && Objects.equals(result.assessmentId, request.assessmentId())
                && Objects.equals(result.topicId, request.topicId())
                && Objects.equals(result.responseCount, request.answers().size())
                && "reader-depth-evidence-v1".equals(result.diagnosticVersion)
                && notBlank(result.profileVersion) && notBlank(result.configVersion)
                && result.configHash != null && result.configHash.matches("sha256:[0-9a-f]{64}")
                && result.concepts != null && result.limitations != null && !result.limitations.isEmpty()
                && result.limitations.stream().allMatch(MlReaderDiagnostics::notBlank));
        var wire = MlProfileHttpContract.toWire(request);
        var expected = new TreeMap<String, List<MlProfileHttpContract.Response>>();
        int untagged = 0;
        for (var answer : wire.responses()) {
            if (answer.conceptId() == null) untagged++;
            else expected.computeIfAbsent(answer.conceptId().trim(), k -> new ArrayList<>()).add(answer);
        }
        require(Objects.equals(result.untaggedResponseCount, untagged));
        var seen = new HashSet<String>();
        for (var concept : result.concepts) {
            require(concept != null && seen.add(concept.conceptId()) && expected.containsKey(concept.conceptId()));
            var responses = expected.get(concept.conceptId());
            require(unit(concept.observedScore()) && Objects.equals(concept.responseCount(), responses.size())
                    && concept.evidence() != null && concept.evidence().size() == 9);
            int index = 0;
            boolean fullyObserved = true;
            NextCheck review = null;
            NextCheck missing = null;
            for (String difficulty : List.of("easy", "medium", "hard")) {
                for (String type : List.of("vocabulary", "background_knowledge", "comprehension")) {
                    var cell = concept.evidence().get(index++);
                    var rows = responses.stream().filter(r -> r.difficulty().equals(difficulty)
                            && r.questionType().equals(type)).toList();
                    int count = rows.size();
                    int correct = (int) rows.stream().filter(MlProfileHttpContract.Response::correct).count();
                    var ids = rows.stream().map(MlProfileHttpContract.Response::questionId).sorted().toList();
                    require(cell != null && type.equals(cell.questionType()) && difficulty.equals(cell.difficulty())
                            && Objects.equals(cell.responseCount(), count)
                            && Objects.equals(cell.fullCreditCount(), correct)
                            && ids.equals(cell.questionIds()));
                    require(count == 0 ? cell.score() == null
                            : unit(cell.score()) && Math.abs(cell.score() - (double) correct / count) < 1e-9);
                    if (count == 0) {
                        fullyObserved = false;
                        if (missing == null) missing = new NextCheck(type, difficulty, "unassessed");
                    } else if (correct < count && review == null) {
                        review = new NextCheck(type, difficulty, "review_observed_gap");
                    }
                }
            }
            require(Objects.equals(concept.fullyObserved(), fullyObserved)
                    && Objects.equals(concept.nextCheck(), review != null ? review : missing));
        }
        require(seen.equals(expected.keySet()));
        return result;
    }

    private static boolean unit(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 1;
    }
    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid reader diagnostics contract");
    }
}
