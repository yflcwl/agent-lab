package com.example.incremental.agent;

import com.example.incremental.writing.ContentEntry;
import com.example.incremental.writing.ChapterStageCoordinator;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.writing.WritingToolContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WritingTools {

    private final TaskWorkspaceService workspaceService;
    private final ChapterStageCoordinator chapterStageCoordinator;

    public WritingTools(TaskWorkspaceService workspaceService, ChapterStageCoordinator chapterStageCoordinator) {
        this.workspaceService = workspaceService;
        this.chapterStageCoordinator = chapterStageCoordinator;
    }

    @Tool(name = "read_document_summary", description = "读取资料 Agent 首次生成的全局资料概览。它只用于导航和理解写作风格，不能代替本章重新查询原始资料。", readOnly = true)
    public String readDocumentSummary(WritingToolContext context) {
        return workspaceService.readDocumentSummary(context.taskId());
    }

    @Tool(name = "save_document_summary", description = "保存资料 Agent 对参考文档写法和资料目录的全局概览。概览不属于正式章节，也不能作为事实证据。")
    public String saveDocumentSummary(
            @ToolParam(name = "summary", description = "Markdown 资料概览，包含参考文档写法、资料目录、主题范围和原文查阅索引") String summary,
            WritingToolContext context) {
        context.saveSummary(() -> workspaceService.saveDocumentSummary(context.taskId(), summary));
        return "文档摘要已保存: document-summary.md";
    }

    @Tool(name = "read_working_plan", description = "读取当前可调整的临时章节计划。每章使用新的 Session，因此计划以任务文件为准。", readOnly = true)
    public String readWorkingPlan(WritingToolContext context) {
        return workspaceService.readWorkingPlan(context.taskId());
    }

    @Tool(name = "update_working_plan", description = "更新临时章节计划。计划可以根据用户意见和写作进展改变，不是预先写死的任务队列；本轮最多调用一次。")
    public String updateWorkingPlan(
            @ToolParam(name = "plan", description = "完整 JSON 对象，至少表达已完成章节、下一方向、剩余方向和本次调整原因") String plan,
            WritingToolContext context) {
        context.saveWorkingPlan(() -> workspaceService.stageWorkingPlan(
                context.taskId(), context.stageId(), plan));
        return "临时章节计划已暂存到 ChapterStage";
    }

    @Tool(name = "read_document_state", description = "读取 memory/document-state.md 的固定大小滚动状态，了解已经覆盖的核心内容、统一术语、跨章约束、用户要求和尚未处理的问题。每轮开始时必须读取。", readOnly = true)
    public String readDocumentState(WritingToolContext context) {
        return workspaceService.readDocumentState(context.taskId());
    }

    @Tool(name = "update_document_state", description = "用本章完成后的最新整体状态覆盖滚动文档状态，不得拼接全部章节摘要；本轮最多调用一次。")
    public String updateDocumentState(
            @ToolParam(name = "state", description = "不超过 12000 字符的完整 Markdown 状态快照") String state,
            WritingToolContext context) {
        context.saveDocumentState(() -> workspaceService.stageDocumentState(
                context.taskId(), context.stageId(), state));
        return "滚动文档状态已暂存到 ChapterStage";
    }

    @Tool(name = "list_completed_contents", description = "读取 outputs/index.json 对应的已完成章节目录，不加载全部正文。每轮开始时必须读取，再由你判断是否需要读取具体历史章节。", readOnly = true)
    public List<ContentEntry> listCompletedContents(WritingToolContext context) {
        return workspaceService.listContents(context.taskId());
    }

    @Tool(name = "read_completed_content", description = "从 outputs/ 读取一章已经完成的准确正文。需要核对前文原句、细节或衔接时按需调用，不要一次读取全部章节。", readOnly = true)
    public String readCompletedContent(
            @ToolParam(name = "filename", description = "list_completed_contents 返回的文件名") String filename,
            WritingToolContext context) {
        return workspaceService.readContent(context.taskId(), filename);
    }

    @Tool(name = "read_staged_chapter", description = "读取当前未提交 ChapterStage 的正文和已暂存状态。仅用于故障恢复；它不是已完成章节，不能通过 read_completed_content 读取。", readOnly = true)
    public String readStagedChapter(WritingToolContext context) {
        return workspaceService.readChapterStage(context.taskId(), context.stageId());
    }

    @Tool(name = "list_chapter_memories", description = "列出 memory/chapters/ 下的历史章节记忆文件，不会把所有摘要塞入上下文。由你判断当前章节需要读取哪些。", readOnly = true)
    public List<String> listChapterMemories(WritingToolContext context) {
        return workspaceService.listChapterMemories(context.taskId());
    }

    @Tool(name = "search_chapter_memories", description = "按你选择的关键词搜索 memory/chapters/ 下的历史章节记忆，返回少量匹配片段；不使用向量数据库。", readOnly = true)
    public String searchChapterMemories(
            @ToolParam(name = "query", description = "当前章节需要回顾的主题或术语") String query,
            @ToolParam(name = "max_results", description = "可选；最多返回多少处命中，范围 1-20", required = false) Integer maxResults,
            WritingToolContext context) {
        return workspaceService.searchChapterMemories(context.taskId(), query, maxResults);
    }

    @Tool(name = "read_chapter_memory", description = "从 memory/chapters/ 读取一章独立的结构化记忆。只有当前章节确实相关时才调用；需要准确原文时再调用 read_completed_content。", readOnly = true)
    public String readChapterMemory(
            @ToolParam(name = "filename", description = "list_chapter_memories 返回的文件名") String filename,
            WritingToolContext context) {
        return workspaceService.readChapterMemory(context.taskId(), filename);
    }

    @Tool(name = "save_content", description = "暂存本轮生成的一章完整正文为 ChapterStage。不得保存章节计划或多章正文，每轮最多成功调用一次；完整 ChapterStage 必须再通过 commit_chapter 请求审核。")
    public String saveContent(
            @ToolParam(name = "title", description = "本章标题") String title,
            @ToolParam(name = "reference_basis", description = "本章借鉴参考文档的结构或表达方式，自由描述") String referenceBasis,
            @ToolParam(name = "content", description = "仅包含本章的完整 Markdown 正文") String content,
            WritingToolContext context) {
        ContentEntry saved = context.save(() -> workspaceService.createChapterStage(
                context.taskId(), title, referenceBasis, content));
        return "正文已暂存: " + saved.filename() + "，请继续暂存章节记忆、滚动状态和临时计划";
    }

    @Tool(name = "save_chapter_memory", description = "为本轮刚暂存的正文建立独立章节记忆，记录摘要、事实来源、术语、跨章约束和后续衔接；必须在 save_content 之后调用，本轮最多一次。")
    public String saveChapterMemory(
            @ToolParam(name = "memory", description = "不超过 6000 字符的 Markdown 章节记忆") String memory,
            WritingToolContext context) {
        String filename = context.saveChapterMemory(() -> workspaceService.stageChapterMemory(
                context.taskId(), context.stageId(), memory));
        return "章节记忆已暂存: memory/chapters/" + filename;
    }

    @Tool(name = "commit_chapter", description = "请求提交完整 ChapterStage。仅当正文、章节记忆、滚动状态和临时计划都已暂存时调用；此操作需要用户审核确认。")
    public String commitChapter(
            @ToolParam(name = "stage_id", description = "当前完整 ChapterStage 的 stageId") String stageId,
            WritingToolContext context) {
        if (!context.stageId().equals(stageId)
                && (context.stage() == null || context.stage().content() == null
                || !context.stage().content().filename().equals(stageId))) {
            throw new IllegalArgumentException("只能提交当前 ChapterStage: " + context.stageId());
        }
        ChapterStageCoordinator.ChapterCommit commit = chapterStageCoordinator.commitIfComplete(context.taskId(), context.stage());
        context.markCommitted(commit.content());
        return "章节已提交: " + commit.content().filename();
    }
}

