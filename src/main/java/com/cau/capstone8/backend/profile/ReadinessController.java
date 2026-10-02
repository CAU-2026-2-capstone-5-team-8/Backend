package com.cau.capstone8.backend.profile;

import com.cau.capstone8.backend.account.CurrentAccount;
import com.cau.capstone8.backend.assessment.AssessmentDiagnosticsService;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/readiness")
public class ReadinessController {
    private final ReadinessService readiness;
    private final AssessmentDiagnosticsService diagnostics;
    public ReadinessController(ReadinessService readiness,AssessmentDiagnosticsService diagnostics) {
        this.readiness=readiness;
        this.diagnostics=diagnostics;
    }
    @GetMapping
    public ReadinessService.Overview overview() { return readiness.overview(CurrentAccount.id()); }

    @GetMapping("/{topicId}/history")
    public ReadinessService.History history(@PathVariable @Positive long topicId,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return readiness.history(CurrentAccount.id(),topicId,page,size);
    }

    @GetMapping("/{topicId}/diagnostics")
    public AssessmentDiagnosticsService.Response diagnostics(@PathVariable @Positive long topicId) {
        return diagnostics.get(readiness.latestSession(CurrentAccount.id(),topicId));
    }
}
