package com.example.solution.agent;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;

final class AgentScopeEventObserver {

    private final AgentExecutionObserver observer;
    private int generatedCharacters;
    private int lastReportedCharacters;
    private boolean thinkingReported;

    AgentScopeEventObserver(AgentExecutionObserver observer) {
        this.observer = observer;
    }

    void accept(AgentEvent event) {
        if (observer == null) {
            return;
        }
        if (event instanceof AgentStartEvent) {
            emit("AGENT_STARTED", null, null, "Agent 已启动，正在准备执行");
        } else if (event instanceof ModelCallStartEvent) {
            thinkingReported = false;
            emit("MODEL_CALL_STARTED", null, null, "正在请求模型分析");
        } else if (event instanceof ThinkingBlockDeltaEvent && !thinkingReported) {
            thinkingReported = true;
            emit("THINKING", null, null, "模型正在推理");
        } else if (event instanceof ToolCallStartEvent tool) {
            emit("TOOL_CALL_STARTED", tool.getToolCallId(), tool.getToolCallName(), toolMessage(tool.getToolCallName(), "正在调用"));
        } else if (event instanceof ToolCallEndEvent tool) {
            emit("TOOL_CALL_SUBMITTED", tool.getToolCallId(), tool.getToolCallName(), toolMessage(tool.getToolCallName(), "已提交调用"));
        } else if (event instanceof ToolResultStartEvent tool) {
            emit("TOOL_RESULT_STARTED", tool.getToolCallId(), tool.getToolCallName(), toolMessage(tool.getToolCallName(), "正在执行"));
        } else if (event instanceof ToolResultEndEvent tool) {
            emit("TOOL_RESULT_FINISHED", tool.getToolCallId(), tool.getToolCallName(),
                    toolMessage(tool.getToolCallName(), "SUCCESS".equals(String.valueOf(tool.getState())) ? "执行完成" : "执行失败"));
        } else if (event instanceof TextBlockDeltaEvent text) {
            generatedCharacters += text.getDelta().length();
            if (generatedCharacters - lastReportedCharacters >= 800) {
                lastReportedCharacters = generatedCharacters;
                emit("GENERATING", null, null, "正在生成响应，已接收 " + generatedCharacters + " 个字符");
            }
        } else if (event instanceof ModelCallEndEvent) {
            emit("MODEL_CALL_FINISHED", null, null, "本轮模型调用完成");
        } else if (event instanceof AgentResultEvent) {
            emit("AGENT_RESULT", null, null, "Agent 已返回最终结果");
        } else if (event instanceof AgentEndEvent) {
            emit("AGENT_FINISHED", null, null, "Agent 执行结束");
        }
    }

    private void emit(String type, String toolCallId, String toolName, String detail) {
        observer.onEvent(new AgentExecutionEvent(type, toolCallId, toolName, detail));
    }

    private String toolMessage(String toolName, String action) {
        if ("read_source".equals(toolName)) {
            return action + "资料读取";
        }
        if ("list_sources".equals(toolName)) {
            return action + "资料目录查询";
        }
        return action + "工具：" + toolName;
    }
}
