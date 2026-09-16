package com.cau.capstone8.backend.recommendation;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendation_run")
public class RecommendationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    @Column(name = "profile_id", nullable = false) private Long profileId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private RecommendationStatus status;
    @Column(name = "request_key", nullable = false, length = 200) private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "attempt_id") private UUID attemptId;
    @Column(name = "processing_expires_at") private OffsetDateTime processingExpiresAt;
    @Column(name = "model_version", length = 80) private String modelVersion;
    @Column(name = "candidate_count", nullable = false) private int candidateCount;
    @Column(name = "excluded_count", nullable = false) private int excludedCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb") private Map<String, Object> inputSnapshot;
    @Column(name = "last_failure_code", length = 40) private String lastFailureCode;
    @Column(name = "last_failure_message", columnDefinition = "text") private String lastFailureMessage;
    @Column(name = "completed_at") private OffsetDateTime completedAt;

    protected RecommendationRun() {}

    public RecommendationRun(Long userId, Long topicId, Long profileId, String requestKey, String requestHash,
                              UUID attemptId, OffsetDateTime processingExpiresAt, int candidateCount,
                              int excludedCount, Map<String, Object> inputSnapshot) {
        this.userId = userId;
        this.topicId = topicId;
        this.profileId = profileId;
        this.status = RecommendationStatus.PROCESSING;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.attemptId = attemptId;
        this.processingExpiresAt = processingExpiresAt;
        this.candidateCount = candidateCount;
        this.excludedCount = excludedCount;
        this.inputSnapshot = Map.copyOf(inputSnapshot);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTopicId() { return topicId; }
    public Long getProfileId() { return profileId; }
    public RecommendationStatus getStatus() { return status; }
    public String getRequestKey() { return requestKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getAttemptId() { return attemptId; }
    public OffsetDateTime getProcessingExpiresAt() { return processingExpiresAt; }
    public String getModelVersion() { return modelVersion; }
    public int getCandidateCount() { return candidateCount; }
    public int getExcludedCount() { return excludedCount; }
    public Map<String, Object> getInputSnapshot() { return inputSnapshot; }
    public String getLastFailureCode() { return lastFailureCode; }
    public String getLastFailureMessage() { return lastFailureMessage; }
    public OffsetDateTime getCompletedAt() { return completedAt; }

    public boolean hasActiveProcessingLease(OffsetDateTime now) {
        return status == RecommendationStatus.PROCESSING
                && processingExpiresAt != null && processingExpiresAt.isAfter(now);
    }

    public boolean ownsAttempt(UUID expectedAttemptId) {
        return status == RecommendationStatus.PROCESSING && expectedAttemptId.equals(attemptId);
    }

    public void succeed(String modelVersion, OffsetDateTime completionTime) {
        this.status = RecommendationStatus.SUCCEEDED;
        this.modelVersion = modelVersion;
        this.completedAt = completionTime;
        this.attemptId = null;
        this.processingExpiresAt = null;
        this.lastFailureCode = null;
        this.lastFailureMessage = null;
    }

    public void fail(String failureCode, String failureMessage) {
        this.status = RecommendationStatus.FAILED;
        this.attemptId = null;
        this.processingExpiresAt = null;
        this.lastFailureCode = failureCode;
        this.lastFailureMessage = failureMessage;
    }
}
