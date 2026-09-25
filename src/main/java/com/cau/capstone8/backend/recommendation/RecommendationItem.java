package com.cau.capstone8.backend.recommendation;

import com.cau.capstone8.backend.integration.ml.MlRankV2Request;
import com.cau.capstone8.backend.integration.ml.MlRankV2Result;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "run_id", nullable = false) private Long runId;
    @Column(name = "book_id", nullable = false) private Long bookId;
    @Column(name = "feature_id") private Long featureId;
    @Column(name = "projection_id") private Long projectionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "ranking_mode", nullable = false, length = 40)
    private RecommendationRankingMode rankingMode;
    @Column(nullable = false) private int rank;

    @Column private Double score;
    @Column(name = "topic_fit") private Double topicFit;
    @Column(name = "vocabulary_fit") private Double vocabularyFit;
    @Column(name = "knowledge_fit") private Double knowledgeFit;
    @Column(name = "comprehension_fit") private Double comprehensionFit;
    @Column(name = "book_feature_version", nullable = false, length = 80)
    private String bookFeatureVersion;
    @Column(name = "vocabulary_requirement") private Double vocabularyRequirement;
    @Column(name = "knowledge_requirement") private Double knowledgeRequirement;
    @Column(name = "comprehension_requirement") private Double comprehensionRequirement;
    @Column(name = "topic_relevance") private Double topicRelevance;

    @Column(name = "prerequisite_readiness") private Double prerequisiteReadiness;
    @Column(name = "prerequisite_assessed_count") private Integer prerequisiteAssessedCount;
    @Column(name = "prerequisite_total_count") private Integer prerequisiteTotalCount;
    @Column(name = "prerequisite_coverage") private Double prerequisiteCoverage;
    @Column(name = "direct_learning_opportunity") private Double directLearningOpportunity;
    @Column(name = "direct_assessed_count") private Integer directAssessedCount;
    @Column(name = "direct_total_count") private Integer directTotalCount;
    @Column(name = "direct_coverage") private Double directCoverage;
    @Column(name = "availability_status", length = 30) private String availabilityStatus;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "covered_concepts", columnDefinition = "jsonb")
    private List<String> coveredConcepts;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inferred_prerequisites", columnDefinition = "jsonb")
    private List<String> inferredPrerequisites;
    @Column(name = "book_config_version", length = 80) private String bookConfigVersion;
    @Column(name = "book_config_hash", length = 71) private String bookConfigHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    protected RecommendationItem() {
    }

    public RecommendationItem(
            long runId, long bookId, long featureId, int rank, double score,
            double topicFit, double vocabularyFit, double knowledgeFit,
            double comprehensionFit, String bookFeatureVersion,
            double vocabularyRequirement, double knowledgeRequirement,
            double comprehensionRequirement, double topicRelevance, List<String> reasons) {
        this.runId = runId;
        this.bookId = bookId;
        this.featureId = featureId;
        this.rankingMode = RecommendationRankingMode.LEGACY_SCALAR;
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

    public static RecommendationItem fromV2(
            long runId, MlRankV2Request.Candidate candidate, MlRankV2Result.Item ranked) {
        RecommendationItem item = new RecommendationItem();
        item.runId = runId;
        item.bookId = candidate.backendBookId();
        item.projectionId = candidate.projectionId();
        item.rankingMode = RecommendationRankingMode.PREREQUISITE_FIRST_V2;
        item.rank = ranked.rank();
        item.bookFeatureVersion = ranked.bookFeatureVersion();
        item.prerequisiteReadiness = ranked.prerequisiteReadiness();
        item.prerequisiteAssessedCount = ranked.prerequisiteAssessedCount();
        item.prerequisiteTotalCount = ranked.prerequisiteTotalCount();
        item.prerequisiteCoverage = ranked.prerequisiteCoverage();
        item.directLearningOpportunity = ranked.directLearningOpportunity();
        item.directAssessedCount = ranked.directAssessedCount();
        item.directTotalCount = ranked.directTotalCount();
        item.directCoverage = ranked.directCoverage();
        item.availabilityStatus = ranked.availabilityStatus();
        item.coveredConcepts = List.copyOf(ranked.coveredConcepts());
        item.inferredPrerequisites = List.copyOf(ranked.inferredPrerequisites());
        item.bookConfigVersion = ranked.bookConfigVersion();
        item.bookConfigHash = ranked.bookConfigHash();
        item.reasons = List.copyOf(ranked.reasons());
        return item;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getBookId() { return bookId; }
    public RecommendationRankingMode getRankingMode() { return rankingMode; }
    public int getRank() { return rank; }
    public Double getScore() { return score; }
    public Double getTopicFit() { return topicFit; }
    public Double getVocabularyFit() { return vocabularyFit; }
    public Double getKnowledgeFit() { return knowledgeFit; }
    public Double getComprehensionFit() { return comprehensionFit; }
    public String getBookFeatureVersion() { return bookFeatureVersion; }
    public Double getPrerequisiteReadiness() { return prerequisiteReadiness; }
    public Integer getPrerequisiteAssessedCount() { return prerequisiteAssessedCount; }
    public Integer getPrerequisiteTotalCount() { return prerequisiteTotalCount; }
    public Double getPrerequisiteCoverage() { return prerequisiteCoverage; }
    public Double getDirectLearningOpportunity() { return directLearningOpportunity; }
    public Integer getDirectAssessedCount() { return directAssessedCount; }
    public Integer getDirectTotalCount() { return directTotalCount; }
    public Double getDirectCoverage() { return directCoverage; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public List<String> getCoveredConcepts() { return coveredConcepts; }
    public List<String> getInferredPrerequisites() { return inferredPrerequisites; }
    public String getBookConfigVersion() { return bookConfigVersion; }
    public String getBookConfigHash() { return bookConfigHash; }
    public List<String> getReasons() { return reasons; }
}
