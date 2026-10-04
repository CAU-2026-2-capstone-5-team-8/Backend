package com.cau.capstone8.backend.learning;

import jakarta.persistence.*;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "learning_recommendation")
public class LearningRecommendation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    @Column(name = "profile_id", nullable = false) private Long profileId;
    @Column(name = "request_key", nullable = false, length = 200) private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> result;
    @Column(nullable = false, length = 20) private String status = "SUCCEEDED";
    @Column(name = "attempt_id") private UUID attemptId;
    @Column(name = "processing_expires_at") private OffsetDateTime processingExpiresAt;

    protected LearningRecommendation() {}
    public LearningRecommendation(long userId, long topicId, long profileId, String requestKey,
            String requestHash, Map<String, Object> inputSnapshot, Map<String, Object> result) {
        this.userId = userId;
        this.topicId = topicId;
        this.profileId = profileId;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.inputSnapshot = Map.copyOf(inputSnapshot);
        this.result = Map.copyOf(result);
    }
    public static LearningRecommendation processing(long userId, long topicId, long profileId,
            String requestKey, String requestHash, Map<String, Object> inputSnapshot,
            UUID attemptId, OffsetDateTime expiresAt) {
        var run = new LearningRecommendation(userId, topicId, profileId, requestKey, requestHash,
                inputSnapshot, Map.of());
        run.status = "PROCESSING";
        run.attemptId = attemptId;
        run.processingExpiresAt = expiresAt;
        return run;
    }
    public boolean isProcessing() { return "PROCESSING".equals(status); }
    public boolean ownsAttempt(UUID attempt) { return isProcessing() && attempt.equals(attemptId); }
    public boolean hasActiveLease(OffsetDateTime now) {
        return isProcessing() && processingExpiresAt.isAfter(now);
    }
    public void succeed(Map<String, Object> result) {
        this.result = Map.copyOf(result);
        this.status = "SUCCEEDED";
        this.attemptId = null;
        this.processingExpiresAt = null;
    }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTopicId() { return topicId; }
    public Long getProfileId() { return profileId; }
    public String getRequestHash() { return requestHash; }
    public Map<String, Object> getResult() { return result; }
}
