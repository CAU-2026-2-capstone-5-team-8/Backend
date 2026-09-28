package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;

@Entity
@Table(name = "assessment_answer")
public class AssessmentAnswer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "assessment_question_id", nullable = false, unique = true) private Long assessmentQuestionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_mode", nullable = false, length = 30) private AnswerMode answerMode;
    @Column(name = "knows_concept") private Boolean knowsConcept;
    @Column(name = "selected_choice_index") private Integer selectedChoiceIndex;
    @Column private Boolean correct;

    protected AssessmentAnswer() {}

    public static AssessmentAnswer selfReport(Long assessmentQuestionId, boolean knowsConcept) {
        AssessmentAnswer answer = new AssessmentAnswer();
        answer.assessmentQuestionId = assessmentQuestionId;
        answer.answerMode = AnswerMode.SELF_REPORT;
        answer.knowsConcept = knowsConcept;
        return answer;
    }

    public static AssessmentAnswer multipleChoice(
            Long assessmentQuestionId, int selectedChoiceIndex, boolean correct) {
        AssessmentAnswer answer = new AssessmentAnswer();
        answer.assessmentQuestionId = assessmentQuestionId;
        answer.answerMode = AnswerMode.MULTIPLE_CHOICE;
        answer.selectedChoiceIndex = selectedChoiceIndex;
        answer.correct = correct;
        return answer;
    }

    public Long getId() { return id; }
    public Long getAssessmentQuestionId() { return assessmentQuestionId; }
    public AnswerMode getAnswerMode() { return answerMode; }
    public Boolean getKnowsConcept() { return knowsConcept; }
    public Integer getSelectedChoiceIndex() { return selectedChoiceIndex; }
    public Boolean getCorrect() { return correct; }

    public void updateSelfReport(boolean value) {
        requireMode(AnswerMode.SELF_REPORT);
        knowsConcept = value;
    }

    public void updateMultipleChoice(int selectedIndex, boolean calculatedCorrect) {
        requireMode(AnswerMode.MULTIPLE_CHOICE);
        selectedChoiceIndex = selectedIndex;
        correct = calculatedCorrect;
    }

    public boolean resultForMl() {
        return answerMode == AnswerMode.MULTIPLE_CHOICE
                ? Boolean.TRUE.equals(correct)
                : Boolean.TRUE.equals(knowsConcept);
    }

    private void requireMode(AnswerMode expected) {
        if (answerMode != expected) {
            throw new IllegalStateException("assessment answer mode cannot change");
        }
    }
}
