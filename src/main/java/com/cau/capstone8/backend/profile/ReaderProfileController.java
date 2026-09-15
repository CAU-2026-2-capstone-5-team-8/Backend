package com.cau.capstone8.backend.profile;

import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/profiles")
public class ReaderProfileController {
    private final ReaderProfileService service;

    public ReaderProfileController(ReaderProfileService service) {
        this.service = service;
    }

    @GetMapping("/{topicId}")
    public ReaderProfileResponse latest(
            @PathVariable @Positive long userId,
            @PathVariable @Positive long topicId) {
        return service.latest(userId, topicId);
    }
}
