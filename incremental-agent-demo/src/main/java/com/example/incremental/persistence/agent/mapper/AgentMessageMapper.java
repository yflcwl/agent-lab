package com.example.incremental.persistence.agent.mapper;

import com.example.incremental.persistence.agent.AgentMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentMessageMapper {

    @Insert("""
            INSERT INTO agent_message (id, conversation_id, run_id, role, content, content_json, status, sequence_no, created_at)
            VALUES (#{message.id}, #{message.conversationId}, #{message.runId}, #{message.role}, #{message.content},
                    CAST(#{message.contentJson} AS JSONB), #{message.status}, #{message.sequenceNo}, #{message.createdAt})
            """)
    int insert(@Param("message") AgentMessage message);

    @Select("SELECT * FROM agent_message WHERE id = #{id}")
    AgentMessage findById(@Param("id") String id);

    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM agent_message WHERE conversation_id = #{conversationId}")
    long nextSequenceNo(@Param("conversationId") String conversationId);

    @Select("""
            SELECT * FROM agent_message
            WHERE conversation_id = #{conversationId}
            ORDER BY sequence_no ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentMessage> findByConversationId(
            @Param("conversationId") String conversationId,
            @Param("offset") long offset,
            @Param("limit") int limit);
}
