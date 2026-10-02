package com.cau.capstone8.backend.account;

public class AccountException extends RuntimeException {
    private final int status;
    private final String code;
    public AccountException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
