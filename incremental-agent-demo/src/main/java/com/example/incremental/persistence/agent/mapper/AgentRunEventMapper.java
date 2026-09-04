package com.example.incremental.persistence.agent.mapper;

import com.example.incremental.persistence.agent.AgentRunEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentRunEventMapper {

    @Insert("""
            INSERT INTO agent_run_event (id, run_id, conversation_id, sequence_no, event_type, payload,
                                         reply_id, block_id, tool_call_id, created_at)
            VALUES (#{event.id}, #{event.runId}, #{event.conversationId}, #{event.sequenceNo}, #{event.eventType},
                    CAST(#{event.payload} AS JSONB), #{event.replyId}, #{event.blockId}, #{event.toolCallId},
                    #{event.createdAt})
            """)
    int insert(@Param("event") AgentRunEvent event);

    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM agent_run_event WHERE run_id = #{runId}")
    long nextSequenceNo(@Param("runId") String runId);
}
