package com.cau.capstone8.backend.profile;

import java.time.OffsetDateTime;
import java.util.Map;

public record ReaderProfileResponse(
        long id,
        long sessionId,
        long userId,
        long topicId,
        double vocabulary,
        double backgroundKnowledge,
        double comprehension,
        String calculationVersion,
        Map<String, Object> evidence,
        OffsetDateTime completedAt) {
}
