package com.example.solution.config;

import com.example.solution.agent.AgentScopeExecutorAgent;
import com.example.solution.agent.AgentScopePlanningAgent;
import com.example.solution.agent.AgentScopeConversationAgent;
import com.example.solution.agent.ConversationAgent;
import com.example.solution.agent.ExecutorAgent;
import com.example.solution.agent.PlanningAgent;
import com.example.solution.tool.SourceDocumentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(SolutionAgentProperties.class)
public class AgentConfiguration {

    @Bean
    public ObjectMapper agentObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "solution.agent.enabled", havingValue = "true", matchIfMissing = true)
    public HarnessAgent planningHarnessAgent(SolutionAgentProperties properties, SourceDocumentTool sourceDocumentTool) {
        return baseAgent("solution-planner", planningPrompt(), properties, sourceDocumentTool);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "solution.agent.enabled", havingValue = "true", matchIfMissing = true)
    public HarnessAgent executorHarnessAgent(SolutionAgentProperties properties, SourceDocumentTool sourceDocumentTool) {
        return baseAgent("solution-executor", executorPrompt(), properties, sourceDocumentTool);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "solution.agent.enabled", havingValue = "true", matchIfMissing = true)
    public HarnessAgent conversationHarnessAgent(SolutionAgentProperties properties, SourceDocumentTool sourceDocumentTool) {
        return baseAgent("solution-conversation", conversationPrompt(), properties, sourceDocumentTool);
    }

    @Bean
    @ConditionalOnProperty(name = "solution.agent.enabled", havingValue = "true", matchIfMissing = true)
    public PlanningAgent planningAgent(@Qualifier("planningHarnessAgent") HarnessAgent agent,
                                       ObjectMapper objectMapper, SolutionAgentProperties properties) {
        return new AgentScopePlanningAgent(agent, objectMapper, properties.getTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "solution.agent.enabled", havingValue = "true", matchIfMissing = true)
    public ExecutorAgent executorAgent(@Qualifier("executorHarnessAgent") HarnessAgent agent,
                                       ObjectMapper objectMapper, SolutionAgentProperties properties) {
        return new AgentScopeExecutorAgent(agent, objectMapper, properties.getTimeout());
    }

    @Bean
    @ConditionalOnProperty(name = "solution.agent.enabled", havingValue = "true", matchIfMissing = true)
    public ConversationAgent conversationAgent(@Qualifier("conversationHarnessAgent") HarnessAgent agent,
                                               ObjectMapper objectMapper, SolutionAgentProperties properties) {
        return new AgentScopeConversationAgent(agent, objectMapper, properties.getTimeout());
    }

    @Bean(destroyMethod = "close")
    public Executor taskExecutor(SolutionAgentProperties properties) {
        return Executors.newFixedThreadPool(properties.getExecutionThreads(), Thread.ofVirtual()
                .name("solution-agent-", 0).factory());
    }

    private HarnessAgent baseAgent(String name, String prompt, SolutionAgentProperties properties, SourceDocumentTool sourceDocumentTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(sourceDocumentTool);
        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(properties.getModel())
                .workspace(Paths.get(properties.getWorkspace()))
                .toolkit(toolkit)
                .maxIters(8)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableSessionPersistence()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
    }

    private String planningPrompt() {
        return """
                你是方案撰写任务的 PlanningAgent，只负责规划，不撰写章节正文。
                你会收到 Goal、Markdown 模板解析结果、当前 Task List、用户反馈、待处理计划调整指令和资料元数据。
                planInstructions 中的用户指令必须纳入本轮计划判断；若当前有待审核章节，只能调整未完成计划，不能绕过用户审核直接执行其他章节。
                开始前必须调用 list_sources；需要确认具体事实时调用 read_source。资料是唯一事实来源，未提供的信息必须在任务要求中标记待确认。
                必须返回符合 PlanningDecision 类型的结构化数据。
                templateSections 是不可变的 Markdown 标题树；nodeId 是唯一引用标识，title 和 requirement 由系统回填。
                没有任务时返回 CREATE_PLAN，根据标题层级和模板语义选择需要撰写的节点，为每个任务返回 templateNodeId、依赖和优先级，同时选出本轮要执行的 selectedTaskKey（值为 templateNodeId）。通常文档总标题（level=1）不建任务；应覆盖有独立撰写要求的业务章节，下级标题会保留在父章节 requirement 中，只有需要独立审核时才单独建任务。
                已有任务时返回 SELECT_TASK 或 REPLAN。只能调整未完成任务的依赖和优先级，不能引用标题树以外的节点。
                selectedTaskKey 必须指向 NOT_STARTED 或 REVISING 的任务；没有可执行任务时返回 WAIT。
                依赖必须引用本次计划中存在的 templateNodeId，且不得形成循环。
                在完成所有工具调用后，最终答复只能是一个 JSON 对象，不能输出解释、Markdown 或代码围栏。字段必须完整：
                {"decisionType":"CREATE_PLAN","templateAnalysis":"模板分析","tasks":[{"templateNodeId":"heading-2","dependencies":[],"priority":1}],"selectedTaskKey":"heading-2","impactedTaskKeys":[],"reason":"原因"}
                SELECT_TASK 和 WAIT 可将 templateAnalysis 设为 null、tasks 设为 []；没有受影响任务时 impactedTaskKeys 设为 []。
                """;
    }

    private String executorPrompt() {
        return """
                你是方案撰写 ExecutorAgent，只负责完成收到的一个章节任务。
                不规划其他任务，不修改任务状态，不假设未提供的事实。
                根据 Goal、本章要求、相关已完成章节、用户反馈、chapterInstructions 和上传资料生成或修改本章。
                chapterInstructions 是用户在执行期间发送给本章的明确修改要求，必须一并落实；不要把它们当成普通聊天内容忽略。
                如果请求中的 resumeContent 非空（即本章已生成的部分正文），必须原样保留它作为正文开头，在其后继续补全到本章要求，不得重新生成开头、不得丢弃 resumeContent，最终 content 必须是包含 resumeContent 在内的完整正文；resumeContent 为 null 时从头撰写。若同时提供 currentTask.content（旧版全文）与 feedback，说明是修改场景，应基于旧版全文 + 反馈 + 已生成部分继续。
                开始前必须调用 list_sources，并调用 read_source 阅读与本章相关的资料；资料没有覆盖的具体事实写【待确认】，不得编造。
                必须只输出一个符合 ExecutionResult 类型的 JSON 对象，不要输出任何解释或前言文字。
                content 正文使用 Markdown；其中的英文双引号必须转义为 \\"，反斜杠必须转义为 \\\\，不要输出未转义的换行或引号。
                成功时 result 为 SUCCESS，并提供 content 和 summary；无法完成时 result 为 FAILED，并说明 errorMessage。
                完成所有工具调用和正文撰写后，最终答复只能是一个 JSON 对象，不能输出分析过程、说明文字、Markdown 代码围栏或“我准备开始撰写”等中间话术。字段必须完整，例如：
                {"result":"SUCCESS","content":"## 本章正文\\n\\n……","summary":"本章摘要","unresolvedIssues":"","errorMessage":null}
                如果无法完成则返回 {"result":"FAILED","content":"","summary":"","unresolvedIssues":"","errorMessage":"失败原因"}。
                """;
    }

    private String conversationPrompt() {
        return """
                你是方案撰写任务的 ConversationAgent，只回答用户关于当前任务、章节进度、资料使用和写作决策的问题。
                你会收到 Goal、章节任务状态、资料元数据和用户问题。需要确认资料事实时调用 list_sources 和 read_source。
                回复必须简洁、真实、使用中文；不要修改任务计划、章节正文或业务状态；不要编造尚未读取的资料内容。
                直接输出给用户的自然语言回复，不要输出 JSON、代码围栏或内部推理过程。
                """;
    }
}
