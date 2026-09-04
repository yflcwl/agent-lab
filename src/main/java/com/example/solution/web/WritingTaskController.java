package com.example.solution.web;

import com.example.solution.application.TaskManager;
import com.example.solution.domain.ReviewDecision;
import com.example.solution.domain.TaskMessageType;
import com.example.solution.domain.WritingTaskState;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/writing-tasks")
public class WritingTaskController {

    private final TaskManager taskManager;

    public WritingTaskController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @PostMapping
    public CreateTaskResponse create(@RequestBody CreateTaskRequest request) {
        return new CreateTaskResponse(taskManager.createTask(request.goal(), request.templateMarkdown()));
    }

    @GetMapping("/{taskId}")
    public WritingTaskState detail(@PathVariable long taskId) {
        return taskManager.getState(taskId);
    }

    @PostMapping(value = "/{taskId}/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void uploadSource(@PathVariable long taskId, @RequestParam("file") MultipartFile file) {
        taskManager.uploadSource(taskId, file);
    }

    @PostMapping("/{taskId}/start")
    public void start(@PathVariable long taskId) {
        taskManager.start(taskId);
    }

    @PostMapping("/{taskId}/retry")
    public void retry(@PathVariable long taskId) {
        taskManager.retry(taskId);
    }

    @GetMapping(value = "/{taskId}/content", produces = "text/markdown;charset=UTF-8")
    public String content(@PathVariable long taskId) {
        return taskManager.getContent(taskId);
    }

    @PostMapping("/{taskId}/chapter-tasks/{chapterTaskId}/review")
    public void review(@PathVariable long taskId, @PathVariable long chapterTaskId,
                       @RequestBody ReviewRequest request) {
        if (request.decision() == null) {
            throw new IllegalArgumentException("审核结果不能为空");
        }
        taskManager.review(taskId, chapterTaskId, request.decision(), request.feedback());
    }

    @PostMapping("/{taskId}/messages")
    public void sendMessage(@PathVariable long taskId, @RequestBody TaskMessageRequest request) {
        taskManager.sendMessage(taskId, request.chapterTaskId(), request.messageType(), request.content());
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long taskId) {
        return taskManager.subscribe(taskId);
    }

    public record CreateTaskRequest(String goal, String templateMarkdown) {
    }

    public record CreateTaskResponse(long taskId) {
    }

    public record ReviewRequest(ReviewDecision decision, String feedback) {
    }

    public record TaskMessageRequest(Long chapterTaskId, TaskMessageType messageType, String content) {
    }
}
