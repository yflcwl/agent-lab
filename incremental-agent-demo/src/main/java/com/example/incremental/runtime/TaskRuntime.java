package com.example.incremental.runtime;

import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.writing.TaskStatus;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.WritingTaskView;
import org.springframework.stereotype.Service;

import java.util.List;

/** Parent-task control plane. RunRuntime decides the safe point; this class decides whether work may run. */
@Service
public class TaskRuntime {

    private static final String WRITING_AGENT_ID = "incremental-writing-agent";

    private final TaskWorkspaceService workspaceService;
    private final TaskControlRepository tasks;
    private final AgentRunRuntime runRuntime;

    public TaskRuntime(
            TaskWorkspaceService workspaceService,
            TaskControlRepository tasks,
            AgentRunRuntime runRuntime) {
        this.workspaceService = workspaceService;
        this.tasks = tasks;
        this.runRuntime = runRuntime;
    }

    public synchronized TaskControl find(String taskId) {
        TaskControl task = tasks.find(taskId);
        if (task != null) {
            if (task.deletedAt() != null) {
                throw new IllegalArgumentException("WritingTask 不存在或已删除: " + taskId);
            }
            return task;
        }
        WritingTask writingTask = workspaceService.findTask(taskId);
        return tasks.create(writingTask, WRITING_AGENT_ID, "写作任务 " + taskId);
    }

    public WritingTaskView getTaskView(String taskId) {
        WritingTaskView view = workspaceService.getTaskView(taskId);
        return controlled(view, find(taskId));
    }

    public List<WritingTaskView> listTaskViews() {
        return workspaceService.listTaskViews().stream()
                .filter(view -> {
                    TaskControl control = tasks.find(view.task().id());
                    return control == null || control.deletedAt() == null;
                })
                .map(view -> controlled(view, find(view.task().id())))
                .toList();
    }

    public synchronized void requireCanCreateRun(String taskId) {
        TaskControl task = find(taskId);
        if (task.status() != TaskStatus.RUNNING) {
            throw new IllegalStateException("WritingTask 当前不允许创建新的 Run: " + task.status());
        }
        if (task.activeRunId() != null) {
            throw new IllegalStateException("WritingTask 已有 activeRun: " + task.activeRunId());
        }
    }

    public synchronized TaskControl attachRun(String taskId, String runId) {
        TaskControl task = find(taskId);
        if (task.status() != TaskStatus.RUNNING) {
            throw new IllegalStateException("WritingTask 当前不允许创建新的 Run: " + task.status());
        }
        if (task.activeRunId() != null) {
            throw new IllegalStateException("WritingTask 已有 activeRun: " + task.activeRunId());
        }
        return tasks.transition(task, TaskStatus.RUNNING, runId);
    }

    public synchronized TaskControl replaceRunForRetry(String taskId, String failedRunId, String retryRunId) {
        TaskControl task = find(taskId);
        if (task.status() != TaskStatus.RUNNING || !failedRunId.equals(task.activeRunId())) {
            throw new IllegalStateException("WritingTask 当前不允许调度重试 Run");
        }
        return tasks.transition(task, TaskStatus.RUNNING, retryRunId);
    }

    public synchronized void requireRunCanContinue(String taskId, String runId) {
        TaskControl task = find(taskId);
        if (task.status() != TaskStatus.RUNNING) {
            throw new IllegalStateException("WritingTask 当前不允许继续 Run: " + task.status());
        }
        if (!runId.equals(task.activeRunId())) {
            throw new IllegalArgumentException("Run 不是当前 WritingTask 的 activeRun");
        }
    }

    public synchronized TaskControl requestPause(String taskId) {
        TaskControl task = find(taskId);
        if (task.status() == TaskStatus.PAUSED || task.status() == TaskStatus.PAUSE_REQUESTED) {
            return task;
        }
        if (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.RESUMING) {
            throw new IllegalStateException("WritingTask 当前不能暂停: " + task.status());
        }
        if (task.activeRunId() == null) {
            return tasks.transition(task, TaskStatus.PAUSED, null);
        }

        TaskControl requested = tasks.transition(task, TaskStatus.PAUSE_REQUESTED, task.activeRunId());
        AgentRunRuntime.PauseRequestResult result = runRuntime.requestPause(task.activeRunId());
        if (result == AgentRunRuntime.PauseRequestResult.REQUESTED) {
            return requested;
        }
        AgentRunRecord run = runRuntime.find(task.activeRunId());
        if (run.status() == AgentRunStatus.PAUSED) {
            return tasks.transition(requested, TaskStatus.PAUSED, task.activeRunId());
        }
        if (run.status() == AgentRunStatus.ERROR) {
            return tasks.transition(requested, TaskStatus.FAILED, task.activeRunId());
        }
        return tasks.transition(requested, taskStatusAfterTerminalRun(run.status(), true), null);
    }

    public synchronized TaskResume prepareResume(String taskId) {
        TaskControl task = find(taskId);
        if (task.status() == TaskStatus.PAUSE_REQUESTED) {
            throw new IllegalStateException("WritingTask 尚未到达安全暂停点");
        }
        if (task.status() == TaskStatus.FAILED) {
            if (task.activeRunId() == null) {
                throw new IllegalStateException("WritingTask 没有可恢复的失败 Run");
            }
            AgentRunRecord run = runRuntime.find(task.activeRunId());
            if (run.status() != AgentRunStatus.ERROR) {
                throw new IllegalStateException("WritingTask 的 activeRun 不在可恢复的失败状态");
            }
            TaskControl resuming = tasks.transition(task, TaskStatus.RESUMING, task.activeRunId());
            try {
                return new TaskResume(resuming, runRuntime.prepareErrorResume(taskId, task.activeRunId()));
            } catch (RuntimeException error) {
                tasks.transition(resuming, TaskStatus.FAILED, task.activeRunId());
                throw error;
            }
        }
        if (task.status() != TaskStatus.PAUSED) {
            throw new IllegalStateException("WritingTask 当前不在暂停状态: " + task.status());
        }
        TaskControl resuming = tasks.transition(task, TaskStatus.RESUMING, task.activeRunId());
        if (task.activeRunId() == null) {
            TaskControl running = tasks.transition(resuming, TaskStatus.RUNNING, null);
            return new TaskResume(running, null);
        }
        try {
            AgentRunRuntime.PausedResume run = runRuntime.preparePausedResume(taskId, task.activeRunId());
            if (!run.execute()) {
                TaskControl running = tasks.transition(resuming, TaskStatus.RUNNING, task.activeRunId());
                return new TaskResume(running, run);
            }
            return new TaskResume(resuming, run);
        } catch (RuntimeException error) {
            tasks.transition(resuming, TaskStatus.PAUSED, task.activeRunId());
            throw error;
        }
    }

    public synchronized TaskControl markRunRunning(String taskId, String runId) {
        TaskControl task = find(taskId);
        if (!runId.equals(task.activeRunId())) {
            throw new IllegalArgumentException("Run 不是当前 WritingTask 的 activeRun");
        }
        if (task.status() == TaskStatus.PAUSE_REQUESTED) {
            return task;
        }
        if (task.status() != TaskStatus.RESUMING) {
            throw new IllegalStateException("WritingTask 当前不在恢复中: " + task.status());
        }
        return tasks.transition(task, TaskStatus.RUNNING, runId);
    }

    public synchronized TaskControl markRunPaused(String taskId, String runId) {
        TaskControl task = find(taskId);
        if (!runId.equals(task.activeRunId())) {
            return task;
        }
        if (task.status() != TaskStatus.PAUSE_REQUESTED) {
            throw new IllegalStateException("WritingTask 未请求暂停，不能确认 Run 已暂停");
        }
        return tasks.transition(task, TaskStatus.PAUSED, runId);
    }

    public synchronized TaskControl onRunStreamFinished(String taskId, String runId) {
        TaskControl task = find(taskId);
        if (!runId.equals(task.activeRunId())) {
            return task;
        }
        AgentRunRecord run = runRuntime.find(runId);
        if (run.status() == AgentRunStatus.PAUSED) {
            return task.status() == TaskStatus.PAUSED
                    ? task : tasks.transition(task, TaskStatus.PAUSED, runId);
        }
        if (run.status() == AgentRunStatus.ERROR) {
            return task.status() == TaskStatus.FAILED
                    ? task : tasks.transition(task, TaskStatus.FAILED, runId);
        }
        if (run.status().terminal()) {
            TaskStatus next = taskStatusAfterTerminalRun(
                    run.status(), task.status() == TaskStatus.PAUSE_REQUESTED);
            return tasks.transition(task, next, null);
        }
        if (task.status() == TaskStatus.RESUMING) {
            return tasks.transition(task, TaskStatus.RUNNING, runId);
        }
        return task;
    }

    public synchronized TaskControl markCompleted(String taskId) {
        return markTerminal(taskId, TaskStatus.COMPLETED);
    }

    public synchronized TaskControl markFailed(String taskId) {
        return markTerminal(taskId, TaskStatus.FAILED);
    }

    public synchronized TaskControl markCancelled(String taskId) {
        return markTerminal(taskId, TaskStatus.CANCELLED);
    }

    private TaskControl markTerminal(String taskId, TaskStatus target) {
        TaskControl task = find(taskId);
        if (task.activeRunId() != null) {
            throw new IllegalStateException("WritingTask 仍有 activeRun，不能进入终态");
        }
        if (task.status().terminal()) {
            return task;
        }
        return tasks.transition(task, target, null);
    }

    private WritingTaskView controlled(WritingTaskView view, TaskControl control) {
        return new WritingTaskView(view.task(), view.sources(), view.contents(),
                control.status(), control.activeRunId(), control.updatedAt());
    }

    private TaskStatus taskStatusAfterTerminalRun(AgentRunStatus runStatus, boolean pauseRequested) {
        if (!runStatus.terminal()) {
            throw new IllegalArgumentException("Run 尚未结束: " + runStatus);
        }
        return pauseRequested ? TaskStatus.PAUSED : TaskStatus.RUNNING;
    }

    public record TaskResume(TaskControl task, AgentRunRuntime.PausedResume run) {
    }
}
