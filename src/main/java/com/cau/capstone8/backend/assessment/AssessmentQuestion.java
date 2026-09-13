package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "assessment_question")
public class AssessmentQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "question_id", nullable = false) private Long questionId;
    @Column(name = "order_index", nullable = false) private int orderIndex;
    @Column(name = "prompt_snapshot", nullable = false, columnDefinition = "text") private String promptSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_snapshot", nullable = false, columnDefinition = "jsonb") private String optionsSnapshot;
    @Column(name = "correct_option_id_snapshot", nullable = false, length = 20) private String correctOptionIdSnapshot;
    @Column(name = "version_snapshot", nullable = false, length = 80) private String versionSnapshot;

    protected AssessmentQuestion() {}

    public AssessmentQuestion(Long sessionId, Long questionId, int orderIndex, String promptSnapshot,
                               String optionsSnapshot, String correctOptionIdSnapshot, String versionSnapshot) {
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.orderIndex = orderIndex;
        this.promptSnapshot = promptSnapshot;
        this.optionsSnapshot = optionsSnapshot;
        this.correctOptionIdSnapshot = correctOptionIdSnapshot;
        this.versionSnapshot = versionSnapshot;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getQuestionId() { return questionId; }
    public int getOrderIndex() { return orderIndex; }
    public String getPromptSnapshot() { return promptSnapshot; }
    public String getOptionsSnapshot() { return optionsSnapshot; }
    public String getCorrectOptionIdSnapshot() { return correctOptionIdSnapshot; }
    public String getVersionSnapshot() { return versionSnapshot; }
}
