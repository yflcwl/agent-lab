package com.example.incremental.config;

import com.example.incremental.rag.mapper.TaskRagChunkMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
@MapperScan({"com.example.incremental.rag.mapper", "com.example.incremental.persistence.agent.mapper",
        "com.example.incremental.persistence.writing.mapper"})
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
            DemoProperties properties) {
        return args -> {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__agent_history.sql"))
                    .execute(pgVectorDataSource);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__authoritative_agent_run.sql"))
                    .execute(pgVectorDataSource);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__chapter_stage.sql"))
                    .execute(pgVectorDataSource);
            JdbcTemplate jdbc = new JdbcTemplate(pgVectorDataSource);
            Boolean hasMessageRunForeignKey = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_constraint
                        WHERE conname = 'fk_agent_message_run'
                          AND conrelid = 'agent_message'::regclass
                    )
                    """, Boolean.class);
            if (!Boolean.TRUE.equals(hasMessageRunForeignKey)) {
                jdbc.execute("""
                        ALTER TABLE agent_message
                            ADD CONSTRAINT fk_agent_message_run
                            FOREIGN KEY (run_id) REFERENCES agent_run (id)
                        """);
            }
            ragChunkMapper.createVectorExtension();
            ragChunkMapper.createTable(properties.getRagEmbeddingDimensions());
            ragChunkMapper.createTaskIndex();
            ragChunkMapper.createEmbeddingIndex();
        };
    }
}
