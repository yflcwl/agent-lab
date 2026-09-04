import type {AguiEvent, PendingReviewResponse, WritingTaskView} from "./types";

async function readError(response: Response): Promise<string> {
    try {
        const value = await response.json() as {error?: string};
        return value.error || `请求失败 (${response.status})`;
    } catch {
        return `请求失败 (${response.status})`;
    }
}

export async function readJson<T>(response: Response): Promise<T> {
    if (!response.ok) throw new Error(await readError(response));
    return response.json() as Promise<T>;
}

export async function listTasks(): Promise<WritingTaskView[]> {
    return readJson(await fetch("/api/tasks"));
}

export async function getTask(taskId: string): Promise<WritingTaskView> {
    return readJson(await fetch(`/api/tasks/${taskId}`));
}

export async function getContent(taskId: string): Promise<string> {
    const response = await fetch(`/api/tasks/${taskId}/content`);
    if (!response.ok) throw new Error(await readError(response));
    return response.text();
}

export async function getPendingReview(taskId: string): Promise<PendingReviewResponse | null> {
    const response = await fetch(`/api/tasks/${taskId}/pending-review`);
    if (response.status === 204) return null;
    return readJson(response);
}

export async function createTask(form: FormData): Promise<WritingTaskView> {
    return readJson(await fetch("/api/tasks", {method: "POST", body: form}));
}

export async function consumeSse(
    response: Response,
    onEvent: (event: AguiEvent) => void | Promise<void>
): Promise<void> {
    if (!response.ok) throw new Error(await readError(response));
    if (!response.body) throw new Error("浏览器没有收到流式响应");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done}).replace(/\r\n/g, "\n");
        let boundary = buffer.indexOf("\n\n");
        while (boundary >= 0) {
            const frame = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const lines = frame.split("\n");
            const eventType = lines.find(line => line.startsWith("event:"))?.slice(6).trim();
            const data = lines.filter(line => line.startsWith("data:"))
                .map(line => line.slice(5).trimStart())
                .join("\n");
            if (data) {
                const event = JSON.parse(data) as AguiEvent;
                if (!event.type && eventType) event.type = eventType;
                await onEvent(event);
            }
            boundary = buffer.indexOf("\n\n");
        }
        if (done) break;
    }
}
