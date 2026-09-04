package com.example.incremental.rag;

public record TempRagHit(
        String sourceId,
        String filename,
        String chunkId,
        String content,
        double score) {
}

