package com.example.incremental.persistence.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.incremental.persistence.agent.AgentConversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
}
