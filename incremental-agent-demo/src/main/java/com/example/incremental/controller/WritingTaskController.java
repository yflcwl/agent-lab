package com.example.incremental.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.agentscope.core.agui.event.AguiEvent;
import com.example.incremental.writing.WritingTaskView;
import com.example.incremental.writing.ChapterStageStatus;
import com.example.incremental.runtime.AgentRunInterrupt;
import com.example.incremental.runtime.AgentRunRuntime;
import com.example.incremental.rag.DocumentTextExtractor;
import com.example.incremental.runtime.RoundRunner;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.rag.TempRagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/tasks")
public class WritingTaskController {

    private final TaskWorkspaceService workspaceService;
    private final RoundRunner roundRunner;
    private final AgentRunRuntime agentRunRuntime;
    private final DocumentTextExtractor documentTextExtractor;
    private final TempRagService tempRagService;

    public WritingTaskController(
            TaskWorkspaceService workspaceService,
            RoundRunner roundRunner,
            AgentRunRuntime agentRunRuntime,
            DocumentTextExtractor documentTextExtractor,
            TempRagService tempRagService) {
        this.workspaceService = workspaceService;
        this.roundRunner = roundRunner;
        this.agentRunRuntime = agentRunRuntime;
        this.documentTextExtractor = documentTextExtractor;
        this.tempRagService = tempRagService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<WritingTaskView> create(@RequestBody CreateTaskRequest request) {
        return createAndIndex(request.userId(), request.referenceDocument(), request.sources());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<WritingTaskView> createFromFiles(
            @RequestPart("userId") String userId,
            @RequestPart("referenceDocument") FilePart referenceDocument,
            @RequestPart(name = "sourceFiles", required = false) Flux<FilePart> sourceFiles) {
        Flux<FilePart> files = sourceFiles == null ? Flux.empty() : sourceFiles;
        return Mono.zip(
                        documentTextExtractor.extract(referenceDocument),
                        files.collectList())
                .flatMap(result -> {
                    if (result.getT2().isEmpty()) {
                        throw new IllegalArgumentException("请至少上传一份目标背景资料");
                    }
                    Set<String> filenames = new HashSet<>();
                    result.getT2().forEach(source -> {
                        if (!filenames.add(source.filename())) {
                            throw new IllegalArgumentException("背景资料文件名重复: " + source.filename());
                        }
                    });
                    return createAndIndexFiles(userId, result.getT1(), result.getT2());
                });
    }

    private Mono<WritingTaskView> createAndIndex(
            String userId, String referenceDocument, Map<String, String> sources) {
        Map<String, String> taskSources = sources == null ? Map.of() : new LinkedHashMap<>(sources);
        return Mono.fromCallable(() -> workspaceService.createTask(userId, referenceDocument, taskSources))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(task -> {
                    tempRagService.create(task.id());
                    return Flux.fromIterable(taskSources.entrySet())
                            .concatMap(source -> tempRagService.addDocument(
                                    task.id(), source.getKey(), source.getKey(), source.getValue()))
                            .then(Mono.fromCallable(() -> workspaceService.getTaskView(task.id())))
                            .onErrorResume(error -> {
                                tempRagService.delete(task.id());
                                return Mono.error(error);
                            });
                });
    }

    private Mono<WritingTaskView> createAndIndexFiles(
            String userId, String referenceDocument, List<FilePart> sourceFiles) {
        return Mono.fromCallable(() -> workspaceService.createTask(userId, referenceDocument, Map.of()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(task -> {
                    tempRagService.create(task.id());
                    return Flux.fromIterable(sourceFiles)
                            .concatMap(file -> {
                                Path target = workspaceService.sourcePath(task.id(), file.filename());
                                return file.transferTo(target)
                                        .then(Mono.defer(() -> tempRagService.addDocument(
                                                task.id(), file.filename(), file.filename(), target)));
                            })
                            .then(Mono.fromCallable(() -> workspaceService.getTaskView(task.id())))
                            .onErrorResume(error -> {
                                tempRagService.delete(task.id());
                                return Mono.error(error);
                            });
                });
    }

    @GetMapping
    public List<WritingTaskView> listTasks() {
        return workspaceService.listTaskViews();
    }

    @PostMapping(value = "/{taskId}/rounds", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AguiEvent>> runRound(
            @PathVariable String taskId,
            @RequestBody(required = false) RunRoundRequest request,
            ServerHttpResponse response) {
        response.getHeaders().set("Cache-Control", "no-cache, no-transform");
        response.getHeaders().set("X-Accel-Buffering", "no");
        String message = request == null ? "" : request.message();
        return roundRunner.run(taskId, message)
                .map(event -> ServerSentEvent.<AguiEvent>builder()
                        .event(event.getType().name())
                        .data(event)
                        .build());
    }

    @PostMapping(value = "/{taskId}/rounds/{runId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AguiEvent>> resumeRound(
            @PathVariable String taskId,
            @PathVariable String runId,
            @RequestBody ResumeRoundRequest request,
            ServerHttpResponse response) {
        response.getHeaders().set("Cache-Control", "no-cache, no-transform");
        response.getHeaders().set("X-Accel-Buffering", "no");
        return roundRunner.resume(taskId, runId, request.decisions())
                .map(event -> ServerSentEvent.<AguiEvent>builder()
                        .event(event.getType().name())
                        .data(event)
                        .build());
    }

    @GetMapping("/{taskId}")
    public WritingTaskView getTask(@PathVariable String taskId) {
        return workspaceService.getTaskView(taskId);
    }

    @GetMapping("/{taskId}/pending-review")
    public ResponseEntity<PendingChapterReviewView> getPendingReview(@PathVariable String taskId) {
        var stage = workspaceService.findOpenChapterStage(taskId);
        var run = agentRunRuntime.findAwaitingConfirmation(taskId);
        if (stage == null || stage.status() != ChapterStageStatus.AWAITING_REVIEW || run == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new PendingChapterReviewView(
                stage.stageId(), stage.content().title(), stage.markdown(),
                run.runId(), run.pendingInterrupts()));
    }

    @GetMapping(value = "/{taskId}/content", produces = "text/markdown;charset=UTF-8")
    public String getContent(@PathVariable String taskId) {
        return workspaceService.getFullContent(taskId);
    }
}

record CreateTaskRequest(
        String userId,
        @JsonAlias("template") String referenceDocument,
        Map<String, String> sources) {
}

record RunRoundRequest(String message) {
}

record ResumeRoundRequest(java.util.List<AgentRunDecision> decisions) {
}

record PendingChapterReviewView(
        String stageId,
        String title,
        String markdown,
        String runId,
        List<AgentRunInterrupt> interrupts) {
}

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, String>> handle(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", exception.getMessage()));
    }
}

