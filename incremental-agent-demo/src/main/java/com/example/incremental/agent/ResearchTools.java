package com.example.incremental.agent;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.rag.TempRagHit;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.rag.TempRagService;
import com.example.incremental.writing.WritingToolContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ResearchTools {

    private final TaskWorkspaceService workspaceService;
    private final TempRagService tempRagService;
    private final DemoProperties properties;

    public ResearchTools(
            TaskWorkspaceService workspaceService,
            TempRagService tempRagService,
            DemoProperties properties) {
        this.workspaceService = workspaceService;
        this.tempRagService = tempRagService;
        this.properties = properties;
    }

    @Tool(name = "list_original_documents", description = "仅供 source-research-agent 使用，写作 Agent禁止直接调用：列出当前任务的事实资料文件名。", readOnly = true)
    public List<String> listOriginalDocuments(RuntimeContext runtimeContext, WritingToolContext context) {
        context.requireWritingCommand("检索资料");
        requireSubagent(runtimeContext, "list_original_documents");
        return workspaceService.listSources(context.taskId());
    }

    @Tool(name = "read_original_document", description = "仅供 source-research-agent 使用，写作 Agent 禁止直接调用：按文件名和字符范围读取当前任务的原始资料，用于核对 RAG 命中片段的上下文或未命中的内容。", readOnly = true)
    public String readOriginalDocument(
            @ToolParam(name = "document_name", description = "要读取的资料文件名") String documentName,
            @ToolParam(name = "start_offset", description = "可选；从第几个字符开始读取，默认 0", required = false) Integer startOffset,
            @ToolParam(name = "length", description = "可选；读取字符数，范围 1-12000，默认 4000", required = false) Integer length,
            RuntimeContext runtimeContext,
            WritingToolContext context) {
        context.requireWritingCommand("读取资料");
        requireSubagent(runtimeContext, "read_original_document");
        requireMode("file", "read_original_document");
        return workspaceService.readOriginalDocument(context.taskId(), documentName, startOffset, length);
    }

    @Tool(name = "search_original_documents", description = "仅供 source-research-agent 使用，写作 Agent禁止直接调用：按 query 检索当前任务最相关的资料原文片段。返回结果已经是可作为事实依据的 TopK 原文，不需要再读取完整原始资料。", readOnly = true)
    public String searchOriginalDocuments(
            @ToolParam(name = "query", description = "当前章节需要确认的事实、主题或术语") String query,
            @ToolParam(name = "document_name", description = "兼容旧调用保留；Task RAG 始终在当前任务全部资料中按 query 检索", required = false) String documentName,
            @ToolParam(name = "max_results", description = "可选；最多返回多少处命中，范围 1-20", required = false) Integer maxResults,
            RuntimeContext runtimeContext,
            WritingToolContext context) {
        context.requireWritingCommand("检索资料");
        requireSubagent(runtimeContext, "search_original_documents");
        requireMode("rag", "search_original_documents");
        List<TempRagHit> hits = (maxResults == null
                ? tempRagService.retrieve(context.taskId(), query)
                : tempRagService.retrieve(context.taskId(), query, maxResults)).block();
        if (hits != null && !hits.isEmpty()) {
            context.markResearchLocated();
        }
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关资料片段，请缩小或更换 query。";
        }
        return hits.stream().map(hit -> "## %s · chunk %s · score %.4f\n%s".formatted(
                hit.filename(), hit.chunkId(), hit.score(), hit.content())).reduce((left, right) -> left + "\n\n" + right)
                .orElse("未检索到相关资料片段，请缩小或更换 query。");
    }

    private void requireSubagent(RuntimeContext runtimeContext, String toolName) {
        String sessionId = runtimeContext == null ? null : runtimeContext.getSessionId();
        if (sessionId == null || !sessionId.startsWith("sub-")) {
            throw new IllegalStateException("写作 Agent 不能直接调用 " + toolName
                    + "；请使用 source-research-agent 检索相关资料片段");
        }
    }

    private void requireMode(String requiredMode, String toolName) {
        String mode = properties.getResearchMode() == null
                ? "hybrid"
                : properties.getResearchMode().trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("rag") && !mode.equals("file") && !mode.equals("hybrid")) {
            throw new IllegalStateException("demo.research-mode 必须为 rag、file 或 hybrid");
        }
        if (!mode.equals("hybrid") && !mode.equals(requiredMode)) {
            throw new IllegalStateException("当前 demo.research-mode=" + mode
                    + "，不能调用 " + toolName);
        }
    }

}

