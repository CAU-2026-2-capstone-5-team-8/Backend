package com.cau.capstone8.backend.recommendation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull @Positive Long userId,
        @NotNull Boolean helpful,
        @Size(max = 1000) String comment) {
}
