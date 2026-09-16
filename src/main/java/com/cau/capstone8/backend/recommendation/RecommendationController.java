package com.cau.capstone8.backend.recommendation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RecommendationResponse> create(
            @RequestBody @Valid RecommendationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        RecommendationOutcome outcome = service.create(request, idempotencyKey);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.response());
    }
}
