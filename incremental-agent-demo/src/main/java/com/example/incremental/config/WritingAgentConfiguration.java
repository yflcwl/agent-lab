package com.example.incremental.config;

import com.example.incremental.agent.AgentScopeWritingAgent;
import com.example.incremental.agent.ResearchTools;
import com.example.incremental.agent.WritingTools;
import com.example.incremental.writing.WritingAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class WritingAgentConfiguration {

    @Bean
    ObjectMapper taskObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    PermissionContextState writingPermissionContext() {
        return PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addAskRule("commit_chapter", new PermissionRule(
                        "commit_chapter", null, PermissionBehavior.ASK, "chapter-review"))
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "demo.agent.enabled", havingValue = "true", matchIfMissing = true)
    HarnessAgent writingHarnessAgent(
            DemoProperties properties,
            WritingTools writingTools,
            ResearchTools researchTools,
            PermissionContextState writingPermissionContext) {
        Path agentWorkspace = properties.getAgentWorkspace().toAbsolutePath().normalize();
        try {
            Files.createDirectories(agentWorkspace);
        } catch (IOException e) {
            throw new IllegalStateException("创建 Agent workspace 失败", e);
        }
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(writingTools);
        toolkit.registerTool(researchTools);
        return HarnessAgent.builder()
                .name("incremental-writing-agent")
                .agentId("incremental-writing-agent")
                .sysPrompt(requireSystemPrompt(properties.getWritingSystemPrompt()))
                .model(properties.getModel())
                .workspace(agentWorkspace)
                .stateStore(new JsonFileAgentStateStore(properties.getStateRoot().toAbsolutePath().normalize()))
                .toolkit(toolkit)
                .permissionContext(writingPermissionContext)
                .enablePendingToolRecovery(true)
                .maxIters(15)
                .disableFilesystemTools()
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableShellTool()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "demo.agent.enabled", havingValue = "true", matchIfMissing = true)
    WritingAgent writingAgent(
            HarnessAgent writingHarnessAgent,
            DemoProperties properties,
            PermissionContextState writingPermissionContext) {
        return new AgentScopeWritingAgent(writingHarnessAgent, properties.getTimeout(), writingPermissionContext);
    }

    private String requireSystemPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("未配置写作 Agent 系统提示词");
        }
        return prompt.trim();
    }
}

