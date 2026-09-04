package com.example.solution.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TaskEventService {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final long heartbeatMillis;

    public TaskEventService(@Value("${solution.sse.heartbeat-millis:15000}") long heartbeatMillis) {
        this.heartbeatMillis = heartbeatMillis;
    }

    public SseEmitter subscribe(long taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(taskId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        send(emitter, "CONNECTED", taskId, 0L);
        return emitter;
    }

    public void publish(long taskId, String eventType, long stateVersion) {
        List<SseEmitter> taskEmitters = emitters.getOrDefault(taskId, List.of());
        for (SseEmitter emitter : taskEmitters) {
            send(emitter, eventType, taskId, stateVersion);
        }
        if ("TASK_COMPLETED".equals(eventType)) {
            taskEmitters.forEach(SseEmitter::complete);
            emitters.remove(taskId);
        }
    }

    @Scheduled(fixedDelayString = "${solution.sse.heartbeat-millis:15000}")
    public void heartbeat() {
        emitters.forEach((taskId, taskEmitters) -> taskEmitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat-" + heartbeatMillis));
            } catch (IOException e) {
                remove(taskId, emitter);
            }
        }));
    }

    private void send(SseEmitter emitter, String eventType, long taskId, long stateVersion) {
        try {
            emitter.send(SseEmitter.event().name(eventType)
                    .data(new TaskEvent(taskId, eventType, stateVersion)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void remove(long taskId, SseEmitter emitter) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters != null) {
            taskEmitters.remove(emitter);
            if (taskEmitters.isEmpty()) {
                emitters.remove(taskId);
            }
        }
    }

    public record TaskEvent(long taskId, String type, long stateVersion) {
    }
}
