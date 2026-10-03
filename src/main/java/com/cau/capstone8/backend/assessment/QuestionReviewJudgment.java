package com.cau.capstone8.backend.assessment;

import java.util.Set;
import java.util.regex.Pattern;

record QuestionReviewJudgment(
        String generatedQuestionId,
        String status,
        boolean correct,
        int conceptAlignment,
        boolean difficultyAppropriate,
        int distractorQuality,
        int explanationQuality,
        String notes) {
    private static final Pattern GENERATED_ID = Pattern.compile("^gq_[0-9a-f]{32}$");
    private static final Set<String> STATUSES = Set.of("approve", "reject", "needs_revision");

    QuestionReviewJudgment {
        if (generatedQuestionId == null || !GENERATED_ID.matcher(generatedQuestionId).matches()) {
            throw new QuestionImportException("review generated_question_id has an invalid format");
        }
        if (!STATUSES.contains(status)) {
            throw new QuestionImportException("review status is invalid");
        }
        range(conceptAlignment, "concept_alignment");
        range(distractorQuality, "distractor_quality");
        range(explanationQuality, "explanation_quality");
        if (notes == null) {
            throw new QuestionImportException("review notes must not be null");
        }
    }

    private static void range(int value, String field) {
        if (value < 1 || value > 5) {
            throw new QuestionImportException(field + " must be between 1 and 5");
        }
    }
}
