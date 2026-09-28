package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "question")
public class Question {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_area", nullable = false, length = 40) private MeasurementArea measurementArea;
    @Column(nullable = false) private int difficulty;
    @Column(nullable = false, columnDefinition = "text") private String prompt;
    @Column(name = "concept_id", length = 120) private String conceptId;
    @Column(nullable = false, length = 80) private String version;
    @Column(nullable = false) private boolean active;
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_mode", nullable = false, length = 30) private AnswerMode answerMode;
    @Column(name = "generated_question_id", length = 35) private String generatedQuestionId;
    @Column(name = "question_spec_id", length = 22) private String questionSpecId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private List<String> choices;
    @Column(name = "correct_choice_index") private Integer correctChoiceIndex;
    @Column(columnDefinition = "text") private String explanation;
    @Column(name = "generated_content_hash", length = 71) private String generatedContentHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "upstream_provenance", columnDefinition = "jsonb")
    private Map<String, Object> upstreamProvenance;

    protected Question() {}

    public static Question generated(
            Long topicId,
            MeasurementArea measurementArea,
            int difficulty,
            String prompt,
            String conceptId,
            String version,
            String generatedQuestionId,
            String questionSpecId,
            List<String> choices,
            int correctChoiceIndex,
            String explanation,
            String generatedContentHash,
            Map<String, Object> upstreamProvenance) {
        Question question = new Question();
        question.topicId = topicId;
        question.measurementArea = measurementArea;
        question.difficulty = difficulty;
        question.prompt = prompt;
        question.conceptId = conceptId;
        question.version = version;
        question.active = true;
        question.answerMode = AnswerMode.MULTIPLE_CHOICE;
        question.generatedQuestionId = generatedQuestionId;
        question.questionSpecId = questionSpecId;
        question.choices = List.copyOf(choices);
        question.correctChoiceIndex = correctChoiceIndex;
        question.explanation = explanation;
        question.generatedContentHash = generatedContentHash;
        question.upstreamProvenance = Map.copyOf(upstreamProvenance);
        return question;
    }

    public Long getId() { return id; }
    public Long getTopicId() { return topicId; }
    public MeasurementArea getMeasurementArea() { return measurementArea; }
    public int getDifficulty() { return difficulty; }
    public String getPrompt() { return prompt; }
    public String getConceptId() { return conceptId; }
    public String getVersion() { return version; }
    public boolean isActive() { return active; }
    public AnswerMode getAnswerMode() { return answerMode; }
    public String getGeneratedQuestionId() { return generatedQuestionId; }
    public String getQuestionSpecId() { return questionSpecId; }
    public List<String> getChoices() { return choices == null ? null : List.copyOf(choices); }
    public Integer getCorrectChoiceIndex() { return correctChoiceIndex; }
    public String getExplanation() { return explanation; }
    public String getGeneratedContentHash() { return generatedContentHash; }
    public Map<String, Object> getUpstreamProvenance() {
        return upstreamProvenance == null ? null : Map.copyOf(upstreamProvenance);
    }
}
