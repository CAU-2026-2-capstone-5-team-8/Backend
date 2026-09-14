package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;

@Entity
@Table(name = "assessment_answer")
public class AssessmentAnswer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "assessment_question_id", nullable = false, unique = true) private Long assessmentQuestionId;
    @Column(name = "knows_concept", nullable = false) private boolean knowsConcept;

    protected AssessmentAnswer() {}

    public AssessmentAnswer(Long assessmentQuestionId, boolean knowsConcept) {
        this.assessmentQuestionId = assessmentQuestionId;
        this.knowsConcept = knowsConcept;
    }

    public Long getId() { return id; }
    public Long getAssessmentQuestionId() { return assessmentQuestionId; }
    public boolean isKnowsConcept() { return knowsConcept; }

    public void setKnowsConcept(boolean knowsConcept) { this.knowsConcept = knowsConcept; }
}
