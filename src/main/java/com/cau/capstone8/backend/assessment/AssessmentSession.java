package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "assessment_session")
public class AssessmentSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private AssessmentStatus status;
    @Column(name = "attempt_id") private UUID attemptId;
    @Column(name = "processing_expires_at") private OffsetDateTime processingExpiresAt;
    @Column(name = "last_failure_code", length = 40) private String lastFailureCode;
    @Column(name = "last_failure_message", columnDefinition = "text") private String lastFailureMessage;

    protected AssessmentSession() {}

    public AssessmentSession(Long userId, Long topicId) {
        this.userId = userId;
        this.topicId = topicId;
        this.status = AssessmentStatus.CREATED;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTopicId() { return topicId; }
    public AssessmentStatus getStatus() { return status; }
    public UUID getAttemptId() { return attemptId; }
    public OffsetDateTime getProcessingExpiresAt() { return processingExpiresAt; }
    public String getLastFailureCode() { return lastFailureCode; }
    public String getLastFailureMessage() { return lastFailureMessage; }

    public void setStatus(AssessmentStatus status) { this.status = status; }
}
