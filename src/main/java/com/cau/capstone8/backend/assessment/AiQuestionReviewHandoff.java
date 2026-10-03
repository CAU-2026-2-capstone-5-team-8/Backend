package com.cau.capstone8.backend.assessment;

/** Explicit AI content review, kept separate from the legacy human review format. */
record AiQuestionReviewHandoff(
        String reviewVersion,
        String reviewerType,
        String reviewerName,
        String validationScope,
        QuestionReviewJudgment review) {
    AiQuestionReviewHandoff {
        if (!"ai-question-review-v1".equals(reviewVersion)
                || !"ai".equals(reviewerType)
                || !"content-only".equals(validationScope)) {
            throw new QuestionImportException("AI review version, type or scope is invalid");
        }
        if (reviewerName == null || reviewerName.isBlank()
                || !reviewerName.equals(reviewerName.strip()) || review == null) {
            throw new QuestionImportException("AI review requires a reviewer name and judgment");
        }
    }
}
