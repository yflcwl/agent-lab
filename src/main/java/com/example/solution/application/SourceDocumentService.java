package com.example.solution.application;

import com.example.solution.config.SolutionAgentProperties;
import com.example.solution.domain.SourceDocument;
import com.example.solution.infrastructure.WritingTaskRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipFile;

@Service
public class SourceDocumentService {

    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private final WritingTaskRepository repository;
    private final TaskEventService eventService;
    private final Path workspace;

    public SourceDocumentService(WritingTaskRepository repository, TaskEventService eventService, SolutionAgentProperties properties) {
        this.repository = repository;
        this.eventService = eventService;
        this.workspace = Path.of(properties.getWorkspace()).toAbsolutePath().normalize();
    }

    public SourceDocument upload(long taskId, MultipartFile file) {
        repository.findTask(taskId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要上传的资料");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("单个资料不能超过 30MB");
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String extension = extension(original);
        if (!List.of("pdf", "docx", "md", "txt").contains(extension)) {
            throw new IllegalArgumentException("仅支持 PDF、DOCX、MD、TXT 资料");
        }
        try {
            Path folder = workspace.resolve("task-" + taskId).resolve("sources").normalize();
            Files.createDirectories(folder);
            Path target = folder.resolve(UUID.randomUUID() + "-" + original).normalize();
            if (!target.startsWith(folder)) throw new IllegalArgumentException("文件名不合法");
            try (InputStream input = file.getInputStream()) { Files.copy(input, target); }
            String error = null;
            try { read(target, extension); } catch (Exception e) { error = "无法提取文本：" + e.getMessage(); }
            SourceDocument source = repository.addSource(taskId, original, workspace.relativize(target).toString().replace('\\', '/'),
                    extension.toUpperCase(Locale.ROOT), file.getSize(), sha256(target), error == null ? "READY" : "UNREADABLE", error);
            trace(taskId, "TaskManager", "SOURCE_UPLOADED", original + (error == null ? " 已上传并可读取" : " 已上传，但" + error));
            return source;
        } catch (Exception e) {
            throw new IllegalStateException("保存资料失败：" + e.getMessage(), e);
        }
    }

    public String listForAgent(long taskId) {
        List<SourceDocument> sources = repository.findSources(taskId);
        trace(taskId, "DocumentReader", "TOOL_LIST_SOURCES", "列出 " + sources.size() + " 份已上传资料");
        return sources.stream().map(source -> "ID=" + source.id() + " | " + source.originalFilename() + " | " + source.fileType()
                        + " | " + source.extractionStatus() + (source.extractionError() == null ? "" : " | " + source.extractionError()))
                .reduce((left, right) -> left + "\n" + right).orElse("当前没有可用资料");
    }

    public String readForAgent(long taskId, String sourceId, String query) {
        long id;
        try { id = Long.parseLong(sourceId); } catch (NumberFormatException e) { return "资料 ID 无效"; }
        SourceDocument source = repository.findSource(taskId, id);
        if (!"READY".equals(source.extractionStatus())) return "该资料无法读取：" + source.extractionError();
        try {
            String text = read(workspace.resolve(source.storedPath()).normalize(), source.fileType().toLowerCase(Locale.ROOT));
            String selected = select(text, query);
            trace(taskId, "DocumentReader", "TOOL_READ_SOURCE", "读取《" + source.originalFilename() + "》"
                    + (StringUtils.hasText(query) ? "，关键词：" + query : ""));
            return "来源：《" + source.originalFilename() + "》\n" + selected;
        } catch (Exception e) {
            trace(taskId, "DocumentReader", "TOOL_READ_FAILED", "读取《" + source.originalFilename() + "》失败：" + e.getMessage());
            return "读取资料失败：" + e.getMessage();
        }
    }

    private String read(Path file, String type) throws Exception {
        if ("md".equals(type) || "txt".equals(type)) return Files.readString(file, StandardCharsets.UTF_8);
        if ("pdf".equals(type)) {
            try (var document = Loader.loadPDF(file.toFile())) { return new PDFTextStripper().getText(document); }
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            if (entry == null) throw new IllegalArgumentException("DOCX 缺少正文");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder().parse(zip.getInputStream(entry));
            NodeList paragraphs = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                NodeList texts = ((Element) paragraphs.item(i)).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
                for (int j = 0; j < texts.getLength(); j++) result.append(texts.item(j).getTextContent());
                result.append('\n');
            }
            return result.toString();
        }
    }

    private String select(String text, String query) {
        String normalized = text.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "").trim();
        if (!StringUtils.hasText(query)) return normalized.substring(0, Math.min(normalized.length(), 12000));
        int index = normalized.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (index < 0) return normalized.substring(0, Math.min(normalized.length(), 12000));
        int start = Math.max(0, index - 3000), end = Math.min(normalized.length(), index + 9000);
        return normalized.substring(start, end);
    }

    private String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 1 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(Path file) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format("%02x", value));
        return result.toString();
    }

    private void trace(long taskId, String actor, String eventType, String detail) {
        repository.recordTrace(taskId, actor, eventType, detail);
        eventService.publish(taskId, "AGENT_TRACE", 0L);
    }
}
