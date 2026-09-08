package com.example.incremental.persistence.writing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.incremental.persistence.writing.mapper.ChapterStageMapper;
import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.ChapterStageRepository;
import com.example.incremental.writing.ChapterStageStatus;
import com.example.incremental.writing.ContentEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
public class PostgresChapterStageRepository implements ChapterStageRepository {

    private final ChapterStageMapper mapper;

    public PostgresChapterStageRepository(ChapterStageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(ChapterStage stage) {
        mapper.insert(entity(stage));
    }

    @Override
    public void save(ChapterStage stage) {
        if (mapper.updateById(entity(stage)) != 1) {
            throw new IllegalArgumentException("ChapterStage 不存在: " + stage.stageId());
        }
    }

    @Override
    public ChapterStage findById(String taskId, String stageId) {
        return stage(mapper.selectOne(Wrappers.<ChapterStageEntity>lambdaQuery()
                .eq(ChapterStageEntity::getTaskId, taskId)
                .eq(ChapterStageEntity::getId, stageId)));
    }

    @Override
    public ChapterStage findOpen(String taskId) {
        return stage(mapper.selectOne(Wrappers.<ChapterStageEntity>lambdaQuery()
                .eq(ChapterStageEntity::getTaskId, taskId)
                .in(ChapterStageEntity::getStatus, ChapterStageStatus.STAGED,
                        ChapterStageStatus.AWAITING_REVIEW, ChapterStageStatus.COMMITTING)
                .orderByDesc(ChapterStageEntity::getCreatedAt)
                .last("LIMIT 1")));
    }

    private ChapterStageEntity entity(ChapterStage stage) {
        ContentEntry content = stage.content();
        return new ChapterStageEntity(stage.stageId(), stage.taskId(), content.sequence(), content.title(),
                content.referenceBasis(), content.filename(), content.createdAt(), stage.markdown(),
                stage.chapterMemory(), stage.documentState(), stage.workingPlan(), stage.status(),
                stage.createdAt(), stage.committedAt(), java.time.Instant.now());
    }

    private ChapterStage stage(ChapterStageEntity entity) {
        if (entity == null) {
            return null;
        }
        ContentEntry content = new ContentEntry(entity.getChapterSequence(), entity.getTitle(),
                entity.getReferenceBasis(), entity.getFilename(), entity.getContentCreatedAt());
        return new ChapterStage(entity.getId(), entity.getTaskId(), content, entity.getMarkdown(),
                entity.getChapterMemory(), entity.getDocumentState(), entity.getWorkingPlan(), entity.getStatus(),
                entity.getCreatedAt(), entity.getCommittedAt());
    }
}
