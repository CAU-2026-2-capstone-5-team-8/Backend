package com.cau.capstone8.backend.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reader_profile")
public class ReaderProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private Long sessionId;

    @Column(nullable = false)
    private double vocabulary;

    @Column(name = "background_knowledge", nullable = false)
    private double backgroundKnowledge;

    @Column(nullable = false)
    private double comprehension;

    @Column(name = "calculation_version", nullable = false, length = 80)
    private String calculationVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence;

    protected ReaderProfile() {
    }

    public ReaderProfile(
            Long sessionId,
            double vocabulary,
            double backgroundKnowledge,
            double comprehension,
            String calculationVersion,
            Map<String, Object> evidence) {
        this.sessionId = sessionId;
        this.vocabulary = vocabulary;
        this.backgroundKnowledge = backgroundKnowledge;
        this.comprehension = comprehension;
        this.calculationVersion = calculationVersion;
        this.evidence = Map.copyOf(evidence);
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public double getVocabulary() {
        return vocabulary;
    }

    public double getBackgroundKnowledge() {
        return backgroundKnowledge;
    }

    public double getComprehension() {
        return comprehension;
    }

    public String getCalculationVersion() {
        return calculationVersion;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }
}
