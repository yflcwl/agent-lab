package com.example.incremental.persistence.agent.mapper;

import com.example.incremental.persistence.agent.AgentRun;
import com.example.incremental.persistence.agent.AgentRunStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface AgentRunMapper {

    @Insert("""
            INSERT INTO agent_run (id, conversation_id, agent_id, trigger_message_id, status, error_code, error_message,
                                   started_at, finished_at, created_at, updated_at)
            VALUES (#{run.id}, #{run.conversationId}, #{run.agentId}, #{run.triggerMessageId}, #{run.status},
                    #{run.errorCode}, #{run.errorMessage}, #{run.startedAt}, #{run.finishedAt},
                    #{run.createdAt}, #{run.updatedAt})
            """)
    int insert(@Param("run") AgentRun run);

    @Select("SELECT * FROM agent_run WHERE id = #{id}")
    AgentRun findById(@Param("id") String id);

    @Select("SELECT * FROM agent_run WHERE id = #{id} FOR UPDATE")
    AgentRun lockById(@Param("id") String id);

    @Update("""
            UPDATE agent_run
            SET status = #{status},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                started_at = COALESCE(#{startedAt}, started_at),
                finished_at = COALESCE(#{finishedAt}, finished_at),
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateStatus(
            @Param("id") String id,
            @Param("status") AgentRunStatus status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);
}
