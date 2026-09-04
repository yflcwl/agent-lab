package com.example.solution.domain;

import java.time.LocalDateTime;

public record SourceDocument(
        Long id,
        Long writingTaskId,
        String originalFilename,
        String storedPath,
        String fileType,
        long fileSize,
        String fileHash,
        String extractionStatus,
        String extractionError,
        LocalDateTime createdAt) {
}
