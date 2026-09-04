package com.example.incremental.config;

import com.example.incremental.rag.mapper.TaskRagChunkMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
@MapperScan("com.example.incremental.rag.mapper")
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
    ApplicationRunner initializePgVectorSchema(TaskRagChunkMapper ragChunkMapper, DemoProperties properties) {
        return args -> {
            ragChunkMapper.createVectorExtension();
            ragChunkMapper.createTable(properties.getRagEmbeddingDimensions());
            ragChunkMapper.createTaskIndex();
            ragChunkMapper.createEmbeddingIndex();
        };
    }
}
