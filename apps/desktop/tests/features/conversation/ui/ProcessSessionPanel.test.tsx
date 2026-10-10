// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ConversationProcessPort,
  ConversationProcessSession,
  ConversationProcessSnapshot,
} from "@/features/conversation/application/processSessionTypes";
import { ProcessSessionPanel } from "@/features/conversation/ui/timeline/ProcessSessionPanel";

const scope = { workspaceId: "ws_project", threadId: "thr_project" };
const session: ConversationProcessSession = {
  ...scope,
  generation: 3,
  sessionId: "proc_0123456789abcdef0123456789abcdef",
  startedTurnId: "turn_build",
  startedCallId: "call_build",
  state: "running",
  outputSequence: 5,
  outputTruncated: false,
  exitCode: null,
};

/** 构造与服务端相同的有界增量快照，测试不绕过 Tool 会话身份。 */
function snapshot(values: Partial<ConversationProcessSnapshot> = {}): ConversationProcessSnapshot {
  return {
    ...session,
    output: "ready",
    nextSequence: 5,
    ...values,
  };
}

/** UI 只通过这组窄端口调用 Java 会话服务，测试替身保留真实 RPC 参数形状。 */
function processPort(overrides: Partial<ConversationProcessPort> = {}): ConversationProcessPort {
  return {
    list: vi.fn().mockResolvedValue({ generation: 4, sessions: [session] }),
    read: vi.fn().mockResolvedValue(snapshot()),
    write: vi.fn().mockResolvedValue(snapshot()),
    stop: vi.fn().mockResolvedValue(snapshot({ state: "exited", exitCode: 0 })),
    ...overrides,
  };
}

describe("ProcessSessionPanel", () => {
  afterEach(cleanup);

  /** 旧 ToolResult 在请求会话快照前保持可读；匹配快照到达后只显示权威实时输出。 */
  it("keeps static output as a fallback and replaces it with the scoped session snapshot", async () => {
    let completeRead: ((value: ConversationProcessSnapshot) => void) | undefined;
    const read = vi.fn(
      () =>
        new Promise<ConversationProcessSnapshot>((resolve) => {
          completeRead = resolve;
        }),
    );
    const port = processPort({ read });
    render(
      <ProcessSessionPanel
        enabled
        callId="call_build"
        scope={scope}
        port={port}
        fallbackOutput="静态命令输出"
      />,
    );

    expect(screen.getByText("静态命令输出")).toBeVisible();
    await waitFor(() => expect(read).toHaveBeenCalledOnce());
    await act(async () => {
      completeRead?.(snapshot({ output: "实时命令输出" }));
    });

    expect(screen.getByText("实时命令输出")).toBeVisible();
    expect(screen.queryByText("静态命令输出")).not.toBeInTheDocument();
  });

  /** 会话 owner 暂时不可读时保留已持久化的静态诊断，并提供明确重试入口。 */
  it("keeps fallback output visible when the initial session read fails", async () => {
    const port = processPort({ list: vi.fn().mockRejectedValue(new Error("offline")) });
    render(
      <ProcessSessionPanel
        enabled
        callId="call_build"
        scope={scope}
        port={port}
        fallbackOutput="静态失败诊断"
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取命令会话。");
    expect(screen.getByText("静态失败诊断")).toBeVisible();
  });

  /** 空输入只有配合 closeInput=true 才作为 EOF 写入，且仍携带稳定幂等身份。 */
  it("allows closing stdin without sending text", async () => {
    const user = userEvent.setup();
    const write = vi.fn().mockResolvedValue(snapshot());
    const port = processPort({ write });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    await screen.findByText("ready");
    expect(screen.getByRole("button", { name: "发送输入" })).toBeDisabled();
    await user.click(screen.getByLabelText("发送后关闭输入"));
    const closeButton = screen.getByRole("button", { name: "关闭输入" });
    expect(closeButton).toBeEnabled();
    await user.click(closeButton);

    await waitFor(() => expect(write).toHaveBeenCalledOnce());
    expect(write).toHaveBeenCalledWith({
      ...scope,
      generation: 4,
      sessionId: session.sessionId,
      afterSequence: 5,
      input: "",
      closeInput: true,
      operationKey: expect.stringMatching(/^op_[0-9a-f]{32}$/u),
    });
  });

  /** 空输入关闭 stdin 并收到退出快照后，焦点从消失的按钮移到命令输出。 */
  it("returns focus to command output after stdin EOF", async () => {
    const user = userEvent.setup();
    const exited = snapshot({
      state: "exited",
      exitCode: 0,
      output: "EOF observed",
      nextSequence: 6,
    });
    const list = vi
      .fn()
      .mockResolvedValueOnce({ generation: 4, sessions: [session] })
      .mockResolvedValue({
        generation: 4,
        sessions: [{ ...session, state: "exited" as const, exitCode: 0 }],
      });
    const read = vi.fn().mockResolvedValueOnce(snapshot()).mockResolvedValue(exited);
    const port = processPort({ list, read, write: vi.fn().mockResolvedValue(exited) });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    await screen.findByText("ready");
    await user.click(screen.getByLabelText("发送后关闭输入"));
    await user.click(screen.getByRole("button", { name: "关闭输入" }));

    expect(await screen.findByText("进程已结束，退出码 0。")).toBeVisible();
    await waitFor(() => expect(screen.getByLabelText("命令输出")).toHaveFocus());
  });

  /** EOF 等待期间切到面板外操作后，迟到的退出快照不能抢回焦点。 */
  it("does not steal focus after the user leaves during stdin EOF", async () => {
    const user = userEvent.setup();
    let finishWrite: ((value: ConversationProcessSnapshot) => void) | undefined;
    const exited = snapshot({
      state: "exited",
      exitCode: 0,
      output: "EOF observed",
      nextSequence: 6,
    });
    const port = processPort({
      write: vi.fn(
        () =>
          new Promise<ConversationProcessSnapshot>((resolve) => {
            finishWrite = resolve;
          }),
      ),
    });
    render(
      <>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
        <textarea aria-label="消息" />
      </>,
    );

    await screen.findByText("ready");
    await user.click(screen.getByLabelText("发送后关闭输入"));
    await user.click(screen.getByRole("button", { name: "关闭输入" }));
    await waitFor(() => expect(port.write).toHaveBeenCalledOnce());
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    await act(async () => finishWrite?.(exited));

    expect(await screen.findByText("进程已结束，退出码 0。")).toBeVisible();
    expect(composer).toHaveFocus();
  });

  /** Thread 隐藏期间会话退出的迟到快照不能把焦点放进不可见的历史面板。 */
  it("does not focus a process output after its panel becomes hidden", async () => {
    const user = userEvent.setup();
    let finishWrite: ((value: ConversationProcessSnapshot) => void) | undefined;
    const exited = snapshot({
      state: "exited",
      exitCode: 0,
      output: "EOF observed",
      nextSequence: 6,
    });
    const port = processPort({
      write: vi.fn(
        () =>
          new Promise<ConversationProcessSnapshot>((resolve) => {
            finishWrite = resolve;
          }),
      ),
    });
    const view = render(
      <div>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
      </div>,
    );

    await screen.findByText("ready");
    await user.click(screen.getByLabelText("发送后关闭输入"));
    await user.click(screen.getByRole("button", { name: "关闭输入" }));
    await waitFor(() => expect(port.write).toHaveBeenCalledOnce());
    view.rerender(
      <div hidden>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
      </div>,
    );
    const output = view.container.querySelector(".ja-process-session__output");
    expect(output).not.toBeNull();
    await act(async () => finishWrite?.(exited));

    await waitFor(() => expect(output?.textContent).toContain("EOF observed"));
    expect(output).not.toHaveFocus();
  });

  /** Registry 暂时不返回已绑定的会话时保留最近快照与输出，并禁用过期写入入口。 */
  it("preserves the latest output when a bound session disappears from the registry", async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce({ generation: 4, sessions: [session] })
      .mockResolvedValue({ generation: 4, sessions: [] });
    const port = processPort({
      list,
      read: vi.fn().mockResolvedValue(snapshot({ output: "最近权威输出" })),
    });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    expect(await screen.findByText("最近权威输出")).toBeVisible();
    await waitFor(() => expect(screen.getByText("本次命令没有可继续的进程会话。")).toBeVisible(), {
      timeout: 2_500,
    });
    expect(screen.getByText("最近权威输出")).toBeVisible();
    expect(screen.queryByLabelText("输入到命令")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "停止进程" })).not.toBeInTheDocument();
  });

  /** 重试入口消失后将键盘焦点交给恢复出的命令输入框，避免落到页面 body。 */
  it("returns focus to the process input after a successful retry", async () => {
    const user = userEvent.setup();
    const list = vi
      .fn()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue({ generation: 4, sessions: [session] });
    const port = processPort({ list });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    await screen.findByRole("alert");
    await user.click(screen.getByRole("button", { name: "重试读取命令会话" }));
    await waitFor(() => expect(screen.getByLabelText("输入到命令")).toHaveFocus());
  });

  /** 重试期间用户改选其他控件后，异步读取完成不能抢回焦点。 */
  it("does not steal focus after the user moves it during retry", async () => {
    const user = userEvent.setup();
    let completeList:
      | ((page: { generation: number; sessions: readonly (typeof session)[] }) => void)
      | undefined;
    const list = vi
      .fn()
      .mockRejectedValueOnce(new Error("offline"))
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            completeList = resolve;
          }),
      );
    const port = processPort({ list });
    render(
      <>
        <button type="button">其他动作</button>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
      </>,
    );

    await screen.findByRole("alert");
    const retry = screen.getByRole("button", { name: "重试读取命令会话" });
    await user.click(retry);
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
    await user.click(screen.getByRole("button", { name: "其他动作" }));
    await act(async () => {
      completeList?.({ generation: 4, sessions: [session] });
    });

    await waitFor(() => expect(screen.getByRole("button", { name: "其他动作" })).toHaveFocus());
    expect(screen.getByLabelText("输入到命令")).not.toHaveFocus();
  });

  /** 同一请求游标去重输出；不确定的 stdin ACK 必须以原幂等身份重试。 */
  it("增量显示输出并使用原请求键重试 stdin", async () => {
    const user = userEvent.setup();
    const write = vi
      .fn()
      .mockRejectedValueOnce(new Error("transport lost"))
      .mockResolvedValueOnce(snapshot({ output: ">", nextSequence: 6, outputSequence: 6 }));
    const port = processPort({ write });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    expect(await screen.findByText("ready")).toBeVisible();
    await user.type(screen.getByLabelText("输入到命令"), "test{Enter}");
    await user.click(screen.getByRole("button", { name: "发送输入" }));
    expect(await screen.findByText(/输入结果尚未确认/u)).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重试发送" }));

    await waitFor(() => expect(write).toHaveBeenCalledTimes(2));
    const first = write.mock.calls[0]?.[0];
    const second = write.mock.calls[1]?.[0];
    expect(first?.operationKey).toMatch(/^op_[0-9a-f]{32}$/u);
    expect(second?.operationKey).toBe(first?.operationKey);
    expect(first?.afterSequence).toBe(5);
    await waitFor(() => expect(screen.getByText("ready>")).toBeVisible());
  });

  /** 停止请求绑定最近一次 registry generation 与会话身份，结束文案只由服务端快照决定。 */
  it("stops the active process with its scoped identity and waits for authoritative status", async () => {
    const user = userEvent.setup();
    const list = vi
      .fn()
      .mockResolvedValueOnce({ generation: 4, sessions: [session] })
      .mockResolvedValue({
        generation: 4,
        sessions: [{ ...session, state: "stopping" as const }],
      });
    const read = vi
      .fn()
      .mockResolvedValueOnce(snapshot())
      .mockResolvedValue(snapshot({ state: "stopping" }));
    const stop = vi.fn(async () => snapshot({ state: "stopping" }));
    const port = processPort({ list, read, stop });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    expect(await screen.findByText("ready")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "停止进程" }));

    await waitFor(() => expect(stop).toHaveBeenCalledOnce());
    expect(stop).toHaveBeenCalledWith({
      ...scope,
      generation: 4,
      sessionId: session.sessionId,
    });
    expect(await screen.findByText("正在停止进程…")).toBeVisible();
    expect(screen.queryByLabelText("输入到命令")).not.toBeInTheDocument();
  });

  /** 停止确认后将消失按钮焦点交给输出，方便键盘与读屏用户检查最终结果。 */
  it("returns focus to command output after stopping a process", async () => {
    const user = userEvent.setup();
    const exited = snapshot({ state: "exited", exitCode: 0, output: "stopped", nextSequence: 6 });
    const list = vi
      .fn()
      .mockResolvedValueOnce({ generation: 4, sessions: [session] })
      .mockResolvedValue({
        generation: 4,
        sessions: [{ ...session, state: "exited" as const, exitCode: 0 }],
      });
    const read = vi.fn().mockResolvedValueOnce(snapshot()).mockResolvedValue(exited);
    const port = processPort({ list, read, stop: vi.fn().mockResolvedValue(exited) });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    await screen.findByText("ready");
    await user.click(screen.getByRole("button", { name: "停止进程" }));

    expect(await screen.findByText("进程已结束，退出码 0。")).toBeVisible();
    await waitFor(() => expect(screen.getByLabelText("命令输出")).toHaveFocus());
  });

  /** 停止 RPC 等待期间用户切到 Composer 后，终态快照不得夺回焦点。 */
  it("does not steal focus after the user leaves during process stop", async () => {
    const user = userEvent.setup();
    let finishStop: ((value: ConversationProcessSnapshot) => void) | undefined;
    const exited = snapshot({ state: "exited", exitCode: 0, output: "stopped", nextSequence: 6 });
    const list = vi
      .fn()
      .mockResolvedValueOnce({ generation: 4, sessions: [session] })
      .mockResolvedValue({
        generation: 4,
        sessions: [{ ...session, state: "exited" as const, exitCode: 0 }],
      });
    const read = vi.fn().mockResolvedValueOnce(snapshot()).mockResolvedValue(exited);
    const port = processPort({
      list,
      read,
      stop: vi.fn(
        () =>
          new Promise<ConversationProcessSnapshot>((resolve) => {
            finishStop = resolve;
          }),
      ),
    });
    render(
      <>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
        <textarea aria-label="消息" />
      </>,
    );

    await screen.findByText("ready");
    await user.click(screen.getByRole("button", { name: "停止进程" }));
    await waitFor(() => expect(port.stop).toHaveBeenCalledOnce());
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    await act(async () => finishStop?.(exited));

    expect(await screen.findByText("进程已结束，退出码 0。")).toBeVisible();
    expect(composer).toHaveFocus();
  });

  /** Thread 切换会清除旧停止请求的焦点归还意图，迟到快照不能进入新会话。 */
  it("does not restore focus from a process after switching Threads", async () => {
    const user = userEvent.setup();
    let finishStop: ((value: ConversationProcessSnapshot) => void) | undefined;
    const exited = snapshot({ state: "exited", exitCode: 0, output: "stopped", nextSequence: 6 });
    const port = processPort({
      stop: vi.fn(
        () =>
          new Promise<ConversationProcessSnapshot>((resolve) => {
            finishStop = resolve;
          }),
      ),
    });
    const view = render(
      <>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
        <textarea aria-label="消息" />
      </>,
    );

    await screen.findByText("ready");
    await user.click(screen.getByRole("button", { name: "停止进程" }));
    await waitFor(() => expect(port.stop).toHaveBeenCalledOnce());
    const composer = screen.getByRole("textbox", { name: "消息" });
    view.rerender(
      <>
        <ProcessSessionPanel
          enabled
          callId="call_build"
          scope={{ ...scope, threadId: "thr_other" }}
          port={port}
        />
        <textarea aria-label="消息" />
      </>,
    );
    await user.click(composer);
    await act(async () => finishStop?.(exited));

    expect(composer).toHaveFocus();
    expect(screen.queryByText("进程已结束，退出码 0。")).not.toBeInTheDocument();
  });

  /** 卸载会话面板会撤销等待中的焦点归还，迟到 stop 结果不影响仍挂载的 Composer。 */
  it("does not restore focus after its process panel unmounts", async () => {
    const user = userEvent.setup();
    let finishStop: ((value: ConversationProcessSnapshot) => void) | undefined;
    const exited = snapshot({ state: "exited", exitCode: 0, output: "stopped", nextSequence: 6 });
    const port = processPort({
      stop: vi.fn(
        () =>
          new Promise<ConversationProcessSnapshot>((resolve) => {
            finishStop = resolve;
          }),
      ),
    });
    const view = render(
      <>
        <ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />
        <textarea aria-label="消息" />
      </>,
    );

    await screen.findByText("ready");
    await user.click(screen.getByRole("button", { name: "停止进程" }));
    await waitFor(() => expect(port.stop).toHaveBeenCalledOnce());
    view.rerender(<textarea aria-label="消息" />);
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    await act(async () => finishStop?.(exited));

    expect(composer).toHaveFocus();
  });

  /** 中断表示运行时重启时副作用未知；页面只提供检查提示和只读输出。 */
  it("对中断进程说明副作用未知并隐藏输入与停止动作", async () => {
    const port = processPort({
      list: vi.fn().mockResolvedValue({
        generation: 4,
        sessions: [{ ...session, state: "interrupted" as const }],
      }),
      read: vi.fn().mockResolvedValue(snapshot({ state: "interrupted" })),
    });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    expect(await screen.findByText(/副作用状态未知，请先检查工作区/u)).toBeVisible();
    expect(screen.queryByLabelText("输入到命令")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "停止进程" })).not.toBeInTheDocument();
  });

  /** 已确认退出的快照保留退出码，且不继续轮询或接受 stdin。 */
  it("结束后显示权威退出码", async () => {
    const port = processPort({
      list: vi.fn().mockResolvedValue({
        generation: 4,
        sessions: [{ ...session, state: "exited" as const, exitCode: 2 }],
      }),
      read: vi
        .fn()
        .mockResolvedValue(
          snapshot({ state: "exited", exitCode: 2, output: "failed", nextSequence: 6 }),
        ),
    });
    render(<ProcessSessionPanel enabled callId="call_build" scope={scope} port={port} />);

    expect(await screen.findByText("进程已结束，退出码 2。")).toBeVisible();
    expect(screen.queryByLabelText("输入到命令")).not.toBeInTheDocument();
  });
});
