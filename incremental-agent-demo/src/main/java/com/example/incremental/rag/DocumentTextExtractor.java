package com.example.incremental.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
public class DocumentTextExtractor {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "docx", "pdf");

    public Mono<String> extract(FilePart filePart) {
        String filename = filePart.filename();
        String extension = extension(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            return Mono.error(new IllegalArgumentException(
                    "不支持的文件格式: " + filename + "，仅支持 docx、pdf、md、txt"));
        }
        return DataBufferUtils.join(filePart.content(), MAX_FILE_SIZE)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return extract(filename, extension, bytes);
                })
                .onErrorMap(DataBufferLimitException.class,
                        ignored -> new IllegalArgumentException("文件不能超过 10MB: " + filename));
    }

    public String extract(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在");
        }
        String filename = file.getFileName().toString();
        String extension = extension(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "不支持的文件格式: " + filename + "，仅支持 docx、pdf、md、txt");
        }
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("文件不能超过 10MB: " + filename);
            }
            return extract(filename, extension, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件: " + filename, e);
        }
    }

    private String extract(String filename, String extension, byte[] bytes) {
        try {
            String text = switch (extension) {
                case "txt", "md" -> new String(bytes, StandardCharsets.UTF_8);
                case "docx" -> extractDocx(bytes);
                case "pdf" -> extractPdf(bytes);
                default -> throw new IllegalStateException("未处理的文件格式");
            };
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("文件中没有可读取的文字: " + filename);
            }
            return text.trim();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件: " + filename, e);
        }
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendLine(text, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    table.getRows().forEach(row -> row.getTableCells().stream()
                            .map(XWPFTableCell::getText)
                            .filter(StringUtils::hasText)
                            .forEach(value -> appendLine(text, value)));
                }
            }
            return text.toString();
        }
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private void appendLine(StringBuilder text, String value) {
        if (StringUtils.hasText(value)) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(value.trim());
        }
    }

    private String extension(String filename) {
        int separator = filename.lastIndexOf('.');
        if (separator < 0 || separator == filename.length() - 1) {
            return "";
        }
        return filename.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}

