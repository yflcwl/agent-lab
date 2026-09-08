package com.example.incremental.writing;

import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.ContentEntry;
import java.util.function.Supplier;

public final class WritingToolContext {

    private final String taskId;
    private final ChapterStage recoveryStage;
    private ChapterStage stage;
    private ContentEntry savedContent;
    private ContentEntry newlySavedContent;
    private boolean summarySaved;
    private boolean researchLocated;
    private boolean workingPlanSaved;
    private boolean documentStateSaved;
    private boolean chapterMemorySaved;
    private ContentEntry committedContent;

    public WritingToolContext(String taskId) {
        this(taskId, null);
    }

    public WritingToolContext(String taskId, ChapterStage recoveryStage) {
        if (recoveryStage != null && !taskId.equals(recoveryStage.taskId())) {
            throw new IllegalArgumentException("ChapterStage 不属于当前写作任务");
        }
        this.taskId = taskId;
        this.recoveryStage = recoveryStage;
        this.stage = recoveryStage;
        this.savedContent = recoveryStage == null ? null : recoveryStage.content();
        this.chapterMemorySaved = recoveryStage != null && recoveryStage.chapterMemory() != null;
        this.documentStateSaved = recoveryStage != null && recoveryStage.documentState() != null;
        this.workingPlanSaved = recoveryStage != null && recoveryStage.workingPlan() != null;
    }

    public String taskId() {
        return taskId;
    }

    public synchronized ContentEntry save(Supplier<ChapterStage> operation) {
        if (savedContent != null) {
            throw new IllegalStateException(recoveryStage == null
                    ? "本轮已经保存过一项内容，不能再次保存"
                    : "当前 ChapterStage 已有正文，本轮只能补齐章节记忆、滚动状态和临时计划，不能重写正文");
        }
        if (!researchLocated) {
            throw new IllegalStateException("保存正文前必须让资料 Agent 检索相关资料片段");
        }
        ChapterStage createdStage = operation.get();
        if (createdStage == null || !taskId.equals(createdStage.taskId())) {
            throw new IllegalStateException("新建 ChapterStage 不属于当前写作任务");
        }
        stage = createdStage;
        savedContent = stage.content();
        newlySavedContent = savedContent;
        return savedContent;
    }

    public synchronized ContentEntry savedContent() {
        return savedContent;
    }

    public synchronized ChapterStage recoveryStage() {
        return recoveryStage;
    }

    public synchronized ContentEntry newlySavedContent() {
        return newlySavedContent;
    }

    public synchronized void saveSummary(Runnable operation) {
        requireWritingCommand("保存资料概览");
        if (summarySaved) {
            throw new IllegalStateException("本轮已经保存过文档摘要，不能再次保存");
        }
        operation.run();
        summarySaved = true;
    }

    public synchronized void markResearchLocated() {
        requireWritingCommand("检索资料");
        researchLocated = true;
    }

    public synchronized void saveWorkingPlan(Supplier<ChapterStage> operation) {
        requireStage("更新临时章节计划");
        if (workingPlanSaved) {
            throw new IllegalStateException("本轮已经更新过临时章节计划，不能再次更新");
        }
        stage = operation.get();
        workingPlanSaved = true;
    }

    public synchronized void saveDocumentState(Supplier<ChapterStage> operation) {
        requireStage("更新滚动文档状态");
        if (documentStateSaved) {
            throw new IllegalStateException("本轮已经更新过滚动文档状态，不能再次更新");
        }
        stage = operation.get();
        documentStateSaved = true;
    }

    public synchronized String saveChapterMemory(Supplier<ChapterStage> operation) {
        if (savedContent == null) {
            throw new IllegalStateException("必须先保存本章正文，才能保存章节记忆");
        }
        if (chapterMemorySaved) {
            throw new IllegalStateException("本轮已经保存过章节记忆，不能再次保存");
        }
        stage = operation.get();
        chapterMemorySaved = true;
        return stage.content().filename();
    }

    public synchronized String stageId() {
        return requireCurrentStage().stageId();
    }

    public synchronized ChapterStage stage() {
        return stage;
    }

    public synchronized ChapterStage requireCurrentStage() {
        if (stage == null) {
            throw new IllegalStateException("当前 Run 尚未绑定 ChapterStage");
        }
        return stage;
    }

    public synchronized ChapterStage requireCurrentStage(String requestedStageId) {
        ChapterStage current = requireCurrentStage();
        if (!current.stageId().equals(requestedStageId)) {
            throw new IllegalArgumentException("当前 Run 只能访问 ChapterStage: " + current.stageId());
        }
        return current;
    }

    public synchronized void markCommitted(ContentEntry content) {
        committedContent = content;
    }

    public synchronized ContentEntry committedContent() {
        return committedContent;
    }

    public synchronized void requireWritingCommand(String action) {
        if (recoveryStage != null) {
            throw new IllegalStateException("RECOVER_STAGE 不允许" + action
                    + "；只能读取当前 Stage 并补齐缺失状态");
        }
    }

    private void requireStage(String action) {
        if (stage == null) {
            throw new IllegalStateException("必须先保存本章正文，才能" + action);
        }
    }
}

