package com.example.incremental.runtime;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.migration.LegacyRunImporter;
import com.example.incremental.migration.LegacyRunImportApplication;
import com.example.incremental.persistence.agent.AgentHistoryService;
import com.example.incremental.persistence.agent.PostgresAgentRunRepository;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_TEST_JDBC_URL", matches = ".+")
class PostgresAgentRunRepositoryTest {
    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate sql;
    private JdbcTemplate admin;
    private String schema;
    private AgentRunRuntime runtime;
    private AgentRunRepository repository;
    private DemoProperties properties;

    @BeforeEach
    void setUp() {
        String url = System.getenv("RUN_TEST_JDBC_URL");
        var source = new DriverManagerDataSource(url, System.getenv("RUN_TEST_DB_USER"), System.getenv("RUN_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(source);
        schema = "run_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var isolated = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("RUN_TEST_DB_USER"), System.getenv("RUN_TEST_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__agent_history.sql"),
                new ClassPathResource("db/migration/V2__authoritative_agent_run.sql")).execute(isolated);
        properties = new DemoProperties();
        properties.setStateRoot(directory.resolve("state"));
        properties.setDataRoot(directory.resolve("data"));
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test", Map.of("demo.rag-store", "pgvector")));
        context.registerBean(DataSource.class, () -> isolated);
        context.registerBean(DemoProperties.class, () -> properties);
        context.register(Config.class);
        context.refresh();
        sql = new JdbcTemplate(isolated);
        runtime = context.getBean(AgentRunRuntime.class);
        repository = context.getBean(AgentRunRepository.class);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (schema != null && schema.matches("run_test_[0-9a-f]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void interruptResumesFromDatabaseAndFinishedCannotOverwriteError() {
        var run = awaitingRun();
        assertThat(properties.getStateRoot().resolve("runs")).doesNotExist();
        var restored = new AgentRunRuntime(repository, context.getBean(AgentRunEventRecorder.class));
        assertThat(restored.findAwaitingConfirmation(run.correlationId()).threadId()).isEqualTo(run.threadId());
        restored.prepareResume(run.correlationId(), run.runId(), List.of(new AgentRunDecision("call-1", true)));
        restored.continueRun(run, restored.findHistory(run.runId()), Flux.just(
                new AguiEvent.RunStarted(run.threadId(), run.runId()),
                new AguiEvent.RunError(run.threadId(), run.runId(), "model failed", "TEST_ERROR"),
                new AguiEvent.RunFinished(run.threadId(), run.runId()))).collectList().block();
        assertThat(repository.find(run.runId()).status()).isEqualTo(AgentRunStatus.ERROR);
        assertThat(sql.queryForObject("SELECT status FROM agent_run WHERE id=?", String.class, run.runId())).isEqualTo("ERROR");
        assertThat(sql.queryForObject("SELECT error_code FROM agent_run WHERE id=?", String.class, run.runId())).isEqualTo("TEST_ERROR");
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run_event WHERE event_type='RUN_STATE_CHANGED' "
                + "AND payload->>'to'='ERROR'", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run_event WHERE event_type='RUN_FINISHED' "
                + "AND payload->>'outcome'='COMPLETED'", Integer.class)).isZero();
        assertThat(context.getBean(AgentHistoryService.class).findHistory(run.correlationId()).runs().getFirst().status())
                .isEqualTo(AgentRunStatus.ERROR);
    }

    @Test
    void onlyOneConcurrentResumeCanClaimTheRun() throws Exception {
        var run = awaitingRun();
        var other = new AgentRunRuntime(repository, context.getBean(AgentRunEventRecorder.class));
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> first = () -> claim(runtime, run, gate);
            Callable<Boolean> second = () -> claim(other, run, gate);
            var a = pool.submit(first);
            var b = pool.submit(second);
            gate.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run_event WHERE payload->>'to'='RESUMING'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void stateAndEventRollbackTogetherWhenEventInsertFails() {
        var run = runtime.createRun(UUID.randomUUID().toString(), "chapter-test");
        var history = runtime.beginHistory(run, "user", "agent", "test", "start");
        sql.execute("ALTER TABLE agent_run_event ADD CONSTRAINT reject_transition CHECK (event_type <> 'RUN_STATE_CHANGED')");
        assertThatThrownBy(() -> runtime.start(run, history, Flux.empty()).blockLast()).isInstanceOf(RuntimeException.class);
        assertThat(repository.find(run.runId()).status()).isEqualTo(AgentRunStatus.CREATED);
        assertThat(repository.find(run.runId()).lockVersion()).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run_event", Integer.class)).isZero();
    }

    @Test
    void runCreationRollsBackTriggerMessageOnDuplicateId() {
        var run = runtime.createRun(UUID.randomUUID().toString(), "chapter-test");
        runtime.beginHistory(run, "user", "agent", "test", "original");
        assertThatThrownBy(() -> runtime.beginHistory(run, "user", "agent", "test", "duplicate"))
                .isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_message", Integer.class)).isEqualTo(1);
    }

    @Test
    void legacyImportIsExplicitIdempotentAndPreservesAgentScopeState() throws Exception {
        var json = context.getBean(ObjectMapper.class);
        var task = new TaskWorkspaceService(json, properties).createTask("owner", "# reference", Map.of("source.md", "data"));
        String runId = "writing-" + UUID.randomUUID();
        Path runDirectory = properties.getStateRoot().resolve("runs");
        Files.createDirectories(runDirectory);
        var now = java.time.Instant.now();
        var legacy = new AgentRunRecord(runId, task.id(), task.sessionId() + "-chapter-001-active",
                AgentRunStatus.AWAITING_CONFIRM, List.of(new AgentRunInterrupt("i-1", "call-1", "commit_chapter",
                Map.of("stage_id", "stage-1"))), now, now, 0);
        var oldJson = json.valueToTree(legacy);
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldJson).remove("lockVersion");
        json.writeValue(runDirectory.resolve(runId + ".json").toFile(), oldJson);
        Path agentState = properties.getStateRoot().resolve("owner").resolve(legacy.threadId()).resolve("agent_state.json");
        Files.createDirectories(agentState.getParent());
        Files.writeString(agentState, "{\"context\":[\"retained\"]}");
        byte[] original = Files.readAllBytes(agentState);
        var importer = context.getBean(LegacyRunImporter.class);
        assertThat(importer.importRuns(false)).singleElement().asString().startsWith("WOULD_IMPORT");
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run", Integer.class)).isZero();
        importer.importRuns(true);
        assertThat(repository.find(runId).pendingInterrupts()).isEqualTo(legacy.pendingInterrupts());
        assertThat(importer.importRuns(true)).singleElement().asString().startsWith("SKIP");
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run", Integer.class)).isEqualTo(1);
        assertThat(Files.readAllBytes(agentState)).isEqualTo(original);
        assertThat(runDirectory.resolve(runId + ".json")).exists();
        runtime.prepareResume(task.id(), runId, List.of(new AgentRunDecision("call-1", true)));
        assertThat(json.readTree(runDirectory.resolve(runId + ".json").toFile()).path("status").asText())
                .isEqualTo("AWAITING_CONFIRM");
        assertThat(repository.find(runId).status()).isEqualTo(AgentRunStatus.RESUMING);
    }

    private AgentRunContext awaitingRun() {
        var run = runtime.createRun(UUID.randomUUID().toString(), "chapter-test");
        var history = runtime.beginHistory(run, "user", "agent", "test", "start");
        runtime.start(run, history, Flux.just(new AguiEvent.RunStarted(run.threadId(), run.runId()),
                new AguiEvent.RunFinished(run.threadId(), run.runId(), null,
                        new AguiEvent.RunFinishedInterruptOutcome(List.of(
                                new AguiEvent.Interrupt("i-1", "ASK", "review", "call-1", Map.of(), null,
                                        Map.of("toolName", "commit_chapter", "toolInput", Map.of("stage_id", "stage-1"))))))))
                .blockLast();
        return run;
    }

    @Test
    void malformedLegacyFileRollsBackTheWholeBatch() throws Exception {
        var json = context.getBean(ObjectMapper.class);
        var task = new TaskWorkspaceService(json, properties).createTask("owner", "# reference", Map.of());
        var runDirectory = properties.getStateRoot().resolve("runs");
        Files.createDirectories(runDirectory);
        var now = java.time.Instant.now();
        var valid = new AgentRunRecord("run-00000000-0000-0000-0000-000000000001", task.id(), "session",
                AgentRunStatus.FINISHED, List.of(), now, now, 0);
        var invalid = new AgentRunRecord("run-00000000-0000-0000-0000-000000000002", task.id(), null,
                AgentRunStatus.FINISHED, List.of(), now, now, 0);
        json.writeValue(runDirectory.resolve(valid.runId() + ".json").toFile(), valid);
        json.writeValue(runDirectory.resolve(invalid.runId() + ".json").toFile(), invalid);
        assertThatThrownBy(() -> context.getBean(LegacyRunImporter.class).importRuns(true))
                .hasMessageContaining("字段不完整");
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_message", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM agent_run_event", Integer.class)).isZero();
    }

    @Test
    void maintenancePreviewAlsoRollsBackSchemaChanges() {
        String previewSchema = schema + "_preview";
        admin.execute("CREATE SCHEMA " + previewSchema);
        try {
            String url = System.getenv("RUN_TEST_JDBC_URL");
            LegacyRunImportApplication.main(new String[]{
                    "--demo.rag-jdbc-url=" + url + (url.contains("?") ? "&" : "?") + "currentSchema=" + previewSchema,
                    "--demo.rag-database-username=" + System.getenv("RUN_TEST_DB_USER"),
                    "--demo.rag-database-password=" + System.getenv("RUN_TEST_DB_PASSWORD"),
                    "--demo.state-root=" + properties.getStateRoot(),
                    "--demo.data-root=" + properties.getDataRoot(),
                    "--demo.run-import.apply=false", "--logging.level.com.example.incremental=INFO"
            });
            assertThat(admin.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=?",
                    Integer.class, previewSchema)).isZero();
        } finally {
            admin.execute("DROP SCHEMA " + previewSchema + " CASCADE");
        }
    }

    private boolean claim(AgentRunRuntime claimant, AgentRunContext run, CountDownLatch gate) throws Exception {
        gate.await();
        try {
            claimant.prepareResume(run.correlationId(), run.runId(), List.of(new AgentRunDecision("call-1", true)));
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan("com.example.incremental.persistence.agent.mapper")
    @Import({AgentHistoryService.class, PostgresAgentRunRepository.class, AgentRunRuntime.class,
            AgentRunEventRecorder.class, LegacyRunImporter.class})
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
    }
}
