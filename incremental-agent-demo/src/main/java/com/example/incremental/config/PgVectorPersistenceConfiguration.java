package com.example.incremental.config;

import com.example.incremental.rag.mapper.TaskRagChunkMapper;
import com.example.incremental.persistence.agent.mapper.AgentHistorySchemaMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
@MapperScan({"com.example.incremental.rag.mapper", "com.example.incremental.persistence.agent.mapper"})
public class PgVectorPersistenceConfiguration {

    @Bean(destroyMethod = "close")
    DataSource pgVectorDataSource(DemoProperties properties) {
        if (!StringUtils.hasText(properties.getRagJdbcUrl())
                || !StringUtils.hasText(properties.getRagDatabaseUsername())
                || !StringUtils.hasText(properties.getRagDatabasePassword())) {
            throw new IllegalStateException("使用 pgvector 时必须配置 RAG 数据库连接信息");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getRagJdbcUrl());
        dataSource.setUsername(properties.getRagDatabaseUsername());
        dataSource.setPassword(properties.getRagDatabasePassword());
        return dataSource;
    }

    @Bean
    @Order(0)
    ApplicationRunner initializePgVectorSchema(
            DataSource pgVectorDataSource,
            TaskRagChunkMapper ragChunkMapper,
            AgentHistorySchemaMapper agentHistorySchemaMapper,
            DemoProperties properties) {
        return args -> {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__agent_history.sql"))
                    .execute(pgVectorDataSource);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__authoritative_agent_run.sql"))
                    .execute(pgVectorDataSource);
            if (!agentHistorySchemaMapper.hasMessageRunForeignKey()) {
                agentHistorySchemaMapper.addMessageRunForeignKey();
            }
            ragChunkMapper.createVectorExtension();
            ragChunkMapper.createTable(properties.getRagEmbeddingDimensions());
            ragChunkMapper.createTaskIndex();
            ragChunkMapper.createEmbeddingIndex();
        };
    }
}
