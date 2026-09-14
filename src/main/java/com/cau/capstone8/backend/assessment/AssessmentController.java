package com.cau.capstone8.backend.assessment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {
    private final AssessmentService service;
    public AssessmentController(AssessmentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<AssessmentResponse> create(@RequestBody @Valid AssessmentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.userId(), request.topicId()));
    }

    @GetMapping("/{sessionId}")
    public AssessmentResponse get(@PathVariable @Positive long sessionId) {
        return service.get(sessionId);
    }

    @PutMapping("/{sessionId}/answers/{assessmentQuestionId}")
    public AssessmentResponse.IssuedQuestion answer(@PathVariable @Positive long sessionId,
                                                      @PathVariable @Positive long assessmentQuestionId,
                                                      @RequestBody @Valid AssessmentAnswerRequest request) {
        return service.answer(sessionId, assessmentQuestionId, request.knowsConcept());
    }
}
