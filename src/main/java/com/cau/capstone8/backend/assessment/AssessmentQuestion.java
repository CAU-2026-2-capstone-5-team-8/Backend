package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "assessment_question")
public class AssessmentQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "question_id", nullable = false) private Long questionId;
    @Column(name = "order_index", nullable = false) private int orderIndex;
    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_area_snapshot", nullable = false, length = 40) private MeasurementArea measurementAreaSnapshot;
    @Column(name = "passage_snapshot", columnDefinition = "text") private String passageSnapshot;
    @Column(name = "prompt_snapshot", nullable = false, columnDefinition = "text") private String promptSnapshot;
    @Column(name = "concept_id_snapshot", length = 120) private String conceptIdSnapshot;
    @Column(name = "version_snapshot", nullable = false, length = 80) private String versionSnapshot;
    @Column(name = "difficulty_snapshot", nullable = false) private int difficultySnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_mode_snapshot", nullable = false, length = 30)
    private AnswerMode answerModeSnapshot;
    @Column(name = "generated_question_id_snapshot", length = 35)
    private String generatedQuestionIdSnapshot;
    @Column(name = "question_spec_id_snapshot", length = 22) private String questionSpecIdSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "choices_snapshot", columnDefinition = "jsonb") private List<String> choicesSnapshot;
    @Column(name = "correct_choice_index_snapshot") private Integer correctChoiceIndexSnapshot;
    @Column(name = "explanation_snapshot", columnDefinition = "text") private String explanationSnapshot;
    @Column(name = "generated_content_hash_snapshot", length = 71)
    private String generatedContentHashSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "upstream_provenance_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> upstreamProvenanceSnapshot;

    protected AssessmentQuestion() {}

    public AssessmentQuestion(Long sessionId, Long questionId, int orderIndex, MeasurementArea measurementAreaSnapshot,
                               String passageSnapshot, String promptSnapshot, String conceptIdSnapshot, String versionSnapshot,
                               int difficultySnapshot, AnswerMode answerModeSnapshot,
                               String generatedQuestionIdSnapshot, String questionSpecIdSnapshot,
                               List<String> choicesSnapshot, Integer correctChoiceIndexSnapshot,
                               String explanationSnapshot, String generatedContentHashSnapshot,
                               Map<String, Object> upstreamProvenanceSnapshot) {
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.orderIndex = orderIndex;
        this.measurementAreaSnapshot = measurementAreaSnapshot;
        this.passageSnapshot = passageSnapshot;
        this.promptSnapshot = promptSnapshot;
        this.conceptIdSnapshot = conceptIdSnapshot;
        this.versionSnapshot = versionSnapshot;
        this.difficultySnapshot = difficultySnapshot;
        this.answerModeSnapshot = answerModeSnapshot;
        this.generatedQuestionIdSnapshot = generatedQuestionIdSnapshot;
        this.questionSpecIdSnapshot = questionSpecIdSnapshot;
        this.choicesSnapshot = choicesSnapshot == null ? null : List.copyOf(choicesSnapshot);
        this.correctChoiceIndexSnapshot = correctChoiceIndexSnapshot;
        this.explanationSnapshot = explanationSnapshot;
        this.generatedContentHashSnapshot = generatedContentHashSnapshot;
        this.upstreamProvenanceSnapshot = upstreamProvenanceSnapshot == null
                ? null : Map.copyOf(upstreamProvenanceSnapshot);
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getQuestionId() { return questionId; }
    public int getOrderIndex() { return orderIndex; }
    public MeasurementArea getMeasurementAreaSnapshot() { return measurementAreaSnapshot; }
    public String getPassageSnapshot() { return passageSnapshot; }
    public String getPromptSnapshot() { return promptSnapshot; }
    public String getConceptIdSnapshot() { return conceptIdSnapshot; }
    public String getVersionSnapshot() { return versionSnapshot; }
    public int getDifficultySnapshot() { return difficultySnapshot; }
    public AnswerMode getAnswerModeSnapshot() { return answerModeSnapshot; }
    public String getGeneratedQuestionIdSnapshot() { return generatedQuestionIdSnapshot; }
    public String getQuestionSpecIdSnapshot() { return questionSpecIdSnapshot; }
    public List<String> getChoicesSnapshot() {
        return choicesSnapshot == null ? null : List.copyOf(choicesSnapshot);
    }
    public Integer getCorrectChoiceIndexSnapshot() { return correctChoiceIndexSnapshot; }
    public String getExplanationSnapshot() { return explanationSnapshot; }
    public String getGeneratedContentHashSnapshot() { return generatedContentHashSnapshot; }
    public Map<String, Object> getUpstreamProvenanceSnapshot() {
        return upstreamProvenanceSnapshot == null ? null : Map.copyOf(upstreamProvenanceSnapshot);
    }
}
