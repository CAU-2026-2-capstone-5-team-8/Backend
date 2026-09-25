package com.cau.capstone8.backend.book;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "book_ranking_v2_projection")
public class BookRankingV2Projection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(nullable = false, length = 80)
    private String version;

    @Column(nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topic_distribution", nullable = false, columnDefinition = "jsonb")
    private Map<String, Double> topicDistribution;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "covered_concepts", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> coveredConcepts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prerequisite_concepts", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> prerequisiteConcepts;

    @Column(name = "lexical_difficulty")
    private Double lexicalDifficulty;

    @Column(name = "syntactic_complexity")
    private Double syntacticComplexity;

    @Column(name = "concept_density")
    private Double conceptDensity;

    @Column(name = "prerequisite_demand")
    private Double prerequisiteDemand;

    @Column(name = "config_version", nullable = false, length = 80)
    private String configVersion;

    @Column(name = "config_hash", nullable = false, length = 71)
    private String configHash;

    @Column(name = "source_artifact_version", nullable = false, length = 120)
    private String sourceArtifactVersion;

    @Column(name = "source_artifact_hash", nullable = false, length = 71)
    private String sourceArtifactHash;

    protected BookRankingV2Projection() {
    }

    public Long getId() { return id; }
    public Long getBookId() { return bookId; }
    public Long getTopicId() { return topicId; }
    public String getVersion() { return version; }
    public boolean isActive() { return active; }
    public Map<String, Double> getTopicDistribution() { return topicDistribution; }
    public List<Map<String, Object>> getCoveredConcepts() { return coveredConcepts; }
    public List<Map<String, Object>> getPrerequisiteConcepts() { return prerequisiteConcepts; }
    public Double getLexicalDifficulty() { return lexicalDifficulty; }
    public Double getSyntacticComplexity() { return syntacticComplexity; }
    public Double getConceptDensity() { return conceptDensity; }
    public Double getPrerequisiteDemand() { return prerequisiteDemand; }
    public String getConfigVersion() { return configVersion; }
    public String getConfigHash() { return configHash; }
    public String getSourceArtifactVersion() { return sourceArtifactVersion; }
    public String getSourceArtifactHash() { return sourceArtifactHash; }
}
