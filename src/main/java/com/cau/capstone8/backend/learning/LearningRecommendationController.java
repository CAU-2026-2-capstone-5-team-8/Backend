package com.cau.capstone8.backend.learning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning-recommendations")
public class LearningRecommendationController {
    private final LearningRecommendationService service;
    public LearningRecommendationController(LearningRecommendationService service) { this.service = service; }

    public record Request(@Positive long userId, @Positive long topicId, @Positive long profileId,
                          @NotNull Ability ability, @Min(1) @Max(20) int topK) {}
    public enum Ability { meaning, application, reasoning }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
            @RequestBody @Valid Request request) {
        var created = service.create(request, key);
        return created.fresh()
                ? ResponseEntity.created(URI.create("/api/learning-recommendations/" + created.body().get("id")))
                    .body(created.body())
                : ResponseEntity.ok(created.body());
    }
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable @Positive long id) { return service.get(id); }
}
