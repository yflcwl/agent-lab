package com.example.incremental.migration;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.persistence.agent.AgentHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.mybatis.spring.annotation.MapperScan;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("legacy-run-import")
@EnableAutoConfiguration
@EnableConfigurationProperties(DemoProperties.class)
@Import({AgentHistoryService.class, LegacyRunImporter.class})
@MapperScan("com.example.incremental.persistence.agent.mapper")
public class LegacyRunImportApplication {
    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(LegacyRunImportApplication.class)
                .web(WebApplicationType.NONE).profiles("legacy-run-import").run(args)) {
            // Closing the maintenance context releases its datasource after import.
        }
    }

    @Bean
    ObjectMapper migrationObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "close")
    DataSource migrationDataSource(DemoProperties properties) {
        if (!"pgvector".equals(properties.getRagStore())) {
            throw new IllegalStateException("Run 导入需要配置 PostgreSQL");
        }
        var source = new HikariDataSource();
        source.setJdbcUrl(properties.getRagJdbcUrl());
        source.setUsername(properties.getRagDatabaseUsername());
        source.setPassword(properties.getRagDatabasePassword());
        return source;
    }

    @Bean
    @Order(1)
    ApplicationRunner importLegacyRuns(LegacyRunImporter importer, DataSource source, TransactionTemplate transaction,
                                      @Value("${demo.run-import.apply:false}") boolean apply) {
        return args -> {
            var report = transaction.execute(status -> {
                try {
                    new ResourceDatabasePopulator(
                            new ClassPathResource("db/migration/V1__agent_history.sql"),
                            new ClassPathResource("db/migration/V2__authoritative_agent_run.sql"))
                            .populate(DataSourceUtils.getConnection(source));
                    var result = importer.importRuns(apply);
                    if (!apply) status.setRollbackOnly();
                    return result;
                } catch (Exception error) {
                    throw new IllegalStateException("旧 Run 导入失败，事务已回滚", error);
                }
            });
            report.forEach(System.out::println);
        };
    }
}
