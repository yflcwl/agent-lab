package com.example.solution.domain;

public record ExecutionResult(
        ResultStatus result,
        String content,
        String summary,
        String unresolvedIssues,
        String errorMessage) {

    public enum ResultStatus {
        SUCCESS,
        FAILED
    }
}
