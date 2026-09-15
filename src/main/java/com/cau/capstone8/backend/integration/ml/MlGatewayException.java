package com.cau.capstone8.backend.integration.ml;

public class MlGatewayException extends RuntimeException {
    private final String failureCode;

    public MlGatewayException(String failureCode, String message) {
        super(message);
        this.failureCode = validateFailureCode(failureCode);
    }

    public MlGatewayException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = validateFailureCode(failureCode);
    }

    public String getFailureCode() {
        return failureCode;
    }

    private static String validateFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank() || failureCode.length() > 40) {
            throw new IllegalArgumentException("ML failure code must contain 1 to 40 characters");
        }
        return failureCode;
    }
}
