package com.cau.capstone8.backend.assessment;

public class QuestionImportException extends RuntimeException {
    public QuestionImportException(String message) {
        super(message);
    }

    public QuestionImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
