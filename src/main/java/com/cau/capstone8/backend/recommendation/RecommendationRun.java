package com.cau.capstone8.backend.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendation_run")
public class RecommendationRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecommendationStatus status;

    @Column(name = "request_key", nullable = false, length = 200)
    private String requestKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "challenge_level", nullable = false, length = 20)
    private ChallengeLevel challengeLevel;

    @Column(name = "target_book_id")
    private Long targetBookId;

    @Column(name = "requested_top_k", nullable = false)
    private int requestedTopK;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "processing_expires_at")
    private OffsetDateTime processingExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSnapshot;

    @Column(name = "eligible_candidate_count", nullable = false)
    private int eligibleCandidateCount;

    @Column(name = "excluded_candidate_count", nullable = false)
    private int excludedCandidateCount;

    @Column(name = "model_version", length = 80)
    private String modelVersion;

    @Column(name = "last_failure_code", length = 40)
    private String lastFailureCode;

    @Column(name = "last_failure_message", columnDefinition = "text")
    private String lastFailureMessage;

    @Column(name = "failure_http_status")
    private Integer failureHttpStatus;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected RecommendationRun() {
    }

    public RecommendationRun(
            long userId,
            long topicId,
            long profileId,
            String requestKey,
            String requestHash,
            ChallengeLevel challengeLevel,
            Long targetBookId,
            int requestedTopK,
            UUID attemptId,
            OffsetDateTime processingExpiresAt,
            Map<String, Object> inputSnapshot,
            int eligibleCandidateCount,
            int excludedCandidateCount) {
        this.userId = userId;
        this.topicId = topicId;
        this.profileId = profileId;
        this.status = RecommendationStatus.PROCESSING;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.challengeLevel = challengeLevel;
        this.targetBookId = targetBookId;
        this.requestedTopK = requestedTopK;
        this.attemptId = attemptId;
        this.processingExpiresAt = processingExpiresAt;
        this.inputSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(inputSnapshot));
        this.eligibleCandidateCount = eligibleCandidateCount;
        this.excludedCandidateCount = excludedCandidateCount;
    }

    public boolean ownsAttempt(UUID expectedAttemptId) {
        return status == RecommendationStatus.PROCESSING
                && expectedAttemptId != null
                && expectedAttemptId.equals(attemptId);
    }

    public boolean hasActiveLease(OffsetDateTime now) {
        return status == RecommendationStatus.PROCESSING
                && processingExpiresAt != null
                && processingExpiresAt.isAfter(now);
    }

    public void succeed(String newModelVersion, OffsetDateTime completionTime) {
        status = RecommendationStatus.SUCCEEDED;
        attemptId = null;
        processingExpiresAt = null;
        modelVersion = newModelVersion;
        completedAt = completionTime;
    }

    public void fail(
            String failureCode,
            String failureMessage,
            int httpStatus,
            OffsetDateTime completionTime) {
        status = RecommendationStatus.FAILED;
        attemptId = null;
        processingExpiresAt = null;
        lastFailureCode = failureCode;
        lastFailureMessage = failureMessage;
        failureHttpStatus = httpStatus;
        completedAt = completionTime;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTopicId() { return topicId; }
    public Long getProfileId() { return profileId; }
    public RecommendationStatus getStatus() { return status; }
    public String getRequestHash() { return requestHash; }
    public ChallengeLevel getChallengeLevel() { return challengeLevel; }
    public Long getTargetBookId() { return targetBookId; }
    public int getRequestedTopK() { return requestedTopK; }
    public int getEligibleCandidateCount() { return eligibleCandidateCount; }
    public int getExcludedCandidateCount() { return excludedCandidateCount; }
    public String getModelVersion() { return modelVersion; }
    public String getLastFailureCode() { return lastFailureCode; }
    public String getLastFailureMessage() { return lastFailureMessage; }
    public Integer getFailureHttpStatus() { return failureHttpStatus; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
