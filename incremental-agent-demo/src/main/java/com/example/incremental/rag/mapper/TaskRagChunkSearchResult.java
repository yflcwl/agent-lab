package com.example.incremental.rag.mapper;

public record TaskRagChunkSearchResult(
        String sourceId,
        String filename,
        String chunkId,
        String content,
        Double score) {
}
