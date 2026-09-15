package com.cau.capstone8.backend.integration.ml;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MlProfileRequest(
        UUID requestId,
        String contractVersion,
        long userId,
        String assessmentId,
        String topicId,
        List<Answer> answers) {

    public MlProfileRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (!"v1".equals(contractVersion)) {
            throw new IllegalArgumentException("contractVersion must be v1");
        }
        if (userId <= 0 || assessmentId == null || assessmentId.isBlank()
                || topicId == null || topicId.isBlank() || answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("invalid ML profile request");
        }
        answers = List.copyOf(answers);
        var questionIds = new HashSet<String>();
        var measurementAreas = EnumSet.noneOf(MeasurementArea.class);
        for (Answer answer : answers) {
            Objects.requireNonNull(answer, "answer");
            if (!questionIds.add(answer.questionId())) {
                throw new IllegalArgumentException("duplicate ML profile questionId");
            }
            measurementAreas.add(answer.measurementArea());
        }
        if (!measurementAreas.equals(EnumSet.allOf(MeasurementArea.class))) {
            throw new IllegalArgumentException("all ML profile measurement areas are required");
        }
    }

    public record Answer(
            String questionId,
            MeasurementArea measurementArea,
            int difficulty,
            boolean knowsConcept,
            double points) {

        public Answer {
            if (questionId == null || questionId.isBlank() || measurementArea == null
                    || difficulty < 1 || difficulty > 5 || !Double.isFinite(points) || points <= 0) {
                throw new IllegalArgumentException("invalid ML profile answer");
            }
        }
    }
}
