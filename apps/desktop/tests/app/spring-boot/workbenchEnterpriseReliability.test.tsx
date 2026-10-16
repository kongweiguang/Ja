// @author kongweiguang
import { act, cleanup, render, renderHook, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ConversationPane } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/ConversationPane";
import { useWorkbench } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/useWorkbench";
import {
  emptySession,
  emptyTurn,
  readPreferences,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/state";
import type {
  AttachmentMetadata,
  RecoveryView,
  ThreadView,
  WaitView,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/types";
import {
  deletePendingUpload,
  readPendingUpload,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/attachmentStore";

vi.mock(
  "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/attachmentStore",
  () => ({
    deletePendingUpload: vi.fn(async () => undefined),
    readPendingUpload: vi.fn(async () => undefined),
    savePendingUpload: vi.fn(async () => undefined),
  }),
);

// jsdom 不实现滚动 API；历史加载后的真实 Workbench 只需观察其触发边界。
Object.defineProperty(HTMLElement.prototype, "scrollTo", { configurable: true, value: vi.fn() });

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

/** 控制服务端响应时序，覆盖浏览器断连与上传完成同时发生的边界。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** 使用真实 HTTP 错误响应验证 ProblemDetail code 到界面语义的映射。 */
function jsonResponse(value: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

/** 提供最小 Spring Workbench 契约，其余请求交给每个用例单独控制。 */
function springFetch(
  route: (path: string, init?: RequestInit) => Response | Promise<Response>,
  threadIds = ["thr_a", "thr_b"],
  latestTurn: { turnId: string; state: string } | null = null,
) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const path = url.pathname.replace("/api/chat/", "");
    if (path === "model") {
      return jsonResponse({
        model: "test-model",
        experience: true,
        attachmentCapabilities: {
          declaredFormats: ["image/png"],
          effectiveFormats: ["image/png"],
          maxSingleBytes: 5_000_000,
          maxTotalBytes: 5_000_000,
          maxAttachments: 8,
        },
      });
    }
    if (path === "threads/list") {
      return jsonResponse(
        threadIds.map(
          (threadId): ThreadView => ({
            threadId,
            agentId: "general",
            title: threadId,
            archived: false,
            version: 1,
          }),
        ),
      );
    }
    if (path === "threads/agents") return jsonResponse([]);
    if (path === "threads/history") {
      return jsonResponse({
        items: [],
        nextSequence: 0,
        throughSequence: 0,
        hasMore: false,
        latestTurn,
      });
    }
    return route(path, init);
  });
}

/** 等待本地工作台完成模型目录与当前 Thread 历史初始化。 */
async function mountWorkbench(fetchMock: ReturnType<typeof springFetch>) {
  localStorage.clear();
  vi.stubGlobal("fetch", fetchMock);
  const hook = renderHook(() => useWorkbench());
  await waitFor(() => expect(hook.result.current.state.selectedThreadId).toBe("thr_a"));
  await waitFor(() =>
    expect(hook.result.current.state.sessions["thr_a"]?.historyLoaded).toBe(true),
  );
  return hook;
}

/** 使用真实对话组件投影 Hook 状态，验证显式核实与恢复入口形成闭环。 */
function workbenchConversationView(
  workbench: ReturnType<typeof useWorkbench>,
  onError: (error: unknown) => void = () => undefined,
) {
  const threadId = "thr_a";
  return (
    <ConversationPane
      threadId={threadId}
      session={workbench.state.sessions[threadId] ?? emptySession()}
      model={workbench.state.model}
      pending={workbench.state.pending[threadId]}
      pendingRecoveries={workbench.state.pendingRecoveries}
      onLoadMoreHistory={workbench.loadMoreHistory}
      onSubmit={workbench.submit}
      onSessionChange={workbench.updateSession}
      onAnswer={workbench.answerAndResume}
      onResume={workbench.resumeTurn}
      onError={onError}
      onRetry={workbench.retrySubmission}
      onControl={workbench.controlTurn}
      onWorkbench={() => undefined}
      onRefreshRecoveries={workbench.refreshRecoveries}
      onResolveRecovery={workbench.resolveRecovery}
    />
  );
}

/** 构造服务端已接受的附件元数据，不在前端测试中伪造字节解析结果。 */
function uploadedAttachment(
  id = "att_a",
  fileName = "report.png",
  sizeBytes = 3,
): AttachmentMetadata {
  return {
    id,
    threadId: "thr_a",
    fileName,
    mediaType: "image/png",
    sizeBytes,
    sha256: "a".repeat(64),
    createdAt: "2026-10-06T00:00:00Z",
    referenced: false,
  };
}

/** 每项测试结束后清理持久状态和浏览器边界替身。 */
afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  vi.mocked(readPendingUpload).mockReset().mockResolvedValue(undefined);
  vi.unstubAllGlobals();
});

/** 为 Hook 的主题观察器提供 jsdom 未实现的浏览器媒体查询边界。 */
beforeEach(() => {
  vi.stubGlobal("matchMedia", (media: string) => ({
    matches: false,
    media,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
});

describe("Spring Boot enterprise workbench reliability", () => {
  /** 刷新后只靠持久化的 Thread 草稿索引恢复附件，并沿用原上传操作键。 */
  it("刷新后读取原文件并以同一操作键核实未知上传", async () => {
    const file = new File([new Uint8Array([1, 2, 3])], "report.png", { type: "image/png" });
    vi.mocked(readPendingUpload).mockResolvedValueOnce(undefined).mockResolvedValueOnce(file);
    const uploadForms: FormData[] = [];
    let uploads = 0;
    const fetchMock = springFetch(async (path, init) => {
      if (path === "attachments/upload") {
        uploadForms.push(init?.body as FormData);
        uploads += 1;
        if (uploads === 1) throw new TypeError("connection reset after upload");
        return jsonResponse(uploadedAttachment());
      }
      return jsonResponse({});
    });
    const firstMount = await mountWorkbench(fetchMock);

    await act(async () => {
      await firstMount.result.current.addAttachments("thr_a", [file]);
    });
    await waitFor(() =>
      expect(firstMount.result.current.state.sessions["thr_a"]?.attachmentDrafts[0]?.status).toBe(
        "unknown",
      ),
    );
    await waitFor(() => expect(readPendingUpload).toHaveBeenCalledTimes(1));
    const draft = firstMount.result.current.state.sessions["thr_a"]?.attachmentDrafts[0];
    expect(draft).toBeDefined();
    await waitFor(() =>
      expect(readPreferences().attachmentDrafts?.["thr_a"]?.[0]?.operationKey).toBe(
        draft!.operationKey,
      ),
    );
    firstMount.unmount();

    const reloaded = renderHook(() => useWorkbench());
    await waitFor(() => expect(reloaded.result.current.state.selectedThreadId).toBe("thr_a"));
    await waitFor(() =>
      expect(reloaded.result.current.state.sessions["thr_a"]?.attachmentDrafts[0]?.status).toBe(
        "uploaded",
      ),
    );
    expect(uploads).toBe(2);
    expect(uploadForms.map((form) => form.get("operationKey"))).toEqual([
      draft!.operationKey,
      draft!.operationKey,
    ]);
    expect(readPendingUpload).toHaveBeenCalledWith(draft!.operationKey);
  });

  /** 服务端完成上传而浏览器已请求取消时，须丢弃服务端草稿并保留当前文字。 */
  it("上传成功与取消同时到达时回收附件且不清除文字草稿", async () => {
    const file = new File([new Uint8Array([1, 2, 3])], "report.png", { type: "image/png" });
    const upload = deferred<Response>();
    const started = deferred<void>();
    const discardBodies: unknown[] = [];
    const fetchMock = springFetch(async (path, init) => {
      if (path === "attachments/upload") {
        started.resolve();
        return upload.promise;
      }
      if (path === "attachments/discard") {
        discardBodies.push(JSON.parse(String(init?.body)));
        return jsonResponse({ discardedIds: ["att_a"] });
      }
      return jsonResponse({});
    });
    const { result } = await mountWorkbench(fetchMock);
    act(() =>
      result.current.commit({
        type: "session-patch",
        threadId: "thr_a",
        value: { draft: "保留这段说明" },
      }),
    );
    await act(async () => {
      await result.current.addAttachments("thr_a", [file]);
    });
    await started.promise;
    const draft = result.current.state.sessions["thr_a"]!.attachmentDrafts[0]!;

    act(() => result.current.cancelAttachmentUpload("thr_a", draft.operationKey));
    await act(async () => {
      upload.resolve(jsonResponse(uploadedAttachment()));
      await upload.promise;
    });

    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.attachmentDrafts[0]?.status).toBe("error"),
    );
    expect(result.current.state.sessions["thr_a"]?.draft).toBe("保留这段说明");
    expect(discardBodies).toEqual([{ threadId: "thr_a", attachmentIds: ["att_a"] }]);
    expect(result.current.state.sessions["thr_a"]?.attachmentDrafts[0]?.error).toContain(
      "上传已取消",
    );
  });

  /** 异步上传响应只能更新发起请求的 Thread，不能覆盖当前会话草稿。 */
  it("切换 Thread 后迟到的上传响应仍归原会话", async () => {
    const file = new File([new Uint8Array([1, 2, 3])], "report.png", { type: "image/png" });
    const upload = deferred<Response>();
    const started = deferred<void>();
    const fetchMock = springFetch(async (path) => {
      if (path === "attachments/upload") {
        started.resolve();
        return upload.promise;
      }
      return jsonResponse({});
    });
    const { result } = await mountWorkbench(fetchMock);
    act(() =>
      result.current.commit({
        type: "session-patch",
        threadId: "thr_a",
        value: { draft: "A 的说明" },
      }),
    );
    await act(async () => {
      await result.current.addAttachments("thr_a", [file]);
    });
    await started.promise;
    act(() => result.current.selectThread("thr_b"));
    await waitFor(() => expect(result.current.state.sessions["thr_b"]?.historyLoaded).toBe(true));

    await act(async () => {
      upload.resolve(jsonResponse(uploadedAttachment()));
      await upload.promise;
    });
    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.attachmentDrafts[0]?.status).toBe("uploaded"),
    );
    expect(result.current.state.sessions["thr_a"]?.draft).toBe("A 的说明");
    expect(result.current.state.sessions["thr_b"]?.attachmentDrafts).toEqual([]);
    expect(result.current.state.sessions["thr_b"]?.draft).toBe("");
  });

  /** 跨历史请求量超限的上传响应仍显示可执行提示并保留可核实的文件选择。 */
  it("REQUEST_TOO_LARGE 上传拒绝保留文字和文件草稿", async () => {
    const file = new File([new Uint8Array([1, 2, 3])], "report.png", { type: "image/png" });
    const fetchMock = springFetch(async (path) =>
      path === "attachments/upload"
        ? jsonResponse({ code: "REQUEST_TOO_LARGE", detail: "internal policy detail" }, 413)
        : jsonResponse({}),
    );
    const { result } = await mountWorkbench(fetchMock);
    act(() =>
      result.current.commit({
        type: "session-patch",
        threadId: "thr_a",
        value: { draft: "不要丢掉的说明" },
      }),
    );

    await act(async () => {
      await result.current.addAttachments("thr_a", [file]);
    });
    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.attachmentDrafts[0]?.status).toBe("error"),
    );

    const session = result.current.state.sessions["thr_a"]!;
    expect(session.draft).toBe("不要丢掉的说明");
    expect(session.attachmentDrafts[0]?.fileName).toBe("report.png");
    expect(session.attachmentDrafts[0]?.error).toContain("请压缩当前会话，或移除新资料后再试");
    expect(session.attachmentDrafts[0]?.error).toContain("消息文字和文件选择已保留");
    expect(session.attachmentDrafts[0]?.error).not.toContain("internal policy detail");
    expect(deletePendingUpload).not.toHaveBeenCalled();
  });

  /** 确定的请求总量拒绝不是未知执行；恢复 Composer 的精确文字和已上传附件。 */
  it("REQUEST_TOO_LARGE 发送拒绝恢复原文字和附件草稿", async () => {
    const fetchMock = springFetch(
      async (path) =>
        path === "stream"
          ? jsonResponse({ code: "REQUEST_TOO_LARGE", detail: "internal policy detail" }, 413)
          : jsonResponse({}),
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const attachment = uploadedAttachment("att_history");
    const rawDraft = "  请保留这段说明  ";
    act(() =>
      result.current.commit({
        type: "session-patch",
        threadId: "thr_a",
        value: {
          draft: rawDraft,
          attachmentDrafts: [
            {
              operationKey: "upload_original",
              fileName: attachment.fileName,
              mediaType: attachment.mediaType,
              sizeBytes: attachment.sizeBytes,
              status: "uploaded",
              attachment,
            },
          ],
        },
      }),
    );

    act(() =>
      result.current.submit({ text: rawDraft, skillIds: [], attachmentIds: [attachment.id] }),
    );
    await waitFor(() =>
      expect(result.current.state.notice?.text).toContain("请压缩当前会话，或移除新资料后再试"),
    );

    const session = result.current.state.sessions["thr_a"]!;
    expect(session.draft).toBe(rawDraft);
    expect(session.attachmentDrafts).toHaveLength(1);
    expect(session.attachmentDrafts[0]).toMatchObject({
      status: "uploaded",
      attachment: { id: "att_history" },
    });
    expect(session.turns).toEqual([]);
    expect(result.current.state.pending["thr_a"]).toBeUndefined();
    expect(result.current.state.notice?.text).toContain("请压缩当前会话，或移除新资料后再试");
    expect(result.current.state.notice?.text).toContain("消息文字和文件选择已保留");
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith("/status"))).toBe(false);
  });

  /** 纯附件是合法输入；发送时正文保持为空而附件 ID 进入原生消息请求。 */
  it("仅发送附件时保留附件引用并允许空正文", async () => {
    let streamBody: Record<string, unknown> | undefined;
    const fetchMock = springFetch(
      async (path, init) => {
        if (path === "stream") {
          streamBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
          return new Response("event: accepted\ndata: {}\n\n", {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        }
        if (path === "status") return jsonResponse({ state: "COMPLETED" });
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const attachment = uploadedAttachment("att_only");
    act(() =>
      result.current.commit({
        type: "session-patch",
        threadId: "thr_a",
        value: {
          attachmentDrafts: [
            {
              operationKey: "upload_only",
              fileName: attachment.fileName,
              mediaType: attachment.mediaType,
              sizeBytes: attachment.sizeBytes,
              status: "uploaded",
              attachment,
            },
          ],
        },
      }),
    );

    act(() => result.current.submit({ text: "", skillIds: [], attachmentIds: [attachment.id] }));
    await waitFor(() => expect(streamBody).toBeDefined());
    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.turns[0]?.state).toBe("COMPLETED"),
    );

    expect(streamBody).toMatchObject({
      content: { text: "", skillIds: [], attachmentIds: ["att_only"] },
    });
    expect(result.current.state.sessions["thr_a"]?.turns[0]?.attachments).toEqual([attachment]);
    expect(result.current.state.sessions["thr_a"]?.attachmentDrafts).toEqual([]);
  });

  /** UI 只呈现 Core 当前允许的恢复决策，拒绝动作在恢复语义中保持显式。 */
  it("恢复卡片只展示 Core allowedActions", async () => {
    const user = userEvent.setup();
    const recovery: RecoveryView = {
      recoveryId: "recovery_a",
      callId: "call_a",
      revision: 4,
      allowedActions: ["SKIP"],
      status: "PENDING",
    };
    const turn = {
      ...emptyTurn("turn_a", "", []),
      state: "SUSPENDED" as const,
      recoveries: [recovery],
    };
    const onResolveRecovery = vi.fn(async () => undefined);
    render(
      <ConversationPane
        threadId="thr_a"
        session={{ ...emptySession(), historyLoaded: true, turns: [turn] }}
        model="test-model"
        pendingRecoveries={{}}
        onLoadMoreHistory={async () => undefined}
        onSubmit={() => undefined}
        onSessionChange={() => undefined}
        onAnswer={async () => undefined}
        onResume={async () => undefined}
        onRetry={() => undefined}
        onControl={async () => undefined}
        onWorkbench={() => undefined}
        onRefreshRecoveries={async () => [recovery]}
        onResolveRecovery={onResolveRecovery}
      />,
    );

    const card = screen.getByRole("region", { name: "待核实操作" });
    expect(card.textContent).toContain("操作结果待核实");
    expect(screen.queryByRole("button", { name: "明确重试" })).toBeNull();
    await user.click(within(card).getByRole("button", { name: "跳过本次操作" }));
    expect(onResolveRecovery).toHaveBeenCalledWith("thr_a", turn, recovery, "SKIP");
  });

  /** 丢失恢复响应后先查 Core revision；仍待处理时复用原键，然后显式 resume。 */
  it("恢复裁决响应未知时以相同操作键重试并继续原 Turn", async () => {
    const recovery: RecoveryView = {
      recoveryId: "recovery_a",
      callId: "call_a",
      revision: 7,
      allowedActions: ["RETRY"],
      status: "PENDING",
    };
    const resolutions: Array<Record<string, unknown>> = [];
    let resolutionAttempt = 0;
    const terminalEvent = {
      sequence: 1,
      eventType: "agent.execution.terminal",
      payloadJson: JSON.stringify({ state: "COMPLETED", summary: "恢复后已完成" }),
    };
    const fetchMock = springFetch(
      async (path, init) => {
        if (path === "recoveries") return jsonResponse({ recoveries: [recovery] });
        if (path === "recoveries/resolve") {
          resolutions.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
          resolutionAttempt += 1;
          return resolutionAttempt === 1
            ? jsonResponse({ detail: "gateway timeout" }, 503)
            : jsonResponse(null, 204);
        }
        if (path === "resume") return jsonResponse(null, 204);
        if (path === "reconnect") {
          return new Response(`event: agent-event\ndata: ${JSON.stringify(terminalEvent)}\n\n`, {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        }
        return jsonResponse({ state: "RUNNING" });
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = {
      ...emptyTurn("turn_a", "", []),
      state: "SUSPENDED" as const,
      recoveries: [recovery],
    };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.resolveRecovery("thr_a", turn, recovery, "RETRY");
    });
    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.turns[0]?.state).toBe("COMPLETED"),
    );

    expect(resolutions).toHaveLength(2);
    expect(resolutions[0]).toMatchObject({
      recoveryId: "recovery_a",
      callId: "call_a",
      expectedRecoveryRevision: 7,
      decision: "RETRY",
    });
    expect(resolutions[1]?.["operationKey"]).toBe(resolutions[0]?.["operationKey"]);
    expect(resolutions[1]?.["expectedRecoveryRevision"]).toBe(7);
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/resume")),
    ).toHaveLength(1);
  });

  /** 真实 SseFrame 故障先冻结自动重连，再 resume 对账并展示 Core 允许的裁决。 */
  it.each(["RETRY", "SKIP"] as const)(
    "执行句柄丢失后先核实，再按 Core allowedActions 执行 %s",
    async (decision) => {
      const user = userEvent.setup();
      const recovery: RecoveryView = {
        recoveryId: "recovery_a",
        callId: "call_a",
        revision: 3,
        allowedActions: [decision],
        status: "PENDING",
      };
      const decisions: Array<Record<string, unknown>> = [];
      const resumeCalls: string[] = [];
      let stage: "unobserved" | "suspended" | "running" | "completed" = "unobserved";
      const terminalEvent = {
        sequence: 1,
        eventType: "agent.execution.terminal",
        payloadJson: JSON.stringify({ state: "COMPLETED", summary: "已完成原任务" }),
      };
      const fetchMock = springFetch(
        async (path, init) => {
          if (path === "stream")
            return new Response(
              'event: accepted\ndata: {}\n\nevent: recovery-required\ndata: {"reason":"execution-unobserved"}\n\n',
              { status: 200, headers: { "Content-Type": "text/event-stream" } },
            );
          if (path === "status") {
            if (stage === "unobserved")
              return jsonResponse({ state: "RUNNING", resumeRequired: true });
            if (stage === "suspended")
              return jsonResponse({ state: "SUSPENDED", resumeRequired: false });
            return jsonResponse({ state: "RUNNING", resumeRequired: false });
          }
          if (path === "recoveries")
            return jsonResponse({ recoveries: stage === "suspended" ? [recovery] : [] });
          if (path === "resume") {
            resumeCalls.push(String(init?.body));
            stage = stage === "unobserved" ? "suspended" : "running";
            return jsonResponse(null, 204);
          }
          if (path === "recoveries/resolve") {
            decisions.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
            return jsonResponse(null, 204);
          }
          if (path === "reconnect") {
            stage = "completed";
            return new Response(`event: agent-event\ndata: ${JSON.stringify(terminalEvent)}\n\n`, {
              status: 200,
              headers: { "Content-Type": "text/event-stream" },
            });
          }
          return jsonResponse({});
        },
        ["thr_a"],
      );
      const { result } = await mountWorkbench(fetchMock);

      act(() => result.current.submit({ text: "保存这段资料", skillIds: [], attachmentIds: [] }));
      await waitFor(() => {
        const turn = result.current.state.sessions["thr_a"]?.turns.at(-1);
        expect(turn?.state).toBe("RUNNING");
        expect(turn?.resumeRequired).toBe(true);
        expect(result.current.state.sessions["thr_a"]?.observing).toBe(false);
      });
      expect(
        fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/reconnect")),
      ).toHaveLength(0);

      act(() => result.current.updateSession("thr_a", { draft: "保留的下一条草稿" }));
      const pane = render(workbenchConversationView(result.current));
      expect(screen.getByText("执行状态待核实，继续操作将先核实保存结果。")).toBeVisible();
      expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("保留的下一条草稿");
      expect(screen.getByRole("textbox", { name: "消息" })).toBeEnabled();
      expect(screen.getByRole("button", { name: "继续以核实保存结果" })).toBeVisible();
      expect(screen.queryByRole("button", { name: "取消执行" })).toBeNull();
      expect(screen.queryByRole("button", { name: "发送" })).toBeNull();
      expect(screen.queryByText("正在工作")).toBeNull();

      await user.click(screen.getByRole("button", { name: "继续以核实保存结果" }));
      await waitFor(() => {
        const turn = result.current.state.sessions["thr_a"]?.turns.at(-1);
        expect(turn?.state).toBe("SUSPENDED");
        expect(turn?.resumeRequired).toBe(false);
        expect(turn?.recoveries).toEqual([recovery]);
      });
      pane.rerender(workbenchConversationView(result.current));
      const recoveryCard = screen.getByRole("region", { name: "待核实操作" });
      expect(recoveryCard.textContent).toContain("操作结果待核实");
      expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("保留的下一条草稿");
      const actionName = decision === "RETRY" ? "明确重试" : "跳过本次操作";
      await user.click(within(recoveryCard).getByRole("button", { name: actionName }));
      await waitFor(() =>
        expect(result.current.state.sessions["thr_a"]?.turns.at(-1)?.state).toBe("COMPLETED"),
      );

      expect(decisions).toHaveLength(1);
      expect(decisions[0]).toMatchObject({
        recoveryId: "recovery_a",
        callId: "call_a",
        expectedRecoveryRevision: 3,
        decision,
      });
      expect(resumeCalls).toHaveLength(2);
      expect(
        fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/reconnect")),
      ).toHaveLength(1);
      expect(result.current.state.sessions["thr_a"]?.draft).toBe("保留的下一条草稿");
    },
  );

  /** 刷新历史后仍以服务端标记阻止 durable RUNNING 被误判为可自动监听。 */
  it("历史加载发现 resumeRequired 时保留 RUNNING 原值并停止自动重连", async () => {
    const fetchMock = springFetch(
      async (path) =>
        path === "status"
          ? jsonResponse({ state: "RUNNING", resumeRequired: true })
          : path === "recoveries"
            ? jsonResponse({ recoveries: [] })
            : jsonResponse({}),
      ["thr_a"],
      { turnId: "turn_a", state: "RUNNING" },
    );
    const { result } = await mountWorkbench(fetchMock);

    const turn = result.current.state.sessions["thr_a"]?.turns.at(-1);
    expect(turn).toMatchObject({ state: "RUNNING", resumeRequired: true });
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/reconnect")),
    ).toHaveLength(0);
  });

  /** resume 响应未知且状态仍要求对账时保留按钮，不尝试自动重连。 */
  it("resume 响应异常后查到 resumeRequired 时保留显式核实动作", async () => {
    const fetchMock = springFetch(
      async (path) => {
        if (path === "resume") return jsonResponse({ detail: "resume response lost" }, 503);
        if (path === "status") return jsonResponse({ state: "RUNNING", resumeRequired: true });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = {
      ...emptyTurn("turn_a", "原任务", []),
      state: "RUNNING" as const,
      resumeRequired: true,
    };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await expect(result.current.resumeTurn("thr_a", turn)).rejects.toThrow(
        "resume response lost",
      );
    });

    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
      state: "RUNNING",
      resumeRequired: true,
    });
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/resume")),
    ).toHaveLength(1);
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/reconnect")),
    ).toHaveLength(0);
  });

  /** 明确拒绝且状态仍暂停时保留用户入口，不把失败请求误当成 202 受理。 */
  it("resume 被拒绝且状态仍为 SUSPENDED 时不自动重连", async () => {
    let reconnects = 0;
    const fetchMock = springFetch(
      async (path) => {
        if (path === "resume") return jsonResponse({ detail: "resume rejected" }, 409);
        if (path === "status") return jsonResponse({ state: "SUSPENDED", resumeRequired: false });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "reconnect") reconnects += 1;
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = { ...emptyTurn("turn_a", "原任务", []), state: "SUSPENDED" as const };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await expect(result.current.resumeTurn("thr_a", turn)).rejects.toThrow("resume rejected");
    });

    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)?.state).toBe("SUSPENDED");
    expect(result.current.state.sessions["thr_a"]?.observing).toBeFalsy();
    expect(reconnects).toBe(0);
  });

  /** 状态事件与等待引用可能分属两次 SSE；只有读到完整等待才交还用户操作。 */
  it.each(["approval", "input"] as const)(
    "%s 状态先到时继续读取等待记录并显示表单",
    async (kind) => {
      let reconnects = 0;
      const waitingState = kind === "approval" ? "WAITING_APPROVAL" : "WAITING_INPUT";
      const wait: WaitView = {
        interactionId: "interaction_split",
        interactionRevision: 1,
        kind,
        status: "PENDING",
        questions: [
          {
            questionId: "question_split",
            prompt: "补充确认",
            type: "TEXT",
            required: true,
            allowFreeText: true,
            options: [],
          },
        ],
      };
      const stateEvent = {
        sequence: 1,
        eventType: "agent.execution.state",
        payloadJson: JSON.stringify({ from: "RUNNING", to: waitingState }),
      };
      const waitEvent = {
        sequence: 2,
        eventType: "agent.wait.requested",
        payloadJson: JSON.stringify({ requestRecordId: "record_split_wait" }),
      };
      const fetchMock = springFetch(
        async (path) => {
          if (path === "stream")
            return new Response(
              `event: accepted\ndata: {}\n\nevent: agent-event\ndata: ${JSON.stringify(stateEvent)}\n\n`,
              { status: 200, headers: { "Content-Type": "text/event-stream" } },
            );
          if (path === "status") return jsonResponse({ state: waitingState });
          if (path === "wait") return jsonResponse(wait);
          if (path === "reconnect") {
            reconnects += 1;
            return new Response(`event: agent-event\ndata: ${JSON.stringify(waitEvent)}\n\n`, {
              status: 200,
              headers: { "Content-Type": "text/event-stream" },
            });
          }
          return jsonResponse({ recoveries: [] });
        },
        ["thr_a"],
      );
      const { result } = await mountWorkbench(fetchMock);
      act(() => result.current.submit({ text: "需要确认的任务", skillIds: [], attachmentIds: [] }));
      await waitFor(
        () => expect(result.current.state.sessions["thr_a"]?.turns.at(-1)?.wait).toEqual(wait),
        { timeout: 2500 },
      );
      await waitFor(() => expect(result.current.state.sessions["thr_a"]?.observing).toBe(false));
      render(workbenchConversationView(result.current));
      expect(screen.getByText("补充确认")).toBeVisible();
      expect(reconnects).toBe(1);
      expect(result.current.state.sessions["thr_a"]?.turns.at(-1)?.cursor).toBe(2);
    },
  );

  /**
   * @author kongweiguang
   * 使用 Spring 实际返回的 input DTO 与 SINGLE option 语义，覆盖表单展示、稳定身份回答及显式恢复闭环。
   */
  it("澄清等待异步回读期间保留最新 Thread 状态，并提交原选项后恢复", async () => {
    const waitResponse = deferred<Response>();
    let waitReadStarted = false;
    const answerRequests: Array<Record<string, unknown>> = [];
    const stateEvent = {
      sequence: 1,
      eventType: "agent.execution.state",
      payloadJson: JSON.stringify({ from: "RUNNING", to: "WAITING_INPUT" }),
    };
    const waitEvent = {
      sequence: 2,
      eventType: "agent.wait.requested",
      payloadJson: JSON.stringify({ requestRecordId: "rec_input_wait" }),
    };
    const terminalEvent = {
      sequence: 3,
      eventType: "agent.execution.terminal",
      payloadJson: JSON.stringify({ state: "COMPLETED", summary: "已按你的选择回答" }),
    };
    const wait: WaitView = {
      interactionId: "interaction_input",
      interactionRevision: 0,
      status: "PENDING",
      kind: "input",
      questions: [
        {
          questionId: "question_demo",
          prompt: "选择风格",
          type: "SINGLE",
          required: true,
          allowFreeText: false,
          options: [
            {
              optionId: "option_0",
              label: "简短",
              description: "快速概括重点",
              recommended: false,
            },
            {
              optionId: "option_1",
              label: "详细",
              description: "包含背景与依据",
              recommended: true,
            },
          ],
        },
      ],
    };
    const fetchMock = springFetch(
      async (path, init) => {
        if (path === "stream")
          return new Response(
            [stateEvent, waitEvent]
              .map((event) => `event: agent-event\ndata: ${JSON.stringify(event)}\n\n`)
              .join(""),
            { status: 200, headers: { "Content-Type": "text/event-stream" } },
          );
        if (path === "wait") {
          waitReadStarted = true;
          return waitResponse.promise;
        }
        if (path === "answers") {
          answerRequests.push(JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>);
          return jsonResponse(null, 204);
        }
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") return jsonResponse({ state: "RUNNING", resumeRequired: false });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "reconnect")
          return new Response(`event: agent-event\ndata: ${JSON.stringify(terminalEvent)}\n\n`, {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    act(() => result.current.submit({ text: "验证澄清后继续", skillIds: [], attachmentIds: [] }));
    await waitFor(() => expect(waitReadStarted).toBe(true));

    const current = result.current.state.sessions["thr_a"]?.turns.at(-1);
    expect(current).toBeDefined();
    act(() =>
      result.current.commit({
        type: "session-turn",
        threadId: "thr_a",
        turn: { ...current!, recoveryError: "并发状态仍待检查" },
      }),
    );
    await act(async () => waitResponse.resolve(jsonResponse(wait)));

    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
        state: "WAITING_INPUT",
        wait,
        waitAnswered: false,
        recoveryError: "并发状态仍待检查",
      }),
    );
    await waitFor(() => expect(result.current.state.sessions["thr_a"]?.observing).toBe(false));
    render(workbenchConversationView(result.current));
    expect(screen.getByText("请回答以下问题")).toBeVisible();
    expect(screen.getByText("选择风格")).toBeVisible();
    expect(screen.getByText("包含背景与依据")).toBeVisible();
    expect(screen.getByText("推荐")).toBeVisible();

    const user = userEvent.setup();
    await user.click(screen.getByRole("radio", { name: "简短" }));
    await user.click(screen.getByRole("button", { name: "确认并继续" }));

    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
        state: "COMPLETED",
        answer: "已按你的选择回答",
      }),
    );
    expect(answerRequests).toHaveLength(1);
    expect(answerRequests[0]).toMatchObject({
      threadId: "thr_a",
      turnId: current!.turnId,
      interactionId: "interaction_input",
      expectedInteractionRevision: 0,
      answers: [
        {
          questionId: "question_demo",
          optionIds: ["option_0"],
          freeText: null,
          skipped: false,
        },
      ],
    });
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/resume")),
    ).toHaveLength(1);
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/reconnect")),
    ).toHaveLength(1);
  });

  /**
   * @author kongweiguang
   * 异步正文读取后只以最新 Turn 作事件投影基底，保留与正文无关的并发核验状态。
   */
  it("模型正文回读期间的并发核验状态不会丢失", async () => {
    const messageResponse = deferred<Response>();
    let messageReadStarted = false;
    const modelEvent = {
      sequence: 1,
      eventType: "agent.model.completed",
      payloadJson: JSON.stringify({
        messageRecordId: "record_model_message",
        usageRecordId: "record_model_usage",
      }),
    };
    const fetchMock = springFetch(
      async (path) => {
        if (path === "stream")
          return new Response(`event: agent-event\ndata: ${JSON.stringify(modelEvent)}\n\n`, {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        if (path === "message") {
          messageReadStarted = true;
          return messageResponse.promise;
        }
        if (path === "status") return jsonResponse({ state: "RUNNING", resumeRequired: false });
        return jsonResponse({ recoveries: [] });
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    act(() => result.current.submit({ text: "保留并发核验", skillIds: [], attachmentIds: [] }));
    await waitFor(() => expect(messageReadStarted).toBe(true));

    const current = result.current.state.sessions["thr_a"]?.turns.at(-1);
    expect(current).toBeDefined();
    act(() =>
      result.current.commit({
        type: "session-turn",
        threadId: "thr_a",
        turn: { ...current!, recoveryError: "并发核验提示" },
      }),
    );
    await act(async () =>
      messageResponse.resolve(
        jsonResponse({ modelRequestId: "model_request", text: "读取到的权威正文", calls: [] }),
      ),
    );

    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
        recoveryError: "并发核验提示",
        items: [expect.objectContaining({ kind: "assistant", text: "读取到的权威正文" })],
      }),
    );
  });

  /**
   * @author kongweiguang
   * 旧 wait 读取可能晚于同 Turn 的下一次等待，必须以事件游标和 wait identity 保留较新事实。
   */
  it("迟到的旧 wait 响应不会覆盖同一 Turn 的较新等待", async () => {
    const waitResponse = deferred<Response>();
    let waitReadStarted = false;
    const waitEvent = {
      sequence: 2,
      eventType: "agent.wait.requested",
      payloadJson: JSON.stringify({ requestRecordId: "record_old" }),
    };
    const wait: WaitView = {
      interactionId: "interaction_old",
      interactionRevision: 0,
      status: "PENDING",
      kind: "input",
      questions: [
        {
          questionId: "question_old",
          prompt: "过期问题",
          type: "TEXT",
          required: true,
          allowFreeText: true,
          options: [],
        },
      ],
    };
    const newerWait: WaitView = {
      ...wait,
      interactionId: "interaction_new",
      questions: [{ ...wait.questions[0]!, questionId: "question_new", prompt: "最新问题" }],
    };
    const fetchMock = springFetch(
      async (path) => {
        if (path === "stream")
          return new Response(`event: agent-event\ndata: ${JSON.stringify(waitEvent)}\n\n`, {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        if (path === "wait") {
          waitReadStarted = true;
          return waitResponse.promise;
        }
        return jsonResponse({ recoveries: [] });
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    act(() => result.current.submit({ text: "等待替换竞争", skillIds: [], attachmentIds: [] }));
    await waitFor(() => expect(waitReadStarted).toBe(true));

    const current = result.current.state.sessions["thr_a"]?.turns.at(-1);
    expect(current).toBeDefined();
    act(() =>
      result.current.commit({
        type: "session-turn",
        threadId: "thr_a",
        turn: {
          ...current!,
          cursor: 3,
          state: "WAITING_INPUT",
          wait: newerWait,
          waitRecordId: "record_new",
          waitAnswered: false,
        },
      }),
    );

    await act(async () => waitResponse.resolve(jsonResponse(wait)));
    await waitFor(() => expect(result.current.state.sessions["thr_a"]?.observing).toBe(false));
    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
      cursor: 3,
      wait: newerWait,
      waitRecordId: "record_new",
      waitAnswered: false,
    });
  });

  /**
   * @author kongweiguang
   * 线程切换或关闭会话后，未取消的 HTTP wait 回读不得重建旧 Turn 或重新打开旧投影。
   */
  it.each(["switch", "close"] as const)(
    "wait 回读尚未完成时%s会话可安全隔离迟到响应",
    async (action) => {
      const waitResponse = deferred<Response>();
      let waitReadStarted = false;
      const waitEvent = {
        sequence: 1,
        eventType: "agent.wait.requested",
        payloadJson: JSON.stringify({ requestRecordId: "record_pending" }),
      };
      const fetchMock = springFetch(
        async (path) => {
          if (path === "stream")
            return new Response(`event: agent-event\ndata: ${JSON.stringify(waitEvent)}\n\n`, {
              status: 200,
              headers: { "Content-Type": "text/event-stream" },
            });
          if (path === "wait") {
            waitReadStarted = true;
            return waitResponse.promise;
          }
          return jsonResponse({ recoveries: [] });
        },
        ["thr_a", "thr_b"],
      );
      const { result } = await mountWorkbench(fetchMock);
      act(() => result.current.submit({ text: "等待切换或关闭", skillIds: [], attachmentIds: [] }));
      await waitFor(() => expect(waitReadStarted).toBe(true));

      if (action === "switch") act(() => result.current.selectThread("thr_b"));
      else act(() => result.current.commit({ type: "remove-thread", threadId: "thr_a" }));
      await act(async () =>
        waitResponse.resolve(
          jsonResponse({
            interactionId: "interaction_late",
            interactionRevision: 0,
            kind: "input",
            status: "PENDING",
            questions: [],
          }),
        ),
      );

      if (action === "switch") {
        await waitFor(() =>
          expect(result.current.state.sessions["thr_b"]?.historyLoaded).toBe(true),
        );
        expect(result.current.state.selectedThreadId).toBe("thr_b");
        expect(result.current.state.sessions["thr_a"]?.turns.at(-1)?.wait).toBeUndefined();
      } else {
        await waitFor(() => expect(result.current.state.sessions["thr_a"]).toBeUndefined());
      }
    },
  );

  /** 已受理恢复遇到新的人工作业等待后必须停下，答案仍由等待表单提交。 */
  it("恢复观察遇到新的未回答等待后停止重连", async () => {
    let reconnects = 0;
    const wait: WaitView = {
      interactionId: "interaction_new",
      interactionRevision: 1,
      kind: "input",
      status: "PENDING",
      questions: [
        {
          questionId: "question_new",
          prompt: "补充信息",
          type: "TEXT",
          required: true,
          allowFreeText: true,
          options: [],
        },
      ],
    };
    const waitEvent = {
      sequence: 1,
      eventType: "agent.wait.requested",
      payloadJson: JSON.stringify({ requestRecordId: "record_wait" }),
    };
    const fetchMock = springFetch(
      async (path) => {
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") return jsonResponse({ state: "SUSPENDED", resumeRequired: false });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "wait") return jsonResponse(wait);
        if (path === "reconnect") {
          reconnects += 1;
          return new Response(`event: agent-event\ndata: ${JSON.stringify(waitEvent)}\n\n`, {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        }
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = { ...emptyTurn("turn_a", "原任务", []), state: "SUSPENDED" as const };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.resumeTurn("thr_a", turn);
    });
    await waitFor(() => expect(result.current.state.sessions["thr_a"]?.observing).toBe(false));

    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
      state: "WAITING_INPUT",
      wait: { status: "PENDING" },
    });
    expect(reconnects).toBe(1);
  });

  /**
   * @author kongweiguang
   * 卸载时取消的不只是当前 HTTP 请求，也必须使同一观察器的退避循环失效，避免旧 Hook 在新挂载后借用新 fetch。
   */
  it("卸载 Workbench 后不再重启旧 Turn 的退避观察", async () => {
    let reconnects = 0;
    let statusReads = 0;
    let reconnectSignal: AbortSignal | null | undefined;
    const pendingStatus = deferred<Response>();
    const fetchMock = springFetch(
      async (path, init) => {
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") {
          statusReads += 1;
          if (statusReads === 1) return jsonResponse({ state: "RUNNING", resumeRequired: false });
          return pendingStatus.promise;
        }
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "reconnect") {
          reconnects += 1;
          reconnectSignal = init?.signal;
          return new Response("", {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        }
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result, unmount } = await mountWorkbench(fetchMock);
    const turn = { ...emptyTurn("turn_a", "原任务", []), state: "SUSPENDED" as const };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.resumeTurn("thr_a", turn);
    });
    await waitFor(() => expect(statusReads).toBe(2));
    expect(reconnects).toBe(1);
    unmount();
    expect(reconnectSignal?.aborted).toBe(true);

    await act(async () => {
      pendingStatus.resolve(jsonResponse({ state: "RUNNING", resumeRequired: false }));
      await pendingStatus.promise;
      await new Promise((resolve) => window.setTimeout(resolve, 0));
    });
    expect(reconnects).toBe(1);
  });

  /**
   * @author kongweiguang
   * 本次恢复期间提交的人工暂停是明确用户意图，游标与持久事件都必须保留且阻止自动重连。
   */
  it("恢复观察遇到新的人工暂停请求后停止", async () => {
    let reconnects = 0;
    const pauseEvent = {
      sequence: 1,
      eventType: "agent.execution.control",
      payloadJson: JSON.stringify({ action: "SUSPEND_REQUESTED", reasonCode: "USER_PAUSED" }),
    };
    const fetchMock = springFetch(
      async (path) => {
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") return jsonResponse({ state: "SUSPENDED", resumeRequired: false });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "reconnect") {
          reconnects += 1;
          return new Response(`event: agent-event\ndata: ${JSON.stringify(pauseEvent)}\n\n`, {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          });
        }
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = { ...emptyTurn("turn_a", "原任务", []), state: "SUSPENDED" as const };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.resumeTurn("thr_a", turn);
    });
    await waitFor(() => expect(result.current.state.sessions["thr_a"]?.observing).toBe(false));

    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
      state: "SUSPENDED",
      cursor: 1,
      events: [
        expect.objectContaining({
          eventType: "agent.execution.control",
          sequence: 1,
          payloadJson: pauseEvent.payloadJson,
        }),
      ],
    });
    expect(reconnects).toBe(1);
  });

  /** 非终态上的持久错误也必须结束自动观察，避免把人工恢复入口藏在重连中。 */
  it("恢复观察遇到持久错误时停止", async () => {
    let reconnects = 0;
    const fetchMock = springFetch(
      async (path) => {
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") return jsonResponse({ state: "SUSPENDED", resumeRequired: false });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "reconnect") reconnects += 1;
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = {
      ...emptyTurn("turn_a", "原任务", []),
      state: "SUSPENDED" as const,
      error: "等待存储核实",
    };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.resumeTurn("thr_a", turn);
    });

    expect(reconnects).toBe(0);
    expect(result.current.state.sessions["thr_a"]?.observing).toBeFalsy();
  });

  /**
   * @author kongweiguang
   * 状态查询可能先看到已完成；显式恢复仍须重连读取原游标后的失败工具结果和最终正文。
   */
  it("resume 后状态立即为 COMPLETED 时仍回放最终持久消息", async () => {
    const readRecords: string[] = [];
    const reconnectRequests: Array<Record<string, unknown>> = [];
    const toolResultRecordId = "record_approval_tool_result";
    const finalMessageRecordId = "record_approval_final_message";
    const events = [
      {
        sequence: 24,
        eventType: "agent.execution.state",
        payloadJson: JSON.stringify({ from: "WAITING_APPROVAL", to: "SUSPENDED" }),
      },
      {
        sequence: 26,
        eventType: "agent.execution.state",
        payloadJson: JSON.stringify({ from: "SUSPENDED", to: "QUEUED" }),
      },
      {
        sequence: 27,
        eventType: "agent.execution.state",
        payloadJson: JSON.stringify({ from: "QUEUED", to: "RUNNING" }),
      },
      {
        sequence: 28,
        eventType: "agent.tool.completed",
        payloadJson: JSON.stringify({
          state: "FAILED",
          callId: "call_approval_probe",
          resultRecordId: toolResultRecordId,
        }),
      },
      {
        sequence: 30,
        eventType: "agent.model.completed",
        payloadJson: JSON.stringify({
          messageRecordId: finalMessageRecordId,
          usageRecordId: "usage_approval_final",
        }),
      },
      {
        sequence: 31,
        eventType: "agent.assistant.settled",
        payloadJson: JSON.stringify({ messageRecordId: finalMessageRecordId }),
      },
      {
        sequence: 32,
        eventType: "agent.execution.state",
        payloadJson: JSON.stringify({ from: "RUNNING", to: "COMPLETED" }),
      },
      {
        sequence: 33,
        eventType: "agent.execution.terminal",
        payloadJson: JSON.stringify({ state: "COMPLETED", summary: "用户拒绝，未执行" }),
      },
    ];
    const wait: WaitView = {
      interactionId: "interaction_approval",
      interactionRevision: 1,
      kind: "approval",
      status: "PENDING",
      questions: [
        {
          questionId: "question_approval",
          prompt: "允许执行 approval_probe？",
          type: "SINGLE",
          required: true,
          allowFreeText: false,
          options: [
            {
              optionId: "option_approve",
              label: "允许",
              description: "执行工具",
              recommended: false,
            },
            {
              optionId: "option_deny",
              label: "拒绝",
              description: "不执行工具",
              recommended: false,
            },
          ],
        },
      ],
    };
    const fetchMock = springFetch(
      async (path, init) => {
        if (path === "answers") return jsonResponse(null, 204);
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") return jsonResponse({ state: "COMPLETED", resumeRequired: false });
        if (path === "reconnect") {
          reconnectRequests.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
          return new Response(
            events
              .map((event) => `event: agent-event\ndata: ${JSON.stringify(event)}\n\n`)
              .join(""),
            { status: 200, headers: { "Content-Type": "text/event-stream" } },
          );
        }
        if (path === "message") {
          const request = JSON.parse(String(init?.body)) as { recordId: string };
          readRecords.push(request.recordId);
          if (request.recordId === toolResultRecordId)
            return jsonResponse({ text: "审批已拒绝，工具未执行。" });
          if (request.recordId === finalMessageRecordId)
            return jsonResponse({
              modelRequestId: "model_request_approval_final",
              text: "用户拒绝，未执行",
              calls: [],
            });
        }
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = {
      ...emptyTurn("turn_approval", "验证审批拒绝", []),
      state: "WAITING_APPROVAL" as const,
      cursor: 23,
      items: [
        {
          key: "tool:call_approval_probe",
          kind: "tool" as const,
          text: "",
          callId: "call_approval_probe",
          name: "approval_probe",
          state: "RUNNING",
        },
      ],
      wait,
    };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.answerAndResume("thr_a", turn, [
        {
          questionId: "question_approval",
          optionIds: ["option_deny"],
          freeText: null,
          skipped: false,
        },
      ]);
    });
    await waitFor(() =>
      expect(result.current.state.sessions["thr_a"]?.turns.at(-1)?.cursor).toBe(33),
    );

    expect(reconnectRequests).toEqual([
      { threadId: "thr_a", turnId: "turn_approval", afterSequence: 23 },
    ]);
    expect(readRecords).toEqual([toolResultRecordId, finalMessageRecordId]);
    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
      state: "COMPLETED",
      answer: "用户拒绝，未执行",
      cursor: 33,
      wait: { status: "CLOSED" },
      items: expect.arrayContaining([
        expect.objectContaining({
          kind: "tool-result",
          recordId: toolResultRecordId,
          callId: "call_approval_probe",
          state: "FAILED",
          text: "审批已拒绝，工具未执行。",
        }),
      ]),
      events: expect.arrayContaining([
        expect.objectContaining({ sequence: 24, eventType: "agent.execution.state" }),
        expect.objectContaining({ sequence: 26, eventType: "agent.execution.state" }),
        expect.objectContaining({ sequence: 27, eventType: "agent.execution.state" }),
        expect.objectContaining({ sequence: 28, eventType: "agent.tool.completed" }),
        expect.objectContaining({ sequence: 30, eventType: "agent.model.completed" }),
        expect.objectContaining({ sequence: 33, eventType: "agent.execution.terminal" }),
      ]),
    });
    expect(result.current.state.sessions["thr_a"]?.observing).toBe(false);
    render(workbenchConversationView(result.current));
    expect(screen.getByText("用户拒绝，未执行")).toBeVisible();
  });

  /** 恢复初次重连可能没有新事件；202 与旧暂停快照都不能提前结束后续观察。 */
  it("resume 受理后跨越旧 SUSPENDED 快照并观察到 Core 终态", async () => {
    let reconnects = 0;
    const finalReconnect = deferred<Response>();
    const wait: WaitView = {
      interactionId: "interaction_a",
      interactionRevision: 2,
      kind: "input",
      status: "PENDING",
      questions: [
        {
          questionId: "question_a",
          prompt: "选择风格",
          type: "SINGLE",
          required: true,
          allowFreeText: false,
          options: [
            {
              optionId: "short",
              label: "简短",
              description: "快速概括重点",
              recommended: false,
            },
          ],
        },
      ],
    };
    const stateEvent = {
      sequence: 4,
      eventType: "agent.execution.state",
      payloadJson: JSON.stringify({ from: "SUSPENDED", to: "RUNNING" }),
    };
    const terminalEvent = {
      sequence: 5,
      eventType: "agent.execution.terminal",
      payloadJson: JSON.stringify({ state: "COMPLETED", summary: "澄清后已完成" }),
    };
    const fetchMock = springFetch(
      async (path) => {
        if (path === "answers") return jsonResponse(null, 204);
        if (path === "resume") return jsonResponse({ accepted: true }, 202);
        if (path === "status") return jsonResponse({ state: "SUSPENDED", resumeRequired: false });
        if (path === "recoveries") return jsonResponse({ recoveries: [] });
        if (path === "reconnect") {
          reconnects += 1;
          if (reconnects === 1)
            return new Response("", {
              status: 200,
              headers: { "Content-Type": "text/event-stream" },
            });
          return finalReconnect.promise;
        }
        return jsonResponse({});
      },
      ["thr_a"],
    );
    const { result } = await mountWorkbench(fetchMock);
    const turn = {
      ...emptyTurn("turn_a", "验证澄清后继续", []),
      state: "WAITING_INPUT" as const,
      wait,
    };
    act(() => result.current.commit({ type: "session-turn", threadId: "thr_a", turn }));

    await act(async () => {
      await result.current.answerAndResume("thr_a", turn, [
        { questionId: "question_a", optionIds: ["short"], freeText: null, skipped: false },
      ]);
    });
    await waitFor(() => expect(reconnects).toBe(2), { timeout: 2500 });
    expect(result.current.state.sessions["thr_a"]?.turns.at(-1)).toMatchObject({
      state: "SUSPENDED",
      waitAnswered: true,
    });
    expect(result.current.state.sessions["thr_a"]?.observing).toBe(true);

    await act(async () => {
      finalReconnect.resolve(
        new Response(
          [stateEvent, terminalEvent]
            .map((event) => `event: agent-event\ndata: ${JSON.stringify(event)}\n\n`)
            .join(""),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        ),
      );
    });

    await waitFor(() => {
      const resumed = result.current.state.sessions["thr_a"]?.turns.at(-1);
      expect(resumed).toMatchObject({ state: "COMPLETED", answer: "澄清后已完成" });
      expect(result.current.state.sessions["thr_a"]?.observing).toBe(false);
    });
    expect(reconnects).toBe(2);
    expect(
      fetchMock.mock.calls.filter(([input]) => String(input).endsWith("/resume")),
    ).toHaveLength(1);
  });
});
