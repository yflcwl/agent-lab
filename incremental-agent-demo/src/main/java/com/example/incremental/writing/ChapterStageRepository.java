package com.example.incremental.writing;

public interface ChapterStageRepository {

    void create(ChapterStage stage);

    void save(ChapterStage stage);

    ChapterStage findById(String taskId, String stageId);

    ChapterStage findOpen(String taskId);
}
