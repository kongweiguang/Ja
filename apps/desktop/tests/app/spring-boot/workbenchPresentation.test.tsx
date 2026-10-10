// @author kongweiguang
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/App";
import { ConversationPane } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/ConversationPane";
import {
  emptySession,
  emptyTurn,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/state";

Object.defineProperty(HTMLElement.prototype, "scrollTo", { configurable: true, value: vi.fn() });

/** 在与真实 Spring 控制器相同的浏览器 fetch 边界返回 JSON。 */
function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** 隔离界面断言与 Provider/PG，同时保留 Thread 元数据和带版本号的 HTTP 修改流程。 */
function installServer() {
  vi.stubGlobal("matchMedia", () => ({
    matches: false,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
  const thread = {
    threadId: "thr_ui",
    agentId: "general",
    title: "当前对话",
    archived: false,
    version: 3,
  };
  const requests: Array<{ action: string; body: Record<string, unknown> }> = [];
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const action = String(input).replace("/api/chat/", "");
    const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
    requests.push({ action, body });
    if (action === "model") return json({ model: "upstream-model", experience: true });
    if (action === "threads/agents") return json([{ id: "general", name: "通用助手" }]);
    if (action === "threads/list") return json(body["search"] ? [] : [thread]);
    if (action === "threads/history")
      return json({
        items: [],
        nextSequence: 0,
        throughSequence: 0,
        hasMore: false,
        latestTurn: null,
      });
    if (action === "threads/update") {
      if (body["title"] === "冲突名称") {
        thread.version++;
        return json({ detail: "请读取最新版本后重试" }, 409);
      }
      return json({ ...thread, ...body, version: 4 });
    }
    return json([]);
  });
  vi.stubGlobal("fetch", fetch);
  return { requests };
}

/** 每个测试单独初始化计时器和浏览器状态，避免上一个用例的焦点或持久化状态让当前断言意外通过。 */
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe("Spring Boot desktop-style presentation", () => {
  /** 搜索只负责导航；隐藏搜索框时应清除筛选条件，同时保留当前草稿。 */
  it("搜索无匹配会话也保留当前对话和草稿，Escape 清除筛选", async () => {
    installServer();
    const user = userEvent.setup();
    render(<App />);
    const composer = await screen.findByRole("textbox", { name: "消息" });
    await user.type(composer, "还没发送的草稿");
    await user.click(screen.getByRole("button", { name: "搜索会话" }));
    const search = screen.getByRole("textbox", { name: "搜索会话" });
    expect(search).toHaveFocus();
    await user.type(search, "不存在");
    await screen.findByText("没有匹配的会话");
    expect(screen.getByRole("heading", { name: "当前对话" })).toBeInTheDocument();
    expect(composer).toHaveValue("还没发送的草稿");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("textbox", { name: "搜索会话" })).not.toBeInTheDocument();
    await screen.findByRole("button", { name: "当前对话" });
  });

  /** 侧栏折叠不影响服务端执行，外观设置也必须可以实际编辑。 */
  it("收起侧栏不卸载对话，设置弹层支持主题切换和键盘关闭", async () => {
    installServer();
    const user = userEvent.setup();
    const view = render(<App />);
    await screen.findByRole("textbox", { name: "消息" });
    await user.click(screen.getByRole("button", { name: "收起会话栏" }));
    expect(view.container.querySelector(".app-shell")).toHaveClass("sidebar-collapsed");
    expect(screen.getByRole("textbox", { name: "消息" })).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "打开会话栏" })[0]!);
    await user.click(screen.getByRole("button", { name: "设置" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "界面主题" }), "dark");
    await waitFor(() => expect(document.documentElement.dataset["theme"]).toBe("dark"));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("combobox", { name: "界面主题" })).not.toBeInTheDocument();
  });

  /** 失败后保留编辑内容；只有明确重试时，才应用控制器重新读取到的版本。 */
  it("重命名冲突原地保留编辑值，显式重试按最新观测版本保存", async () => {
    const { requests } = installServer();
    const user = userEvent.setup();
    render(<App />);
    await screen.findByRole("textbox", { name: "消息" });
    await user.click(screen.getByRole("button", { name: "会话操作：当前对话" }));
    await user.click(screen.getByRole("menuitem", { name: "重命名" }));
    const dialog = screen.getByRole("dialog", { name: "重命名会话" });
    const name = within(dialog).getByRole("textbox", { name: "会话名称" });
    await user.clear(name);
    await user.type(name, "冲突名称");
    await user.click(within(dialog).getByRole("button", { name: "保存" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("请读取最新版本后重试");
    expect(name).toHaveValue("冲突名称");
    await user.clear(name);
    await user.type(name, "已修改名称");
    await user.click(within(dialog).getByRole("button", { name: "保存" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(
      requests.filter((request) => request.action === "threads/update").at(-1)?.body,
    ).toMatchObject({ threadId: "thr_ui", expectedVersion: 4, title: "已修改名称" });
  });

  /** Composer 使用真实的 Skill 身份和队列控制；固定单模型不能让选择器变成无效控件。 */
  it("Skills 可移除、IME Enter 不提交，运行状态只保留一个停止入口", async () => {
    const user = userEvent.setup();
    const onSessionChange = vi.fn();
    const onSubmit = vi.fn();
    const onControl = vi.fn(async () => undefined);
    const turn = { ...emptyTurn("turn_ui", "处理任务", []), state: "RUNNING" as const };
    const view = render(
      <ConversationPane
        threadId="thr_ui"
        model="upstream-model"
        agentName="通用助手"
        experienceEnabled
        session={{
          ...emptySession(),
          historyLoaded: true,
          draft: "输入法候选",
          selectedSkills: ["demo-pg:brief-reply"],
          turns: [turn],
        }}
        onLoadMoreHistory={vi.fn(async () => undefined)}
        onSubmit={onSubmit}
        onSessionChange={onSessionChange}
        onAnswer={vi.fn(async () => undefined)}
        onResume={vi.fn(async () => undefined)}
        onRetry={vi.fn()}
        onControl={onControl}
        onWorkbench={vi.fn()}
        pendingRecoveries={{}}
        onRefreshRecoveries={async () => undefined}
      />,
    );
    fireEvent.keyDown(screen.getByRole("textbox", { name: "消息" }), {
      key: "Enter",
      keyCode: 229,
    });
    expect(onSubmit).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "移除 Skill brief-reply" }));
    expect(onSessionChange).toHaveBeenCalledWith("thr_ui", { selectedSkills: [] });
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus();
    expect(screen.getAllByRole("button", { name: "取消执行" })).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "取消执行" }));
    expect(onControl).toHaveBeenCalledWith("thr_ui", turn, "cancel");
    expect(view.container.querySelector(".composer-model")).toHaveTextContent("upstream-model");
  });

  /** 内容增长时跟随底部；用户向上阅读后保持原位置，直到明确返回底部。 */
  it("流式内容跟随底部，上翻时不抢位置，返回最新消息后恢复跟随", async () => {
    const props = {
      threadId: "thr_scroll",
      model: "test-model",
      session: {
        ...emptySession(),
        historyLoaded: true,
        turns: [emptyTurn("turn_scroll", "任务", [])],
      },
      onLoadMoreHistory: vi.fn(async () => undefined),
      onSubmit: vi.fn(),
      onSessionChange: vi.fn(),
      onAnswer: vi.fn(async () => undefined),
      onResume: vi.fn(async () => undefined),
      onRetry: vi.fn(),
      onControl: vi.fn(async () => undefined),
      onWorkbench: vi.fn(),
      pendingRecoveries: {},
      onRefreshRecoveries: vi.fn(async () => undefined),
    };
    const view = render(<ConversationPane {...props} />);
    const scroll = view.container.querySelector<HTMLElement>(".conversation-scroll")!;
    Object.defineProperties(scroll, {
      scrollHeight: { configurable: true, value: 900 },
      clientHeight: { configurable: true, value: 300 },
    });
    view.rerender(
      <ConversationPane
        {...props}
        session={{ ...props.session, turns: [...props.session.turns] }}
      />,
    );
    expect(scroll.scrollTop).toBe(900);
    scroll.scrollTop = 200;
    fireEvent.scroll(scroll);
    Object.defineProperty(scroll, "scrollHeight", { configurable: true, value: 1200 });
    view.rerender(
      <ConversationPane
        {...props}
        session={{ ...props.session, turns: [...props.session.turns] }}
      />,
    );
    expect(scroll.scrollTop).toBe(200);
    fireEvent.click(screen.getByRole("button", { name: "回到最新消息" }));
    expect(scroll.scrollTop).toBe(1200);
    Object.defineProperty(scroll, "scrollHeight", { configurable: true, value: 1500 });
    view.rerender(
      <ConversationPane
        {...props}
        session={{ ...props.session, turns: [...props.session.turns] }}
      />,
    );
    expect(scroll.scrollTop).toBe(1500);
  });
});
