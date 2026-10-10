// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 复用生产隔离 launcher，验证三协议 SSE 在真实 Java/Rust/React/WebView2 链路中的闭环。 */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { execFile } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { parseArguments } from "./conversation-recovery-webview2.mjs";
import { runProduction } from "./review-redesign-production.mjs";

const PROTOCOLS = ["openai_responses", "openai_chat_completions", "anthropic_messages"];
const FILE_MARKER = "JA_SSE_FILE_CONTENT";
const execFileAsync = promisify(execFile);

/** Anthropic 合并同角色消息；只取最后一个用户文本块，不能让已取消的旧场景污染当前请求。 */
export function latestUserPrompt(payload) {
  const messages = payload.input ?? payload.messages ?? [];
  const texts = messages
    .filter((item) => item.role === "user")
    .flatMap((item) =>
      typeof item.content === "string"
        ? [item.content]
        : (item.content ?? [])
            .filter((part) => ["text", "input_text"].includes(part.type))
            .map((part) => part.text),
    );
  return texts.at(-1) ?? "";
}

/** 只诊断本轮隔离 home 的 JVM，并包含虚拟线程；失败不影响 runner 的进程清理责任。 */
async function captureIsolatedThreadDump({ isolatedRuntimeHome, evidenceDirectory }, options) {
  const jcmd = join(options.javaHome, "bin", "jcmd.exe");
  const normalize = (path) => resolve(path.replace(/^\\\\\?\\/u, "")).toLowerCase();
  try {
    const { stdout } = await execFileAsync(jcmd, ["-l"], {
      windowsHide: true,
      timeout: 10_000,
    });
    const candidates = stdout.split(/\r?\n/u).filter((line) => {
      const home = /--home-dir-base64=([A-Za-z0-9_=-]+)/u.exec(line)?.[1];
      return (
        home !== undefined &&
        normalize(Buffer.from(home, "base64url").toString("utf8")) ===
          normalize(isolatedRuntimeHome)
      );
    });
    assert.equal(candidates.length, 1, "one isolated JVM must own the scenario");
    const pid = candidates[0].split(" ", 1)[0];
    await execFileAsync(
      jcmd,
      [pid, "Thread.dump_to_file", "-format=json", join(evidenceDirectory, "jvm-threads.json")],
      { windowsHide: true, timeout: 10_000 },
    );
  } catch (error) {
    await writeFile(
      join(evidenceDirectory, "thread-dump-error.txt"),
      String(error.message).slice(0, 500),
      "utf8",
    );
  }
}

/** 只编码 fixture JSON，不接触用户配置、凭据或真实 Provider。 */
function frame(type, data) {
  return `${type ? `event: ${type}\n` : ""}data: ${typeof data === "string" ? data : JSON.stringify(data)}\n\n`;
}

/** 三协议保持独立 wire，响应文本与 Tool 标识共同绑定当前场景。 */
function stream(protocol, model, marker, tool = false, stalled = false) {
  const argumentsValue = { path: "sse-fixture.txt", offset: 1, limit: 32 };
  const usage = { input_tokens: 20, output_tokens: 5, total_tokens: 25 };
  if (protocol === "openai_chat_completions") {
    const chunk = (delta, finish, extra = {}) =>
      frame(null, {
        id: "chatcmpl_sse",
        object: "chat.completion.chunk",
        created: 1,
        model,
        choices: [{ index: 0, delta, finish_reason: finish }],
        ...extra,
      });
    const delta = tool
      ? {
          tool_calls: [
            {
              index: 0,
              id: "call_sse_probe",
              type: "function",
              function: { name: "read", arguments: JSON.stringify(argumentsValue) },
            },
          ],
        }
      : { content: marker };
    return (
      chunk(delta, null) +
      (stalled
        ? ""
        : chunk({}, tool ? "tool_calls" : "stop") +
          frame(null, {
            id: "chatcmpl_sse",
            object: "chat.completion.chunk",
            created: 1,
            model,
            choices: [],
            usage: { prompt_tokens: 20, completion_tokens: 5, total_tokens: 25 },
          }) +
          frame(null, "[DONE]"))
    );
  }
  if (protocol === "anthropic_messages") {
    const event = (type, fields = {}) => frame(type, { type, ...fields });
    const start = event("message_start", {
      message: {
        id: "msg_sse",
        type: "message",
        role: "assistant",
        content: [],
        model,
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 20, output_tokens: 0 },
      },
    });
    const block = tool
      ? { type: "tool_use", id: "call_sse_probe", name: "read", input: argumentsValue }
      : { type: "text", text: "" };
    const content =
      start +
      event("content_block_start", { index: 0, content_block: block }) +
      (tool
        ? ""
        : event("content_block_delta", { index: 0, delta: { type: "text_delta", text: marker } }));
    return (
      content +
      (stalled
        ? ""
        : event("content_block_stop", { index: 0 }) +
          event("message_delta", {
            delta: { stop_reason: tool ? "tool_use" : "end_turn", stop_sequence: null },
            usage: { output_tokens: 5 },
          }) +
          event("message_stop"))
    );
  }
  let sequence = 0;
  const event = (type, fields = {}) =>
    frame(type, { type, sequence_number: sequence++, ...fields });
  const response = (status, output) => ({
    id: "resp_sse",
    object: "response",
    created_at: 1,
    model,
    status,
    output,
    tools: [],
    parallel_tool_calls: true,
    usage,
    error: null,
    incomplete_details: null,
  });
  const item = tool
    ? {
        id: "item_sse",
        type: "function_call",
        call_id: "call_sse_probe",
        name: "read",
        arguments: JSON.stringify(argumentsValue),
        status: "completed",
      }
    : {
        id: "message_sse",
        type: "message",
        role: "assistant",
        status: "completed",
        content: [{ type: "output_text", text: marker, annotations: [], logprobs: [] }],
      };
  const added = tool
    ? { ...item, arguments: "", status: "in_progress" }
    : { ...item, content: [], status: "in_progress" };
  const content =
    event("response.created", { response: response("in_progress", []) }) +
    event("response.output_item.added", { output_index: 0, item: added }) +
    (tool
      ? event("response.function_call_arguments.done", {
          output_index: 0,
          item_id: item.id,
          arguments: item.arguments,
        })
      : event("response.output_text.delta", {
          output_index: 0,
          content_index: 0,
          item_id: item.id,
          delta: marker,
          logprobs: [],
        }));
  return (
    content +
    (stalled
      ? ""
      : (tool
          ? ""
          : event("response.output_text.done", {
              output_index: 0,
              content_index: 0,
              item_id: item.id,
              text: marker,
              logprobs: [],
            })) +
        event("response.output_item.done", { output_index: 0, item }) +
        event("response.completed", { response: response("completed", [item]) }))
  );
}

/** 公开思考已完成但缺少 opaque 载荷时，由真实 DOM 验收驱动后续正文与终态分开送达。 */
function summaryOnlyStream(response, model) {
  let sequence = 0;
  let text = "";
  const reasoning = {
    id: "reason_sse_summary_only",
    type: "reasoning",
    summary: [{ type: "summary_text", text: "准备生成总结" }],
  };
  const message = {
    id: "message_sse_summary_only",
    type: "message",
    role: "assistant",
    status: "completed",
    content: [],
  };
  /** fixture 的两个输出项保持原生坐标，避免以伪协议跳过 Adapter 顺序屏障。 */
  const write = (type, fields) =>
    response.write(frame(type, { type, sequence_number: sequence++, ...fields }));
  /** 终态和创建帧使用同一响应身份，终态 opaque 补回必须保持前序 reasoning 位置。 */
  const envelope = (status, output) => ({
    id: "resp_sse_summary_only",
    object: "response",
    created_at: 1,
    model,
    status,
    output,
    tools: [],
    parallel_tool_calls: true,
    usage: { input_tokens: 20, output_tokens: 5, total_tokens: 25 },
    error: null,
    incomplete_details: null,
  });
  write("response.created", { response: envelope("in_progress", []) });
  write("response.output_item.added", { output_index: 0, item: { ...reasoning, summary: [] } });
  write("response.reasoning_summary_text.delta", {
    output_index: 0,
    item_id: reasoning.id,
    summary_index: 0,
    delta: "准备生成总结",
  });
  write("response.reasoning_summary_text.done", {
    output_index: 0,
    item_id: reasoning.id,
    summary_index: 0,
    text: "准备生成总结",
  });
  write("response.output_item.done", { output_index: 0, item: reasoning });
  write("response.output_item.added", {
    output_index: 1,
    item: { ...message, status: "in_progress" },
  });
  return {
    /** 每段均独立写入；调用方先证明片段进真实 DOM，再允许发送下一段。 */
    append(delta) {
      text += delta;
      write("response.output_text.delta", {
        output_index: 1,
        content_index: 0,
        item_id: message.id,
        delta,
        logprobs: [],
      });
    },
    /** 回补有效 opaque 后才发送完整终态，验证公开流和内部模型历史互不阻塞。 */
    complete() {
      message.content = [{ type: "output_text", text, annotations: [], logprobs: [] }];
      write("response.output_text.done", {
        output_index: 1,
        content_index: 0,
        item_id: message.id,
        text,
        logprobs: [],
      });
      write("response.output_item.done", { output_index: 1, item: message });
      write("response.completed", {
        response: envelope("completed", [
          { ...reasoning, encrypted_content: "opaque_sse_terminal" },
          message,
        ]),
      });
      response.end();
    },
  };
}

/** 仅监听 loopback；停止与 summary-only 场景由 DOM 验收逐片推进，证明正文不依赖终态。 */
async function startFixture(protocol) {
  const facts = {
    toolCalls: 0,
    toolResultSeen: false,
    recoverAttempts: 0,
    failedDraftReplayed: false,
    lateReasoningReplayedBeforeAnswer: null,
    cancelClosed: false,
    terminalClosed: 0,
  };
  const sockets = new Set();
  let activeStream;
  let activeSummaryOnly;
  const server = createServer(async (request, response) => {
    try {
      let raw = "";
      for await (const chunk of request) {
        raw += chunk;
        assert.ok(raw.length < 2_000_000, "fixture request exceeds budget");
      }
      const payload = JSON.parse(raw);
      const prompt = latestUserPrompt(payload);
      // 标题生成也会带用户正文；仅 Agent 请求具备工具定义，避免把后台标题误计为执行。
      const agentRequest = Array.isArray(payload.tools) && payload.tools.length > 0;
      if (
        protocol === "openai_responses" &&
        agentRequest &&
        prompt.includes("JA_SSE_SUMMARY_ONLY")
      ) {
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        activeSummaryOnly = summaryOnlyStream(response, payload.model);
        activeSummaryOnly.append("JA_SSE_SUMMARY_FIRST 中文总结逐段显示。");
        response.once("close", () => {
          activeSummaryOnly = undefined;
          facts.terminalClosed++;
        });
        return;
      }
      const cancel = agentRequest && prompt.includes("JA_SSE_CANCEL");
      const recover = agentRequest && prompt.includes("JA_SSE_RECOVER");
      const toolScenario = agentRequest && prompt.includes("JA_SSE_TOOL");
      if (recover && ++facts.recoverAttempts === 1) {
        response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
        response.end(stream(protocol, payload.model, "JA_SSE_INTERRUPTED", false, true));
        return;
      }
      if (recover) {
        facts.failedDraftReplayed = raw.includes("JA_SSE_INTERRUPTED");
        assert.equal(
          facts.failedDraftReplayed,
          false,
          "failed draft must not enter next model context",
        );
        // 终态才到达的原生块必须按原坐标回放，不能因为公开正文先显示而排到正文之后。
        if (protocol === "openai_responses") {
          const reasoningIndex = payload.input.findIndex(
            (item) => item.type === "reasoning" && item.encrypted_content === "opaque_sse_terminal",
          );
          const answerIndex = payload.input.findIndex((item) =>
            JSON.stringify(item).includes("JA_SSE_SUMMARY_FIRST"),
          );
          facts.lateReasoningReplayedBeforeAnswer =
            reasoningIndex >= 0 && answerIndex > reasoningIndex;
          assert.equal(facts.lateReasoningReplayedBeforeAnswer, true);
        }
      }
      const tool = toolScenario && !raw.includes(FILE_MARKER);
      if (tool) facts.toolCalls++;
      if (toolScenario && !tool) facts.toolResultSeen = true;
      const marker = cancel
        ? `JA_SSE_CANCEL_STARTED_${protocol}`
        : recover
          ? `JA_SSE_RECOVERED_${protocol}`
          : `JA_SSE_COMPLETED_${protocol}`;
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-store",
      });
      response.write(stream(protocol, payload.model, marker, tool, cancel));
      let heartbeat;
      if (cancel) {
        activeStream = { response, model: payload.model, sequence: 3 };
        heartbeat = setInterval(() => response.write(": heartbeat\n\n"), 100);
      }
      response.once("close", () => {
        clearInterval(heartbeat);
        if (activeStream?.response === response) activeStream = undefined;
        if (cancel) facts.cancelClosed = true;
        else facts.terminalClosed++;
      });
    } catch (error) {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: String(error.message).slice(0, 200) }));
    }
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.once("close", () => sockets.delete(socket));
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  return {
    facts,
    baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
    /** 仅在前段已可见后推进下一段，控制终态未到达的可验证时间窗口。 */
    appendSummaryOnlyText(text) {
      assert.ok(activeSummaryOnly, "summary-only stream must be open");
      activeSummaryOnly.append(text);
    },
    /** 单独释放终态，让测试先检查 Markdown 尾部与第二段正文的实时可见性。 */
    completeSummaryOnly() {
      assert.ok(activeSummaryOnly, "summary-only stream must be open");
      activeSummaryOnly.complete();
    },
    /** 第一片已进入真实 DOM 后才发送第二片，不用固定 sleep 猜测流式刷新。 */
    appendCancelText(text) {
      assert.ok(activeStream, "cancel stream must be open");
      const { response, model } = activeStream;
      if (protocol === "anthropic_messages") {
        response.write(
          frame("content_block_delta", {
            type: "content_block_delta",
            index: 0,
            delta: { type: "text_delta", text },
          }),
        );
      } else if (protocol === "openai_chat_completions") {
        response.write(
          frame(null, {
            id: "chatcmpl_sse",
            object: "chat.completion.chunk",
            created: 1,
            model,
            choices: [{ index: 0, delta: { content: text }, finish_reason: null }],
          }),
        );
      } else {
        response.write(
          frame("response.output_text.delta", {
            type: "response.output_text.delta",
            sequence_number: activeStream.sequence++,
            output_index: 0,
            content_index: 0,
            item_id: "message_sse",
            delta: text,
            logprobs: [],
          }),
        );
      }
    },
    /** 只回收 fixture 自己的 socket，避免影响用户运行中的 Ja。 */
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise((done) => server.close(done));
    },
  };
}

/** 有限等待只读取条件，不以固定延迟假定 UI 或 socket 已完成。 */
async function until(label, predicate, timeout = 30_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 刷新后通过真实项目和精确 Thread 控件恢复，验证 SQLite 历史进入应用 DOM。 */
async function selectThread(page, threadId) {
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  await project.waitFor();
  await project.click();
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor();
  await row.click();
}

/** 首问保持运行以核对即时标题和两次流式刷新；所有发送与停止均使用真实 Composer。 */
async function runScenario({ page, workspaceRoot, evidenceDirectory }, protocol, fixture) {
  await mkdir(evidenceDirectory, { recursive: true });
  await writeFile(join(workspaceRoot, "sse-fixture.txt"), `${FILE_MARKER}\n`, "utf8");
  await page.setViewportSize({ width: 1280, height: 820 });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  await page.waitForFunction(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const state = await new TauriRuntimeHostAdapter().state();
    return ["ready", "busy"].includes(state.status);
  });
  const created = await page.evaluate(
    async ({ cwd }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      return createHistoryAdapter().threadCreate({
        cwd,
        title: "新对话",
        providerId: "provider_e2e",
        modelId: "model_e2e",
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
    },
    { cwd: workspaceRoot },
  );
  await selectThread(page, created.threadId);
  /** 用产品发送控件发起每个场景，避免直接调用 turn/start 绕过 Composer 状态。 */
  const send = async (prompt) => {
    const input = page.getByRole("textbox", { name: "消息", exact: true });
    await input.fill(prompt);
    await page.getByRole("button", { name: "发送", exact: true }).click();
  };
  /** 以最终响应状态判定结算，避免把用户输入中的同名标记当作模型结果。 */
  const final = (marker) =>
    page
      .locator('.ja-chat-message-final[data-response-state="completed"]')
      .filter({ hasText: marker })
      .last();
  await send("JA_SSE_CANCEL 请持续输出。");
  let streamingError;
  try {
    await until("streaming content", async () =>
      (await page.locator("body").innerText()).includes(`JA_SSE_CANCEL_STARTED_${protocol}`),
    );
    const row = page.locator(
      `[aria-label="最近对话列表"] button[data-thread-id="${created.threadId}"]`,
    );
    await until("first-input title before terminal", async () =>
      (await row.innerText()).includes("JA_SSE_CANCEL"),
    );
    const second = ` JA_SSE_SECOND_DELTA_${protocol}`;
    fixture.appendCancelText(second);
    await until("second streaming delta before terminal", async () =>
      (await page.locator("body").innerText()).includes(second.trim()),
    );
    assert.equal(await final(`JA_SSE_CANCEL_STARTED_${protocol}`).count(), 0);
    await page.screenshot({ path: join(evidenceDirectory, "streaming-before-terminal.png") });
  } catch (error) {
    streamingError = String(error.message);
    await page.screenshot({ path: join(evidenceDirectory, "streaming-failure.png") });
    const state = await page.evaluate(async () => {
      const { useTimelineStore } = await import(
        "/src/features/conversation/application/timelineStore.ts"
      );
      return useTimelineStore.getState().resyncRequired;
    });
    await writeFile(
      join(evidenceDirectory, "streaming-recovery-state.json"),
      JSON.stringify(state, null, 2),
      "utf8",
    );
  }
  await page.getByRole("button", { name: "停止生成", exact: true }).click();
  await until("cancel closes socket", () => fixture.facts.cancelClosed);
  await until("composer restored", async () => {
    // 取消后的空 Composer 复用图标位显示“继续回复”；两种真实就绪动作均须可操作。
    const sendButton = page.getByRole("button", { name: "发送", exact: true });
    const continueButton = page.getByRole("button", { name: "继续回复", exact: true });
    return (
      !(await page.getByRole("button", { name: "停止生成", exact: true }).isVisible()) &&
      (((await sendButton.isVisible()) && (await sendButton.isEnabled())) ||
        ((await continueButton.isVisible()) && (await continueButton.isEnabled())))
    );
  });
  await send("JA_SSE_TOOL 请读取 sse-fixture.txt 并完成答复。");
  await final(`JA_SSE_COMPLETED_${protocol}`).waitFor({ timeout: 30_000 });
  assert.equal(fixture.facts.toolCalls, 1);
  assert.equal(fixture.facts.toolResultSeen, true);
  await page.screenshot({ path: join(evidenceDirectory, "tool-completed.png") });
  if (protocol === "openai_responses") {
    await send("JA_SSE_SUMMARY_ONLY 请逐段生成总结。");
    await until("summary-only first text before terminal", async () =>
      (await page.locator("body").innerText()).includes("JA_SSE_SUMMARY_FIRST"),
    );
    assert.equal(await final("JA_SSE_SUMMARY_FIRST").count(), 0);
    fixture.appendSummaryOnlyText("\n\n**JA_SSE_SUMMARY_SECOND 中文与 Emoji 🙂");
    await until("summary-only open Markdown before terminal", async () =>
      (await page.locator("body").innerText()).includes("JA_SSE_SUMMARY_SECOND"),
    );
    assert.equal(await final("JA_SSE_SUMMARY_FIRST").count(), 0);
    await page.screenshot({ path: join(evidenceDirectory, "summary-only-before-terminal.png") });
    fixture.appendSummaryOnlyText(" 连续显示。**");
    await until("summary-only closing Markdown before terminal", async () =>
      (await page.locator("body").innerText()).includes("连续显示。"),
    );
    fixture.completeSummaryOnly();
    await final("JA_SSE_SUMMARY_FIRST").waitFor({ timeout: 30_000 });
    assert.equal(await final("JA_SSE_SUMMARY_FIRST").count(), 1);
    await page.screenshot({ path: join(evidenceDirectory, "summary-only-completed.png") });
  }
  await send("JA_SSE_RECOVER 请继续答复。");
  await final(`JA_SSE_RECOVERED_${protocol}`).waitFor({ timeout: 30_000 });
  assert.equal(fixture.facts.recoverAttempts, 2);
  assert.equal(fixture.facts.failedDraftReplayed, false);
  assert.ok(
    !(await final(`JA_SSE_RECOVERED_${protocol}`).innerText()).includes("JA_SSE_INTERRUPTED"),
  );
  await selectThread(page, created.threadId);
  await final(`JA_SSE_COMPLETED_${protocol}`).waitFor();
  await final(`JA_SSE_RECOVERED_${protocol}`).waitFor();
  const history = await page.evaluate(async (threadId) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    return createHistoryAdapter().threadRead({ threadId, limit: 200 });
  }, created.threadId);
  const serialized = JSON.stringify(history);
  assert.ok(serialized.includes(`JA_SSE_COMPLETED_${protocol}`));
  assert.ok(serialized.includes(`JA_SSE_RECOVERED_${protocol}`));
  assert.ok(serialized.includes(FILE_MARKER), "Tool result must survive history read");
  assert.equal(fixture.facts.toolCalls, 1, "reload must not execute Tool again");
  await page.screenshot({ path: join(evidenceDirectory, "history-restored.png") });
  return {
    verdict: streamingError === undefined ? "PASS" : "FAIL",
    error: streamingError,
    protocol,
    streaming: streamingError === undefined,
    titleBeforeTerminal: streamingError === undefined,
    multipleDeltasBeforeTerminal: streamingError === undefined,
    summaryOnlyReasoningStreaming: protocol === "openai_responses" ? true : null,
    toolContinuation: true,
    recovery: true,
    cancellation: true,
    historyReload: true,
    fixture: { ...fixture.facts },
  };
}

/** 三协议依次运行，复用隔离构建目录并为每次启动生成独立 profile 和数据库。 */
async function main() {
  const argumentsList = process.argv.slice(2);
  const selectors = argumentsList.filter((argument) => argument.startsWith("--protocol="));
  assert.ok(selectors.length <= 1, "select at most one protocol");
  const selected = selectors[0]?.slice("--protocol=".length);
  assert.ok(selected === undefined || PROTOCOLS.includes(selected), "unknown protocol selector");
  const protocols = selected === undefined ? PROTOCOLS : [selected];
  const options = parseArguments(argumentsList.filter((argument) => !selectors.includes(argument)));
  const reports = {};
  for (const protocol of protocols) {
    const fixture = await startFixture(protocol);
    try {
      reports[protocol] = await runProduction({
        ...options,
        providerBaseUrl: fixture.baseUrl,
        evidenceDirectory: join(options.evidenceDirectory, protocol),
        hiddenWindow: true,
        prewarmWebview: options.edgeDriver === undefined,
        preserveFailedProfile: true,
        scope: "git",
        fixture: "no-head",
        ignoredFiles: 0,
        untrackedFiles: 0,
        reportFileName: "sse-report.json",
        /** 修改本轮临时 profile 的协议，真实用户配置保持独立。 */
        prepareIsolatedHome: async ({ home }) => {
          const file = join(home, "config.toml");
          const config = await readFile(file, "utf8");
          await writeFile(
            file,
            config.replace('api = "openai_responses"', `api = "${protocol}"`),
            "utf8",
          );
        },
        /** 失败时保留真实 DOM 和 fixture 事实，区分测试 wire 错误与产品失败。 */
        driver: async (driverOptions) => {
          try {
            return await runScenario(driverOptions, protocol, fixture);
          } catch (error) {
            await captureIsolatedThreadDump(driverOptions, options);
            await writeFile(
              join(driverOptions.evidenceDirectory, "failure-ui.txt"),
              await driverOptions.page.locator("body").innerText(),
              "utf8",
            );
            await writeFile(
              join(driverOptions.evidenceDirectory, "fixture-facts.json"),
              JSON.stringify(fixture.facts, null, 2),
              "utf8",
            );
            await driverOptions.page.screenshot({
              path: join(driverOptions.evidenceDirectory, "failure.png"),
            });
            throw error;
          }
        },
      });
      process.stdout.write(`JA_SSE_WEBVIEW2_PROTOCOL_${reports[protocol].verdict} ${protocol}\n`);
    } catch (error) {
      reports[protocol] = {
        verdict: "FAIL",
        protocol,
        error: String(error.message),
        fixture: { ...fixture.facts },
      };
      process.stderr.write(`JA_SSE_WEBVIEW2_PROTOCOL_FAIL ${protocol}: ${error.message}\n`);
    } finally {
      await fixture.close();
    }
  }
  await mkdir(options.evidenceDirectory, { recursive: true });
  await writeFile(
    join(options.evidenceDirectory, "sse-suite-report.json"),
    `${JSON.stringify(
      {
        verdict: Object.values(reports).every((report) => report.verdict === "PASS")
          ? "PASS"
          : "FAIL",
        boundary: "Windows Tauri/WebView2 + JVM JAR + loopback Provider",
        coveredProtocols: protocols,
        reports,
        realProviderVerified: false,
        nativeImageVerified: false,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
  if (Object.values(reports).some((report) => report.verdict !== "PASS")) process.exitCode = 1;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    process.stderr.write(`JA_SSE_WEBVIEW2_FAIL ${String(error.message).slice(0, 2_000)}\n`);
    process.exitCode = 1;
  });
}
