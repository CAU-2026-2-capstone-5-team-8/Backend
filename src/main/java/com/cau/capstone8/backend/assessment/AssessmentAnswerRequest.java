package com.cau.capstone8.backend.assessment;

import jakarta.validation.constraints.NotNull;

public record AssessmentAnswerRequest(@NotNull Boolean knowsConcept) {}
