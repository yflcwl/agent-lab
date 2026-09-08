package com.example.incremental.workspace;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.ChapterStageRepository;
import com.example.incremental.writing.ChapterStageStatus;
import com.example.incremental.writing.ContentEntry;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.WritingTaskView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskWorkspaceService {

    private static final int MAX_ORIGINAL_READ_LENGTH = 12_000;
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final int MAX_WORKING_PLAN_LENGTH = 12_000;
    private static final int MAX_DOCUMENT_STATE_LENGTH = 12_000;
    private static final int MAX_CHAPTER_MEMORY_LENGTH = 6_000;
    private static final int MAX_DOCUMENT_SUMMARY_LENGTH = 30_000;
    private static final TypeReference<List<ContentEntry>> CONTENT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path dataRoot;
    private final ChapterStageRepository chapterStages;

    public TaskWorkspaceService(
            ObjectMapper objectMapper, DemoProperties properties, ChapterStageRepository chapterStages) {
        this.objectMapper = objectMapper;
        this.dataRoot = properties.getDataRoot().toAbsolutePath().normalize();
        this.chapterStages = chapterStages;
    }

    public WritingTask createTask(String userId, String referenceDocument, Map<String, String> sources) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(referenceDocument)) {
            throw new IllegalArgumentException("referenceDocument 不能为空");
        }
        String taskId = UUID.randomUUID().toString();
        WritingTask task = new WritingTask(taskId, userId.trim(), "writing-" + taskId, Instant.now());
        Path taskDirectory = taskDirectory(taskId);
        try {
            Files.createDirectories(taskDirectory.resolve("sources"));
            Files.createDirectories(taskDirectory.resolve("outputs"));
            Files.createDirectories(taskDirectory.resolve("memory/chapters"));
            writeJson(taskDirectory.resolve("task.json"), task);
            Files.writeString(taskDirectory.resolve("reference-document.md"),
                    referenceDocument.trim(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> source : (sources == null ? Map.<String, String>of() : sources).entrySet()) {
                String filename = safeFilename(source.getKey());
                if (!StringUtils.hasText(source.getValue())) {
                    throw new IllegalArgumentException("资料内容不能为空: " + filename);
                }
                Files.writeString(resolveInside(taskDirectory.resolve("sources"), filename),
                        source.getValue(), StandardCharsets.UTF_8);
            }
            writeJson(taskDirectory.resolve("outputs/index.json"), List.of());
            Files.writeString(taskDirectory.resolve("working-plan.json"), """
                    {
                      "version": 0,
                      "completed": [],
                      "nextDirection": "由写作 Agent 在第一章开始时决定",
                      "remainingDirections": [],
                      "adjustmentReason": "尚未形成临时章节计划"
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(taskDirectory.resolve("memory/document-state.md"), """
                    # 文档当前状态

                    尚未生成任何章节。写作 Agent 将在第一章完成后更新此状态。
                    """, StandardCharsets.UTF_8);
            return task;
        } catch (IOException e) {
            throw new IllegalStateException("创建写作任务失败", e);
        }
    }

    public WritingTask findTask(String taskId) {
        try {
            Path file = taskDirectory(taskId).resolve("task.json");
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("写作任务不存在: " + taskId);
            }
            return objectMapper.readValue(file.toFile(), WritingTask.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取写作任务失败", e);
        }
    }

    public String readReferenceDocument(String taskId) {
        Path taskDirectory = taskDirectory(taskId);
        Path referenceDocument = taskDirectory.resolve("reference-document.md");
        return readText(Files.isRegularFile(referenceDocument)
                ? referenceDocument
                : taskDirectory.resolve("template.md"));
    }

    public List<String> listSources(String taskId) {
        return listFiles(taskDirectory(taskId).resolve("sources"));
    }

    public String readSource(String taskId, String filename) {
        return readText(resolveInside(taskDirectory(taskId).resolve("sources"), safeFilename(filename)));
    }

    public Path sourcePath(String taskId, String filename) {
        findTask(taskId);
        return resolveInside(taskDirectory(taskId).resolve("sources"), safeFilename(filename));
    }

    public List<String> listOriginalDocuments(String taskId) {
        return List.copyOf(originalDocuments(taskId).keySet());
    }

    public String readOriginalDocument(String taskId, String documentName, Integer startOffset, Integer length) {
        Map.Entry<String, Path> document = originalDocument(taskId, documentName);
        String content = readText(document.getValue());
        int start = startOffset == null ? 0 : startOffset;
        int requestedLength = length == null ? 4_000 : length;
        if (start < 0 || start > content.length()) {
            throw new IllegalArgumentException("start_offset 超出文档范围: " + start);
        }
        if (requestedLength < 1 || requestedLength > MAX_ORIGINAL_READ_LENGTH) {
            throw new IllegalArgumentException("length 必须在 1 到 " + MAX_ORIGINAL_READ_LENGTH + " 之间");
        }
        int end = Math.min(content.length(), start + requestedLength);
        return "文档: %s\n字符范围: %d-%d / %d\n\n%s".formatted(
                document.getKey(), start, end, content.length(), content.substring(start, end));
    }

    public String searchOriginalDocuments(
            String taskId, String query, String documentName, Integer maxResults) {
        Map<String, Path> documents = originalDocuments(taskId);
        if (StringUtils.hasText(documentName)) {
            Map.Entry<String, Path> selected = originalDocument(documents, documentName);
            documents = Map.of(selected.getKey(), selected.getValue());
        }
        return searchFiles(documents, query, maxResults);
    }

    public boolean hasDocumentSummary(String taskId) {
        findTask(taskId);
        return Files.isRegularFile(taskDirectory(taskId).resolve("document-summary.md"));
    }

    public String readDocumentSummary(String taskId) {
        findTask(taskId);
        Path summary = taskDirectory(taskId).resolve("document-summary.md");
        return Files.isRegularFile(summary)
                ? readText(summary)
                : "尚未生成资料概览。请调用资料 Agent 分析原始文档后，再保存资料概览。";
    }

    public synchronized void saveDocumentSummary(String taskId, String summary) {
        findTask(taskId);
        requireTextWithinLimit(summary, "summary", MAX_DOCUMENT_SUMMARY_LENGTH);
        try {
            Files.writeString(taskDirectory(taskId).resolve("document-summary.md"),
                    summary.trim(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存文档摘要失败", e);
        }
    }

    public String readWorkingPlan(String taskId) {
        findTask(taskId);
        Path plan = taskDirectory(taskId).resolve("working-plan.json");
        return Files.isRegularFile(plan) ? readText(plan) : """
                {
                  "version": 0,
                  "completed": [],
                  "nextDirection": "由写作 Agent 决定",
                  "remainingDirections": [],
                  "adjustmentReason": "尚未形成临时章节计划"
                }
                """;
    }

    public synchronized ChapterStage stageWorkingPlan(String taskId, String stageId, String plan) {
        requireTextWithinLimit(plan, "plan", MAX_WORKING_PLAN_LENGTH);
        try {
            var json = objectMapper.readTree(plan);
            if (!json.isObject()) {
                throw new IllegalArgumentException("plan 必须是 JSON 对象");
            }
            ChapterStage stage = requireOpenStage(taskId, stageId).withWorkingPlan(
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
            chapterStages.save(stage);
            return stage;
        } catch (IOException e) {
            throw new IllegalArgumentException("plan 不是合法 JSON", e);
        }
    }

    public String readDocumentState(String taskId) {
        findTask(taskId);
        Path state = taskDirectory(taskId).resolve("memory/document-state.md");
        return Files.isRegularFile(state)
                ? readText(state)
                : "# 文档当前状态\n\n尚未生成任何章节。";
    }

    public synchronized ChapterStage stageDocumentState(String taskId, String stageId, String state) {
        requireTextWithinLimit(state, "state", MAX_DOCUMENT_STATE_LENGTH);
        ChapterStage stage = requireOpenStage(taskId, stageId).withDocumentState(state.trim());
        chapterStages.save(stage);
        return stage;
    }

    public List<String> listChapterMemories(String taskId) {
        findTask(taskId);
        return listFiles(taskDirectory(taskId).resolve("memory/chapters"));
    }

    public String readChapterMemory(String taskId, String filename) {
        return readText(resolveInside(
                taskDirectory(taskId).resolve("memory/chapters"), safeFilename(filename)));
    }

    public String searchChapterMemories(String taskId, String query, Integer maxResults) {
        findTask(taskId);
        Map<String, Path> memories = new LinkedHashMap<>();
        Path directory = taskDirectory(taskId).resolve("memory/chapters");
        for (String filename : listFiles(directory)) {
            memories.put(filename, resolveInside(directory, filename));
        }
        return searchFiles(memories, query, maxResults);
    }

    public synchronized ChapterStage stageChapterMemory(String taskId, String stageId, String memory) {
        requireTextWithinLimit(memory, "memory", MAX_CHAPTER_MEMORY_LENGTH);
        ChapterStage stage = requireOpenStage(taskId, stageId).withChapterMemory(memory.trim());
        chapterStages.save(stage);
        return stage;
    }

    public synchronized ChapterStage findOpenChapterStage(String taskId) {
        findTask(taskId);
        return chapterStages.findOpen(taskId);
    }

    public String readChapterStage(String taskId, String stageId) {
        ChapterStage stage = readStage(taskId, stageId);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stage);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("序列化 ChapterStage 失败", e);
        }
    }

    public List<ContentEntry> listContents(String taskId) {
        Path index = taskDirectory(taskId).resolve("outputs/index.json");
        try {
            if (!Files.isRegularFile(index)) {
                return List.of();
            }
            return List.copyOf(objectMapper.readValue(index.toFile(), CONTENT_LIST));
        } catch (IOException e) {
            throw new IllegalStateException("读取已完成内容索引失败", e);
        }
    }

    public String readContent(String taskId, String filename) {
        return readText(resolveInside(taskDirectory(taskId).resolve("outputs"), safeFilename(filename)));
    }

    public synchronized ChapterStage createChapterStage(String taskId, String title, String referenceBasis, String content) {
        findTask(taskId);
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("content 不能为空");
        }
        ChapterStage openStage = findOpenChapterStage(taskId);
        if (openStage != null) {
            throw new IllegalStateException("已有未提交的 ChapterStage: " + openStage.stageId());
        }
        int sequence = listContents(taskId).size() + 1;
        String filename = "%03d-%s.md".formatted(sequence, filenamePart(title));
        ContentEntry entry = new ContentEntry(sequence, title.trim(),
                StringUtils.hasText(referenceBasis) ? referenceBasis.trim() : "", filename, Instant.now());
        ChapterStage stage = new ChapterStage(UUID.randomUUID().toString(), taskId, entry, content.trim(),
                null, null, null, ChapterStageStatus.STAGED, Instant.now(), null);
        chapterStages.create(stage);
        return stage;
    }

    public synchronized ContentEntry commitChapter(String taskId, String stageId) {
        ChapterStage stage = readStage(taskId, stageId);
        if (stage.status() == ChapterStageStatus.COMMITTED) {
            return stage.content();
        }
        if (!stage.isComplete()) {
            throw new IllegalStateException("ChapterStage 尚未完整，不能提交: " + stageId);
        }
        chapterStages.save(stage.withStatus(ChapterStageStatus.COMMITTING, null));
        Path taskDirectory = taskDirectory(taskId);
        Path outputs = taskDirectory.resolve("outputs");
        try {
            writeText(resolveInside(outputs, stage.content().filename()), stage.markdown(), "提交章节正文失败");
            writeText(resolveInside(taskDirectory.resolve("memory/chapters"), stage.content().filename()),
                    stage.chapterMemory(), "提交章节记忆失败");
            writeText(taskDirectory.resolve("memory/document-state.md"), markCommitted(stage.documentState()), "提交滚动文档状态失败");
            writeText(taskDirectory.resolve("working-plan.json"), markCommitted(stage.workingPlan()), "提交临时章节计划失败");

            List<ContentEntry> contents = new ArrayList<>(listContents(taskId));
            if (contents.stream().noneMatch(entry -> entry.filename().equals(stage.content().filename()))) {
                contents.add(stage.content());
                writeJson(outputs.resolve("index.json"), contents);
            }
            chapterStages.save(stage.withStatus(ChapterStageStatus.COMMITTED, Instant.now()));
            return stage.content();
        } catch (IOException e) {
            throw new IllegalStateException("提交 ChapterStage 失败，可使用相同 stageId 重试", e);
        }
    }

    public synchronized void markChapterStageAwaitingReview(String taskId, String stageId) {
        ChapterStage stage = readStage(taskId, stageId);
        if (stage.status() == ChapterStageStatus.AWAITING_REVIEW) {
            return;
        }
        if (stage.status() != ChapterStageStatus.STAGED || !stage.isComplete()) {
            throw new IllegalStateException("ChapterStage 尚未完整，不能等待章节审核: " + stageId);
        }
        chapterStages.save(stage.withStatus(ChapterStageStatus.AWAITING_REVIEW, null));
    }

    public synchronized void rejectChapterStage(String taskId, String stageId) {
        ChapterStage stage = readStage(taskId, stageId);
        if (stage.status() != ChapterStageStatus.AWAITING_REVIEW) {
            throw new IllegalStateException("当前 ChapterStage 不在等待审核状态: " + stageId);
        }
        chapterStages.save(stage.withStatus(ChapterStageStatus.REJECTED, null));
    }

    private ChapterStage requireOpenStage(String taskId, String stageId) {
        ChapterStage stage = readStage(taskId, stageId);
        if (stage.status() == ChapterStageStatus.COMMITTED) {
            throw new IllegalStateException("ChapterStage 已提交，不能继续修改: " + stageId);
        }
        return stage;
    }

    private ChapterStage readStage(String taskId, String stageId) {
        findTask(taskId);
        if (!StringUtils.hasText(stageId) || !stageId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("stageId 不合法");
        }
        ChapterStage stage = chapterStages.findById(taskId, stageId);
        if (stage == null) {
            throw new IllegalArgumentException("ChapterStage 不存在: " + stageId);
        }
        return stage;
    }

    private String markCommitted(String value) {
        return value == null ? null : value.replace("已暂存待审核", "已审核通过");
    }

    public WritingTaskView getTaskView(String taskId) {
        return new WritingTaskView(findTask(taskId), listSources(taskId), listContents(taskId));
    }

    public List<WritingTaskView> listTaskViews() {
        try {
            if (!Files.isDirectory(dataRoot)) {
                return List.of();
            }
            try (var paths = Files.list(dataRoot)) {
                return paths.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.matches("task-[0-9a-fA-F-]{36}"))
                        .map(name -> getTaskView(name.substring("task-".length())))
                        .sorted(Comparator.comparing(
                                (WritingTaskView view) -> view.task().createdAt()).reversed())
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取写作任务列表失败", e);
        }
    }

    public String getFullContent(String taskId) {
        return listContents(taskId).stream()
                .map(entry -> readContent(taskId, entry.filename()).trim())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    Path taskDirectory(String taskId) {
        if (!StringUtils.hasText(taskId) || !taskId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("taskId 不合法");
        }
        return resolveInside(dataRoot, "task-" + taskId);
    }

    private List<String> listFiles(Path directory) {
        try {
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
            try (var paths = Files.list(directory)) {
                return paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .sorted(Comparator.naturalOrder())
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败", e);
        }
    }

    private Map<String, Path> originalDocuments(String taskId) {
        findTask(taskId);
        Path taskDirectory = taskDirectory(taskId);
        Map<String, Path> documents = new LinkedHashMap<>();
        Path reference = Files.isRegularFile(taskDirectory.resolve("reference-document.md"))
                ? taskDirectory.resolve("reference-document.md")
                : taskDirectory.resolve("template.md");
        documents.put("reference/reference-document.md", reference);
        for (String filename : listSources(taskId)) {
            documents.put("sources/" + filename,
                    resolveInside(taskDirectory.resolve("sources"), filename));
        }
        return documents;
    }

    private Map.Entry<String, Path> originalDocument(String taskId, String documentName) {
        return originalDocument(originalDocuments(taskId), documentName);
    }

    private Map.Entry<String, Path> originalDocument(
            Map<String, Path> documents, String documentName) {
        if (!StringUtils.hasText(documentName)) {
            throw new IllegalArgumentException("document_name 不能为空");
        }
        String normalizedName = documentName.trim().replace('\\', '/');
        while (normalizedName.startsWith("./")) {
            normalizedName = normalizedName.substring(2);
        }
        Path exact = documents.get(normalizedName);
        if (exact != null) {
            return Map.entry(normalizedName, exact);
        }
        if (!normalizedName.contains("/")) {
            String filename = normalizedName;
            List<Map.Entry<String, Path>> matches = documents.entrySet().stream()
                    .filter(entry -> entry.getKey().substring(entry.getKey().indexOf('/') + 1)
                            .equals(filename))
                    .toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                        "原始文档名不明确，请使用 list_original_documents 返回的完整名称: "
                                + documentName);
            }
        }
        throw new IllegalArgumentException("原始文档不存在: " + documentName);
    }

    private String searchFiles(Map<String, Path> files, String query, Integer maxResults) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int limit = maxResults == null ? 8 : maxResults;
        if (limit < 1 || limit > MAX_SEARCH_RESULTS) {
            throw new IllegalArgumentException("max_results 必须在 1 到 " + MAX_SEARCH_RESULTS + " 之间");
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String value : query.trim().split("[\\s|,，;；]+")) {
            if (!value.isBlank()) {
                keywords.add(value.toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder result = new StringBuilder();
        int matches = 0;
        for (Map.Entry<String, Path> file : files.entrySet()) {
            String content = readText(file.getValue());
            String normalized = content.toLowerCase(Locale.ROOT);
            Set<Integer> offsets = new LinkedHashSet<>();
            for (String keyword : keywords) {
                int from = 0;
                while (matches + offsets.size() < limit) {
                    int offset = normalized.indexOf(keyword, from);
                    if (offset < 0) {
                        break;
                    }
                    offsets.add(offset);
                    from = offset + Math.max(1, keyword.length());
                }
            }
            for (int offset : offsets.stream().sorted().toList()) {
                int start = Math.max(0, offset - 180);
                int end = Math.min(content.length(), offset + 360);
                result.append("## ").append(file.getKey())
                        .append(" · offset ").append(offset).append('\n')
                        .append(content, start, end).append("\n\n");
                matches++;
                if (matches >= limit) {
                    break;
                }
            }
            if (matches >= limit) {
                break;
            }
        }
        return matches == 0
                ? "没有找到匹配内容。请调整关键词，或先查看文档目录后分段阅读原文。"
                : result.toString().trim();
    }

    private String readText(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("文件不存在: " + file.getFileName());
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败", e);
        }
    }

    private void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            moveReplace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeText(Path file, String value, String errorMessage) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
            try {
                Files.writeString(temporary, value.trim(), StandardCharsets.UTF_8);
                moveReplace(temporary, file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireTextWithinLimit(String value, String name, int limit) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.length() > limit) {
            throw new IllegalArgumentException(name + " 不能超过 " + limit + " 个字符");
        }
    }

    private Path resolveInside(Path parent, String child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path result = normalizedParent.resolve(child).normalize();
        if (!result.startsWith(normalizedParent)) {
            throw new IllegalArgumentException("路径不合法");
        }
        return result;
    }

    private String safeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String cleaned = filename.trim().replace('\\', '/');
        if (cleaned.contains("/") || cleaned.equals(".") || cleaned.equals("..")) {
            throw new IllegalArgumentException("文件名不合法: " + filename);
        }
        return cleaned;
    }

    private String filenamePart(String title) {
        String value = title.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-")
                .replaceAll("\\s+", "-");
        return value.substring(0, Math.min(value.length(), 80));
    }
}

