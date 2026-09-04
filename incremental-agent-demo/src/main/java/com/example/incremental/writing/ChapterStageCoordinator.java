package com.example.incremental.writing;

import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.ContentEntry;
import com.example.incremental.workspace.TaskWorkspaceService;
import org.springframework.stereotype.Service;

@Service
public class ChapterStageCoordinator {

    private final TaskWorkspaceService workspaceService;

    public ChapterStageCoordinator(TaskWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    public ChapterStage findOpenStage(String taskId) {
        return workspaceService.findOpenChapterStage(taskId);
    }

    public ChapterCommit commitIfComplete(String taskId, ChapterStage stage) {
        if (stage == null) {
            return null;
        }
        if (!stage.isComplete()) {
            throw new IllegalStateException("ChapterStage 尚未完整：正文、章节记忆、滚动状态和临时计划必须全部暂存");
        }
        ContentEntry content = workspaceService.commitChapter(taskId, stage.stageId());
        return new ChapterCommit(stage.stageId(), content);
    }

    public ChapterCommit commitRecoveredStageIfReady(String taskId, ChapterStage stage) {
        return stage == null || stage.status() != com.example.incremental.writing.ChapterStageStatus.COMMITTING
                || !stage.isComplete() ? null : commitIfComplete(taskId, stage);
    }

    public void markAwaitingReview(String taskId, String stageId) {
        workspaceService.markChapterStageAwaitingReview(taskId, stageId);
    }

    public void reject(String taskId, String stageId) {
        workspaceService.rejectChapterStage(taskId, stageId);
    }

    public record ChapterCommit(String stageId, ContentEntry content) {
    }
}

