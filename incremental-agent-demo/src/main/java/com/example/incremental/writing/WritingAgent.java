package com.example.incremental.writing;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import com.example.incremental.writing.WritingTask;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface WritingAgent {

    Flux<AguiEvent> streamRound(
            WritingTask task,
            String chapterSessionId,
            String runId,
            WritingRunCommand command,
            WritingToolContext toolContext);

    Flux<AguiEvent> resumeRound(
            WritingTask task,
            String chapterSessionId,
            String runId,
            List<AguiResume> resume,
            Map<String, String> resumeToolCallIds,
            WritingToolContext toolContext,
            String message);

    default Flux<AguiEvent> resumePausedRound(
            WritingTask task,
            String chapterSessionId,
            String runId,
            WritingRunCommand command,
            WritingToolContext toolContext) {
        return streamRound(task, chapterSessionId, runId, command, toolContext);
    }

    default void requestPause(WritingTask task, String chapterSessionId) {
    }
}

