package com.cau.capstone8.backend.assessment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public final class AssessmentAnswerRequest {
    private Boolean knowsConcept;
    @Min(0) @Max(3) private Integer selectedChoiceIndex;
    private boolean hasUnknownField;

    public Boolean getKnowsConcept() { return knowsConcept; }
    public void setKnowsConcept(Boolean knowsConcept) { this.knowsConcept = knowsConcept; }
    public Integer getSelectedChoiceIndex() { return selectedChoiceIndex; }
    public void setSelectedChoiceIndex(Integer selectedChoiceIndex) {
        this.selectedChoiceIndex = selectedChoiceIndex;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        hasUnknownField = true;
    }

    @JsonIgnore
    @AssertTrue
    public boolean isValidShape() {
        return !hasUnknownField && (knowsConcept == null) != (selectedChoiceIndex == null);
    }
}
