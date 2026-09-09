export interface ContentEntry {
    filename: string;
    title: string;
}

export interface WritingTask {
    id: string;
    userId: string;
    sessionId: string;
    createdAt: string | number;
}

export type TaskStatus = "RUNNING" | "PAUSE_REQUESTED" | "PAUSED" | "RESUMING"
    | "COMPLETED" | "FAILED" | "CANCELLED";

export interface TaskControl {
    taskId: string;
    status: TaskStatus;
    activeRunId?: string | null;
    createdAt: string | number;
    updatedAt: string | number;
    lockVersion: number;
}

export interface WritingTaskView {
    task: WritingTask;
    sources: string[];
    contents: ContentEntry[];
    status: TaskStatus;
    activeRunId?: string | null;
    updatedAt: string | number;
}

export interface AgentRunInterrupt {
    interruptId: string;
    toolCallId: string;
    toolName: string;
    toolInput: Record<string, unknown>;
}

export interface PendingReviewResponse {
    stageId: string;
    title: string;
    markdown: string;
    runId: string;
    interrupts: AgentRunInterrupt[];
}

export interface ConversationHistory {
    messages: Array<{id: string; runId?: string; role: string; content: string; sequenceNo: number; createdAt: string}>;
    runs: Array<{id: string; status: string; createdAt: string; updatedAt: string}>;
    events: Array<{runId: string; eventType: string; payload: string; toolCallId?: string; createdAt: string; sequenceNo: number}>;
}

export interface PendingReview {
    taskId: string;
    stageId: string;
    title: string;
    markdown: string;
    runId: string;
    toolCallId: string;
}

export type AguiEvent = Record<string, unknown> & {
    type: string;
    timestamp?: string;
};

export interface ProcessEntry {
    id: string;
    sequence: number;
    timestamp: number;
    label: string;
    meta: string;
    kind: "model-text" | "final-output" | "thinking" | "tool-call" | "tool-result" | "status" | "error";
    content?: string;
    open?: boolean;
    running?: boolean;
}

export interface UserMessage {
    id: string;
    kind: "user";
    text: string;
    meta: string;
}

export interface NoticeMessage {
    id: string;
    kind: "notice";
    text: string;
    meta: string;
}

export interface StreamMessage {
    id: string;
    kind: "stream";
    process: ProcessEntry[];
    streaming: boolean;
    failed: boolean;
    nextSequence: number;
    savedFiles: Set<string>;
    toolCalls: Record<string, {name: string; argumentEntry?: ProcessEntry; contentEntry?: ProcessEntry; resultEntry?: ProcessEntry}>;
}

export interface ReviewMessage {
    id: string;
    kind: "review";
    review: PendingReview;
    approved: boolean;
    rejected?: boolean;
    feedback?: string;
    status: string;
}

export type ConversationItem = UserMessage | NoticeMessage | StreamMessage | ReviewMessage;
