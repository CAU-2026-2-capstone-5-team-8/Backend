package com.cau.capstone8.backend.recommendation;

// created=true means a new run was computed just now (201); false means an existing
// successful run was returned as-is for the same Idempotency-Key (200).
public record RecommendationOutcome(RecommendationResponse response, boolean created) {
}
