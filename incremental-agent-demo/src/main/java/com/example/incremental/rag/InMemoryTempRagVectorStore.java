package com.example.incremental.rag;

import com.example.incremental.rag.TempRagHit;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class InMemoryTempRagVectorStore implements TempRagVectorStore {

    private final List<TempRagVectorRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void add(List<TempRagVectorRecord> newRecords) {
        records.addAll(newRecords);
    }

    @Override
    public List<TempRagHit> search(float[] queryEmbedding, int topK) {
        return records.stream()
                .map(record -> new TempRagHit(
                        record.sourceId(),
                        record.filename(),
                        record.chunkId(),
                        record.content(),
                        cosineSimilarity(queryEmbedding, record.embedding())))
                .sorted(Comparator.comparingDouble(TempRagHit::score).reversed())
                .limit(topK)
                .toList();
    }

    @Override
    public boolean isEmpty() {
        return records.isEmpty();
    }

    @Override
    public void clear() {
        records.clear();
    }

    private double cosineSimilarity(float[] left, float[] right) {
        double dotProduct = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dotProduct / Math.sqrt(leftNorm * rightNorm);
    }
}

