package com.example.incremental.rag;

import com.example.incremental.rag.TempRagHit;

import java.util.List;

interface TempRagVectorStore {

    void add(List<TempRagVectorRecord> records);

    List<TempRagHit> search(float[] queryEmbedding, int topK);

    boolean isEmpty();

    void clear();
}

