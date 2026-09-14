package com.cau.capstone8.backend.assessment;

import java.util.List;

public record AssessmentResponse(long id, long userId, long topicId, String status, List<IssuedQuestion> questions) {
    public record IssuedQuestion(long id, int orderIndex, String measurementArea, String prompt, Boolean knowsConcept) {}
}
