package com.example.incremental.persistence.agent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentHistorySchemaMapper {

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM pg_constraint
                WHERE conname = 'fk_agent_message_run'
                  AND conrelid = 'agent_message'::regclass
            )
            """)
    boolean hasMessageRunForeignKey();

    @Update("""
            ALTER TABLE agent_message
                ADD CONSTRAINT fk_agent_message_run
                FOREIGN KEY (run_id) REFERENCES agent_run (id)
            """)
    void addMessageRunForeignKey();
}
