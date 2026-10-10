// @author kongweiguang
import type { SseFrame } from "./types-internal";
import type { AttachmentMetadata, RecoveryView } from "./types";

const jsonHeaders = { "Content-Type": "application/json", Accept: "application/json" };

/** 保留 HTTP 状态码，使调用方能够区分命令被拒绝与传输结果未知。 */
export class ChatHttpError extends Error {
  /** 保留稳定错误类别供界面映射，同时不暴露服务端诊断正文。 */
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = "ChatHttpError";
  }
}

/** 集中维护示例的 POST JSON 契约，将 Spring 问题详情转换为可用的界面错误说明。 */
export async function postChat<T>(action: string, body: unknown, signal?: AbortSignal): Promise<T> {
  return post<T>(`/api/chat/${action}`, body, signal);
}

/** 体验接口与对话控制接口共用宿主侧的错误结构。 */
export async function postExperience<T>(
  action: string,
  body: unknown = {},
  signal?: AbortSignal,
): Promise<T> {
  return post<T>(`/api/chat/experience/${action}`, body, signal);
}

/** 上传使用 multipart 原始 File，并将稳定操作键放在表单中供服务端幂等恢复。 */
export async function uploadAttachment(
  threadId: string,
  operationKey: string,
  file: File,
  signal?: AbortSignal,
): Promise<AttachmentMetadata> {
  const form = new FormData();
  form.set("threadId", threadId);
  form.set("operationKey", operationKey);
  form.set("file", file, file.name);
  const response = await fetch("/api/chat/attachments/upload", {
    method: "POST",
    body: form,
    signal,
  });
  if (!response.ok) throw await responseError(response);
  return response.json() as Promise<AttachmentMetadata>;
}

/** 获取原始预览字节；是否下载由服务端安全控制 Content-Disposition。 */
export async function readAttachmentContent(
  threadId: string,
  attachmentId: string,
  download = false,
  signal?: AbortSignal,
): Promise<Blob> {
  const query = new URLSearchParams({ threadId, ...(download ? { download: "true" } : {}) });
  const response = await fetch(
    `/api/chat/attachments/${encodeURIComponent(attachmentId)}/content?${query}`,
    { signal },
  );
  if (!response.ok) throw await responseError(response);
  return response.blob();
}

/** 批量丢弃未引用草稿，保留服务端幂等语义并统一作用域到原 Thread。 */
export async function discardAttachments(threadId: string, attachmentIds: string[]): Promise<void> {
  await post<{ discardedIds: string[] }>("/api/chat/attachments/discard", {
    threadId,
    attachmentIds,
  });
}

/** 仅返回仍待处理的恢复项；调用方必须保留 Core revision 并显式提交裁决。 */
export async function readRecoveries(threadId: string, turnId: string): Promise<RecoveryView[]> {
  const result = await postChat<{ recoveries?: RecoveryView[] }>("recoveries", {
    threadId,
    turnId,
  });
  return Array.isArray(result.recoveries) ? result.recoveries : [];
}

/** 用 Core revision CAS 与稳定操作键提交单次用户裁决。 */
export async function resolveRecovery(command: {
  threadId: string;
  turnId: string;
  recoveryId: string;
  callId: string;
  expectedRecoveryRevision: number;
  operationKey: string;
  decision: "RETRY" | "SKIP";
}): Promise<void> {
  await postChat("recoveries/resolve", command);
}

/** 通过当前 Thread 鉴权批量读取笔记来源的附件元数据，不请求文件正文。 */
export async function readAttachmentMetadata(
  threadId: string,
  attachmentIds: string[],
  signal?: AbortSignal,
): Promise<AttachmentMetadata[]> {
  if (!attachmentIds.length) return [];
  const result = await post<{ items?: AttachmentMetadata[] }>(
    "/api/chat/attachments/metadata",
    { threadId, attachmentIds },
    signal,
  );
  return Array.isArray(result.items) ? result.items : [];
}

/** 增量读取 POST 请求返回的 SSE，避免大型模型响应需要缓存完整数据流。 */
export async function observeChat(
  action: "stream" | "reconnect",
  body: unknown,
  signal: AbortSignal,
  onFrame: (frame: SseFrame) => Promise<void>,
): Promise<void> {
  const response = await fetch(`/api/chat/${action}`, {
    method: "POST",
    headers: { ...jsonHeaders, Accept: "text/event-stream" },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) throw await responseError(response);
  if (!response.body) throw new Error("服务端没有返回事件流。");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let frame: SseFrame = { event: "message", data: [] };
  const dispatch = async () => {
    if (frame.data.length || frame.event !== "message") await onFrame(frame);
    frame = { event: "message", data: [] };
  };
  const line = async (value: string) => {
    if (value === "") {
      await dispatch();
      return;
    }
    if (value.startsWith(":")) return;
    const separator = value.indexOf(":");
    const field = separator < 0 ? value : value.slice(0, separator);
    const raw = separator < 0 ? "" : value.slice(separator + 1).replace(/^ /, "");
    if (field === "event") frame.event = raw;
    else if (field === "data") frame.data.push(raw);
    else if (field === "id") frame.id = raw;
  };

  try {
    while (true) {
      const chunk = await reader.read();
      buffer += decoder.decode(chunk.value, { stream: !chunk.done });
      if (buffer.length > 8_000_000) throw new Error("事件帧超过允许大小，已停止读取。");
      let newline = buffer.indexOf("\n");
      while (newline >= 0) {
        await line(buffer.slice(0, newline).replace(/\r$/, ""));
        buffer = buffer.slice(newline + 1);
        newline = buffer.indexOf("\n");
      }
      if (chunk.done) break;
    }
    if (buffer) await line(buffer.replace(/\r$/, ""));
    await dispatch();
  } finally {
    reader.releaseLock();
  }
}

/** 读取 ProblemDetail 的稳定错误码供界面映射；非 JSON 正文仍收敛为安全说明。 */
async function responseError(response: Response): Promise<Error> {
  const body = (await response.json().catch(() => null)) as {
    detail?: unknown;
    title?: unknown;
    message?: unknown;
    code?: unknown;
  } | null;
  const message = [body?.detail, body?.title, body?.message].find(
    (value) => typeof value === "string",
  );
  return new ChatHttpError(
    typeof message === "string" ? message : `请求失败（${response.status}）。`,
    response.status,
    typeof body?.code === "string" ? body.code : undefined,
  );
}

/** 控制接口将 204 视为空结果，同时保留切换视图时的请求中止行为。 */
async function post<T>(url: string, body: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) throw await responseError(response);
  if (response.status === 204) return null as T;
  return (await response.json()) as T;
}
