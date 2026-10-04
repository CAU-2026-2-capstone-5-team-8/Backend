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
    private final AssessmentCompletionService completionService;
    private final com.cau.capstone8.backend.account.AccountOwnership ownership;
    public AssessmentController(AssessmentService service, AssessmentCompletionService completionService,
            com.cau.capstone8.backend.account.AccountOwnership ownership) {
        this.service = service;
        this.completionService = completionService;
        this.ownership = ownership;
    }

    @PostMapping
    public ResponseEntity<AssessmentResponse> create(@RequestBody @Valid AssessmentCreateRequest request) {
        ownership.user(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.userId(), request.topicId()));
    }

    @PostMapping("/concepts")
    public ResponseEntity<AssessmentResponse> createConceptAssessment(@RequestBody @Valid AssessmentCreateRequest request) {
        ownership.user(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createConceptAssessment(request.userId(), request.topicId()));
    }

    @GetMapping("/{sessionId}")
    public AssessmentResponse get(@PathVariable @Positive long sessionId) {
        return service.get(sessionId);
    }

    @PutMapping("/{sessionId}/answers/{assessmentQuestionId}")
    public AssessmentResponse.IssuedQuestion answer(@PathVariable @Positive long sessionId,
                                                      @PathVariable @Positive long assessmentQuestionId,
                                                      @RequestBody @Valid AssessmentAnswerRequest request) {
        return service.answer(sessionId, assessmentQuestionId, request);
    }

    @PostMapping("/{sessionId}/complete")
    public AssessmentCompletionResponse complete(@PathVariable @Positive long sessionId) {
        return completionService.complete(sessionId);
    }
}
