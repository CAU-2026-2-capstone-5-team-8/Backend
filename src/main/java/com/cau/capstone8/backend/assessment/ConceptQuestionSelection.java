package com.cau.capstone8.backend.assessment;

import java.util.*;

/** Diversify concept/ability cells instead of taking three questions per legacy area. */
public final class ConceptQuestionSelection {
    private ConceptQuestionSelection() {}
    private static final Map<String, String> ABILITIES = Map.of(
            "recognize", "meaning", "recall", "meaning", "apply", "application",
            "compare", "reasoning", "relate", "reasoning", "integrate", "reasoning", "infer", "reasoning");

    public static List<Question> select(List<Question> bank, int limit) {
        return select(bank, limit, Map.of());
    }
    public static List<Question> select(List<Question> bank, int limit, Map<Long, Long> exposure) {
        var conceptBank = bank.stream()
                .filter(q -> "generated-question-v5".equals(q.getVersion()))
                .filter(q -> q.getAnswerMode() == AnswerMode.MULTIPLE_CHOICE)
                .filter(q -> q.getConceptId() != null && ability(q) != null)
                .toList();
        if (!conceptBank.isEmpty()) return selectConceptBank(conceptBank, limit, exposure);
        List<Question> eligible = bank.stream()
                .filter(q -> q.getConceptId() != null && !q.getConceptId().isBlank())
                .filter(q -> q.getAnswerMode() == AnswerMode.SELF_REPORT || ability(q) != null)
                .sorted(Comparator.comparing((Question q) -> q.getAnswerMode() == AnswerMode.SELF_REPORT)
                        .thenComparing(Question::getId)).toList();
        List<Question> selected = new ArrayList<>();
        // One per old area keeps persisted v1 fields calculable during migration.
        // These fields do not drive the new concept recommendation or visualization.
        for (MeasurementArea area : MeasurementArea.values()) {
            var representative = eligible.stream().filter(q -> q.getMeasurementArea() == area).findFirst();
            if (representative.isEmpty()) return List.of();
            selected.add(representative.get());
        }
        while (selected.size() < limit) {
            var next = eligible.stream().filter(q -> !selected.contains(q))
                    .min(Comparator.comparingLong((Question q) -> selected.stream()
                            .filter(s -> cell(s).equals(cell(q))).count())
                            .thenComparing(q -> q.getAnswerMode() == AnswerMode.SELF_REPORT)
                            .thenComparingLong(q -> selected.stream()
                                    .filter(s -> s.getConceptId().equals(q.getConceptId())).count())
                            .thenComparing(Question::getId));
            if (next.isEmpty()) break;
            selected.add(next.get());
        }
        return List.copyOf(selected);
    }
    private static List<Question> selectConceptBank(List<Question> bank, int limit,
                                                   Map<Long, Long> exposure) {
        List<Question> selected = new ArrayList<>();
        while (selected.size() < limit) {
            var next = bank.stream().filter(q -> !selected.contains(q))
                    .min(Comparator.comparingLong((Question q) -> selected.stream()
                            .filter(s -> cell(s).equals(cell(q))).count())
                            .thenComparingLong(q -> selected.stream()
                                    .filter(s -> ability(s).equals(ability(q))).count())
                            .thenComparingLong(q -> selected.stream()
                                    .filter(s -> s.getConceptId().equals(q.getConceptId())).count())
                            .thenComparingLong(q -> exposure.getOrDefault(q.getId(), 0L))
                            .thenComparing(Question::getId));
            if (next.isEmpty()) break;
            selected.add(next.get());
        }
        return List.copyOf(selected);
    }
    private static String ability(Question question) {
        var provenance = question.getUpstreamProvenance();
        return provenance != null && provenance.get("cognitiveOperation") instanceof String operation
                ? ABILITIES.get(operation) : null;
    }
    private static String cell(Question question) {
        return question.getConceptId() + ":" + (question.getAnswerMode() == AnswerMode.SELF_REPORT
                ? "self-report" : ability(question));
    }
}
