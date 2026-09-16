package com.cau.capstone8.backend.recommendation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService recommendations;
    private final FeedbackService feedback;

    public RecommendationController(
            RecommendationService recommendations, FeedbackService feedback) {
        this.recommendations = recommendations;
        this.feedback = feedback;
    }

    @PostMapping
    public ResponseEntity<RecommendationResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String requestKey,
            @RequestBody @Valid RecommendationCreateRequest request) {
        RecommendationService.Created created = recommendations.create(request, requestKey);
        if (!created.newlyCreated()) {
            return ResponseEntity.ok(created.response());
        }
        return ResponseEntity.created(URI.create("/api/recommendations/" + created.response().id()))
                .body(created.response());
    }

    @GetMapping("/{runId}")
    public RecommendationResponse get(@PathVariable @Positive long runId) {
        return recommendations.get(runId);
    }

    @PostMapping("/{itemId}/feedback")
    public ResponseEntity<FeedbackResponse> feedback(
            @PathVariable @Positive long itemId,
            @RequestBody @Valid FeedbackRequest request) {
        FeedbackService.Saved saved = feedback.save(itemId, request);
        if (!saved.newlyCreated()) {
            return ResponseEntity.ok(saved.response());
        }
        return ResponseEntity.created(URI.create("/api/recommendations/"
                        + itemId + "/feedback"))
                .body(saved.response());
    }
}
