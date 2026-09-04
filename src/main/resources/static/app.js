const elements = {
    createForm: document.querySelector('#createForm'),
    createPanel: document.querySelector('#createPanel'),
    workspace: document.querySelector('#workspace'),
    goalInput: document.querySelector('#goalInput'),
    templateInput: document.querySelector('#templateInput'),
    sourceFiles: document.querySelector('#sourceFiles'),
    airportTemplate: document.querySelector('#airportTemplateButton'),
    newTask: document.querySelector('#newTaskButton'),
    connection: document.querySelector('#connectionState'),
    taskId: document.querySelector('#taskIdLabel'),
    overallStatus: document.querySelector('#overallStatus'),
    planVersion: document.querySelector('#planVersion'),
    lastDecision: document.querySelector('#lastDecision'),
    chapterList: document.querySelector('#chapterList'),
    taskRailToggle: document.querySelector('#taskRailToggle'),
    activeKey: document.querySelector('#activeChapterKey'),
    activeTitle: document.querySelector('#activeChapterTitle'),
    chapterContent: document.querySelector('#chapterContent'),
    fullContent: document.querySelector('#fullContent'),
    sourceList: document.querySelector('#sourceList'),
    traceList: document.querySelector('#traceList'),
    messageList: document.querySelector('#messageList'),
    messageForm: document.querySelector('#messageForm'),
    messageType: document.querySelector('#messageType'),
    messageInput: document.querySelector('#messageInput'),
    retryTask: document.querySelector('#retryTaskButton'),
    reviewPanel: document.querySelector('#reviewPanel'),
    feedback: document.querySelector('#feedbackInput'),
    approve: document.querySelector('#approveButton'),
    revise: document.querySelector('#reviseButton'),
    reviewCountdown: document.querySelector('#reviewCountdown'),
    copy: document.querySelector('#copyButton'),
    toast: document.querySelector('#toast')
};

const REVIEW_TIMEOUT_SECONDS = 20;

let taskId = new URLSearchParams(location.search).get('taskId');
let state = null;
let selectedChapterId = null;
let eventSource = null;
let reviewTimer = null;
let taskRailCollapsed = localStorage.getItem('solution-task-rail-collapsed') === 'true';

elements.taskRailToggle.addEventListener('click', () => {
    taskRailCollapsed = !taskRailCollapsed;
    localStorage.setItem('solution-task-rail-collapsed', String(taskRailCollapsed));
    applyTaskRailState();
});

elements.newTask.addEventListener('click', () => {
    closeEvents();
    clearReviewTimer();
    taskId = null;
    state = null;
    selectedChapterId = null;
    elements.createForm.reset();
    elements.workspace.classList.add('hidden');
    elements.createPanel.classList.remove('hidden');
    elements.newTask.classList.add('hidden');
    history.replaceState(null, '', location.pathname);
    setConnection('idle', '尚未连接');
    elements.goalInput.focus();
});

elements.createForm.addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.submitter;
    button.disabled = true;
    try {
        const response = await api('/writing-tasks', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({goal: elements.goalInput.value, templateMarkdown: elements.templateInput.value})
        });
        taskId = response.taskId;
        for (const file of elements.sourceFiles.files) {
            const formData = new FormData();
            formData.append('file', file);
            await api(`/writing-tasks/${taskId}/sources`, {method: 'POST', body: formData});
        }
        await api(`/writing-tasks/${taskId}/start`, {method: 'POST'});
        history.replaceState(null, '', `?taskId=${taskId}`);
        showWorkspace();
        await refresh();
        connectEvents();
    } catch (error) {
        showToast(error.message);
    } finally {
        button.disabled = false;
    }
});

elements.approve.addEventListener('click', () => submitReview('APPROVE'));
elements.revise.addEventListener('click', () => submitReview('REVISE'));
elements.messageForm.addEventListener('submit', async event => {
    event.preventDefault();
    const content = elements.messageInput.value.trim();
    const messageType = elements.messageType.value;
    if (!content) return;
    if (messageType === 'CHAPTER_INSTRUCTION' && !selectedChapterId) {
        showToast('请先选择需要修改的章节');
        return;
    }
    const button = event.submitter;
    button.disabled = true;
    try {
        await api(`/writing-tasks/${taskId}/messages`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                chapterTaskId: messageType === 'CHAPTER_INSTRUCTION' ? selectedChapterId : null,
                messageType,
                content
            })
        });
        elements.messageInput.value = '';
        await refresh();
        if (!eventSource) connectEvents();
    } catch (error) {
        showToast(error.message);
    } finally {
        button.disabled = false;
    }
});
elements.retryTask.addEventListener('click', async () => {
    elements.retryTask.disabled = true;
    try {
        await api(`/writing-tasks/${taskId}/retry`, {method: 'POST'});
        await refresh();
    } catch (error) {
        showToast(error.message);
    } finally {
        elements.retryTask.disabled = false;
    }
});
elements.airportTemplate.addEventListener('click', async () => {
    elements.airportTemplate.disabled = true;
    try {
        elements.templateInput.value = await apiText('/templates/airport-maintenance-construction-plan.md');
        if (!elements.goalInput.value.trim()) {
            elements.goalInput.value = '依据项目背景资料，编制一份可审核的机场维修工程施工方案。';
        }
    } catch (error) {
        showToast('施工方案模板加载失败');
    } finally {
        elements.airportTemplate.disabled = false;
    }
});
elements.copy.addEventListener('click', async () => {
    await navigator.clipboard.writeText(elements.fullContent.textContent);
    showToast('Markdown 已复制');
});
elements.feedback.addEventListener('focus', () => {
    if (reviewTimer) updateReviewCountdown(true);
});
elements.feedback.addEventListener('blur', () => {
    if (reviewTimer) {
        reviewTimer.remaining = REVIEW_TIMEOUT_SECONDS;
        updateReviewCountdown(false);
    }
});

async function submitReview(decision) {
    if (!selectedChapterId) return;
    if (decision === 'REVISE' && !elements.feedback.value.trim()) {
        showToast('请先填写修改意见');
        return;
    }
    setReviewDisabled(true);
    try {
        await api(`/writing-tasks/${taskId}/chapter-tasks/${selectedChapterId}/review`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({decision, feedback: elements.feedback.value.trim()})
        });
        elements.feedback.value = '';
        await refresh();
    } catch (error) {
        showToast(error.message);
    } finally {
        setReviewDisabled(false);
    }
}

function startReviewTimer(chapterId) {
    clearReviewTimer();
    reviewTimer = {
        chapterId,
        remaining: REVIEW_TIMEOUT_SECONDS,
        intervalId: window.setInterval(tickReviewTimer, 1000)
    };
    updateReviewCountdown(false);
}

function tickReviewTimer() {
    if (!reviewTimer) return;
    if (document.activeElement === elements.feedback) {
        updateReviewCountdown(true);
        return;
    }
    reviewTimer.remaining -= 1;
    if (reviewTimer.remaining <= 0) {
        autoApprove();
    } else {
        updateReviewCountdown(false);
    }
}

function autoApprove() {
    const chapterId = reviewTimer.chapterId;
    clearReviewTimer();
    const chapter = (state.taskList || []).find(item => item.id === chapterId);
    if (!chapter || chapter.status !== 'WAITING_REVIEW') return;
    selectedChapterId = chapterId;
    submitReview('APPROVE');
}

function clearReviewTimer() {
    if (reviewTimer) {
        window.clearInterval(reviewTimer.intervalId);
        reviewTimer = null;
        if (elements.reviewCountdown) elements.reviewCountdown.textContent = '';
    }
}

function updateReviewCountdown(paused) {
    if (!elements.reviewCountdown || !reviewTimer) return;
    elements.reviewCountdown.textContent = paused
        ? '已暂停 · 等待输入完成'
        : `${reviewTimer.remaining} 秒后自动通过`;
}

async function refresh() {
    if (!taskId) return;
    try {
        const [taskState, content] = await Promise.all([
            api(`/writing-tasks/${taskId}`),
            apiText(`/writing-tasks/${taskId}/content`)
        ]);
        state = taskState;
        renderState(content);
    } catch (error) {
        showToast(error.message);
    }
}

function renderState(content) {
    elements.taskId.textContent = state.task.id;
    elements.overallStatus.textContent = state.task.status;
    elements.planVersion.textContent = state.task.planVersion;
    elements.lastDecision.textContent = state.task.lastError
        ? `执行异常：${state.task.lastError}`
        : (state.task.lastDecision || 'PlanningAgent 正在分析当前任务状态…');
    elements.fullContent.textContent = content || '尚未生成内容。';
    elements.retryTask.classList.toggle('hidden', !state.task.lastError);
    renderSources();
    renderTraces();
    renderMessages();
    renderChapters();
    const hasPendingMessage = (state.messages || []).some(message =>
        message.role === 'USER' && (message.status === 'QUEUED' || message.status === 'PROCESSING'));
    if (state.task.status === 'COMPLETED' && !hasPendingMessage) {
        closeEvents();
        setConnection('idle', '任务已完成');
    }
}

function renderSources() {
    elements.sourceList.replaceChildren();
    const sources = state.sourceDocuments || [];
    if (!sources.length) {
        elements.sourceList.textContent = '尚未上传背景资料。';
        return;
    }
    sources.forEach(source => {
        const item = document.createElement('p');
        item.textContent = `${source.originalFilename} · ${source.fileType} · ${source.extractionStatus}`;
        elements.sourceList.append(item);
    });
}

function renderTraces() {
    elements.traceList.replaceChildren();
    const runs = state.agentRuns || [];
    const events = state.agentRunEvents || [];
    if (!runs.length) {
        renderLegacyTraces();
        return;
    }
    runs.forEach(run => {
        const item = document.createElement('details');
        item.className = `run-entry ${run.status === 'RUNNING' ? 'running' : ''}`;
        item.open = run.status === 'RUNNING';
        const summary = document.createElement('summary');
        const title = document.createElement('span');
        title.textContent = `${run.agentName} · ${run.runType}`;
        const status = document.createElement('span');
        status.className = `run-status ${run.status.toLowerCase()}`;
        status.textContent = run.status;
        summary.append(title, status);
        const timeline = document.createElement('div');
        timeline.className = 'observation-timeline';
        events.filter(event => event.agentRunId === run.id).reverse().forEach(event => {
            const row = document.createElement('div');
            row.className = `observation-entry ${event.eventType.toLowerCase()}`;
            const marker = document.createElement('span');
            marker.className = 'observation-marker';
            const content = document.createElement('div');
            const detail = document.createElement('strong');
            detail.textContent = event.detail;
            const meta = document.createElement('small');
            const time = event.createdAt ? new Date(event.createdAt).toLocaleTimeString('zh-CN', {
                hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
            }) : '';
            meta.textContent = [event.toolName, event.eventType, time].filter(Boolean).join(' · ');
            content.append(detail, meta);
            row.append(marker, content);
            timeline.append(row);
        });
        if (!timeline.childElementCount) {
            timeline.textContent = '等待 Agent 返回观测事件。';
        }
        item.append(summary, timeline);
        elements.traceList.append(item);
    });
}

function renderLegacyTraces() {
    const traces = state.traces || [];
    if (!traces.length) {
        elements.traceList.textContent = '等待 Agent 执行。';
        return;
    }
    traces.forEach(trace => {
        const item = document.createElement('details');
        item.className = 'trace-entry';
        item.open = trace.eventType === 'REASONING_SUMMARY' || trace.eventType === 'EXECUTION_FAILED';
        const title = document.createElement('summary');
        title.textContent = `${trace.actor} · ${trace.eventType}`;
        const detail = document.createElement('p');
        detail.textContent = trace.detail;
        item.append(title, detail);
        elements.traceList.append(item);
    });
}

function renderMessages() {
    elements.messageList.replaceChildren();
    const messages = state.messages || [];
    if (!messages.length) {
        elements.messageList.textContent = '可在 Agent 执行时继续提问或发送修改指令。';
        return;
    }
    messages.forEach(message => {
        const item = document.createElement('article');
        item.className = `chat-message ${message.role.toLowerCase()}`;
        const meta = document.createElement('div');
        const role = document.createElement('strong');
        role.textContent = message.role === 'USER' ? '你' : message.role === 'AGENT' ? 'Agent' : '系统';
        const type = document.createElement('span');
        type.textContent = message.status === 'QUEUED' ? '等待处理' : message.status === 'PROCESSING' ? '处理中' : message.messageType;
        meta.append(role, type);
        const content = document.createElement('p');
        content.textContent = message.content;
        item.append(meta, content);
        elements.messageList.append(item);
    });
    elements.messageList.scrollTop = elements.messageList.scrollHeight;
}

function renderChapters() {
    elements.chapterList.replaceChildren();
    const chapters = state.taskList || [];
    const important = chapters.find(chapter => chapter.status === 'WAITING_REVIEW')
        || chapters.find(chapter => chapter.status === 'EXECUTING' || chapter.status === 'REVISING');
    const selected = chapters.find(chapter => chapter.id === selectedChapterId);
    let active = !selected || (important && selected.status === 'COMPLETED') ? important : selected;
    active ||= chapters[0];
    if (active) selectedChapterId = active.id;

    chapters.forEach(chapter => {
        const card = document.createElement('div');
        card.className = `chapter-card${chapter.id === selectedChapterId ? ' active' : ''}`;
        const title = document.createElement('strong');
        title.textContent = chapter.title;
        const status = document.createElement('small');
        status.textContent = `${chapter.status} · PRIORITY ${chapter.priority}`;
        card.append(title, status);
        card.addEventListener('click', () => {
            selectedChapterId = chapter.id;
            renderChapters();
        });
        elements.chapterList.append(card);
    });

    if (!active) {
        elements.activeKey.textContent = 'CURRENT CHAPTER';
        elements.activeTitle.textContent = 'PlanningAgent 正在建立任务列表';
        elements.chapterContent.textContent = '任务规划完成后，章节会出现在左侧。';
        elements.reviewPanel.classList.add('hidden');
        clearReviewTimer();
        return;
    }
    elements.activeKey.textContent = `CHAPTER / ${active.status}`;
    elements.activeTitle.textContent = active.title;
    elements.chapterContent.textContent = active.content || statusMessage(active.status);
    const waiting = active.status === 'WAITING_REVIEW';
    elements.reviewPanel.classList.toggle('hidden', !waiting);
    if (waiting) {
        if (!reviewTimer || reviewTimer.chapterId !== active.id) {
            startReviewTimer(active.id);
        }
    } else {
        clearReviewTimer();
    }
}

function statusMessage(status) {
    return {
        NOT_STARTED: '等待 PlanningAgent 选择该任务。',
        EXECUTING: 'ExecutorAgent 正在生成本章，请稍候…',
        REVISING: '修改意见已提交，等待 ExecutorAgent 处理。',
        COMPLETED: '本章已审核通过。'
    }[status] || '等待任务更新。';
}

function connectEvents() {
    closeEvents();
    eventSource = new EventSource(`/writing-tasks/${taskId}/events`);
    eventSource.onopen = () => {
        setConnection('live', '实时同步中');
        refresh();
    };
    ['TASK_CHANGED', 'TASK_MESSAGE', 'AGENT_TRACE', 'AGENT_OBSERVATION', 'CHAPTER_EXECUTING', 'CHAPTER_WAITING_REVIEW', 'EXECUTION_FAILED', 'TASK_COMPLETED']
        .forEach(type => eventSource.addEventListener(type, refresh));
    eventSource.onerror = () => setConnection('error', '连接中断，正在重连');
}

function closeEvents() {
    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }
}

function showWorkspace() {
    elements.createPanel.classList.add('hidden');
    elements.workspace.classList.remove('hidden');
    elements.newTask.classList.remove('hidden');
    applyTaskRailState();
}

function applyTaskRailState() {
    elements.workspace.classList.toggle('task-rail-collapsed', taskRailCollapsed);
    elements.taskRailToggle.textContent = taskRailCollapsed ? '›' : '‹';
    elements.taskRailToggle.setAttribute('aria-expanded', String(!taskRailCollapsed));
    elements.taskRailToggle.setAttribute('aria-label', taskRailCollapsed ? '展开章节任务' : '收起章节任务');
    elements.taskRailToggle.title = taskRailCollapsed ? '展开章节任务' : '收起章节任务';
}

function setConnection(status, text) {
    elements.connection.dataset.state = status;
    elements.connection.lastElementChild.textContent = text;
}

function setReviewDisabled(disabled) {
    elements.approve.disabled = disabled;
    elements.revise.disabled = disabled;
}

function showToast(message) {
    elements.toast.textContent = message;
    elements.toast.classList.remove('hidden');
    window.setTimeout(() => elements.toast.classList.add('hidden'), 3600);
}

async function api(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) {
        const error = await response.json().catch(() => ({message: response.statusText}));
        throw new Error(error.message || '请求失败');
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

async function apiText(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error('方案正文加载失败');
    return response.text();
}

if (taskId) {
    showWorkspace();
    refresh();
    connectEvents();
}
