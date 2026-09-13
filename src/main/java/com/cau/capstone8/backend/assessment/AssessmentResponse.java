package com.cau.capstone8.backend.assessment;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record AssessmentResponse(long id, long userId, long topicId, String status, List<IssuedQuestion> questions) {
    public record IssuedQuestion(long id, int orderIndex, String prompt, JsonNode options) {}
}
