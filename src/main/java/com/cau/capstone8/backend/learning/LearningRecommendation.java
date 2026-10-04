package com.cau.capstone8.backend.learning;

import jakarta.persistence.*;
import java.util.Map;
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
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTopicId() { return topicId; }
    public Long getProfileId() { return profileId; }
    public String getRequestHash() { return requestHash; }
    public Map<String, Object> getResult() { return result; }
}
