package com.cau.capstone8.backend.recommendation;

public class RecommendationFailureException extends RuntimeException {
    private final String failureCode;
    private final int httpStatus;

    public RecommendationFailureException(String failureCode, String message, int httpStatus) {
        super(message);
        this.failureCode = failureCode;
        this.httpStatus = httpStatus;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
