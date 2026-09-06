package com.example.incremental.agent;

import com.example.incremental.runtime.AgentRunContext;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.runtime.AgentRunInterrupt;
import com.example.incremental.writing.WritingAgent;
import com.example.incremental.writing.WritingRunCommand;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.WritingToolContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only adapter that invokes AgentScope-facing writing agents.
 */
@Service
public class AgentExecutor {

    private final WritingAgent writingAgent;

    public AgentExecutor(WritingAgent writingAgent) {
        this.writingAgent = writingAgent;
    }

    public Flux<AguiEvent> execute(
            AgentRunContext run,
            WritingTask task,
            WritingRunCommand command,
            WritingToolContext toolContext) {
        return writingAgent.streamRound(task, run.threadId(), run.runId(), command, toolContext);
    }

    public Flux<AguiEvent> resume(
            AgentRunContext run,
            WritingTask task,
            List<AgentRunDecision> decisions,
            List<AgentRunInterrupt> interrupts,
            WritingToolContext toolContext,
            String message) {
        Map<String, AgentRunInterrupt> interruptsByToolCallId = new LinkedHashMap<>();
        interrupts.forEach(interrupt -> interruptsByToolCallId.put(interrupt.toolCallId(), interrupt));
        List<AguiResume> resume = decisions.stream()
                .map(decision -> {
                    AgentRunInterrupt interrupt = interruptsByToolCallId.get(decision.toolCallId());
                    return new AguiResume(interrupt.interruptId(), decision.approved()
                            ? AguiResume.STATUS_RESOLVED : AguiResume.STATUS_CANCELLED,
                            Map.of("approved", decision.approved(),
                                    "feedback", decision.feedback() == null ? "" : decision.feedback()));
                })
                .toList();
        Map<String, String> toolCallIds = new LinkedHashMap<>();
        interrupts.forEach(interrupt -> toolCallIds.put(interrupt.interruptId(), interrupt.toolCallId()));
        String resumeMessage = message == null || message.isBlank()
                ? decisions.stream().map(AgentRunDecision::feedback)
                        .filter(feedback -> feedback != null && !feedback.isBlank())
                        .collect(java.util.stream.Collectors.joining("\n"))
                : message;
        return writingAgent.resumeRound(task, run.threadId(), run.runId(), resume, Map.copyOf(toolCallIds),
                toolContext, resumeMessage);
    }
}
