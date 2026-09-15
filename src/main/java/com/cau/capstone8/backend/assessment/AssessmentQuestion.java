package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;

@Entity
@Table(name = "assessment_question")
public class AssessmentQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "question_id", nullable = false) private Long questionId;
    @Column(name = "order_index", nullable = false) private int orderIndex;
    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_area_snapshot", nullable = false, length = 40) private MeasurementArea measurementAreaSnapshot;
    @Column(name = "prompt_snapshot", nullable = false, columnDefinition = "text") private String promptSnapshot;
    @Column(name = "concept_id_snapshot", length = 120) private String conceptIdSnapshot;
    @Column(name = "version_snapshot", nullable = false, length = 80) private String versionSnapshot;
    @Column(name = "difficulty_snapshot", nullable = false) private int difficultySnapshot;

    protected AssessmentQuestion() {}

    public AssessmentQuestion(Long sessionId, Long questionId, int orderIndex, MeasurementArea measurementAreaSnapshot,
                               String promptSnapshot, String conceptIdSnapshot, String versionSnapshot,
                               int difficultySnapshot) {
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.orderIndex = orderIndex;
        this.measurementAreaSnapshot = measurementAreaSnapshot;
        this.promptSnapshot = promptSnapshot;
        this.conceptIdSnapshot = conceptIdSnapshot;
        this.versionSnapshot = versionSnapshot;
        this.difficultySnapshot = difficultySnapshot;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getQuestionId() { return questionId; }
    public int getOrderIndex() { return orderIndex; }
    public MeasurementArea getMeasurementAreaSnapshot() { return measurementAreaSnapshot; }
    public String getPromptSnapshot() { return promptSnapshot; }
    public String getConceptIdSnapshot() { return conceptIdSnapshot; }
    public String getVersionSnapshot() { return versionSnapshot; }
    public int getDifficultySnapshot() { return difficultySnapshot; }
}
