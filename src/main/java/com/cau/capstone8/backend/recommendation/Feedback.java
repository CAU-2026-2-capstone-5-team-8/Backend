package com.cau.capstone8.backend.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "feedback")
public class Feedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommendation_item_id", nullable = false)
    private Long recommendationItemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean helpful;

    @Column(columnDefinition = "text")
    private String comment;

    protected Feedback() {
    }

    public Feedback(long recommendationItemId, long userId, boolean helpful, String comment) {
        this.recommendationItemId = recommendationItemId;
        this.userId = userId;
        this.helpful = helpful;
        this.comment = comment;
    }

    public void replace(boolean newHelpful, String newComment) {
        helpful = newHelpful;
        comment = newComment;
    }

    public Long getId() { return id; }
    public Long getRecommendationItemId() { return recommendationItemId; }
    public Long getUserId() { return userId; }
    public boolean isHelpful() { return helpful; }
    public String getComment() { return comment; }
}
