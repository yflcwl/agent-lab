package com.example.incremental.rag;

record TempRagVectorRecord(
        String sourceId,
        String filename,
        String chunkId,
        String content,
        float[] embedding) {
}

