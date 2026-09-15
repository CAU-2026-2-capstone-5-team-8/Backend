package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.profile.ReaderProfileResponse;

public record AssessmentCompletionResponse(
        long id,
        long userId,
        long topicId,
        String status,
        ReaderProfileResponse profile) {
}
