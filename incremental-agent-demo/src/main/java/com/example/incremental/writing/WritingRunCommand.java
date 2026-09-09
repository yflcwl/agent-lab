package com.example.incremental.writing;

import org.springframework.util.StringUtils;

public record WritingRunCommand(Type type, String userMessage, String stageId) {

    public enum Type {
        WRITE_CHAPTER,
        RECOVER_STAGE,
        REQUEST_CHAPTER_COMMIT,
        RESUME_PAUSED
    }

    public static WritingRunCommand writeChapter(String userMessage) {
        return new WritingRunCommand(Type.WRITE_CHAPTER,
                StringUtils.hasText(userMessage) ? userMessage.trim() : "", null);
    }

    public static WritingRunCommand recoverStage(String stageId) {
        if (!StringUtils.hasText(stageId)) {
            throw new IllegalArgumentException("stageId 不能为空");
        }
        return new WritingRunCommand(Type.RECOVER_STAGE, "", stageId);
    }

    public static WritingRunCommand requestChapterCommit(String stageId) {
        if (!StringUtils.hasText(stageId)) {
            throw new IllegalArgumentException("stageId 不能为空");
        }
        return new WritingRunCommand(Type.REQUEST_CHAPTER_COMMIT, "", stageId);
    }

    public static WritingRunCommand resumePaused() {
        return new WritingRunCommand(Type.RESUME_PAUSED, "", null);
    }
}

