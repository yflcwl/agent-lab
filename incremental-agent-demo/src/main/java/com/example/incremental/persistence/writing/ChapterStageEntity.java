package com.example.incremental.persistence.writing;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.incremental.writing.ChapterStageStatus;

import java.time.Instant;

@TableName("chapter_stage")
public class ChapterStageEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("task_id")
    private String taskId;
    @TableField("chapter_sequence")
    private int chapterSequence;
    private String title;
    @TableField("reference_basis")
    private String referenceBasis;
    private String filename;
    @TableField("content_created_at")
    private Instant contentCreatedAt;
    private String markdown;
    @TableField("chapter_memory")
    private String chapterMemory;
    @TableField("document_state")
    private String documentState;
    @TableField("working_plan")
    private String workingPlan;
    private ChapterStageStatus status;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("committed_at")
    private Instant committedAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public ChapterStageEntity() {
    }

    public ChapterStageEntity(String id, String taskId, int chapterSequence, String title,
                              String referenceBasis, String filename, Instant contentCreatedAt,
                              String markdown, String chapterMemory, String documentState, String workingPlan,
                              ChapterStageStatus status, Instant createdAt, Instant committedAt, Instant updatedAt) {
        this.id = id;
        this.taskId = taskId;
        this.chapterSequence = chapterSequence;
        this.title = title;
        this.referenceBasis = referenceBasis;
        this.filename = filename;
        this.contentCreatedAt = contentCreatedAt;
        this.markdown = markdown;
        this.chapterMemory = chapterMemory;
        this.documentState = documentState;
        this.workingPlan = workingPlan;
        this.status = status;
        this.createdAt = createdAt;
        this.committedAt = committedAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public int getChapterSequence() { return chapterSequence; }
    public void setChapterSequence(int chapterSequence) { this.chapterSequence = chapterSequence; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getReferenceBasis() { return referenceBasis; }
    public void setReferenceBasis(String referenceBasis) { this.referenceBasis = referenceBasis; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Instant getContentCreatedAt() { return contentCreatedAt; }
    public void setContentCreatedAt(Instant contentCreatedAt) { this.contentCreatedAt = contentCreatedAt; }
    public String getMarkdown() { return markdown; }
    public void setMarkdown(String markdown) { this.markdown = markdown; }
    public String getChapterMemory() { return chapterMemory; }
    public void setChapterMemory(String chapterMemory) { this.chapterMemory = chapterMemory; }
    public String getDocumentState() { return documentState; }
    public void setDocumentState(String documentState) { this.documentState = documentState; }
    public String getWorkingPlan() { return workingPlan; }
    public void setWorkingPlan(String workingPlan) { this.workingPlan = workingPlan; }
    public ChapterStageStatus getStatus() { return status; }
    public void setStatus(ChapterStageStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant committedAt) { this.committedAt = committedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
