package com.cau.capstone8.backend.assessment;

import java.util.Locale;

public enum MeasurementArea {
    VOCABULARY,
    BACKGROUND_KNOWLEDGE,
    COMPREHENSION;

    public static MeasurementArea fromQuestionType(String questionType) {
        if (questionType == null) {
            throw new IllegalArgumentException("question type is required");
        }
        try {
            return valueOf(questionType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported question type: " + questionType, exception);
        }
    }

    public String toQuestionType() {
        return name().toLowerCase(Locale.ROOT);
    }
}
