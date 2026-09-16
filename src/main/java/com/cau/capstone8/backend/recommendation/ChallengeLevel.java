package com.cau.capstone8.backend.recommendation;

public enum ChallengeLevel {
    COMFORTABLE(-0.2), BALANCED(0.0), CHALLENGING(0.2);

    private final double offset;

    ChallengeLevel(double offset) { this.offset = offset; }

    public double offset() { return offset; }
}
