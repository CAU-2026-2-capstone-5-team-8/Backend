package com.cau.capstone8.backend.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recommendation_item")
public class RecommendationItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "feature_id", nullable = false)
    private Long featureId;

    @Column(nullable = false)
    private int rank;

    @Column(nullable = false)
    private double score;

    @Column(name = "topic_fit", nullable = false)
    private double topicFit;

    @Column(name = "vocabulary_fit", nullable = false)
    private double vocabularyFit;

    @Column(name = "knowledge_fit", nullable = false)
    private double knowledgeFit;

    @Column(name = "comprehension_fit", nullable = false)
    private double comprehensionFit;

    @Column(name = "book_feature_version", nullable = false, length = 80)
    private String bookFeatureVersion;

    @Column(name = "vocabulary_requirement", nullable = false)
    private double vocabularyRequirement;

    @Column(name = "knowledge_requirement", nullable = false)
    private double knowledgeRequirement;

    @Column(name = "comprehension_requirement", nullable = false)
    private double comprehensionRequirement;

    @Column(name = "topic_relevance", nullable = false)
    private double topicRelevance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    protected RecommendationItem() {
    }

    public RecommendationItem(
            long runId,
            long bookId,
            long featureId,
            int rank,
            double score,
            double topicFit,
            double vocabularyFit,
            double knowledgeFit,
            double comprehensionFit,
            String bookFeatureVersion,
            double vocabularyRequirement,
            double knowledgeRequirement,
            double comprehensionRequirement,
            double topicRelevance,
            List<String> reasons) {
        this.runId = runId;
        this.bookId = bookId;
        this.featureId = featureId;
        this.rank = rank;
        this.score = score;
        this.topicFit = topicFit;
        this.vocabularyFit = vocabularyFit;
        this.knowledgeFit = knowledgeFit;
        this.comprehensionFit = comprehensionFit;
        this.bookFeatureVersion = bookFeatureVersion;
        this.vocabularyRequirement = vocabularyRequirement;
        this.knowledgeRequirement = knowledgeRequirement;
        this.comprehensionRequirement = comprehensionRequirement;
        this.topicRelevance = topicRelevance;
        this.reasons = List.copyOf(reasons);
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getBookId() { return bookId; }
    public int getRank() { return rank; }
    public double getScore() { return score; }
    public double getTopicFit() { return topicFit; }
    public double getVocabularyFit() { return vocabularyFit; }
    public double getKnowledgeFit() { return knowledgeFit; }
    public double getComprehensionFit() { return comprehensionFit; }
    public String getBookFeatureVersion() { return bookFeatureVersion; }
    public List<String> getReasons() { return reasons; }
}
