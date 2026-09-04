package com.example.incremental.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TaskRagChunkMapper extends BaseMapper<TaskRagChunkEntity> {

    @Update("CREATE EXTENSION IF NOT EXISTS vector")
    void createVectorExtension();

    @Update("""
            CREATE TABLE IF NOT EXISTS task_rag_chunk (
              task_id VARCHAR(64) NOT NULL,
              chunk_id VARCHAR(255) NOT NULL,
              source_id VARCHAR(255) NOT NULL,
              filename VARCHAR(255) NOT NULL,
              content TEXT NOT NULL,
              embedding vector(${dimensions}) NOT NULL,
              PRIMARY KEY (task_id, chunk_id)
            )
            """)
    void createTable(@Param("dimensions") int dimensions);

    @Update("CREATE INDEX IF NOT EXISTS task_rag_chunk_task_id_idx ON task_rag_chunk (task_id)")
    void createTaskIndex();

    @Update("""
            CREATE INDEX IF NOT EXISTS task_rag_chunk_embedding_idx
            ON task_rag_chunk USING hnsw (embedding vector_cosine_ops)
            """)
    void createEmbeddingIndex();

    @Insert("""
            <script>
            INSERT INTO task_rag_chunk (task_id, chunk_id, source_id, filename, content, embedding)
            VALUES
            <foreach collection="records" item="record" separator=",">
              (#{record.taskId}, #{record.chunkId}, #{record.sourceId}, #{record.filename},
               #{record.content}, CAST(#{record.embedding} AS vector))
            </foreach>
            ON CONFLICT (task_id, chunk_id) DO UPDATE SET
              source_id = EXCLUDED.source_id,
              filename = EXCLUDED.filename,
              content = EXCLUDED.content,
              embedding = EXCLUDED.embedding
            </script>
            """)
    int upsert(@Param("records") List<TaskRagChunkEntity> records);

    @Select("""
            SELECT source_id, filename, chunk_id, content,
                   1 - (embedding <=> CAST(#{queryEmbedding} AS vector)) AS score
            FROM task_rag_chunk
            WHERE task_id = #{taskId}
            ORDER BY embedding <=> CAST(#{queryEmbedding} AS vector)
            LIMIT #{topK}
            """)
    List<TaskRagChunkSearchResult> search(
            @Param("taskId") String taskId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK);

    @Select("SELECT COUNT(1) FROM task_rag_chunk WHERE task_id = #{taskId}")
    int countByTaskId(@Param("taskId") String taskId);

    @Delete("DELETE FROM task_rag_chunk WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") String taskId);
}
