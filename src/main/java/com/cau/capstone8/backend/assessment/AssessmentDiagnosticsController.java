package com.cau.capstone8.backend.assessment;

import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assessments")
public class AssessmentDiagnosticsController {
    private final AssessmentDiagnosticsService service;
    public AssessmentDiagnosticsController(AssessmentDiagnosticsService service) { this.service = service; }

    @GetMapping("/{sessionId}/diagnostics")
    public AssessmentDiagnosticsService.Response get(@PathVariable @Positive long sessionId) {
        return service.get(sessionId);
    }
}
