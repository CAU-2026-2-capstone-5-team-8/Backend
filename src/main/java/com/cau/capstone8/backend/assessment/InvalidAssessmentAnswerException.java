package com.cau.capstone8.backend.assessment;

public class InvalidAssessmentAnswerException extends RuntimeException {
    public InvalidAssessmentAnswerException(String message) {
        super(message);
    }
}
