package com.example.incremental.rag.mapper;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("task_rag_chunk")
public record TaskRagChunkEntity(
        @TableField("task_id") String taskId,
        @TableField("chunk_id") String chunkId,
        @TableField("source_id") String sourceId,
        String filename,
        String content,
        String embedding,
        @TableField(exist = false) Double score) {
}
