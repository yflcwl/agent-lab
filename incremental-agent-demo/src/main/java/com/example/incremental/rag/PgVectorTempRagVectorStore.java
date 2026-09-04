package com.example.incremental.rag;

import com.example.incremental.rag.mapper.TaskRagChunkEntity;
import com.example.incremental.rag.mapper.TaskRagChunkMapper;

import java.util.List;

final class PgVectorTempRagVectorStore implements TempRagVectorStore {

    private final String taskId;
    private final TaskRagChunkMapper ragChunkMapper;

    PgVectorTempRagVectorStore(String taskId, TaskRagChunkMapper ragChunkMapper) {
        this.taskId = taskId;
        this.ragChunkMapper = ragChunkMapper;
    }

    @Override
    public void add(List<TempRagVectorRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        ragChunkMapper.upsert(records.stream().map(record -> new TaskRagChunkEntity(
                taskId,
                record.chunkId(),
                record.sourceId(),
                record.filename(),
                record.content(),
                vector(record.embedding()),
                null)).toList());
    }

    @Override
    public List<TempRagHit> search(float[] queryEmbedding, int topK) {
        return ragChunkMapper.search(taskId, vector(queryEmbedding), topK).stream()
                .map(chunk -> new TempRagHit(
                        chunk.sourceId(),
                        chunk.filename(),
                        chunk.chunkId(),
                        chunk.content(),
                        chunk.score()))
                .toList();
    }

    @Override
    public boolean isEmpty() {
        return ragChunkMapper.countByTaskId(taskId) == 0;
    }

    @Override
    public void clear() {
        ragChunkMapper.deleteByTaskId(taskId);
    }

    private String vector(float[] values) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(values[index]);
        }
        return value.append(']').toString();
    }
}
