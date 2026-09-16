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
    @Column(name = "completed_at") private OffsetDateTime completedAt;

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
    public OffsetDateTime getCompletedAt() { return completedAt; }

    public void setStatus(AssessmentStatus status) { this.status = status; }

    public boolean hasActiveProcessingLease(OffsetDateTime now) {
        return status == AssessmentStatus.PROCESSING
                && processingExpiresAt != null
                && processingExpiresAt.isAfter(now);
    }

    public void beginProcessing(UUID newAttemptId, OffsetDateTime expiresAt) {
        status = AssessmentStatus.PROCESSING;
        attemptId = newAttemptId;
        processingExpiresAt = expiresAt;
        lastFailureCode = null;
        lastFailureMessage = null;
    }

    public boolean ownsAttempt(UUID expectedAttemptId) {
        return status == AssessmentStatus.PROCESSING && expectedAttemptId.equals(attemptId);
    }

    public void complete(OffsetDateTime completionTime) {
        status = AssessmentStatus.COMPLETED;
        attemptId = null;
        processingExpiresAt = null;
        completedAt = completionTime;
        lastFailureCode = null;
        lastFailureMessage = null;
    }

    public void failProcessing(String failureCode, String failureMessage) {
        status = AssessmentStatus.IN_PROGRESS;
        attemptId = null;
        processingExpiresAt = null;
        lastFailureCode = failureCode;
        lastFailureMessage = failureMessage;
    }
}
