package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;

@Entity
@Table(name = "assessment_answer")
public class AssessmentAnswer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "assessment_question_id", nullable = false, unique = true) private Long assessmentQuestionId;
    @Column(name = "selected_option_id", nullable = false, length = 20) private String selectedOptionId;

    protected AssessmentAnswer() {}

    public Long getId() { return id; }
    public Long getAssessmentQuestionId() { return assessmentQuestionId; }
    public String getSelectedOptionId() { return selectedOptionId; }
}
