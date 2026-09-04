package com.example.solution.tool;

import com.example.solution.application.SourceDocumentService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SourceDocumentTool {

    private final SourceDocumentService sourceDocumentService;

    public SourceDocumentTool(SourceDocumentService sourceDocumentService) {
        this.sourceDocumentService = sourceDocumentService;
    }

    @Tool(name = "list_sources", description = "列出当前写作任务已上传的资料。开始规划或撰写前先调用。", readOnly = true)
    public String listSources(SourceToolContext context) {
        return sourceDocumentService.listForAgent(context.writingTaskId());
    }

    @Tool(name = "read_source", description = "按资料 ID 读取背景资料；可传 query 获取相关片段。必须以工具返回的资料为准，不得编造。", readOnly = true)
    public String readSource(
            @ToolParam(name = "source_id", description = "由 list_sources 返回的资料 ID") String sourceId,
            @ToolParam(name = "query", description = "本章要查找的关键词；无特定关键词时传空字符串") String query,
            SourceToolContext context) {
        return sourceDocumentService.readForAgent(context.writingTaskId(), sourceId, query);
    }
}
