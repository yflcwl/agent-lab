package com.example.incremental.persistence.writing;

import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.ChapterStageRepository;
import com.example.incremental.writing.ChapterStageStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Explicit non-durable storage for the existing in-memory demo/test mode. */
@Repository
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "in-memory")
public class InMemoryChapterStageRepository implements ChapterStageRepository {

    private final Map<String, ChapterStage> stages = new HashMap<>();

    @Override
    public synchronized void create(ChapterStage stage) {
        if (findOpen(stage.taskId()) != null) {
            throw new IllegalStateException("已有未提交的 ChapterStage");
        }
        if (stages.putIfAbsent(stage.stageId(), stage) != null) {
            throw new IllegalStateException("ChapterStage 已存在: " + stage.stageId());
        }
    }

    @Override
    public synchronized void save(ChapterStage stage) {
        ChapterStage current = stages.get(stage.stageId());
        if (current == null || !current.taskId().equals(stage.taskId())) {
            throw new IllegalArgumentException("ChapterStage 不存在: " + stage.stageId());
        }
        stages.put(stage.stageId(), stage);
    }

    @Override
    public synchronized ChapterStage findById(String taskId, String stageId) {
        ChapterStage stage = stages.get(stageId);
        return stage != null && stage.taskId().equals(taskId) ? stage : null;
    }

    @Override
    public synchronized ChapterStage findOpen(String taskId) {
        return stages.values().stream()
                .filter(stage -> taskId.equals(stage.taskId()))
                .filter(stage -> stage.status() != ChapterStageStatus.COMMITTED
                        && stage.status() != ChapterStageStatus.REJECTED)
                .max(Comparator.comparing(ChapterStage::createdAt))
                .orElse(null);
    }
}
