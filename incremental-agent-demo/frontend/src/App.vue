<script setup lang="ts">
import {computed, nextTick, onBeforeUnmount, onMounted, reactive, ref} from "vue";
import type {ComponentPublicInstance} from "vue";
import {consumeSse, createTask, getContent, getHistory, getPendingReview, getTask, listTasks} from "./api";
import {renderMarkdown} from "./markdown";
import type {
    AguiEvent,
    ConversationItem,
    PendingReview,
    ProcessEntry,
    ReviewMessage,
    StreamMessage,
    WritingTaskView
} from "./types";

const MAX_FILE_SIZE = 10 * 1024 * 1024;
const AUTO_FOLLOW_DISTANCE = 72;
const HIDDEN_EVENT_TYPES = new Set([
    "REASONING_MESSAGE_START",
    "REASONING_MESSAGE_END",
    "TEXT_MESSAGE_START",
    "TEXT_MESSAGE_END",
    "AGENT_START",
    "AGENT_END",
    "SUBAGENT_EXPOSED",
    "TEXT_BLOCK_START",
    "TEXT_BLOCK_END",
    "THINKING_BLOCK_START",
    "THINKING_BLOCK_END"
]);

const tasks = ref<WritingTaskView[]>([]);
const taskId = ref<string | null>(null);
const taskView = ref<WritingTaskView | null>(null);
const pendingReview = ref<PendingReview | null>(null);
const running = ref(false);
const runningTaskId = ref<string | null>(null);
const stopping = ref(false);
const createBusy = ref(false);
const rawContent = ref("");
const artifactHtml = ref("");
const toast = ref("");
const toastError = ref(false);
const messageInput = ref("");
const userId = ref("demo-user");
const taskRequirement = ref("");
const referenceFile = ref<File | null>(null);
const sourceFiles = ref<File[]>([]);
const leftCollapsed = ref(false);
const rightCollapsed = ref(false);
const leftWidth = ref(288);
const rightWidth = ref(390);
let resizeCleanup: (() => void) | null = null;
const conversations = reactive<Record<string, ConversationItem[]>>({});
const conversationScroll = ref<HTMLElement | null>(null);
const targetElements = new Map<string, HTMLElement>();
const activeIndex = ref(0);
const hoveredIndex = ref<number | null>(null);
const hoveredTop = ref(0);
let abortController: AbortController | null = null;
let toastTimer: ReturnType<typeof setTimeout> | null = null;
let followsOutput = true;
let scrollAnimationFrame: number | null = null;
let forceScroll = false;

const currentConversation = computed(() => taskId.value ? conversations[taskId.value] || [] : []);
const userMessages = computed(() => currentConversation.value.filter(item => item.kind === "user"));
const hoveredMessage = computed(() => hoveredIndex.value == null ? null : userMessages.value[hoveredIndex.value] || null);
const hoveredReply = computed(() => {
    const message = hoveredMessage.value;
    if (!message) return "";
    const start = currentConversation.value.findIndex(item => item.id === message.id);
    const reply = currentConversation.value.slice(start + 1).find(item => item.kind !== "user");
    if (!reply) return "";
    if (reply.kind === "notice") return reply.text;
    if (reply.kind === "stream") return modelOutput(reply);
    if (reply.kind === "review") return reply.status;
    return "";
});
const indexPreviewStyle = computed(() => ({top: `${Math.max(0, hoveredTop.value - 10)}px`}));

function showIndexPreview(index: number, event: MouseEvent): void {
    hoveredIndex.value = index;
    hoveredTop.value = (event.currentTarget as HTMLElement).offsetTop;
}
const contentCount = computed(() => taskView.value?.contents.length || 0);
const runnable = computed(() => Boolean(taskView.value?.sources.length));
const viewingBackgroundTask = computed(() => running.value && taskId.value !== runningTaskId.value);
const awaitingReview = computed(() => pendingReview.value?.taskId === taskId.value);
const canSend = computed(() => runnable.value && !viewingBackgroundTask.value && !stopping.value && (!awaitingReview.value || running.value));
const sessionStatus = computed(() => {
    if (running.value && taskId.value === runningTaskId.value) return "本轮生成中";
    if (awaitingReview.value) return "等待章节审核";
    return runnable.value ? "上下文已就绪" : "缺少背景资料";
});
const sessionStatusClass = computed(() => running.value && taskId.value === runningTaskId.value
    ? "running" : awaitingReview.value || !runnable.value ? "warning" : "ready");
const composerPlaceholder = computed(() => {
    if (viewingBackgroundTask.value) return "另一项任务正在生成，当前任务暂时只能查看";
    if (!runnable.value) return "请先在左侧创建写作任务";
    if (awaitingReview.value) return "当前章节等待审核通过";
    return contentCount.value === 0 ? "输入消息，例如：开始写第一章" : "输入对上一章的建议，或发送“继续”";
});
const composerHint = computed(() => {
    if (viewingBackgroundTask.value) return "正在后台生成另一项任务；本轮结束后会自动返回";
    if (running.value) return "正在执行当前请求";
    if (!runnable.value) return "请先创建或选择一个可运行的对话任务";
    if (awaitingReview.value) return "候选章节尚未提交，请在上方审核卡中确认";
    return contentCount.value === 0
        ? "首轮将形成临时章节计划，并且只暂存第一章候选内容"
        : "每次发送消息只推进一章；候选章节需审核后才会进入成果区";
});
const shellStyle = computed(() => ({
    "--left-width": `${leftCollapsed.value ? 58 : leftWidth.value}px`,
    "--right-width": `${rightCollapsed.value ? 58 : rightWidth.value}px`
}));

function startResize(side: "left" | "right", event: PointerEvent): void {
    if ((side === "left" && leftCollapsed.value) || (side === "right" && rightCollapsed.value)) return;
    event.preventDefault();
    const startX = event.clientX;
    const initial = side === "left" ? leftWidth.value : rightWidth.value;
    const move = (moveEvent: PointerEvent) => {
        const delta = moveEvent.clientX - startX;
        if (side === "left") leftWidth.value = Math.min(420, Math.max(220, initial + delta));
        else rightWidth.value = Math.min(560, Math.max(300, initial - delta));
    };
    const stop = () => {
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", stop);
        resizeCleanup = null;
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", stop);
    resizeCleanup = stop;
}

onBeforeUnmount(() => resizeCleanup?.());

function newId(): string {
    return crypto.randomUUID();
}

function showToast(message: string, error = false): void {
    toast.value = message;
    toastError.value = error;
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        toast.value = "";
        toastTimer = null;
    }, 3200);
}

function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatTaskDate(value: string | number): string {
    const timestamp = typeof value === "number" && value < 1_000_000_000_000 ? value * 1000 : value;
    const date = new Date(timestamp);
    return Number.isNaN(date.getTime())
        ? "未知时间"
        : new Intl.DateTimeFormat("zh-CN", {month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"}).format(date);
}

function formatTime(value?: string): string {
    const date = value ? new Date(value) : new Date();
    return date.toLocaleTimeString("zh-CN", {hour12: false});
}

function ensureConversation(id: string): ConversationItem[] {
    if (!conversations[id]) conversations[id] = [];
    return conversations[id];
}

function appendConversation(id: string, item: ConversationItem): void {
    ensureConversation(id).push(item);
    if (id === taskId.value) scrollToLatest();
}

function appendNotice(id: string, text: string, meta: string): void {
    appendConversation(id, {id: newId(), kind: "notice", text, meta});
}

function createStreamMessage(): StreamMessage {
    return {
        id: newId(),
        kind: "stream",
        process: [],
        streaming: true,
        failed: false,
        nextSequence: 1,
        savedFiles: new Set(taskView.value?.contents.map(content => content.filename) || []),
        toolCalls: {}
    };
}

function scrollToLatest(force = false): void {
    if (!force && !followsOutput) return;
    forceScroll ||= force;
    if (scrollAnimationFrame !== null) return;
    scrollAnimationFrame = requestAnimationFrame(() => {
        scrollAnimationFrame = null;
        const element = conversationScroll.value;
        const shouldForceScroll = forceScroll;
        forceScroll = false;
        if (!element || (!shouldForceScroll && !followsOutput)) return;
        element.scrollTo({top: element.scrollHeight, behavior: "auto"});
    });
}

function captureTarget(id: string, element: Element | ComponentPublicInstance | null): void {
    if (element instanceof HTMLElement) targetElements.set(id, element);
    else targetElements.delete(id);
}

function scrollToConversationItem(id: string): void {
    targetElements.get(id)?.scrollIntoView({behavior: "smooth", block: "center"});
}

function updateActiveIndex(): void {
    if (!conversationScroll.value || !userMessages.value.length) return;
    const viewport = conversationScroll.value.getBoundingClientRect();
    const focusLine = viewport.top + viewport.height * 0.38;
    let nearest = 0;
    let distance = Number.POSITIVE_INFINITY;
    userMessages.value.forEach((item, index) => {
        const element = targetElements.get(item.id);
        if (!element) return;
        const bounds = element.getBoundingClientRect();
        const currentDistance = Math.abs(bounds.top + Math.min(bounds.height / 2, 90) - focusLine);
        if (currentDistance < distance) {
            distance = currentDistance;
            nearest = index;
        }
    });
    activeIndex.value = nearest;
}

function handleConversationScroll(): void {
    const element = conversationScroll.value;
    if (!element) return;
    followsOutput = element.scrollHeight - element.scrollTop - element.clientHeight <= AUTO_FOLLOW_DISTANCE;
    updateActiveIndex();
}

async function refreshTaskList(): Promise<void> {
    tasks.value = await listTasks();
}

async function refreshArtifact(id = taskId.value): Promise<void> {
    if (!id) return;
    const [nextTask, content] = await Promise.all([getTask(id), getContent(id)]);
    if (id !== taskId.value) return;
    taskView.value = nextTask;
    rawContent.value = content;
    artifactHtml.value = content.trim() ? renderMarkdown(content) : "";
}

async function refreshPendingReview(id: string): Promise<void> {
    const review = await getPendingReview(id);
    if (id !== taskId.value) return;
    if (!review) {
        pendingReview.value = null;
        ensureConversation(id).forEach(item => {
            if (item.kind === "review" && !item.approved) {
                item.approved = true;
                item.status = "审核状态已同步，请查看成果区";
            }
        });
        return;
    }
    const interrupt = review.interrupts.find(item => item.toolName === "commit_chapter");
    if (!interrupt) return;
    const nextReview: PendingReview = {
        taskId: id,
        stageId: review.stageId,
        title: review.title,
        markdown: review.markdown,
        runId: review.runId,
        toolCallId: interrupt.toolCallId
    };
    pendingReview.value = nextReview;
    ensureConversation(id).forEach(item => {
        if (item.kind === "review" && item.rejected
            && (item.review.runId !== nextReview.runId || item.review.toolCallId !== nextReview.toolCallId)) {
            item.status = "已按意见废弃，等待新候选审核";
        }
    });
    const exists = ensureConversation(id).some(item => item.kind === "review"
        && item.review.runId === nextReview.runId
        && item.review.toolCallId === nextReview.toolCallId);
    if (!exists) {
        appendConversation(id, {
            id: newId(),
            kind: "review",
            review: nextReview,
            approved: false,
            status: "等待你的确认"
        });
    }
}

function restoreHistory(id: string, history: Awaited<ReturnType<typeof getHistory>>): void {
    const restored: ConversationItem[] = [];
    const streams = new Map<string, StreamMessage>();
    const eventEntries = new Map<string, ProcessEntry[]>();
    for (const event of history.events) {
        const entries = eventEntries.get(event.runId) || [];
        let payload: Record<string, unknown> = {};
        try { payload = JSON.parse(event.payload) as Record<string, unknown>; } catch { /* preserve event without details */ }
        const content = typeof payload.content === "string" ? payload.content
            : typeof payload.result === "string" ? payload.result
                : typeof payload.output === "string" ? payload.output
                    : typeof payload.errorMessage === "string" ? payload.errorMessage
                        : event.eventType === "REQUIRE_CONFIRM" ? "等待用户确认" : "";
        const toolName = typeof payload.toolName === "string" ? payload.toolName : "";
        const label = event.eventType === "TOOL_CALL" ? "调用工具" + (toolName ? " · " + toolName : "")
            : event.eventType === "TOOL_RESULT" ? "工具结果" + (toolName ? " · " + toolName : "") : event.eventType;
        const kind = event.eventType === "TOOL_CALL" ? "tool-call"
            : event.eventType === "TOOL_RESULT" ? "tool-result"
                : event.eventType === "RUN_FAILED" ? "error" : "status";
        entries.push({id: newId(), sequence: event.sequenceNo, timestamp: Date.parse(event.createdAt),
            label, meta: formatTime(event.createdAt), kind, content, open: false, running: false});
        eventEntries.set(event.runId, entries);
    }
    for (const message of [...history.messages].sort((a, b) => a.sequenceNo - b.sequenceNo)) {
        if (message.role === "USER") {
            restored.push({id: message.id, kind: "user", text: message.content, meta: formatTime(message.createdAt)});
        } else if (message.runId && !streams.has(message.runId)) {
            const stream = reactive(createStreamMessage()) as StreamMessage;
            stream.streaming = false;
            stream.process = eventEntries.get(message.runId) || [];
            if (message.content) {
                stream.process.push({id: newId(), sequence: Number.MAX_SAFE_INTEGER, timestamp: Date.parse(message.createdAt),
                    label: "模型输出", meta: formatTime(message.createdAt), kind: "final-output", content: message.content, open: true});
            }
            streams.set(message.runId, stream);
            restored.push(stream);
        }
    }
    conversations[id] = restored;
}

async function activateTask(nextTask: WritingTaskView, addWelcome: boolean): Promise<void> {
    taskId.value = nextTask.task.id;
    taskView.value = nextTask;
    pendingReview.value = null;
    rawContent.value = "";
    artifactHtml.value = "";
    messageInput.value = "";
    targetElements.clear();
    followsOutput = true;
    if (addWelcome && !ensureConversation(nextTask.task.id).length) {
        appendNotice(nextTask.task.id, nextTask.sources.length
            ? "已切换到这项历史任务。你可以发送建议，或发送“继续”来生成下一章。"
            : "这项历史任务没有背景资料，只能查看，不能继续运行。", "Session selected");
    }
    const history = await getHistory(nextTask.task.id);
    restoreHistory(nextTask.task.id, history);
    await Promise.all([refreshArtifact(nextTask.task.id), refreshPendingReview(nextTask.task.id)]);
    scrollToLatest(true);
}

async function selectTask(nextTaskId: string): Promise<void> {
    if (nextTaskId === taskId.value) return;
    try {
        await activateTask(await getTask(nextTaskId), true);
    } catch (error) {
        showToast((error as Error).message, true);
        await refreshTaskList();
    }
}

function resetToNewTask(): void {
    if (running.value) return;
    taskId.value = null;
    taskView.value = null;
    pendingReview.value = null;
    rawContent.value = "";
    artifactHtml.value = "";
    messageInput.value = "";
    taskRequirement.value = "";
    referenceFile.value = null;
    sourceFiles.value = [];
    targetElements.clear();
}

function onReferenceFile(event: Event): void {
    referenceFile.value = (event.target as HTMLInputElement).files?.[0] || null;
}

function onSourceFiles(event: Event): void {
    sourceFiles.value = Array.from((event.target as HTMLInputElement).files || []);
}

async function submitCreateTask(): Promise<void> {
    if (running.value || createBusy.value) return;
    if (!referenceFile.value) return showToast("请上传完整参考文档", true);
    if (!sourceFiles.value.length) return showToast("请至少上传一份目标背景资料", true);
    const oversized = [referenceFile.value, ...sourceFiles.value].find(file => file.size > MAX_FILE_SIZE);
    if (oversized) return showToast(`${oversized.name} 超过 10MB`, true);

    const form = new FormData();
    form.append("userId", userId.value.trim() || "demo-user");
    form.append("referenceDocument", referenceFile.value);
    sourceFiles.value.forEach(file => form.append("sourceFiles", file));
    const requirement = taskRequirement.value.trim() || "已提交完整参考文档和背景资料，请直接开始写作。";
    createBusy.value = true;
    try {
        const created = await createTask(form);
        conversations[created.task.id] = [];
        await activateTask(created, false);
        await refreshTaskList();
        showToast("上下文已建立，正在开始第一章");
        void runRound(requirement);
    } catch (error) {
        showToast((error as Error).message, true);
    } finally {
        createBusy.value = false;
    }
}

function normalizeEvent(event: AguiEvent): AguiEvent {
    const uiEvent: AguiEvent = {...event, createdAt: event.timestamp || undefined};
    if (event.type === "TEXT_MESSAGE_CONTENT") uiEvent.textDelta = event.delta ?? event.content ?? event.text;
    if (event.type === "REASONING_MESSAGE_CONTENT") uiEvent.thinkingDelta = event.delta ?? event.content ?? event.text;
    if (event.type === "TOOL_CALL_START") uiEvent.toolName = event.toolCallName;
    if (event.type === "TOOL_CALL_ARGS") {
        uiEvent.toolName = event.toolCallName;
        uiEvent.delta = event.delta ?? event.arguments ?? event.content;
    }
    if (event.type === "TOOL_CALL_RESULT") uiEvent.toolResult = event.content;
    if (event.type === "CUSTOM" && typeof event.name === "string" && event.name.startsWith("subagent.")) {
        const value = (event.value || {}) as Record<string, unknown>;
        uiEvent.source = value.source;
        uiEvent.toolCallId = value.toolCallId;
        uiEvent.toolName = value.toolName || value.toolCallName;
        if (event.name === "subagent.text") uiEvent.textDelta = value.delta;
        if (event.name === "subagent.thinking") uiEvent.thinkingDelta = value.delta;
        if (event.name === "subagent.tool_call") uiEvent.type = stringValue(value.type);
        if (event.name === "subagent.tool_args") {
            uiEvent.type = "TOOL_CALL_ARGS";
            uiEvent.delta = value.delta;
        }
        if (event.name === "subagent.tool_result") {
            uiEvent.type = `TOOL_CALL_RESULT_${stringValue(value.type).replace("TOOL_RESULT_", "")}`;
            uiEvent.toolResult = value.delta || value.data || value.state;
        }
        if (event.name === "subagent.model_call" || event.name === "subagent.lifecycle"
            || event.name === "subagent.exposed" || event.name === "subagent.event") {
            uiEvent.type = stringValue(value.type);
        }
    }
    return uiEvent;
}

function stringValue(value: unknown): string {
    if (typeof value === "string") return value;
    return value == null ? "" : JSON.stringify(value, null, 2);
}

function eventLabel(type: string): string {
    return {
        "REASONING_MESSAGE_CONTENT": "思考过程",
        "TOOL_CALL_START": "调用工具",
        "TOOL_CALL_ARGS": "工具参数",
        "TOOL_CALL_RESULT": "工具结果",
        "TOOL_CALL_RESULT_START": "开始接收工具结果",
        "TOOL_CALL_RESULT_CONTENT": "工具结果",
        "TOOL_CALL_RESULT_END": "工具结果完成",
        "MODEL_CALL_START": "请求模型",
        "MODEL_CALL_END": "模型响应完成",
        "AGENT_START": "子 Agent 启动",
        "AGENT_END": "子 Agent 结束",
        "SUBAGENT_EXPOSED": "子 Agent 已接入",
        "TEXT_BLOCK_START": "开始生成文本",
        "TEXT_BLOCK_END": "文本生成结束",
        "THINKING_BLOCK_START": "开始思考",
        "THINKING_BLOCK_END": "思考结束",
        "RUN_ERROR": "执行异常",
        "CUSTOM": "自定义事件"
    }[type] || type;
}

function eventPosition(event: AguiEvent, message: StreamMessage): Pick<ProcessEntry, "sequence" | "timestamp" | "meta"> {
    const suppliedSequence = Number(event.sequence);
    const sequence = Number.isFinite(suppliedSequence) && suppliedSequence > 0
        ? suppliedSequence
        : message.nextSequence;
    message.nextSequence = Math.max(message.nextSequence + 1, sequence + 1);
    const suppliedTimestamp = event.timestamp ?? event.createdAt;
    const timestamp = typeof suppliedTimestamp === "number"
        ? suppliedTimestamp
        : Date.parse(stringValue(suppliedTimestamp));
    const effectiveTimestamp = Number.isFinite(timestamp) ? timestamp : Date.now();
    return {sequence, timestamp: effectiveTimestamp, meta: formatTime(new Date(effectiveTimestamp).toISOString())};
}

function addProcessEntry(message: StreamMessage, entry: Omit<ProcessEntry, "id">): ProcessEntry {
    const next = {id: newId(), ...entry};
    message.process.push(next);
    message.process.sort((left, right) => left.sequence - right.sequence || left.timestamp - right.timestamp);
    return next;
}

function closeRunningSteps(message: StreamMessage): void {
    message.process.forEach(entry => {
        if (entry.kind === "thinking" || entry.kind === "tool-call" || entry.kind === "tool-result") {
            entry.open = false;
        }
    });
}

function finishTimeline(message: StreamMessage): void {
    if (!message.process.some(entry => entry.kind === "final-output")) {
        const lastModelText = [...message.process].reverse().find(entry => entry.kind === "model-text");
        if (lastModelText) {
            lastModelText.kind = "final-output";
            lastModelText.label = "最终模型输出";
        }
    }
    message.process.forEach(entry => {
        entry.open = entry.kind === "final-output";
    });
}

function executionEntries(message: StreamMessage): ProcessEntry[] {
    return message.process.filter(entry => entry.kind !== "model-text" && entry.kind !== "final-output");
}

function modelOutput(message: StreamMessage): string {
    return message.process
        .filter(entry => entry.kind === "model-text" || entry.kind === "final-output")
        .map(entry => entry.content || "")
        .join("");
}

function toolCallId(event: AguiEvent): string {
    return stringValue(event.toolCallId || event.callId || event.id || "unknown-tool-call");
}

function toolName(event: AguiEvent, message: StreamMessage, callId: string): string {
    return stringValue(event.toolName || event.toolCallName || message.toolCalls[callId]?.name || callId);
}

function extractJsonStringField(value: string, field: string): string {
    const fieldIndex = value.indexOf(`"${field}"`);
    if (fieldIndex < 0) return "";
    const colonIndex = value.indexOf(":", fieldIndex);
    if (colonIndex < 0) return "";
    const valueStart = value.indexOf('"', colonIndex + 1);
    if (valueStart < 0) return "";
    let escaped = false;
    let encoded = "";
    for (let index = valueStart + 1; index < value.length; index++) {
        const character = value[index];
        if (!escaped && character === '"') break;
        encoded += character;
        escaped = !escaped && character === "\\";
        if (character !== "\\") escaped = false;
    }
    return encoded.replace(/\\u([0-9a-fA-F]{4})|\\([\\"/bfnrt])/g, (_, unicode: string, escapedCharacter: string) => {
        if (unicode) return String.fromCharCode(Number.parseInt(unicode, 16));
        return {b: "\b", f: "\f", n: "\n", r: "\r", t: "\t"}[escapedCharacter] || escapedCharacter;
    });
}

function recordProcessEvent(event: AguiEvent, message: StreamMessage): void {
    if (HIDDEN_EVENT_TYPES.has(event.type)) return;
    const position = eventPosition(event, message);
    if (event.type === "RUN_STARTED") {
        addProcessEntry(message, {...position, kind: "status", label: "Agent 已开始处理本轮请求", running: true});
        return;
    }
    if (event.type === "MODEL_CALL_START") {
        addProcessEntry(message, {...position, kind: "status", label: "模型正在生成", running: true});
        return;
    }
    if (event.type === "MODEL_CALL_END") {
        const runningModel = [...message.process].reverse().find(entry => entry.kind === "status" && entry.label === "模型正在生成" && entry.running);
        if (runningModel) {
            runningModel.label = "模型本次生成完成";
            runningModel.running = false;
        }
        return;
    }
    if (event.type === "RUN_FINISHED") {
        message.process.forEach(entry => entry.running = false);
        return;
    }
    if (event.textDelta) {
        const previous = message.process.at(-1);
        if (previous?.kind === "model-text") previous.content = `${previous.content || ""}${stringValue(event.textDelta)}`;
        else addProcessEntry(message, {
            ...position,
            kind: "model-text",
            label: event.type === "CUSTOM" ? "子 Agent 输出" : "模型输出",
            content: stringValue(event.textDelta)
        });
        return;
    }
    if (event.thinkingDelta) {
        const existing = message.process.at(-1);
        if (existing?.kind === "thinking") existing.content = `${existing.content || ""}${stringValue(event.thinkingDelta)}`;
        else {
            closeRunningSteps(message);
            addProcessEntry(message, {
                ...position,
                kind: "thinking",
                label: event.type === "CUSTOM" ? "子 Agent 思考" : "推理摘要",
                content: stringValue(event.thinkingDelta),
                open: true
            });
        }
        return;
    }
    if (event.type === "TOOL_CALL_RESULT") {
        const callId = toolCallId(event);
        const name = toolName(event, message, callId);
        const detail = stringValue(event.toolResult);
        if (detail) {
            closeRunningSteps(message);
            addProcessEntry(message, {
            ...position,
            kind: /FAIL|ERROR|DENIED/i.test(detail) ? "error" : "tool-result",
            label: `${eventLabel(event.type)} · ${name} · ${callId}`,
            content: detail,
            open: true
            });
        }
        if (message.toolCalls[callId]?.argumentEntry) message.toolCalls[callId].argumentEntry.open = false;
        return;
    }
    if (event.type === "TOOL_CALL_START") {
        const callId = toolCallId(event);
        const name = toolName(event, message, callId);
        closeRunningSteps(message);
        message.toolCalls[callId] = {name};
        const trace = message.toolCalls[callId];
        trace.argumentEntry = addProcessEntry(message, {
            ...position,
            kind: "tool-call",
            label: `${eventLabel(event.type)} · ${name} · ${callId}`,
            content: "",
            open: true
        });
        return;
    }
    if (event.type === "TOOL_CALL_END") {
        const trace = message.toolCalls[toolCallId(event)];
        if (trace?.argumentEntry) trace.argumentEntry.open = false;
        return;
    }
    if (event.type === "TOOL_CALL_ARGS") {
        const callId = toolCallId(event);
        const name = toolName(event, message, callId);
        const trace = message.toolCalls[callId] ||= {name};
        if (!trace.argumentEntry) {
            closeRunningSteps(message);
            trace.argumentEntry = addProcessEntry(message, {
                ...position,
                kind: "tool-call",
                label: `${eventLabel(event.type)} · ${name} · ${callId}`,
                content: "",
                open: true
            });
        }
        trace.argumentEntry.content = `${trace.argumentEntry.content || ""}${stringValue(event.delta)}`;
        if (name === "save_content") {
            const content = extractJsonStringField(trace.argumentEntry.content, "content");
            if (trace.contentEntry) trace.contentEntry.content = content;
            else if (content) {
                trace.argumentEntry.open = false;
                trace.contentEntry = addProcessEntry(message, {
                    ...position,
                    kind: "final-output",
                    label: "最终模型输出",
                    content,
                    open: true
                });
            }
        }
        return;
    }
    if (event.type === "TOOL_CALL_RESULT_START" || event.type === "TOOL_CALL_RESULT_CONTENT"
        || event.type === "TOOL_CALL_RESULT_END") {
        const callId = toolCallId(event);
        const name = toolName(event, message, callId);
        const trace = message.toolCalls[callId] ||= {name};
        if (!trace.resultEntry) {
            closeRunningSteps(message);
            trace.resultEntry = addProcessEntry(message, {
                ...position,
                kind: "tool-result",
                label: `${eventLabel("TOOL_CALL_RESULT_CONTENT")} · ${name} · ${callId}`,
                content: "",
                open: true
            });
        }
        const detail = stringValue(event.toolResult);
        if (detail) trace.resultEntry.content = `${trace.resultEntry.content || ""}${detail}`;
        if (event.type === "TOOL_CALL_RESULT_END") trace.resultEntry.open = false;
        return;
    }
    if (event.type === "RUN_ERROR") {
        closeRunningSteps(message);
        addProcessEntry(message, {
            ...position,
            kind: "error",
            label: eventLabel(event.type),
            content: stringValue(event.message || event.error),
            open: true
        });
        return;
    }
    if (event.type === "CUSTOM" && event.name === "subagent.text") {
        return;
    }
    if (event.type === "CUSTOM") {
        closeRunningSteps(message);
        addProcessEntry(message, {
            ...position,
            kind: "tool-call",
            label: `${eventLabel(event.type)} · ${stringValue(event.name)}`,
            content: stringValue(event.value),
            open: true
        });
        return;
    }
}

function handleStreamEvent(event: AguiEvent, message: StreamMessage, id: string): void {
    const uiEvent = normalizeEvent(event);
    recordProcessEvent(uiEvent, message);
    if (uiEvent.type === "RUN_ERROR") message.failed = true;
    const savedContent = uiEvent.type === "CUSTOM" && uiEvent.name === "chapter.saved"
        ? ((uiEvent.value || {}) as {content?: {filename?: string; title?: string}}).content
        : undefined;
    if (savedContent?.filename && !message.savedFiles.has(savedContent.filename)) {
        message.savedFiles.add(savedContent.filename);
        if (taskId.value === id) {
            pendingReview.value = null;
            for (const item of ensureConversation(id)) {
                if (item.kind === "review" && item.review.runId) {
                    item.approved = true;
                    item.status = "章节已提交，已进入成果区";
                }
            }
            void refreshArtifact(id);
        }
    }
    if (uiEvent.type === "RUN_FINISHED") {
        finishTimeline(message);
        const outcome = uiEvent.outcome as {interrupts?: Array<{metadata?: {toolName?: string}}>} | undefined;
        if (outcome?.interrupts?.some(interrupt => interrupt.metadata?.toolName === "commit_chapter")) {
            void refreshPendingReview(id);
        }
    }
    scrollToLatest();
}

async function renderStreamUpdate(): Promise<void> {
    await nextTick();
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
}

async function runRound(messageOverride?: string): Promise<void> {
    if (!taskId.value || running.value || awaitingReview.value) {
        if (awaitingReview.value) showToast("请先审核当前章节，再开始下一轮", true);
        return;
    }
    const id = taskId.value;
    const usesComposerMessage = messageOverride === undefined;
    const message = messageOverride ?? messageInput.value.trim();
    if (!message) return showToast("请输入消息，例如“开始写”或“继续”", true);

    if (usesComposerMessage) messageInput.value = "";
    appendConversation(id, {id: newId(), kind: "user", text: message, meta: `Round ${contentCount.value + 1}`});
    const stream = reactive(createStreamMessage()) as StreamMessage;
    appendConversation(id, stream);
    scrollToLatest(true);
    running.value = true;
    runningTaskId.value = id;
    stopping.value = false;
    abortController = new AbortController();

    try {
        const response = await fetch(`/api/tasks/${id}/rounds`, {
            method: "POST",
            headers: {"Accept": "text/event-stream", "Content-Type": "application/json"},
            body: JSON.stringify({message}),
            signal: abortController.signal
        });
        await consumeSse(response, async event => {
            handleStreamEvent(event, stream, id);
            await renderStreamUpdate();
        });
        await refreshTaskList();
        if (taskId.value === id) await refreshArtifact(id);
    } catch (error) {
        const stopped = stopping.value || (error as DOMException).name === "AbortError";
        if (!stopped) {
            stream.failed = true;
            addProcessEntry(stream, {
                ...eventPosition({type: "CLIENT_ERROR"}, stream),
                kind: "error",
                label: "CLIENT_ERROR",
                content: (error as Error).message,
                open: true
            });
        }
        showToast(stopped ? "已停止当前生成" : (error as Error).message, !stopped);
    } finally {
        stream.streaming = false;
        running.value = false;
        runningTaskId.value = null;
        stopping.value = false;
        abortController = null;
        if (taskId.value === id) scrollToLatest();
    }
}

function stopRound(): void {
    if (!running.value || !abortController || stopping.value) return;
    stopping.value = true;
    abortController.abort();
}

async function approveReview(item: ReviewMessage): Promise<void> {
    await resumeReview(item, true);
}

async function rewriteReview(item: ReviewMessage): Promise<void> {
    await resumeReview(item, false);
}

async function resumeReview(item: ReviewMessage, approved: boolean): Promise<void> {
    const review = item.review;
    if (running.value || taskId.value !== review.taskId || pendingReview.value?.runId !== review.runId) return;
    const feedback = item.feedback?.trim() || "";
    if (!approved && !feedback) {
        showToast("请先填写具体的修改意见", true);
        return;
    }
    if (!approved) {
        appendConversation(review.taskId, {
            id: newId(),
            kind: "user",
            text: `审核意见：${feedback}`,
            meta: "Chapter revision"
        });
        item.rejected = true;
    }
    const stream = reactive(createStreamMessage()) as StreamMessage;
    appendConversation(review.taskId, stream);
    scrollToLatest(true);
    item.status = approved ? "正在提交…" : "正在根据意见重写…";
    running.value = true;
    runningTaskId.value = review.taskId;
    stopping.value = false;
    abortController = new AbortController();
    try {
        const response = await fetch(`/api/tasks/${review.taskId}/rounds/${review.runId}/resume`, {
            method: "POST",
            headers: {"Accept": "text/event-stream", "Content-Type": "application/json"},
            body: JSON.stringify({decisions: [{toolCallId: review.toolCallId, approved, feedback}]}),
            signal: abortController.signal
        });
        await consumeSse(response, async event => {
            handleStreamEvent(event, stream, review.taskId);
            await renderStreamUpdate();
        });
        if (!approved && stream.failed) item.status = "重写失败，请在下方发送新的修改意见";
        await refreshTaskList();
        if (taskId.value === review.taskId) await refreshArtifact(review.taskId);
        await refreshPendingReview(review.taskId);
    } catch (error) {
        const stopped = stopping.value || (error as DOMException).name === "AbortError";
        item.status = stopped
            ? (approved ? "提交已停止" : "重写已停止，请在下方发送新的修改意见")
            : (approved ? "提交失败，请重试" : "重写请求失败，请在下方发送新的修改意见");
        if (!stopped) {
            stream.failed = true;
            addProcessEntry(stream, {
                ...eventPosition({type: "CLIENT_ERROR"}, stream),
                kind: "error",
                label: "CLIENT_ERROR",
                content: (error as Error).message,
                open: true
            });
        }
        showToast(stopped ? (approved ? "已停止提交" : "已停止重写") : (error as Error).message, !stopped);
    } finally {
        stream.streaming = false;
        running.value = false;
        runningTaskId.value = null;
        stopping.value = false;
        abortController = null;
    }
}

async function copyContent(): Promise<void> {
    if (!rawContent.value) return;
    await navigator.clipboard.writeText(rawContent.value);
    showToast("全部正文已复制");
}

onMounted(async () => {
    try {
        await refreshTaskList();
    } catch (error) {
        showToast((error as Error).message, true);
    }
});
</script>

<template>
    <div class="ambient ambient-one"></div>
    <div class="ambient ambient-two"></div>
    <div class="app-shell" :style="shellStyle">
        <aside :class="['setup-rail', {collapsed: leftCollapsed}]">
            <button class="panel-toggle left-toggle" type="button" :aria-label="leftCollapsed ? '展开任务栏' : '折叠任务栏'" @click="leftCollapsed = !leftCollapsed"><span>{{ leftCollapsed ? '›' : '‹' }}</span><b v-if="!leftCollapsed">任务</b></button>
            <div class="brand">
                <div class="brand-mark" aria-hidden="true">
                    <svg viewBox="0 0 32 32"><path d="M8 7.5 16 3l8 4.5v9L16 21l-8-4.5z"></path><path d="m8 16.5 8 4.5 8-4.5V25l-8 4-8-4z"></path></svg>
                </div>
            </div>
            <section class="task-library">
                <div class="task-library-header">
                    <div><div class="eyebrow">CONVERSATIONS</div><strong>对话任务</strong></div>
                    <button class="new-task-button" type="button" :disabled="running || createBusy" @click="resetToNewTask">
                        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 4v12M4 10h12"></path></svg>新建
                    </button>
                </div>
                <div class="task-list">
                    <div v-if="!tasks.length" class="task-list-empty">还没有历史任务</div>
                    <button v-for="item in tasks" :key="item.task.id" type="button"
                            :class="['task-list-item', {active: item.task.id === taskId, running: item.task.id === runningTaskId, incomplete: !item.sources.length}]"
                            :disabled="createBusy" @click="selectTask(item.task.id)">
                        <span class="task-list-title">{{ item.contents.at(-1)?.title || `写作任务 · ${item.task.id.slice(0, 8)}` }}</span>
                        <span class="task-list-time">{{ formatTaskDate(item.task.createdAt) }}</span>
                        <span class="task-list-meta">{{ item.sources.length }} 份资料 · {{ item.contents.length }} 章成果{{ item.task.id === runningTaskId ? " · 生成中" : "" }}{{ item.sources.length ? "" : " · 不可运行" }}</span>
                    </button>
                </div>
            </section>
            <section v-if="taskView" class="task-card">
                <div class="eyebrow">CURRENT SESSION</div>
                <div class="task-card-title">写作任务 · {{ taskView.task.id.slice(0, 8) }}</div>
                <div class="session-row"><span :class="['status-dot', sessionStatusClass]"></span><span>{{ sessionStatus }}</span></div>
                <code>{{ taskView.task.sessionId }}</code>
            </section>
            <form v-if="!taskView" class="setup-form" @submit.prevent="submitCreateTask">
                <div class="section-heading"><span>建立写作上下文</span></div>
                <label><span>用户标识</span><input v-model="userId" autocomplete="off" required></label>
                <label class="requirement-field"><span class="source-heading"><span>写作需求</span><small>可选 · 首轮使用</small></span><textarea v-model="taskRequirement" rows="3" maxlength="4000" placeholder="例如：重点说明实施流程，面向管理人员，语言正式简洁"></textarea></label>
                <div class="upload-group">
                    <div class="source-heading"><span>完整参考文档</span><small>单文件</small></div>
                    <label :class="['upload-card', {'has-file': referenceFile}]">
                        <span class="upload-icon"><svg viewBox="0 0 24 24"><path d="M7 3.5h7l4 4V20.5H7z"></path><path d="M14 3.5v4h4M10 12h5M10 15h5"></path></svg></span>
                        <span class="upload-copy"><strong>上传写法参考</strong><span>{{ referenceFile ? `${referenceFile.name} · ${formatFileSize(referenceFile.size)}` : "选择 DOCX、PDF、MD 或 TXT" }}</span></span>
                        <span class="upload-action">选择</span><input type="file" accept=".docx,.pdf,.md,.txt" :disabled="createBusy" @change="onReferenceFile">
                    </label>
                </div>
                <div class="upload-group">
                    <div class="source-heading"><span>目标背景资料</span><small>可多选</small></div>
                    <label :class="['upload-card', {'has-file': sourceFiles.length}]">
                        <span class="upload-icon upload-icon-stack"><svg viewBox="0 0 24 24"><path d="M8 5h9v14H8z"></path><path d="M5 8v12h9M11 9h3M11 12h3"></path></svg></span>
                        <span class="upload-copy"><strong>上传待写资料</strong><span>一次选择一个或多个文件</span></span>
                        <span class="upload-action">选择</span><input type="file" accept=".docx,.pdf,.md,.txt" multiple :disabled="createBusy" @change="onSourceFiles">
                    </label>
                    <div class="selected-files"><span v-if="!sourceFiles.length" class="selected-files-empty">尚未选择背景资料</span><span v-for="file in sourceFiles" :key="`${file.name}-${file.size}`" class="selected-file">{{ file.name }} · {{ formatFileSize(file.size) }}</span></div>
                </div>
                <p class="upload-limit">每个文件不超过 10MB。扫描版 PDF 需要先完成文字识别。</p>
                <button class="primary-button" type="submit" :disabled="createBusy"><span>{{ createBusy ? "正在建立 Session…" : "创建写作任务" }}</span><svg viewBox="0 0 20 20"><path d="m7 4 6 6-6 6"></path></svg></button>
            </form>
            <div class="rail-note"><svg viewBox="0 0 20 20"><circle cx="10" cy="10" r="7"></circle><path d="M10 9v5M10 6.5v.5"></path></svg></div>
            <div v-if="!leftCollapsed" class="panel-resizer left-resizer" role="separator" aria-label="调整任务栏宽度" @pointerdown="startResize('left', $event)"></div>
        </aside>

        <main class="conversation-stage">
            <header class="stage-header"><div><div class="eyebrow">LIVE SESSION</div><h1>写作对话</h1></div><div class="memory-badge"><span class="memory-pulse"></span>每章独立上下文</div></header>
            <nav v-if="userMessages.length > 1" class="conversation-index" aria-label="用户消息快速索引">
                <button v-for="(item, index) in userMessages" :key="item.id" type="button" :class="['conversation-index-point', 'user', {active: index === activeIndex}]" :title="item.text" @mouseenter="showIndexPreview(index, $event)" @mouseleave="hoveredIndex = null" @click="scrollToConversationItem(item.id)"></button>
                <div v-if="hoveredMessage" class="conversation-index-preview" :style="indexPreviewStyle" aria-live="polite">
                    <strong>{{ hoveredMessage.text }}</strong>
                    <p>{{ hoveredReply || 'Agent 尚未回复' }}</p>
                </div>
            </nav>
            <div ref="conversationScroll" class="conversation-scroll" aria-live="polite" @scroll.passive="handleConversationScroll">
                <div class="date-divider"><span>当前会话</span></div>
                <article class="message assistant-message welcome-message"><div class="avatar agent-avatar">B</div><div class="message-body"><div class="message-meta"><strong>Blueforge</strong><span>Agent</span></div><div class="message-copy"><p>把一篇完整参考文档和待写资料交给我，上传完成后我会立即开始：先形成临时章节计划，但只写第一章。</p><p>每章会先生成候选内容，审核通过后才会进入正式成果区。</p></div></div></article>
                <template v-for="item in currentConversation" :key="item.id">
                    <article v-if="item.kind === 'user'" :ref="element => captureTarget(item.id, element)" class="message user-message"><div class="avatar user-avatar">U</div><div class="message-body"><div class="message-meta"><strong>你</strong><span>{{ item.meta }}</span></div><div class="message-copy">{{ item.text }}</div></div></article>
                    <article v-else-if="item.kind === 'notice'" :ref="element => captureTarget(item.id, element)" class="message assistant-message"><div class="avatar agent-avatar">B</div><div class="message-body"><div class="message-meta"><strong>Blueforge</strong><span>{{ item.meta }}</span></div><div class="message-copy"><p>{{ item.text }}</p></div></div></article>
                    <article v-else-if="item.kind === 'stream'" :ref="element => captureTarget(item.id, element)" class="message assistant-message">
                        <div class="avatar agent-avatar">B</div>
                        <div class="message-body">
                            <div class="message-meta"><strong>Blueforge</strong><span>{{ item.streaming ? '正在生成' : '模型输出' }}</span></div>
                            <details v-if="executionEntries(item).length" class="execution-trace" :open="item.streaming">
                                <summary>执行过程 · {{ executionEntries(item).length }} 项<span>{{ item.streaming ? '正在接收' : '已完成，点击展开' }}</span></summary>
                                <div class="process-timeline">
                                <template v-for="entry in executionEntries(item)" :key="entry.id">
                                    <div v-if="entry.kind === 'status'" :class="['process-entry', 'timeline-status', {running: entry.running}]">
                                        <span>{{ entry.label }}</span><span>#{{ entry.sequence }} · {{ entry.meta }}</span>
                                    </div>
                                    <details v-else :class="['timeline-detail', 'process-entry', entry.kind]" :open="entry.open">
                                        <summary><span>{{ entry.label }}</span><span class="timeline-detail-time">#{{ entry.sequence }} · {{ entry.meta }}</span></summary>
                                        <div class="timeline-detail-content">{{ entry.content }}</div>
                                    </details>
                                </template>
                                </div>
                            </details>
                            <div v-if="modelOutput(item)" class="message-copy assistant-output" v-html="renderMarkdown(modelOutput(item))"></div>
                        </div>
                    </article>
                    <article v-else :ref="element => captureTarget(item.id, element)" :class="['chapter-review-card', {approved: item.approved}]"><div class="review-card-marker">01</div><div class="review-card-body"><div class="review-card-eyebrow">CHAPTER GATE · HUMAN REVIEW</div><strong>{{ item.review.title || '本章候选内容' }}</strong><p>请审阅本章候选正文。通过后，正文及关联的章节记忆、文档状态和临时计划才会一起进入正式成果区。</p><details class="review-card-preview" open><summary><span>候选正文预览</span><span>{{ item.approved ? '已提交' : '尚未提交' }}</span></summary><div class="review-card-preview-content" v-html="renderMarkdown(item.review.markdown)"></div></details><label v-if="!item.approved && !item.rejected" class="review-feedback"><span>修改意见（不会提交当前候选）</span><textarea v-model="item.feedback" rows="3" maxlength="4000" :disabled="running" placeholder="例如：补充实施依据，删去没有资料支撑的表述，并调整第二节的论证顺序。"></textarea></label><div class="review-card-footer"><span class="review-card-status">{{ item.status }}</span><div v-if="!item.approved && !item.rejected" class="review-actions"><button type="button" class="review-rewrite-button" :disabled="running || !item.feedback?.trim()" @click="rewriteReview(item)">{{ item.status === '正在根据意见重写…' ? '正在重写…' : '按意见重写' }}</button><button type="button" :disabled="running" @click="approveReview(item)">{{ item.status === '正在提交…' ? '正在提交…' : '通过并提交' }}</button></div></div></div></article>
                </template>
            </div>
            <div class="composer-wrap"><div :class="['composer', {running: running && taskId === runningTaskId}]"><textarea v-model="messageInput" rows="1" maxlength="4000" :disabled="!canSend" :placeholder="composerPlaceholder" aria-label="发送给写作 Agent 的消息" @keydown.enter.exact.prevent="runRound()"></textarea><button type="button" :class="{'stop-mode': running && taskId === runningTaskId}" :disabled="!canSend" :aria-label="running && taskId === runningTaskId ? '停止当前生成' : '发送消息'" @click="running && taskId === runningTaskId ? stopRound() : runRound()"><svg class="send-icon" viewBox="0 0 24 24"><path d="M5 12h13M13 6l6 6-6 6"></path></svg><span class="stop-icon"></span></button></div><p :class="['composer-footnote', {reviewing: awaitingReview}]">{{ composerHint }}</p></div>
        </main>

        <aside :class="['artifact-pane', {collapsed: rightCollapsed}]"><button class="panel-toggle right-toggle" type="button" :aria-label="rightCollapsed ? '展开成果栏' : '折叠成果栏'" @click="rightCollapsed = !rightCollapsed"><span>{{ rightCollapsed ? '‹' : '›' }}</span><b v-if="!rightCollapsed">成果</b></button><template v-if="!rightCollapsed"><header class="artifact-header"><div><div class="eyebrow">ACCEPTED OUTPUT</div><h2>生成正文</h2></div><div class="artifact-actions"><span class="content-count">{{ contentCount }} 章</span><button class="icon-button" type="button" title="复制全部正文" :disabled="!rawContent" @click="copyContent"><svg viewBox="0 0 20 20"><rect x="6" y="6" width="9" height="10" rx="2"></rect><path d="M4 13H3.5A1.5 1.5 0 0 1 2 11.5v-8A1.5 1.5 0 0 1 3.5 2h8A1.5 1.5 0 0 1 13 3.5V4"></path></svg></button></div></header><div class="artifact-scroll"><div v-if="!artifactHtml" class="artifact-empty"><div class="empty-orbit"><span></span><span></span><span></span></div><h3>成果将在这里生长</h3><p>Agent 每完成并保存一章合格内容，它就会追加到这份文档中。</p></div><article v-else class="document"><section class="generated-block" v-html="artifactHtml"></section></article></div><footer class="artifact-footer"><span>{{ rawContent ? "正文已同步" : "等待第一章" }}</span><span class="live-indicator"><i></i> 自动同步</span></footer></template><div class="panel-resizer right-resizer" role="separator" aria-label="调整成果栏宽度" @pointerdown="startResize('right', $event)"></div></aside>
    </div>
    <div :class="['toast', {show: toast, error: toastError}]" role="status">{{ toast }}</div>
</template>
