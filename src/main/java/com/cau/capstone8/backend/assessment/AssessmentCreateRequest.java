package com.cau.capstone8.backend.assessment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AssessmentCreateRequest(@NotNull @Positive Long userId, @NotNull @Positive Long topicId) {}
