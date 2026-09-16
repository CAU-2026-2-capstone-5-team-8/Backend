package com.cau.capstone8.backend.recommendation;

import jakarta.persistence.*;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendation_item")
public class RecommendationItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "run_id", nullable = false) private Long runId;
    @Column(name = "book_id", nullable = false) private Long bookId;
    @Column(name = "feature_id", nullable = false) private Long featureId;
    @Column(nullable = false) private int rank;
    @Column(name = "total_score", nullable = false) private double totalScore;
    @Column(name = "topic_fit", nullable = false) private double topicFit;
    @Column(name = "vocabulary_fit", nullable = false) private double vocabularyFit;
    @Column(name = "knowledge_fit", nullable = false) private double knowledgeFit;
    @Column(name = "comprehension_fit", nullable = false) private double comprehensionFit;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private List<String> reasons;

    protected RecommendationItem() {}

    public RecommendationItem(Long runId, Long bookId, Long featureId, int rank, double totalScore,
                               double topicFit, double vocabularyFit, double knowledgeFit,
                               double comprehensionFit, List<String> reasons) {
        this.runId = runId;
        this.bookId = bookId;
        this.featureId = featureId;
        this.rank = rank;
        this.totalScore = totalScore;
        this.topicFit = topicFit;
        this.vocabularyFit = vocabularyFit;
        this.knowledgeFit = knowledgeFit;
        this.comprehensionFit = comprehensionFit;
        this.reasons = List.copyOf(reasons);
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getBookId() { return bookId; }
    public Long getFeatureId() { return featureId; }
    public int getRank() { return rank; }
    public double getTotalScore() { return totalScore; }
    public double getTopicFit() { return topicFit; }
    public double getVocabularyFit() { return vocabularyFit; }
    public double getKnowledgeFit() { return knowledgeFit; }
    public double getComprehensionFit() { return comprehensionFit; }
    public List<String> getReasons() { return reasons; }
}
