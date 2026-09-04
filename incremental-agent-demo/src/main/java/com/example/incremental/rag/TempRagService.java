package com.example.incremental.rag;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.rag.TempRagHit;
import com.example.incremental.rag.mapper.TaskRagChunkMapper;
import com.example.incremental.workspace.TaskWorkspaceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TempRagService {

    private final TempRagEmbeddingModel embeddingModel;
    private final DocumentTextExtractor documentTextExtractor;
    private final TaskWorkspaceService workspaceService;
    private final DemoProperties properties;
    private final ObjectProvider<TaskRagChunkMapper> ragChunkMapper;
    private final Map<String, TempRagVectorStore> storeByTaskId = new ConcurrentHashMap<>();

    public TempRagService(
            TempRagEmbeddingModel embeddingModel,
            DocumentTextExtractor documentTextExtractor,
            TaskWorkspaceService workspaceService,
            DemoProperties properties,
            ObjectProvider<TaskRagChunkMapper> ragChunkMapper) {
        this.embeddingModel = embeddingModel;
        this.documentTextExtractor = documentTextExtractor;
        this.workspaceService = workspaceService;
        this.properties = properties;
        this.ragChunkMapper = ragChunkMapper;
    }

    public void create(String taskId) {
        requireTaskId(taskId);
        storeByTaskId.computeIfAbsent(taskId, this::newStore);
    }

    public Mono<Void> addDocument(String taskId, String sourceId, String filename, String extractedText) {
        requireTaskId(taskId);
        if (!StringUtils.hasText(sourceId) || !StringUtils.hasText(filename) || !StringUtils.hasText(extractedText)) {
            return Mono.error(new IllegalArgumentException("临时 RAG 资料参数不能为空"));
        }
        TempRagVectorStore store = store(taskId);
        List<String> chunks = chunk(extractedText);
        return Flux.range(0, chunks.size())
                .concatMap(index -> embeddingModel.embed(chunks.get(index))
                        .map(embedding -> new TempRagVectorRecord(
                                sourceId,
                                filename,
                                sourceId + "-" + (index + 1),
                                chunks.get(index),
                                requireDimensions(embedding))))
                .collectList()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(store::add)
                .then();
    }

    public Mono<Void> addDocument(String taskId, String sourceId, String filename, Path file) {
        requireTaskId(taskId);
        if (!StringUtils.hasText(sourceId) || !StringUtils.hasText(filename) || file == null || !Files.isRegularFile(file)) {
            return Mono.error(new IllegalArgumentException("临时 RAG 资料参数不能为空或文件不存在"));
        }
        return Mono.fromCallable(() -> documentTextExtractor.extract(file))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(text -> addDocument(taskId, sourceId, filename, text));
    }

    public Mono<List<TempRagHit>> retrieve(String taskId, String query, int topK) {
        requireTaskId(taskId);
        if (!StringUtils.hasText(query)) {
            return Mono.error(new IllegalArgumentException("query 不能为空"));
        }
        if (topK < 1 || topK > 20) {
            return Mono.error(new IllegalArgumentException("topK 必须在 1 到 20 之间"));
        }
        TempRagVectorStore store = store(taskId);
        return ensureIndexed(taskId, store)
                .then(embeddingModel.embed(query.trim()))
                .publishOn(Schedulers.boundedElastic())
                .map(embedding -> store.search(requireDimensions(embedding), topK));
    }

    public Mono<List<TempRagHit>> retrieve(String taskId, String query) {
        return retrieve(taskId, query, properties.getRagTopK());
    }

    public void delete(String taskId) {
        TempRagVectorStore store = storeByTaskId.remove(taskId);
        if (store != null) {
            store.clear();
        }
    }

    private List<String> chunk(String text) {
        String normalized = text.trim();
        int chunkSize = properties.getRagChunkSize();
        int overlap = properties.getRagChunkOverlap();
        if (chunkSize < 1 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalStateException("RAG 切片配置无效");
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length();) {
            int end = Math.min(start + chunkSize, normalized.length());
            if (end < normalized.length()) {
                int paragraphBreak = normalized.lastIndexOf("\n\n", end);
                int lineBreak = normalized.lastIndexOf('\n', end);
                if (paragraphBreak > start + chunkSize / 2) {
                    end = paragraphBreak;
                } else if (lineBreak > start + chunkSize / 2) {
                    end = lineBreak;
                }
            }
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private float[] requireDimensions(float[] embedding) {
        if (embedding == null || embedding.length != properties.getRagEmbeddingDimensions()) {
            throw new IllegalStateException("Embedding 向量维度与 demo.rag-embedding-dimensions 不一致");
        }
        return embedding;
    }

    private TempRagVectorStore store(String taskId) {
        TempRagVectorStore store = storeByTaskId.get(taskId);
        if (store == null && "pgvector".equalsIgnoreCase(properties.getRagStore())) {
            return storeByTaskId.computeIfAbsent(taskId, this::newStore);
        }
        if (store == null) {
            throw new IllegalStateException("当前任务的临时资料索引不存在，请重新创建任务");
        }
        return store;
    }

    private TempRagVectorStore newStore(String taskId) {
        if ("pgvector".equalsIgnoreCase(properties.getRagStore())) {
            return new PgVectorTempRagVectorStore(taskId, ragChunkMapper.getIfAvailable(() -> {
                throw new IllegalStateException("pgvector Mapper 未初始化");
            }));
        }
        if ("in-memory".equalsIgnoreCase(properties.getRagStore())) {
            return new InMemoryTempRagVectorStore();
        }
        throw new IllegalStateException("demo.rag-store 必须为 pgvector 或 in-memory");
    }

    private Mono<Void> ensureIndexed(String taskId, TempRagVectorStore store) {
        return Mono.fromCallable(store::isEmpty)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(isEmpty -> {
                    if (!isEmpty) {
                        return Mono.empty();
                    }
                    return Mono.fromCallable(() -> workspaceService.listSources(taskId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(Flux::fromIterable)
                            .concatMap(filename -> addDocument(taskId, filename, filename,
                                    workspaceService.sourcePath(taskId, filename)))
                            .then();
                });
    }

    private void requireTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
    }
}

