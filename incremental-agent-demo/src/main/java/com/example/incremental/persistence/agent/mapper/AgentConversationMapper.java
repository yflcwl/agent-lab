package com.example.incremental.persistence.agent.mapper;

import com.example.incremental.persistence.agent.AgentConversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentConversationMapper {

    @Insert("""
            INSERT INTO agent_conversation (id, tenant_id, user_id, agent_id, title, status, created_at, updated_at)
            VALUES (#{conversation.id}, #{conversation.tenantId}, #{conversation.userId}, #{conversation.agentId},
                    #{conversation.title}, #{conversation.status}, #{conversation.createdAt}, #{conversation.updatedAt})
            """)
    int insert(@Param("conversation") AgentConversation conversation);

    @Select("SELECT * FROM agent_conversation WHERE id = #{id} FOR UPDATE")
    AgentConversation lockById(@Param("id") String id);

    @Select("SELECT * FROM agent_conversation WHERE id = #{id}")
    AgentConversation findById(@Param("id") String id);

    @Update("UPDATE agent_conversation SET updated_at = #{updatedAt} WHERE id = #{id}")
    int touch(@Param("id") String id, @Param("updatedAt") java.time.Instant updatedAt);

    @Select("""
            SELECT * FROM agent_conversation
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND (tenant_id = #{tenantId} OR (tenant_id IS NULL AND #{tenantId} IS NULL))
            ORDER BY updated_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentConversation> findActiveByUser(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("offset") long offset,
            @Param("limit") int limit);
}
