// @author kongweiguang
import {
  act,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ConversationPane } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/ConversationPane";
import { WorkbenchPanel } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/WorkbenchPanel";
import { useWorkbench } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/useWorkbench";
import {
  appReducer,
  createInitialState,
  emptySession,
  emptyTurn,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/state";

// jsdom 未实现滚动功能；只要存在会话面板，初始化历史记录时仍会调用浏览器 API。
Object.defineProperty(HTMLElement.prototype, "scrollTo", { configurable: true, value: vi.fn() });

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

/** 延迟 HTTP 响应，以便在请求开始与完成之间精确插入 Thread 切换。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** 使用与工作台 Hook 相同的 Spring JSON 响应边界。 */
function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

/** 为 jsdom 提供 Workbench 主题初始化器所依赖的浏览器媒体查询边界。 */
function stubMatchMedia(): void {
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
}

/** 清理渲染期全局状态与 spy，避免 React 诊断污染后续用例。 */
afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe("Spring Boot workbench resource boundaries", () => {
  /** 数据流被接受后，Reducer 重渲染不能重新提交操作，也不能丢失重连身份。 */
  it("状态重渲染后只重连同一 Turn，不重复提交首轮请求", async () => {
    localStorage.clear();
    stubMatchMedia();
    const requests: Array<{ action: string; body: Record<string, unknown> }> = [];
    let closeInitialStream: (() => void) | undefined;
    let resolveStreamStarted!: () => void;
    const streamStarted = new Promise<void>((resolve) => {
      resolveStreamStarted = resolve;
    });
    const completedEvent = JSON.stringify({
      sequence: 1,
      eventType: "agent.execution.terminal",
      payloadJson: JSON.stringify({ state: "COMPLETED", summary: "任务已完成" }),
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const action = String(input).slice("/api/chat/".length);
      const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
      if (action === "model") return jsonResponse({ model: "test-model" });
      if (action === "threads/list")
        return jsonResponse([
          {
            threadId: "thr_stream",
            agentId: "general",
            title: "Stream",
            archived: false,
            version: 1,
          },
        ]);
      if (action === "threads/agents") return jsonResponse([]);
      if (action === "threads/history")
        return jsonResponse({
          items: [],
          nextSequence: 0,
          throughSequence: 0,
          hasMore: false,
          latestTurn: null,
        });
      if (action === "status") return jsonResponse({ state: "RUNNING" });
      if (action === "stream" || action === "reconnect") {
        requests.push({ action, body });
        if (action === "stream") {
          return new Response(
            new ReadableStream<Uint8Array>({
              /** 保持已接受的数据流打开，以便在重连前观察状态变化触发的重渲染。 */
              start(controller) {
                controller.enqueue(new TextEncoder().encode("event: accepted\ndata: {}\n\n"));
                closeInitialStream = () => controller.close();
                resolveStreamStarted();
              },
            }),
            { status: 200, headers: { "Content-Type": "text/event-stream" } },
          );
        }
        const frame = new TextEncoder().encode(`event: agent-event\ndata: ${completedEvent}\n\n`);
        return new Response(
          new ReadableStream<Uint8Array>({
            /** 持久化终态事件到达后立即完成重连，让测试不依赖计时器。 */
            start(controller) {
              controller.enqueue(frame);
              controller.close();
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        );
      }
      return jsonResponse({});
    });
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => useWorkbench());
    await waitFor(() =>
      expect(result.current.state.sessions["thr_stream"]?.historyLoaded).toBe(true),
    );
    act(() => result.current.submit({ text: "执行一次任务", skillIds: [], attachmentIds: [] }));
    await streamStarted;
    await waitFor(() =>
      expect(result.current.state.sessions["thr_stream"]?.turns.at(-1)?.state).toBe("RUNNING"),
    );
    closeInitialStream?.();
    await waitFor(
      () =>
        expect(result.current.state.sessions["thr_stream"]?.turns.at(-1)?.state).toBe("COMPLETED"),
      { timeout: 4_000 },
    );

    expect(requests.filter((request) => request.action === "stream")).toHaveLength(1);
    expect(requests.filter((request) => request.action === "reconnect")).toHaveLength(1);
    expect(requests[0]).toMatchObject({
      action: "stream",
      body: {
        threadId: "thr_stream",
        content: { text: "执行一次任务", skillIds: [], attachmentIds: [] },
      },
    });
    expect(requests[1]).toMatchObject({
      action: "reconnect",
      body: { threadId: "thr_stream", turnId: requests[0]?.body["turnId"], afterSequence: 0 },
    });
    expect(result.current.state.sessions["thr_stream"]?.turns.at(-1)?.answer).toBe("任务已完成");
  });

  /** 切换 Thread 后恢复各自的滚动快照；用户正在进行的滚动始终优先。 */
  it("按 Thread 恢复滚动位置并保存用户自己的落点", async () => {
    vi.useFakeTimers();
    try {
      const onSessionChange = vi.fn();
      const sessionA = { ...emptySession(), historyLoaded: true, scrollTop: 96 };
      const sessionB = { ...emptySession(), historyLoaded: true, scrollTop: 41 };
      const props = {
        threadId: "thr_a",
        session: sessionA,
        model: "test-model",
        onLoadMoreHistory: vi.fn(async () => undefined),
        onSubmit: vi.fn(),
        onSessionChange,
        onAnswer: vi.fn(async () => undefined),
        onResume: vi.fn(async () => undefined),
        onRetry: vi.fn(),
        onControl: vi.fn(async () => undefined),
        onWorkbench: vi.fn(),
        pendingRecoveries: {},
        onRefreshRecoveries: vi.fn(async () => undefined),
      };
      const view = render(<ConversationPane {...props} />);
      const scroll = view.container.querySelector<HTMLElement>(".conversation-scroll");
      expect(scroll).not.toBeNull();
      Object.defineProperty(scroll!, "scrollHeight", { configurable: true, value: 400 });
      expect(scroll!.scrollTop).toBe(96);

      view.rerender(<ConversationPane {...props} threadId="thr_b" session={sessionB} />);
      expect(scroll!.scrollTop).toBe(41);
      scroll!.scrollTop = 27;
      fireEvent.scroll(scroll!);
      view.rerender(
        <ConversationPane {...props} threadId="thr_b" session={sessionB} model="updated-model" />,
      );
      expect(scroll!.scrollTop).toBe(27);

      await act(async () => {
        vi.advanceTimersByTime(160);
      });
      expect(onSessionChange).toHaveBeenCalledWith("thr_b", { scrollTop: 27 });
      view.rerender(
        <ConversationPane {...props} threadId="thr_b" session={{ ...sessionB, scrollTop: 27 }} />,
      );
      expect(scroll!.scrollTop).toBe(27);
      view.rerender(<ConversationPane {...props} session={sessionA} />);
      expect(scroll!.scrollTop).toBe(96);
    } finally {
      vi.useRealTimers();
    }
  });

  /** 延迟的旧 wait 可能在用户切回同一 Thread 并启动新历史读取后才返回。 */
  it("延迟 wait 且切换 Thread 后只清理自身的历史加载登记", async () => {
    localStorage.clear();
    stubMatchMedia();
    const oldWait = deferred<Response>();
    const newerHistory = deferred<Response>();
    let historyARequests = 0;
    let waitRequests = 0;
    const waitingHistory = {
      items: [
        { sequence: 1, turnId: "turn_a", kind: "user", text: "等待中的对话" },
        { sequence: 2, turnId: "turn_a", kind: "wait", recordId: "wait_a" },
      ],
      nextSequence: 2,
      throughSequence: 2,
      hasMore: false,
      latestTurn: { turnId: "turn_a", state: "SUSPENDED" },
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const body = init?.body ? (JSON.parse(String(init.body)) as { threadId?: string }) : {};
      if (url.endsWith("/model")) return jsonResponse({ model: "test" });
      if (url.endsWith("/threads/list"))
        return jsonResponse([
          { threadId: "thr_a", agentId: "general", title: "A", archived: false, version: 1 },
          { threadId: "thr_b", agentId: "general", title: "B", archived: false, version: 1 },
        ]);
      if (url.endsWith("/threads/agents")) return jsonResponse([]);
      if (url.endsWith("/threads/history") && body.threadId === "thr_a") {
        historyARequests += 1;
        return historyARequests === 1 ? jsonResponse(waitingHistory) : newerHistory.promise;
      }
      if (url.endsWith("/threads/history"))
        return jsonResponse({ items: [], nextSequence: 0, throughSequence: 0, hasMore: false });
      if (url.endsWith("/wait")) {
        waitRequests += 1;
        return oldWait.promise;
      }
      return jsonResponse({});
    });
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => useWorkbench());
    await waitFor(() => expect(waitRequests).toBe(1));
    act(() => result.current.selectThread("thr_b"));
    await waitFor(() => expect(result.current.state.selectedThreadId).toBe("thr_b"));
    await waitFor(() => expect(result.current.state.sessions["thr_b"]?.historyLoaded).toBe(true));
    act(() => {
      result.current.selectThread("thr_a");
      void result.current.loadHistory("thr_a");
    });
    await waitFor(() => expect(historyARequests).toBe(2));
    expect(result.current.state.sessions["thr_a"]?.loadingHistory).toBe(true);

    await act(async () => {
      oldWait.resolve(
        jsonResponse({
          status: "PENDING",
          interactionId: "wait_a",
          interactionRevision: 1,
          questions: [],
        }),
      );
      await oldWait.promise;
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });
    expect(result.current.state.sessions["thr_a"]?.loadingHistory).toBe(true);

    await act(async () => {
      newerHistory.resolve(
        jsonResponse({
          items: [],
          nextSequence: 2,
          throughSequence: 2,
          hasMore: false,
          latestTurn: null,
        }),
      );
      await newerHistory.promise;
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });
    await waitFor(() => expect(result.current.state.sessions["thr_a"]?.loadingHistory).toBe(false));
  });

  /** 长会话仍按页显示；同请求身份的持久 Usage 记录各自渲染且不产生重复 key。 */
  it("分页显示执行记录并按需以八条为批读取用量", async () => {
    localStorage.clear();
    const turns = Array.from({ length: 50 }, (_, index) => ({
      ...emptyTurn(`turn_${index}`, `turn_${index}`, []),
      state: "COMPLETED" as const,
      usageRecordIds:
        index === 49
          ? Array.from({ length: 10 }, (_unused, usageIndex) => `usage_${usageIndex}`)
          : [],
    }));
    let state = appReducer(createInitialState(), {
      type: "session-patch",
      threadId: "thr_records",
      value: { ...emptySession(), turns },
    });
    state = appReducer(state, {
      type: "patch",
      value: {
        selectedThreadId: "thr_records",
        workbenchOpen: true,
        workbenchTab: "records",
        workbenchData: { records: { notes: [] } },
      },
    });
    const loadMoreHistory = vi.fn();
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const request = init?.body ? (JSON.parse(String(init.body)) as { recordId?: string }) : {};
      const modelRequestId =
        request.recordId === "usage_0" || request.recordId === "usage_1"
          ? "request_shared"
          : `request_${request.recordId ?? "unknown"}`;
      return jsonResponse({
        modelRequestId,
        usage: { inputTokens: 1, outputTokens: 2, totalTokens: 3 },
      });
    });
    vi.stubGlobal("fetch", fetchMock);
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const user = userEvent.setup();

    render(
      <WorkbenchPanel
        state={state}
        selectedSkills={[]}
        onClose={() => undefined}
        onTab={() => undefined}
        onReload={() => undefined}
        onLoadMoreHistory={loadMoreHistory}
        onNotice={() => undefined}
        onSkillsChange={() => undefined}
      />,
    );

    expect(screen.getAllByText("turn_49")[0]).toBeTruthy();
    expect(screen.queryByText("turn_29")).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "显示更早记录" }));
    expect(screen.getAllByText("turn_29").length).toBeGreaterThan(0);
    expect(screen.queryByText("turn_9")).toBeNull();

    const latestRow = screen.getAllByText("turn_49")[0]?.closest("details");
    expect(latestRow).not.toBeNull();
    await user.click(latestRow!.querySelector("summary")!);
    fetchMock.mockClear();
    await user.click(within(latestRow!).getByRole("button", { name: "读取用量" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(8));
    expect(screen.getByText("模型用量 8 / 10")).toBeTruthy();
    await user.click(within(latestRow!).getByRole("button", { name: "继续读取用量" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(10));
    expect(screen.getByText("模型用量 10 / 10")).toBeTruthy();
    expect(screen.getAllByText("1 输入 · 2 输出 · 3 合计")).toHaveLength(10);
    expect(consoleError.mock.calls.flat().join(" ")).not.toContain("same key");

    await user.click(screen.getByRole("button", { name: "显示更早记录" }));
    expect(screen.getAllByText("turn_9").length).toBeGreaterThan(0);
    expect(loadMoreHistory).not.toHaveBeenCalled();
  });

  /** 默认隐藏协议细节；展开技术详情后仍可回看完整事实、时间与 Turn 身份。 */
  it("记录页按事实结局显示业务标题并折叠技术字段", async () => {
    localStorage.clear();
    const occurredAt = "2026-10-06T01:02:03Z";
    const facts = [
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.note.prepared",
        schemaVersion: 1,
        payload: { outcome: "prepared", title: "月度核查", body: "受控内容" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.policy.decision",
        schemaVersion: 1,
        payload: { outcome: "SUCCEEDED", title: "月度核查" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.policy.decision",
        schemaVersion: 1,
        payload: { outcome: "FAILED", title: "月度核查" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.policy.decision",
        schemaVersion: 1,
        payload: { outcome: "CANCELLED", title: "月度核查" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.policy.decision",
        schemaVersion: 1,
        payload: { outcome: "unrecognized" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.note.saved",
        schemaVersion: 1,
        payload: { outcome: "saved", title: "月度核查" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.delivery.completed",
        schemaVersion: 1,
        payload: { outcome: "delivered", title: "月度核查" },
        occurredAt,
      },
      {
        turnId: "turn_policy",
        namespace: "spring-document",
        type: "document.future.fact",
        schemaVersion: 1,
        payload: { outcome: "new-state" },
        occurredAt,
      },
    ];
    const turns = [
      {
        ...emptyTurn("turn_policy", "检查月度资料并保存结论", []),
        state: "COMPLETED" as const,
      },
    ];
    let state = appReducer(createInitialState(), {
      type: "session-patch",
      threadId: "thr_policy",
      value: { ...emptySession(), turns },
    });
    state = appReducer(state, {
      type: "patch",
      value: {
        selectedThreadId: "thr_policy",
        workbenchOpen: true,
        workbenchTab: "records",
        workbenchData: { records: { notes: [] } },
      },
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes("/policy/facts")
        ? jsonResponse({ facts, nextSequence: facts.length, hasMore: false })
        : jsonResponse({}),
    );
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();

    render(
      <WorkbenchPanel
        state={state}
        selectedSkills={[]}
        onClose={() => undefined}
        onTab={() => undefined}
        onReload={() => undefined}
        onLoadMoreHistory={() => undefined}
        onNotice={() => undefined}
        onSkillsChange={() => undefined}
      />,
    );

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByText("document.note.prepared")).not.toBeInTheDocument();
    const turnRow = screen.getByText("检查月度资料并保存结论").closest("details");
    expect(turnRow).not.toBeNull();
    await user.click(turnRow!.querySelector("summary")!);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    expect(screen.getByText("准备待审批笔记：月度核查")).toBeVisible();
    expect(screen.getByText("保存处理结局：执行成功：月度核查")).toBeVisible();
    expect(screen.getByText("保存处理结局：执行失败：月度核查")).toBeVisible();
    expect(screen.getByText("保存处理结局：已取消：月度核查")).toBeVisible();
    expect(screen.getByText("已保存笔记：月度核查")).toBeVisible();
    expect(screen.getByText("已交付结果：月度核查")).toBeVisible();
    expect(screen.getAllByText("资料策略记录")).toHaveLength(2);
    expect(screen.queryByText(/已批准|已拒绝|已跳过/)).not.toBeInTheDocument();

    const preparedRow = screen.getByText("准备待审批笔记：月度核查").closest("details");
    expect(preparedRow).not.toBeNull();
    const rawType = within(preparedRow!).getByText("document.note.prepared");
    const rawTime = within(preparedRow!).getByText(occurredAt);
    const rawPayload = within(preparedRow!).getByText(/受控内容/);
    expect(rawType).not.toBeVisible();
    expect(rawTime).not.toBeVisible();
    expect(rawPayload).not.toBeVisible();
    expect(preparedRow!.querySelectorAll("details")).toHaveLength(0);

    await user.click(within(preparedRow!).getByText("技术详情"));
    expect(preparedRow).toHaveAttribute("open");
    expect(rawType).toBeVisible();
    expect(rawTime).toBeVisible();
    expect(rawPayload).toBeVisible();

    const turnIdentity = within(turnRow!).getByText("turn_policy");
    expect(turnIdentity).not.toBeVisible();
    await user.click(within(turnRow!).getAllByText("技术详情")[0]!);
    expect(turnIdentity).toBeVisible();
  });
});
