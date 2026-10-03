package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConceptQuestionSelectionTest {
    Question question(long id, MeasurementArea area, String concept, String operation) {
        Question q = Question.generated(1L, area, 2, null, "synthetic prompt", concept,
                "synthetic-v1", "q" + id, "spec" + id, List.of("A", "B", "C", "D"), 0,
                "synthetic explanation", "sha256:" + "a".repeat(64),
                operation == null ? Map.of() : Map.of("cognitiveOperation", operation));
        ReflectionTestUtils.setField(q, "id", id);
        return q;
    }
    @Test void choosesDiverseConceptAbilitiesBeforeRepeatingOneCell() {
        var bank = List.of(
                question(1, MeasurementArea.VOCABULARY, "matrix", "recognize"),
                question(2, MeasurementArea.BACKGROUND_KNOWLEDGE, "vector", "recognize"),
                question(3, MeasurementArea.COMPREHENSION, "matrix", "apply"),
                question(4, MeasurementArea.COMPREHENSION, "matrix", "apply"),
                question(5, MeasurementArea.COMPREHENSION, "matrix", "apply"),
                question(6, MeasurementArea.COMPREHENSION, "basis", "infer"),
                question(7, MeasurementArea.VOCABULARY, "dimension", "recall"));
        assertThat(ConceptQuestionSelection.select(bank, 5).stream().map(Question::getId))
                .containsExactly(1L, 2L, 3L, 6L, 7L);
    }
    @Test void newBankBalancesAbilitiesAndAvoidsPreviouslyAnsweredItems() {
        var bank = new ArrayList<Question>();
        for (int c=0; c<6; c++) for (int a=0; a<3; a++) {
            var q = question(c*3+a+1, MeasurementArea.values()[a], "concept"+c,
                    List.of("recognize", "apply", "infer").get(a));
            ReflectionTestUtils.setField(q, "version", "generated-question-v5");
            bank.add(q);
        }
        var first = ConceptQuestionSelection.select(bank, 9);
        var exposure = new HashMap<Long, Long>();
        first.forEach(q -> exposure.put(q.getId(), 1L));
        var second = ConceptQuestionSelection.select(bank, 9, exposure);
        assertThat(first).hasSize(9);
        assertThat(second).hasSize(9).doesNotContainAnyElementsOf(first);
        for (String operation : List.of("recognize", "apply", "infer")) {
            assertThat(second.stream().filter(q -> operation.equals(q.getUpstreamProvenance().get("cognitiveOperation"))).count()).isEqualTo(3);
        }
        assertThat(second.stream().map(Question::getConceptId).distinct().count()).isEqualTo(6);
    }
    @Test void doesNotGuessMissingOperationFromLegacyArea() {
        var bank = List.of(question(1, MeasurementArea.VOCABULARY, "matrix", null),
                question(2, MeasurementArea.BACKGROUND_KNOWLEDGE, "vector", "recall"),
                question(3, MeasurementArea.COMPREHENSION, "matrix", "apply"));
        assertThat(ConceptQuestionSelection.select(bank, 9)).isEmpty();
    }
}
