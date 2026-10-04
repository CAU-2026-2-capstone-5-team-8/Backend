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
            String conceptId,
            int difficulty,
            boolean correct,
            double points,
            String cognitiveOperation,
            String answerMode,
            String measurementContext) {

        public Answer(String questionId, MeasurementArea measurementArea, String conceptId,
                      int difficulty, boolean correct, double points) {
            this(questionId, measurementArea, conceptId, difficulty, correct, points, null, null, null);
        }

        public Answer(String questionId, MeasurementArea measurementArea, String conceptId,
                      int difficulty, boolean correct, double points,
                      String cognitiveOperation, String answerMode) {
            this(questionId, measurementArea, conceptId, difficulty, correct, points,
                    cognitiveOperation, answerMode, null);
        }

        public Answer {
            if (questionId == null || questionId.isBlank() || measurementArea == null
                    || (conceptId != null && conceptId.isBlank())
                    || difficulty < 1 || difficulty > 5 || !Double.isFinite(points) || points <= 0) {
                throw new IllegalArgumentException("invalid ML profile answer");
            }
            if (cognitiveOperation != null && !java.util.Set.of(
                    "recognize", "recall", "compare", "relate", "apply", "integrate", "infer")
                    .contains(cognitiveOperation)) {
                throw new IllegalArgumentException("unsupported cognitive operation");
            }
            if (measurementContext != null && !java.util.Set.of(
                    "prior-knowledge", "provided-information").contains(measurementContext)) {
                throw new IllegalArgumentException("unsupported measurement context");
            }
            if (answerMode != null && !java.util.Set.of("MULTIPLE_CHOICE", "SELF_REPORT")
                    .contains(answerMode)) {
                throw new IllegalArgumentException("unsupported answer mode");
            }
        }
    }
}
