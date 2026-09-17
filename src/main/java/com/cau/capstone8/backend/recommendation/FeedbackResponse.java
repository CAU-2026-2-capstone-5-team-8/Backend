package com.cau.capstone8.backend.recommendation;

public record FeedbackResponse(
        long id,
        long recommendationItemId,
        long userId,
        boolean helpful,
        String comment) {

    public static FeedbackResponse from(Feedback feedback) {
        return new FeedbackResponse(
                feedback.getId(),
                feedback.getRecommendationItemId(),
                feedback.getUserId(),
                feedback.isHelpful(),
                feedback.getComment());
    }
}
