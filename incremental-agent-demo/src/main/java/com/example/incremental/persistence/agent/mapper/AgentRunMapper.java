package com.example.incremental.persistence.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.incremental.persistence.agent.AgentRun;
import com.example.incremental.runtime.AgentRunStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.io.Serializable;
import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
    String COLUMNS = "id, conversation_id, agent_id, trigger_message_id, status, error_code, error_message, "
            + "started_at, finished_at, created_at, updated_at, correlation_id, thread_id, "
            + "pending_interrupts::text AS pending_interrupts_json, lock_version";

    /** Records are immutable, so use constructor mapping instead of MP's setter-based default result map. */
    @Select("SELECT " + COLUMNS + " FROM agent_run WHERE id = #{id}")
    @Override
    AgentRun selectById(Serializable id);

    @Select("SELECT " + COLUMNS + " FROM agent_run WHERE id = #{id} FOR UPDATE")
    AgentRun lockById(@Param("id") String id);

    @Select("SELECT " + COLUMNS + " FROM agent_run WHERE conversation_id = #{conversationId} ORDER BY created_at ASC")
    List<AgentRun> findByConversationId(@Param("conversationId") String conversationId);

    @Select("SELECT " + COLUMNS + " FROM agent_run WHERE correlation_id = #{correlationId} "
            + "AND status = 'AWAITING_CONFIRM' AND pending_interrupts <> '[]'::jsonb "
            + "ORDER BY updated_at DESC LIMIT 1")
    AgentRun findAwaitingConfirmation(@Param("correlationId") String correlationId);

    @Update("""
            UPDATE agent_run SET status = #{status}, pending_interrupts = CAST(#{interrupts} AS JSONB),
                error_code = #{errorCode}, error_message = #{errorMessage},
                started_at = COALESCE(started_at, #{startedAt}), finished_at = #{finishedAt},
                updated_at = #{updatedAt}, lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = #{version}
            """)
    int transition(@Param("id") String id, @Param("version") long version,
                   @Param("status") AgentRunStatus status, @Param("interrupts") String interrupts,
                   @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
                   @Param("startedAt") Instant startedAt, @Param("finishedAt") Instant finishedAt,
                   @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE agent_run SET correlation_id = #{correlationId}, thread_id = #{threadId}, status = #{status},
                pending_interrupts = CAST(#{interrupts} AS JSONB), updated_at = #{updatedAt},
                finished_at = #{finishedAt}, lock_version = lock_version + 1
            WHERE id = #{id} AND lock_version = 0 AND thread_id IS NULL
            """)
    int importLegacy(@Param("id") String id, @Param("correlationId") String correlationId,
                     @Param("threadId") String threadId, @Param("status") AgentRunStatus status,
                     @Param("interrupts") String interrupts, @Param("updatedAt") Instant updatedAt,
                     @Param("finishedAt") Instant finishedAt);
}
