// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 侧聊的 Windows/WebView2 真窗验收驱动。
 *
 * 驱动只连接 launcher 显式提供的隔离 CDP endpoint；loopback Provider、Tauri bridge
 * 和真实 DOM 都在验收边界内。脚本不读取 React 内部状态，也不把侧聊的状态回流到主
 * 会话作为“通过”证据。初始阶段结束后由 launcher 重启同一隔离 profile，再验证关闭的
 * 侧聊不会被恢复，而主会话和隔离 workspace 文件仍然存在。
 */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import process from "node:process";
import { chromium } from "@playwright/test";
import { assertSideChatCloseReceipt } from "./side-chat-close-evidence.mjs";

const endpoint = process.env.JA_E2E_SIDE_CHAT_CDP_ENDPOINT?.trim();
const evidenceDirectory = process.env.JA_E2E_SIDE_CHAT_ARTIFACT_DIR?.trim();
const isolated = process.env.JA_E2E_SIDE_CHAT_ISOLATED === "1";
const phase = process.env.JA_E2E_SIDE_CHAT_PHASE?.trim() || "initial";
const scenario = process.env.JA_E2E_SIDE_CHAT_SCENARIO?.trim() || "full";
const backendMode = process.env.JA_E2E_SIDE_CHAT_BACKEND_MODE?.trim() || "jvm";
const normalExitRequested = process.env.JA_E2E_SIDE_CHAT_NORMAL_EXIT === "1";
const subagentOnly = process.env.JA_E2E_SIDE_CHAT_SUBAGENT_ONLY === "1";
const retainedFile = process.env.JA_E2E_SIDE_CHAT_RETAINED_FILE?.trim();
const approvalWriteDirectory = process.env.JA_E2E_SIDE_CHAT_APPROVAL_WRITE_DIR?.trim();
const messageCommand =
  process.env.JA_E2E_SIDE_CHAT_MESSAGE_COMMAND?.trim() || "ja_runtime_task_message_send";
const closeCommand = process.env.JA_E2E_SIDE_CHAT_CLOSE_COMMAND?.trim() || "ja_runtime_task_close";
const threadListCommand =
  process.env.JA_E2E_SIDE_CHAT_THREAD_LIST_COMMAND?.trim() || "ja_thread_list";
const threadListScope = process.env.JA_E2E_SIDE_CHAT_THREAD_LIST_SCOPE?.trim() || "all";

const ROOT_MARKER = "JA_SIDE_CHAT_PARENT_CONTEXT";
const SIDE_FIRST_MARKER = "JA_SIDE_CHAT_CHILD_FIRST";
const SIDE_SECOND_MARKER = "JA_SIDE_CHAT_CHILD_SECOND";
const SIDE_RUNNING_MARKER = "JA_SIDE_CHAT_CHILD_RUNNING";
const SIDE_AFTER_RUNNING_MARKER = "JA_SIDE_CHAT_CHILD_AFTER_RUNNING";
const SIDE_PLAN_MARKER = "JA_SIDE_CHAT_SIDE_PLAN";
const BTW_MARKER = "JA_SIDE_CHAT_BTW_CONTENT";
const MAILBOX_MARKER = "JA_SIDE_CHAT_MAILBOX";
const RUNNING_MAILBOX_MARKER = "JA_SIDE_CHAT_RUNNING_MAILBOX";
const SPAWN_MARKER = "JA_SIDE_CHAT_SPAWN_AGENT";
const SUBAGENT_MARKER = "JA_SIDE_CHAT_SUBAGENT_BRIEF";
const APPROVAL_FIXTURES = [
  {
    key: "allow",
    taskName: "审批允许回归",
    spawnMarker: "JA_SUBAGENT_APPROVAL_ALLOW_SPAWN",
    childMarker: "JA_SUBAGENT_APPROVAL_ALLOW_CHILD",
    callId: "call_approval_allow_exec",
    fileName: "approval-allow.txt",
    fileValue: "JA_APPROVAL_ALLOW_FILE",
    verified: "JA_APPROVAL_ALLOW_WRITE_VERIFIED",
  },
  {
    key: "deny",
    taskName: "审批拒绝回归",
    spawnMarker: "JA_SUBAGENT_APPROVAL_DENY_SPAWN",
    childMarker: "JA_SUBAGENT_APPROVAL_DENY_CHILD",
    callId: "call_approval_deny_exec",
    fileName: "approval-deny.txt",
    fileValue: "JA_APPROVAL_DENY_FILE",
    verified: "JA_APPROVAL_DENY_WRITE_VERIFIED",
  },
  {
    key: "cancel",
    taskName: "审批取消回归",
    spawnMarker: "JA_SUBAGENT_APPROVAL_CANCEL_SPAWN",
    childMarker: "JA_SUBAGENT_APPROVAL_CANCEL_CHILD",
    callId: "call_approval_cancel_exec",
    fileName: "approval-cancel.txt",
    fileValue: "JA_APPROVAL_CANCEL_FILE",
    verified: "JA_APPROVAL_CANCEL_WRITE_VERIFIED",
  },
  {
    key: "resume",
    taskName: "审批到期恢复回归",
    spawnMarker: "JA_SUBAGENT_APPROVAL_RESUME_SPAWN",
    childMarker: "JA_SUBAGENT_APPROVAL_RESUME_CHILD",
    callId: "call_approval_resume_exec",
    fileName: "approval-resume.txt",
    fileValue: "JA_APPROVAL_RESUME_FILE",
    verified: "JA_APPROVAL_RESUME_WRITE_VERIFIED",
  },
];
const PRIMARY_MODEL = "ja-side-primary";
const SECONDARY_MODEL = "ja-side-secondary";

/** 只接受 Windows 隔离 CDP 和临时证据目录，避免误连用户当前 Ja 窗口。 */
function validateEnvironment() {
  if (process.platform !== "win32" || !isolated)
    throw new Error("侧聊真窗验收必须在 Windows 隔离实例中设置 JA_E2E_SIDE_CHAT_ISOLATED=1");
  if (!/^http:\/\/(?:127\.0\.0\.1|localhost):\d+\/?$/u.test(endpoint ?? ""))
    throw new Error("JA_E2E_SIDE_CHAT_CDP_ENDPOINT 必须是 loopback CDP endpoint");
  if (evidenceDirectory === undefined || !isAbsolute(evidenceDirectory))
    throw new Error("JA_E2E_SIDE_CHAT_ARTIFACT_DIR 必须是绝对目录");
  if (retainedFile === undefined || !isAbsolute(retainedFile))
    throw new Error("JA_E2E_SIDE_CHAT_RETAINED_FILE 必须是绝对隔离文件路径");
  if (!new Set(["initial", "restart"]).has(phase))
    throw new Error(`不支持的侧聊验收阶段：${phase}`);
  if (!new Set(["full", "approvals"]).has(scenario))
    throw new Error(`不支持的侧聊验收场景：${scenario}`);
  if (!new Set(["jvm", "native"]).has(backendMode))
    throw new Error(`不支持的侧聊后端模式：${backendMode}`);
  if (scenario === "approvals" && phase !== "initial")
    throw new Error("审批专项只支持独立 initial 阶段");
  if (scenario === "approvals" && (approvalWriteDirectory === undefined || !isAbsolute(approvalWriteDirectory)))
    throw new Error("审批专项需要隔离的绝对写入目录");
}

/** PowerShell 单引号字面量转义只用于 runner 创建的短期隔离路径，不在报告回显该路径。@author kongweiguang */
function escapePowerShellLiteral(value) {
  return value.replaceAll("'", "''");
}

/** 审批回归只触及 runner 的临时目录，并在获准后回读标记证明副作用真的完成。@author kongweiguang */
function buildApprovalWriteCommand(fixture) {
  if (approvalWriteDirectory === undefined || !isAbsolute(approvalWriteDirectory))
    throw new Error("审批 Provider 没有隔离写入目录");
  const target = escapePowerShellLiteral(join(approvalWriteDirectory, fixture.fileName));
  const expected = escapePowerShellLiteral(fixture.fileValue);
  const verified = escapePowerShellLiteral(fixture.verified);
  return `$target='${target}'; Set-Content -LiteralPath $target -Value '${expected}' -NoNewline -Encoding utf8; if ((Get-Content -LiteralPath $target -Raw) -ceq '${expected}') { Write-Output '${verified}' } else { exit 17 }`;
}

/** 将 Tauri 错误压缩为稳定 code/message，避免证据文件写入路径、凭据或正文。 */
function safeError(error) {
  if (error === null || typeof error !== "object") return { message: String(error) };
  const candidate = error;
  const nested =
    candidate.error !== null && typeof candidate.error === "object" ? candidate.error : {};
  const code = [candidate.code, candidate.errorCode, nested.code].find(
    (value) => typeof value === "string" && /^[A-Z][A-Z0-9_]{2,63}$/u.test(value),
  );
  return { code: code ?? "RPC_REJECTED", message: "Tauri command rejected" };
}

/** 通过真实 Tauri bridge 执行一个专用 command；返回 envelope 以便测试错误边界。 */
async function invoke(page, command, input) {
  return page.evaluate(
    async ({ commandName, commandInput }) => {
      const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function")
        return { ok: false, error: { code: "TAURI_BRIDGE_UNAVAILABLE" } };
      try {
        return { ok: true, value: await invoke(commandName, { input: commandInput }) };
      } catch (error) {
        const candidate = error !== null && typeof error === "object" ? error : {};
        const nested =
          candidate.error !== null && typeof candidate.error === "object" ? candidate.error : {};
        const code = [candidate.code, candidate.errorCode, nested.code].find(
          (value) => typeof value === "string" && /^[A-Z][A-Z0-9_]{2,63}$/u.test(value),
        );
        return { ok: false, error: { code: code ?? "RPC_REJECTED" } };
      }
    },
    { commandName: command, commandInput: input },
  );
}

/** 在错误仍有意义时才抛出，避免把“未找到已关闭侧聊”误判成脚本异常。 */
function requireInvoke(result, label) {
  if (result?.ok !== true) {
    const error = new Error(`${label} 失败：${result?.error?.code ?? "RPC_REJECTED"}`);
    error.code = result?.error?.code ?? "RPC_REJECTED";
    throw error;
  }
  return result.value;
}

/** 用有界轮询等待最终条件，避免固定 sleep 掩盖 WebView/Provider 竞态。 */
async function waitForCondition(label, predicate, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`${label} 超时`);
}

/** 在浏览器上下文观察 Composer 的公开 busy 状态边沿；DOM 构造器来自该页面，Tab 提前出现时仍等真实 Slash Promise 收敛。@author kongweiguang */
async function armComposerBusyCycle(page, composer) {
  const token = `side-chat-command-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  await composer.evaluate((element, probeToken) => {
    const form = element.matches("form.ja-composer")
      ? element
      : element.querySelector("form.ja-composer");
    if (!(form instanceof globalThis.HTMLFormElement)) throw new Error("Composer 表单不可观察");
    const probes =
      globalThis.__JA_SIDE_CHAT_COMMAND_BUSY_PROBES__ ??
      (globalThis.__JA_SIDE_CHAT_COMMAND_BUSY_PROBES__ = new Map());
    const entry = { phase: form.getAttribute("aria-busy") === "true" ? "busy" : "waiting" };
    const observer = new globalThis.MutationObserver(() => {
      if (form.getAttribute("aria-busy") === "true") {
        entry.phase = "busy";
      } else if (entry.phase === "busy") {
        entry.phase = "settled";
        observer.disconnect();
      }
    });
    observer.observe(form, { attributes: true, attributeFilter: ["aria-busy"] });
    probes.set(probeToken, { entry, observer });
  }, token);
  return token;
}

/** 仅以可访问 busy 状态的 true→false 转换作为命令完成，不用固定 sleep 掩盖异步时序。@author kongweiguang */
async function waitForComposerBusyCycle(page, token, label) {
  try {
    await waitForCondition(`${label}结束`, async () =>
      page.evaluate((probeToken) => {
        const probe = globalThis.__JA_SIDE_CHAT_COMMAND_BUSY_PROBES__?.get(probeToken);
        return probe?.entry.phase === "settled";
      }, token),
    );
  } finally {
    await page.evaluate((probeToken) => {
      const probes = globalThis.__JA_SIDE_CHAT_COMMAND_BUSY_PROBES__;
      const probe = probes?.get(probeToken);
      probe?.observer.disconnect();
      probes?.delete(probeToken);
    }, token).catch(() => undefined);
  }
}

/** 等待真实页面动画收敛，截图只记录稳定组件和全局样式。 */
async function settleVisuals(page) {
  await page.evaluate(async () => {
    await Promise.all(
      globalThis.document
        .getAnimations()
        .filter((animation) =>
          Number.isFinite(animation.effect?.getComputedTiming().endTime ?? Infinity),
        )
        .map((animation) => animation.finished.catch(() => undefined)),
    );
  });
}

/** 保存隔离 WebView2 的真实截图，不复制正文到 stdout 或报告。 */
async function capture(page, name) {
  await settleVisuals(page);
  await page.screenshot({ path: join(evidenceDirectory, `${name}.png`), animations: "disabled" });
}

/**
 * 通过真实 Tauri Window.close 触发正常 CloseRequested/退出握手；先保存 exit 偏好，
 * 使 runner 能区分用户关闭路径和 launcher 强杀路径，随后只等待原生窗口销毁。
 */
async function requestNormalExit(page) {
  let closed = false;
  const closedPromise = new Promise((resolve) => {
    page.once("close", () => {
      closed = true;
      resolve();
    });
  });
  let failure;
  try {
    await page.evaluate(async () => {
      const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function") throw new Error("Tauri bridge unavailable");
      await invoke("ja_desktop_close_behavior_save", { value: "exit" });
      await invoke("plugin:window|close", { label: "main" });
    });
  } catch (error) {
    failure = error;
  }
  if (!closed) {
    await Promise.race([closedPromise, new Promise((resolve) => setTimeout(resolve, 30_000))]);
  }
  if (!closed) throw new Error("正常退出请求后真实 WebView2 窗口未关闭");
  if (failure !== undefined) throw failure;
  return { requested: true, closed: true };
}

/** 提供最小 Anthropic Messages SSE 响应，并记录场景/模型/mailbox 事实。 */
async function startLoopbackProvider() {
  const requests = [];
  let sequence = 0;
  let releaseRunning;
  const runningReleased = new Promise((resolve) => {
    releaseRunning = resolve;
  });
  const server = createServer(async (request, response) => {
    let raw = "";
    for await (const chunk of request) {
      raw += chunk;
      if (raw.length > 2_000_000) {
        response.writeHead(413).end();
        return;
      }
    }
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      response.writeHead(400).end();
      return;
    }
    const serialized = JSON.stringify(body);
    const userTexts = (body.messages ?? [])
      .filter((message) => message.role === "user")
      .flatMap((message) =>
        typeof message.content === "string"
          ? [message.content]
          : (message.content ?? [])
              .filter((block) => block.type === "text")
              .map((block) => block.text),
      )
      .filter((text) => !text.includes('"kind":"external_thread_message"'));
    const scenario = userTexts.at(-1) ?? serialized;
    const approvalSpawnFixture = APPROVAL_FIXTURES.find((fixture) =>
      scenario.includes(fixture.spawnMarker),
    );
    const approvalChildFixture = APPROVAL_FIXTURES.find((fixture) =>
      scenario.includes(fixture.childMarker),
    );
    const marker = approvalSpawnFixture !== undefined
      ? `approval_spawn_${approvalSpawnFixture.key}`
      : approvalChildFixture !== undefined
        ? `approval_${approvalChildFixture.key}`
        : scenario.includes(SPAWN_MARKER)
          ? "spawn"
      : scenario.includes(SUBAGENT_MARKER)
        ? "subagent"
        : scenario.includes(BTW_MARKER)
          ? "btw"
          : scenario.includes(SIDE_AFTER_RUNNING_MARKER)
            ? "side_after_running"
            : scenario.includes(SIDE_PLAN_MARKER)
              ? "side_plan"
              : scenario.includes(SIDE_RUNNING_MARKER)
                ? "side_running"
                : scenario.includes(SIDE_SECOND_MARKER)
                  ? "side_second"
                  : scenario.includes(SIDE_FIRST_MARKER)
                    ? "side_first"
                    : serialized.includes(BTW_MARKER)
                      ? "btw"
                      : serialized.includes(ROOT_MARKER)
                        ? "root"
                        : "unknown";
    const toolResults = (body.messages ?? [])
      .flatMap((message) => (Array.isArray(message.content) ? message.content : []))
      .filter((block) => block?.type === "tool_result")
      .map((block) => {
        const text = typeof block.content === "string"
          ? block.content
          : Array.isArray(block.content)
            ? block.content.filter((part) => part?.type === "text").map((part) => part.text).join("\n")
            : "";
        return {
          callId: block.tool_use_id,
          isError: block.is_error === true,
          containsVerification: APPROVAL_FIXTURES.some((fixture) => text.includes(fixture.verified)),
          containsDenial: /denied|拒绝|未获批准/iu.test(text),
        };
      });
    const record = {
      sequence: ++sequence,
      marker,
      model: typeof body.model === "string" ? body.model : "unknown",
      hasParentMarker: serialized.includes(ROOT_MARKER),
      hasMailboxMarker: serialized.includes(MAILBOX_MARKER),
      hasRunningMailboxMarker: serialized.includes(RUNNING_MAILBOX_MARKER),
      toolResults,
    };
    requests.push(record);
    if (marker === "side_running") await runningReleased;
    const approvalCallId = approvalSpawnFixture === undefined
      ? undefined
      : `call_approval_spawn_${approvalSpawnFixture.key}`;
    const approvalCommand = approvalChildFixture === undefined
      ? undefined
      : buildApprovalWriteCommand(approvalChildFixture);
    const boundaryCall =
      marker === "side_running" && !serialized.includes("call_running_boundary")
        ? { id: "call_running_boundary", name: "list_threads", input: { limit: 10 } }
        : approvalSpawnFixture !== undefined && !serialized.includes(approvalCallId)
          ? {
              id: approvalCallId,
              name: "spawn_agent",
              input: {
                taskName: approvalSpawnFixture.taskName,
                brief: `Run one exec_command for ${approvalSpawnFixture.childMarker}, then report its real result.`,
                accessMode: "approval_required",
              },
            }
          : approvalChildFixture !== undefined && !serialized.includes(approvalChildFixture.callId)
            ? {
                id: approvalChildFixture.callId,
                name: "exec_command",
                input: { cmd: approvalCommand, yield_time_ms: 10_000 },
              }
        : marker === "spawn" && !serialized.includes("call_spawn_child")
          ? {
              id: "call_spawn_child",
              name: "spawn_agent",
              input: { taskName: "侧聊委派子任务", brief: SUBAGENT_MARKER },
            }
          : undefined;
    if (boundaryCall !== undefined) {
      const toolMessage = {
        id: `msg_boundary_${record.sequence}`,
        type: "message",
        role: "assistant",
        model: record.model,
        content: [{ type: "tool_use", ...boundaryCall }],
        stop_reason: "tool_use",
        stop_sequence: null,
        usage: { input_tokens: 24, output_tokens: 12 },
      };
      if (body.stream !== true) {
        response
          .writeHead(200, { "content-type": "application/json" })
          .end(JSON.stringify(toolMessage));
      } else {
        const toolEvents = [
          ["message_start", { message: { ...toolMessage, content: [], stop_reason: null } }],
          [
            "content_block_start",
            {
              index: 0,
              content_block: {
                type: "tool_use",
                id: boundaryCall.id,
                name: boundaryCall.name,
                input: {},
              },
            },
          ],
          [
            "content_block_delta",
            {
              index: 0,
              delta: { type: "input_json_delta", partial_json: JSON.stringify(boundaryCall.input) },
            },
          ],
          ["content_block_stop", { index: 0 }],
          [
            "message_delta",
            {
              delta: { stop_reason: "tool_use", stop_sequence: null },
              usage: { output_tokens: 12 },
            },
          ],
          ["message_stop", {}],
        ];
        response.writeHead(200, { "content-type": "text/event-stream" });
        response.end(
          toolEvents
            .map(
              ([type, value]) => `event: ${type}\ndata: ${JSON.stringify({ type, ...value })}\n\n`,
            )
            .join(""),
        );
      }
      return;
    }
    const text =
      marker.startsWith("approval_spawn_")
        ? "JA_SUBAGENT_APPROVAL_SPAWN_DONE"
        : marker === "approval_allow"
          ? "JA_SUBAGENT_APPROVAL_ALLOW_DONE"
          : marker === "approval_deny"
            ? "JA_SUBAGENT_APPROVAL_DENY_DONE"
            : marker === "approval_resume"
              ? "JA_SUBAGENT_APPROVAL_RESUME_DONE"
              : marker === "approval_cancel"
                ? "JA_SUBAGENT_APPROVAL_CANCEL_DONE"
      : marker === "spawn"
        ? "JA_SIDE_CHAT_SPAWN_DONE"
        : marker === "subagent"
          ? "JA_SIDE_CHAT_SUBAGENT_DONE"
          : marker === "root"
            ? "JA_SIDE_CHAT_PARENT_DONE"
            : marker === "btw"
              ? "JA_SIDE_CHAT_BTW_DONE"
              : marker === "side_after_running"
                ? "JA_SIDE_CHAT_AFTER_RUNNING_DONE"
                : marker === "side_running"
                  ? "JA_SIDE_CHAT_RUNNING_DONE"
                  : marker === "side_plan"
                    ? "JA_SIDE_CHAT_PLAN_DONE"
                    : marker === "side_second"
                      ? "JA_SIDE_CHAT_SECOND_DONE"
                      : "JA_SIDE_CHAT_FIRST_DONE";
    const message = {
      id: `msg_side_chat_${record.sequence}`,
      type: "message",
      role: "assistant",
      model: record.model,
      content: [{ type: "text", text }],
      stop_reason: "end_turn",
      stop_sequence: null,
      usage: { input_tokens: 24, output_tokens: 12 },
    };
    if (body.stream !== true) {
      response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(message));
      return;
    }
    const events = [
      ["message_start", { message: { ...message, content: [], stop_reason: null } }],
      ["content_block_start", { index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { index: 0, delta: { type: "text_delta", text } }],
      ["content_block_stop", { index: 0 }],
      [
        "message_delta",
        { delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 12 } },
      ],
      ["message_stop", {}],
    ];
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.end(
      events
        .map(([type, value]) => `event: ${type}\ndata: ${JSON.stringify({ type, ...value })}\n\n`)
        .join(""),
    );
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.equal(typeof address, "object");
  return {
    server,
    url: `http://127.0.0.1:${address.port}`,
    requests,
    releaseRunning: () => releaseRunning(),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/** 通过真实设置页把本轮 Provider 指向 loopback，禁止调用付费网络或模型验证接口。 */
async function configureLoopbackProvider(page, provider) {
  const settings = page.locator(".ja-settings");
  if (!(await settings.isVisible().catch(() => false))) {
    const sidebar = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await sidebar.isVisible().catch(() => false)) await sidebar.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
  }
  try {
    await settings.waitFor({ state: "visible", timeout: 60_000 });
  } catch (error) {
    await capture(page, "00-side-chat-settings-timeout").catch(() => undefined);
    await writeFile(
      join(evidenceDirectory, "side-chat-settings-timeout.json"),
      `${JSON.stringify(
        {
          url: page.url(),
          title: await page.title().catch(() => ""),
          bodyText: (
            await page
              .locator("body")
              .innerText()
              .catch(() => "")
          ).slice(0, 12_000),
          buttons: await page
            .getByRole("button")
            .allTextContents()
            .catch(() => []),
          runtimeState: await page
            .evaluate(async () => {
              try {
                return await globalThis.__TAURI_INTERNALS__?.invoke("ja_runtime_state");
              } catch (invokeError) {
                return { invokeError: String(invokeError) };
              }
            })
            .catch((evaluateError) => ({ evaluateError: String(evaluateError) })),
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    throw error;
  }
  const modelTab = settings.getByRole("tab", { name: "模型", exact: true });
  if (await modelTab.count()) await modelTab.click();
  const add = settings.getByRole("button", { name: "新增供应商", exact: true });
  const edit = settings.getByRole("button", { name: "编辑供应商", exact: true });
  const creating = (await add.count()) > 0;
  await (creating ? add : edit).first().click();
  const dialog = page.getByRole("dialog", {
    name: creating ? "新增供应商" : "编辑供应商",
    exact: true,
  });
  await dialog.getByLabel("供应商名称").fill("Side Chat Loopback");
  const api = page.getByRole("combobox", { name: "API 规范", exact: true });
  if (await api.count()) {
    await api.click();
    await page.getByRole("option", { name: "Anthropic Messages", exact: true }).click();
  }
  await dialog.getByLabel("Base URL").fill(provider.url);
  await dialog
    .getByRole("textbox", { name: "API Key", exact: true })
    .fill("isolated-loopback-no-billing-token");
  const rows = dialog.locator(".ja-provider-model-row");
  assert.ok((await rows.count()) >= 1, "模型设置页没有模型行");
  const fill = async (row, model) => {
    await row.getByLabel("显示名称").fill(model);
    await row.getByLabel("上游模型标识").fill(model);
    await row.getByLabel("上下文 Tokens").fill("128000");
    await row.getByLabel("单次最大输出 Tokens").fill("8192");
  };
  await fill(rows.nth(0), PRIMARY_MODEL);
  if ((await rows.count()) < 2)
    await dialog.getByRole("button", { name: "手动添加", exact: true }).click();
  await fill(rows.nth(1), SECONDARY_MODEL);
  await dialog
    .getByRole("button", { name: creating ? "保存供应商" : "保存更改", exact: true })
    .click();
  await dialog.waitFor({ state: "hidden", timeout: 30_000 });
  const back = settings.getByRole("button", { name: "返回应用", exact: true });
  if (await back.isVisible().catch(() => false)) await back.click();
  await page
    .getByRole("textbox", { name: "消息", exact: true })
    .first()
    .waitFor({ state: "visible", timeout: 60_000 });
}

/**
 * 设置页只负责持久化 Provider；Thread 的模型偏好仍由当前 Composer 独立持有。
 * 真窗验收必须通过真实模型菜单切换到 loopback 模型，并确认可访问名称已经反映
 * 选择结果，否则请求可能继续命中启动时的 bootstrap Provider。
 */
async function chooseModel(composer, modelIdentifier) {
  const trigger = composer.getByRole("button", { name: /当前模型/u });
  await trigger.waitFor({ state: "visible", timeout: 30_000 });
  await trigger.click();
  const option = composer.page().getByRole("menuitemradio", { name: modelIdentifier, exact: true });
  await option.waitFor({ state: "visible", timeout: 30_000 });
  await option.click();
  await waitForCondition(
    `Composer 选择模型 ${modelIdentifier}`,
    async () => {
      const label = await trigger.getAttribute("aria-label");
      return label?.includes(`当前模型：${modelIdentifier}`) === true;
    },
    30_000,
  );
}

/** 返回当前主 Thread 的稳定 identity；只从真实导航或 Thread list 读取，不猜随机 ID。 */
async function currentThreadId(page) {
  const direct = page
    .locator(
      '.ja-navigation-thread-row[data-state="active"] button[data-thread-id], .ja-navigation-thread-row[aria-current="page"] button[data-thread-id], .ja-navigation-thread-row button[data-thread-id][aria-current="page"]',
    )
    .first();
  const candidate =
    (await direct.count()) > 0
      ? direct
      : page.locator(".ja-navigation-thread-row button[data-thread-id]").first();
  const id = await candidate.getAttribute("data-thread-id");
  if (id === null) throw new Error("无法从真实历史导航读取主 Thread identity");
  return id;
}

/** 读取 Java-owned Thread 快照；关闭验证只认可 read/list 的权威失败或删除结果。 */
async function readThread(page, threadId) {
  return invoke(page, "ja_thread_read", { threadId, limit: 200 });
}

/** 从 Thread 读结果取 workspace、revision 和 turns，适配 envelope 之外不改变协议。 */
function threadFacts(result) {
  if (result?.ok !== true) return undefined;
  const value = result.value;
  return {
    threadId: value?.threadId,
    workspaceId: value?.workspaceId,
    revision: value?.revision,
    turns: Array.isArray(value?.turns) ? value.turns : [],
    taskActivities: Array.isArray(value?.taskActivities) ? value.taskActivities : [],
    serialized: JSON.stringify(value),
  };
}

/** 通过主 Composer 完成一轮真实 Provider 请求并等待终态正文出现。 */
async function sendComposer(composer, text, expectedText) {
  const input = composer.getByRole("textbox", { name: "消息", exact: true });
  if (await input.isDisabled().catch(() => false)) {
    const page = composer.page();
    const diagnostic = await composer
      .evaluate((element) => {
        const inputElement = element.querySelector("textarea.ja-composer__input");
        const detail = element.closest(".ja-task-detail");
        const tab = globalThis.document.querySelector(
          '.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:"], .ja-workbench-tab-shell[data-state="active"][data-tab^="side-task:"]',
        );
        return {
          composer: {
            className: element.className,
            taskState: element.closest(".ja-task-composer")?.getAttribute("data-task-state"),
          },
          input: inputElement
            ? {
                disabled: inputElement.disabled,
                ariaDisabled: inputElement.getAttribute("aria-disabled"),
                className: inputElement.className,
              }
            : undefined,
          detail: detail?.textContent?.slice(0, 4_000),
          activeSideTab: tab?.getAttribute("data-workbench-tab") ?? tab?.getAttribute("data-tab"),
        };
      })
      .catch((error) => ({ evaluateError: String(error) }));
    const activeSideTab = diagnostic.activeSideTab;
    const sideThreadId =
      typeof activeSideTab === "string" && activeSideTab.startsWith("side-task:")
        ? activeSideTab.slice("side-task:".length)
        : undefined;
    const [taskRead, transcriptRead] =
      sideThreadId === undefined
        ? [undefined, undefined]
        : await Promise.all([
            invoke(page, "ja_runtime_task_read", { taskThreadId: sideThreadId, limit: 200 }),
            invoke(page, "ja_thread_read", { threadId: sideThreadId, limit: 200 }),
          ]);
    await capture(page, "diagnostic-side-chat-composer-disabled").catch(() => undefined);
    await writeFile(
      join(evidenceDirectory, "diagnostic-side-chat-composer-disabled.json"),
      `${JSON.stringify(
        {
          ...diagnostic,
          taskRead,
          transcriptRead,
          bodyText: (
            await page
              .locator("body")
              .innerText()
              .catch(() => "")
          ).slice(0, 12_000),
          runtimeState: await page
            .evaluate(async () => {
              try {
                return await globalThis.__TAURI_INTERNALS__?.invoke("ja_runtime_state");
              } catch (error) {
                return { invokeError: String(error) };
              }
            })
            .catch((error) => ({ evaluateError: String(error) })),
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
  }
  await input.fill(text);
  await composer.getByRole("button", { name: "发送", exact: true }).click();
  if (expectedText !== undefined)
    await composer
      .page()
      .getByText(expectedText, { exact: true })
      .last()
      .waitFor({ state: "visible", timeout: 60_000 });
}

/** 等待指定 Provider 场景，确保 UI 终态不是静态文本冒充。 */
async function waitForRequest(provider, marker, afterSequence = 0) {
  let found;
  await waitForCondition(
    `loopback Provider ${marker}`,
    () => {
      found = provider.requests.find(
        (request) => request.marker === marker && request.sequence > afterSequence,
      );
      return found !== undefined;
    },
    60_000,
  );
  return found;
}

/** 打开真实 Workbench 右栏，调用产品入口创建稳定 Child Thread 侧聊。 */
async function openSideChat(page) {
  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    const show = page.getByRole("button", { name: "显示工作区面板", exact: true });
    await show.click();
  }
  await inspector.waitFor({ state: "visible", timeout: 20_000 });
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible", timeout: 20_000 });
  const before = await workbench
    .locator('[data-workbench-tab^="side-task:thr_"], [data-tab^="side-task:thr_"]')
    .count();
  await workbench.getByRole("button", { name: "新建标签页", exact: true }).click();
  await page
    .getByRole("menuitem")
    .filter({ hasText: /新建(?:侧边任务|侧聊)/u })
    .last()
    .click();
  await waitForCondition(
    "新侧聊标签完成创建",
    async () =>
      (await workbench
        .locator('[data-workbench-tab^="side-task:thr_"], [data-tab^="side-task:thr_"]')
        .count()) > before,
  );
  const tab = workbench
    .locator(
      '.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:thr_"], .ja-workbench-tab-shell[data-tab^="side-task:thr_"]',
    )
    .last();
  await tab.waitFor({ state: "visible", timeout: 30_000 });
  const key =
    (await tab.getAttribute("data-workbench-tab")) ?? (await tab.getAttribute("data-tab"));
  assert.match(
    key ?? "",
    /^side-task:thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u,
    "侧聊没有真实 Thread identity",
  );
  assert.ok(
    (await workbench
      .locator('[data-workbench-tab^="side-task:thr_"], [data-tab^="side-task:thr_"]')
      .count()) > before,
  );
  const region = page.locator(".ja-task-detail:visible").last();
  await region.waitFor({ state: "visible", timeout: 20_000 });
  const composer = region.locator(".ja-composer");
  await composer.waitFor({ state: "visible", timeout: 20_000 });
  return {
    threadId: key.slice("side-task:".length),
    tab: workbench.locator(`[data-workbench-tab="${key}"]`).first(),
    region,
    composer,
    workbench,
  };
}

/**
 * 验证 `/btw` 的两个入口都创建新的独立侧聊：空命令只创建 idle Thread，
 * 带正文命令直接把首条用户消息提交给新 Thread；两者都不能复用已有侧聊或
 * 让主 Thread 产生额外 Provider 请求。
 */
async function exerciseBtw(page, rootComposer, provider, existingSideIds) {
  const knownSideIds = new Set(existingSideIds);
  const before = provider.requests.length;
  const input = rootComposer.getByRole("textbox", { name: "消息", exact: true });
  const idleCommandProbe = await armComposerBusyCycle(page, rootComposer);
  await input.fill("/btw");
  await input.press("Enter");
  let idleThreadId;
  await waitForCondition("/btw 空命令创建侧聊", async () => {
    const tabs = page.locator(
      '.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:thr_"], .ja-workbench-tab-shell[aria-selected="true"][data-tab^="side-task:thr_"]',
    );
    for (let index = 0; index < (await tabs.count()); index += 1) {
      const key =
        (await tabs.nth(index).getAttribute("data-workbench-tab")) ??
        (await tabs.nth(index).getAttribute("data-tab"));
      const id = key?.startsWith("side-task:") ? key.slice("side-task:".length) : undefined;
      if (id !== undefined && !knownSideIds.has(id)) {
        idleThreadId = id;
        return true;
      }
    }
    return false;
  });
  assert.equal(provider.requests.length, before, "/btw 空命令不应唤醒 Provider");
  assert.equal(typeof idleThreadId, "string", "/btw 空命令没有返回新的侧聊 identity");
  knownSideIds.add(idleThreadId);
  const tab = page
    .locator(
      `[data-workbench-tab="side-task:${idleThreadId}"], [data-tab="side-task:${idleThreadId}"]`,
    )
    .last();
  await tab.waitFor({ state: "visible", timeout: 20_000 });
  const key =
    (await tab.getAttribute("data-workbench-tab")) ?? (await tab.getAttribute("data-tab"));
  assert.match(
    key ?? "",
    /^side-task:thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u,
    "/btw 没有创建稳定侧聊 Thread",
  );
  const idle = {
    threadId: idleThreadId,
    tab,
    region: page.locator(".ja-task-detail:visible").last(),
    composer: page.locator(".ja-task-detail:visible").last().locator(".ja-composer"),
  };
  await idle.region.waitFor({ state: "visible", timeout: 20_000 });
  await idle.composer.waitFor({ state: "visible", timeout: 20_000 });
  await waitForComposerBusyCycle(page, idleCommandProbe, "/btw 空命令");

  const rootRequestCountBeforeContent = provider.requests.filter(
    (request) => request.marker === "root",
  ).length;
  const contentBefore = provider.requests.length;
  const contentCommandProbe = await armComposerBusyCycle(page, rootComposer);
  await input.fill(`/btw ${BTW_MARKER}`);
  await input.press("Enter");
  let contentThreadId;
  await waitForCondition("/btw 内容创建新侧聊", async () => {
    const tabs = page.locator(
      '.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:thr_"], .ja-workbench-tab-shell[aria-selected="true"][data-tab^="side-task:thr_"]',
    );
    for (let index = 0; index < (await tabs.count()); index += 1) {
      const candidate = tabs.nth(index);
      const tabKey =
        (await candidate.getAttribute("data-workbench-tab")) ??
        (await candidate.getAttribute("data-tab"));
      const id = tabKey?.startsWith("side-task:") ? tabKey.slice("side-task:".length) : undefined;
      if (id !== undefined && !knownSideIds.has(id)) {
        contentThreadId = id;
        return true;
      }
    }
    return false;
  });
  assert.equal(typeof contentThreadId, "string", "/btw 内容复用了已有侧聊或没有创建新 Thread");
  const contentTab = page
    .locator(
      `[data-workbench-tab="side-task:${contentThreadId}"], [data-tab="side-task:${contentThreadId}"]`,
    )
    .last();
  await contentTab.waitFor({ state: "visible", timeout: 20_000 });
  const contentRegion = page.locator(".ja-task-detail:visible").last();
  await contentRegion.waitFor({ state: "visible", timeout: 20_000 });
  await waitForComposerBusyCycle(page, contentCommandProbe, "/btw 内容命令");
  await contentRegion
    .getByText("JA_SIDE_CHAT_BTW_DONE", { exact: true })
    .waitFor({ state: "visible", timeout: 60_000 });
  const request = await waitForRequest(provider, "btw", contentBefore);
  const contentSnapshot = threadFacts(await readThread(page, contentThreadId));
  assert.equal(
    contentSnapshot?.serialized.includes(BTW_MARKER),
    true,
    "/btw 内容没有落到记录的目标 Thread",
  );
  const rootRequestCountAfterContent = provider.requests.filter(
    (item) => item.marker === "root",
  ).length;
  assert.equal(contentThreadId === idleThreadId, false, "/btw 内容错误复用了空闲侧聊");
  assert.equal(
    rootRequestCountAfterContent,
    rootRequestCountBeforeContent,
    "/btw 内容错误增加了主 Thread Provider 请求",
  );
  return {
    idle,
    first: {
      threadId: contentThreadId,
      tab: page.locator(`[data-workbench-tab="side-task:${contentThreadId}"]`).first(),
      region: contentRegion,
      composer: contentRegion.locator(".ja-composer"),
    },
    request,
    requestTargetThreadId: contentThreadId,
    contentSnapshot,
    rootRequestCountBeforeContent,
    rootRequestCountAfterContent,
  };
}

/** 验证主/侧 Composer 共享生产组件且宽窄侧栏没有横向溢出或重排抖动。 */
async function inspectVisualParity(page) {
  const evidence = await page.evaluate(() => {
    const root = globalThis.document.querySelector(".ja-conversation .ja-composer");
    const child = Array.from(
      globalThis.document.querySelectorAll(".ja-task-detail .ja-composer"),
    ).find((element) => element instanceof globalThis.HTMLElement && element.offsetParent !== null);
    const rootStyle =
      root instanceof globalThis.HTMLElement ? globalThis.getComputedStyle(root) : null;
    const childStyle =
      child instanceof globalThis.HTMLElement ? globalThis.getComputedStyle(child) : null;
    const rail = child?.closest(".ja-task-composer");
    return {
      rootFound: root instanceof globalThis.HTMLElement,
      childFound: child instanceof globalThis.HTMLElement,
      sharedSurface:
        rootStyle !== null &&
        childStyle !== null &&
        ["borderRadius", "borderStyle", "borderWidth", "backgroundColor", "gap", "padding"].every(
          (field) => rootStyle[field] === childStyle[field],
        ),
      overflow: rail instanceof globalThis.HTMLElement ? rail.scrollWidth - rail.clientWidth : null,
      narrowOverflow: null,
    };
  });
  assert.equal(evidence.rootFound, true, "主 Composer 不存在");
  assert.equal(evidence.childFound, true, "侧聊 Composer 不存在");
  assert.equal(
    evidence.sharedSurface,
    true,
    `侧聊没有复用主 Composer 生产样式：${JSON.stringify(evidence)}`,
  );
  assert.ok((evidence.overflow ?? 99) <= 1, `侧聊 Composer 横向溢出：${JSON.stringify(evidence)}`);
  await capture(page, "01-side-chat-wide");
  await page.setViewportSize({ width: 720, height: 640 });
  const narrowOverflow = await page.evaluate(() => {
    const element = globalThis.document.querySelector(".ja-task-composer");
    return element instanceof globalThis.HTMLElement
      ? element.scrollWidth - element.clientWidth
      : 99;
  });
  assert.ok(narrowOverflow <= 1, `窄侧栏横向溢出：${narrowOverflow}`);
  await capture(page, "02-side-chat-narrow");
  await page.setViewportSize({ width: 1280, height: 820 });
  return { ...evidence, narrowOverflow };
}

/**
 * QueueOnly 只落到目标 Mailbox，空闲目标不启动 Provider；task/read 只核对入箱事实。
 * 下一次模型请求消费后才按 Thread/item 身份读取正文，不能把 Mailbox 预览当作完整历史或跨会话读取授权。
 * 投递回执的 msg 身份与历史 item 身份分离；operationKey 是该持久写入的去重键，
 * 与 JSON-RPC 请求 id 及 Thread 身份分离；响应未知时重试必须复用同一个键。
 */
async function exerciseMailbox(page, provider, rootThreadId, side) {
  const idleBefore = provider.requests.length;
  const receipt = await invoke(page, messageCommand, {
    senderThreadId: rootThreadId,
    targetThreadId: side.threadId,
    content: [{ type: "text", text: MAILBOX_MARKER }],
    operationKey: `side-chat-mailbox-${Date.now()}`,
  });
  assert.equal(receipt.ok, true, `Mailbox 投递失败：${JSON.stringify(receipt.error)}`);
  await new Promise((resolve) => setTimeout(resolve, 300));
  assert.equal(provider.requests.length, idleBefore, "空闲侧聊收到 QueueOnly 消息后被错误唤醒");
  const taskRead = await invoke(page, "ja_runtime_task_read", {
    taskThreadId: side.threadId,
    limit: 200,
  });
  assert.equal(taskRead.ok, true, `无法读取侧聊 Mailbox：${JSON.stringify(taskRead.error)}`);
  const mailbox = taskRead.value?.mailbox ?? [];
  assert.ok(
    mailbox.some((message) => JSON.stringify(message.content).includes(MAILBOX_MARKER)),
    "Mailbox 没有持久化消息正文",
  );
  const messageId = receipt.value?.messageId;
  assert.match(messageId ?? "", /^msg_/u, "投递回执没有稳定 Mailbox 消息身份");
  const itemId = `item_${createHash("sha256").update(messageId, "utf8").digest("hex")}`;
  const pendingThread = requireInvoke(await readThread(page, side.threadId), "待消费侧聊历史");
  assert.equal(
    pendingThread.items?.some((item) => item.itemId === itemId),
    false,
    "尚未消费的 Mailbox 消息提前进入历史",
  );
  const before = provider.requests.length;
  await sendComposer(side.composer, SIDE_SECOND_MARKER, "JA_SIDE_CHAT_SECOND_DONE");
  const request = await waitForRequest(provider, "side_second", before);
  assert.equal(request.hasMailboxMarker, true, "下一次 Provider 请求前没有消费 Mailbox");
  const thread = requireInvoke(await readThread(page, side.threadId), "收件侧聊历史");
  const messageItem = thread.items?.find((item) => item.itemId === itemId);
  assert.equal(messageItem?.kind, "thread_message", "收件 Thread 未投影对应 Mailbox item");
  const content = requireInvoke(
    await invoke(page, "ja_thread_item_content_read", {
      threadId: side.threadId,
      itemId,
      offsetCharacters: 0,
      limitCharacters: 256,
    }),
    "收件 Mailbox 正文读取",
  );
  assert.equal(content.itemId, itemId);
  assert.equal(content.content, MAILBOX_MARKER, "收件 Thread 读取的正文与投递不一致");
  assert.equal(content.truncated, false, "短 Mailbox 正文不应被截断");
  return { receipt: receipt.value, request, messageId, itemId };
}

/** 同一 owner 的其他 Thread 不能借已投递的 item 身份读取收件侧聊私有正文。 */
async function rejectMailboxReadFromOtherThread(page, threadId, itemId) {
  const result = await invoke(page, "ja_thread_item_content_read", {
    threadId,
    itemId,
    offsetCharacters: 0,
    limitCharacters: 256,
  });
  assert.equal(result.ok, false, "无关 Thread 读取了其他会话的 Mailbox 正文");
  assert.equal(result.error?.code, "THREAD_NOT_FOUND", "未授权 item 应使用统一不可枚举错误");
}

/** 在同一个 Turn 内通过真实工具边界继续第二次模型请求，验证消息无需等当前问题结束。 */
async function exerciseRunningMailbox(page, provider, rootThreadId, side) {
  const before = provider.requests.length;
  const sendPromise = sendComposer(side.composer, SIDE_RUNNING_MARKER, "JA_SIDE_CHAT_RUNNING_DONE");
  const running = await waitForRequest(provider, "side_running", before);
  const message = await invoke(page, messageCommand, {
    senderThreadId: rootThreadId,
    targetThreadId: side.threadId,
    content: [{ type: "text", text: RUNNING_MAILBOX_MARKER }],
    operationKey: `side-chat-running-${Date.now()}`,
  });
  assert.equal(message.ok, true, `运行中 Mailbox 投递失败：${JSON.stringify(message.error)}`);
  assert.equal(provider.requests.length, before + 1, "运行中 QueueOnly 消息错误地启动或打断了请求");
  provider.releaseRunning();
  await sendPromise;
  const request = provider.requests.find(
    (candidate) => candidate.marker === "side_running" && candidate.sequence > running.sequence,
  );
  assert.equal(running.hasRunningMailboxMarker, false, "正在进行的请求被追写消息");
  assert.equal(request?.hasRunningMailboxMarker, true, "同一 Turn 的下一模型请求未消费消息");
  return { running, request };
}

/** 子任务只能由模型原生 spawn_agent 创建，不能把 task/create 产生的另一个侧聊冒充 Subagent。 */
async function createSideOwnedChild(page, side) {
  await sendComposer(side.composer, SPAWN_MARKER, "JA_SIDE_CHAT_SPAWN_DONE");
  const sideRead = requireInvoke(
    await invoke(page, "ja_runtime_task_read", { taskThreadId: side.threadId }),
    "侧聊详情",
  );
  const list = requireInvoke(
    await invoke(page, "ja_runtime_task_list", { rootThreadId: sideRead.task.rootThreadId }),
    "来源树",
  );
  const task = list.items.find(
    (item) => item.parentThreadId === side.threadId && item.taskKind === "subagent",
  );
  assert.ok(task, "模型没有创建真实侧聊所属子任务");
  return task;
}

/**
 * 在真实侧聊 Composer 中单独创建并读取 Plan，确认侧聊复用主会话的 Plan 能力。
 * 该检查走真实 Composer 与 RPC，避免通过直接写状态伪造侧聊的 Plan 能力。
 */
async function createSidePlan(page, provider, side) {
  const input = side.composer.getByRole("textbox", { name: "消息", exact: true });
  await input.fill("/plan on");
  await input.press("Enter");
  await side.composer
    .locator('[data-plan-ui="mode-status"]')
    .waitFor({ state: "visible", timeout: 15_000 });
  const beforePlan = provider.requests.length;
  await sendComposer(side.composer, SIDE_PLAN_MARKER, "JA_SIDE_CHAT_PLAN_DONE");
  const planRequest = await waitForRequest(provider, "side_plan", beforePlan);
  const currentPlan = await invoke(page, "ja_runtime_plan_current_read", {
    threadId: side.threadId,
  });
  assert.equal(currentPlan.ok, true, `侧聊 Plan 创建失败：${JSON.stringify(currentPlan.error)}`);
  assert.equal(
    typeof currentPlan.value?.current?.plan?.planId,
    "string",
    "侧聊没有真实 Plan projection",
  );
  await input.fill("/plan off");
  await input.press("Enter");
  await side.composer
    .locator('[data-plan-ui="mode-status"]')
    .waitFor({ state: "hidden", timeout: 15_000 });
  return {
    planId: currentPlan.value.current.plan.planId,
    planRequest,
  };
}

/**
 * 通过独立会话发现接口读取必要元数据，默认使用跨 Workspace 的 `scope=all`；
 * 只有显式切换为 workspace scope 时才携带 workspaceId，避免把右栏侧聊漏在
 * 主导航的工作区列表之外。
 */
async function listThreads(page, workspaceId) {
  const input = { scope: threadListScope, limit: 200 };
  if (threadListScope === "workspace") input.workspaceId = workspaceId;
  const result = await invoke(page, threadListCommand, input);
  assert.equal(result.ok, true, `thread/list 失败：${JSON.stringify(result.error)}`);
  assert.ok(Array.isArray(result.value?.items), "thread/list 没有返回 items");
  return result.value.items;
}

/**
 * 只对当前侧聊的一次 transport 提交注入延迟失败；真实界面必须保留 Tab 与 Thread，
 * 呈现 closing/错误并允许重试。重试才穿透真实 App Server，返回的 ID 集合作为 UI ACK 证据。
 */
async function exerciseSideChatCloseRetry(page, side, shell) {
  if (backendMode === "native") {
    // Installed bundles intentionally omit the test-only invoke probe; exercise the real close path and let closeSideChat verify the backend receipt.
    const close = shell.locator('[data-tab-close="true"]');
    await close.click();
    await waitForCondition(
      "生产构建真实侧聊关闭 ACK",
      async () =>
        (await page
          .locator(
            `[data-workbench-tab="side-task:${side.threadId}"], [data-tab="side-task:${side.threadId}"]`,
          )
          .count()) === 0,
      30_000,
    );
    return {
      injectedFailure: false,
      productionCloseObserved: true,
      closedThreadIds: undefined,
    };
  }
  await page.evaluate(({ command, threadId }) => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const state = { attempts: 0, delegated: 0, released: false };
    globalThis.__JA_SIDE_CHAT_CLOSE_FAULT__ = state;
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      if (request?.command !== command || request?.args?.input?.taskThreadId !== threadId)
        return previous === undefined ? delegate() : previous(request, delegate);
      state.attempts += 1;
      if (state.attempts === 1) {
        await new Promise((resolve) => { state.release = resolve; });
        state.released = true;
        throw { code: "TASK_CLOSE_FAILED" };
      }
      state.delegated += 1;
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      state.closedThreadIds = Array.isArray(result?.closedThreadIds)
        ? [...result.closedThreadIds]
        : undefined;
      return result;
    };
    state.restore = () => {
      state.release?.();
      globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous;
    };
  }, { command: closeCommand, threadId: side.threadId });
  try {
    const close = shell.locator('[data-tab-close="true"]');
    await close.click();
    await waitForCondition(
      "侧聊 closing transaction",
      async () => (await page.evaluate(() => globalThis.__JA_SIDE_CHAT_CLOSE_FAULT__?.attempts)) === 1 &&
        await close.isDisabled(),
    );
    assert.equal(await side.tab.count(), 1, "closing 期间提前移除了侧聊 Tab");
    assert.equal((await readThread(page, side.threadId)).ok, true, "失败提交前侧聊 Thread 已丢失");
    await capture(page, "03a-side-chat-close-pending");
    await page.evaluate(() => globalThis.__JA_SIDE_CHAT_CLOSE_FAULT__?.release?.());
    const workbench = page.locator('.ja-thread-workbench-session:not([hidden]) .ja-workbench:visible');
    const error = workbench.getByRole("alert").filter({
      has: page.getByRole("heading", { name: "关闭失败", exact: true }),
    });
    await error.waitFor({ state: "visible", timeout: 20_000 });
    assert.equal(await side.tab.count(), 1, "关闭失败后提前移除了侧聊 Tab");
    assert.equal((await readThread(page, side.threadId)).ok, true, "关闭失败后侧聊 Thread 已丢失");
    assert.equal(await close.isEnabled(), true, "关闭失败没有恢复操作入口");
    await capture(page, "03b-side-chat-close-failed");
    await error.getByRole("button", { name: "重试", exact: true }).click();
    await waitForCondition("侧聊重试关闭 ACK", async () => (await side.tab.count()) === 0, 30_000);
    const state = await page.evaluate(() => {
      const fault = globalThis.__JA_SIDE_CHAT_CLOSE_FAULT__;
      return {
        attempts: fault.attempts,
        delegated: fault.delegated,
        released: fault.released,
        closedThreadIds: fault.closedThreadIds,
      };
    });
    assert.deepEqual(
      { attempts: state.attempts, delegated: state.delegated, released: state.released },
      { attempts: 2, delegated: 1, released: true },
    );
    return {
      injectedFailure: "transport_before_commit",
      closingObserved: true,
      failedTabRetained: true,
      failedThreadRetained: true,
      retryDelegatedOnce: true,
      closedThreadIds: state.closedThreadIds,
    };
  } finally {
    await page.evaluate(() => {
      globalThis.__JA_SIDE_CHAT_CLOSE_FAULT__?.restore?.();
      delete globalThis.__JA_SIDE_CHAT_CLOSE_FAULT__;
    }).catch(() => undefined);
  }
}

/**
 * 关闭先验证 UI 操作确实完成服务端删除/关闭，再调用幂等 close 检查回执；
 * 若 UI 只是移除 Tab 而没有关闭后端 Thread，第一次 read 会立即失败验收，
 * 不会被后续的补偿 command 掩盖；UI closing/失败/重试也必须先完成。预期集合包含实际派生子树，不能只检查侧聊根。
 */
async function closeSideChat(page, side, expectedThreadIds = [side.threadId]) {
  // 每个标签的关闭按钮是 tab 的兄弟；必须用固定 Thread 的容器定位，不能按重名“关闭侧聊”猜目标。
  const shell = page
    .locator(".ja-workbench-tab-shell")
    .filter({ has: page.locator(`[data-workbench-tab="side-task:${side.threadId}"]`) });
  const lifecycle = await exerciseSideChatCloseRetry(page, side, shell);
  await waitForCondition(
    "侧聊关闭 ACK",
    async () =>
      (await page
        .locator(
          `[data-workbench-tab="side-task:${side.threadId}"], [data-tab="side-task:${side.threadId}"]`,
        )
        .count()) === 0,
    30_000,
  );
  const uiRead = await readThread(page, side.threadId);
  const uiClosed = uiRead.ok !== true || ["closed", "deleted"].includes(uiRead.value?.status);
  assert.equal(
    uiClosed,
    true,
    `UI 关闭后后端 Thread 仍可读取：${JSON.stringify(uiRead.error ?? uiRead.value?.status)}`,
  );
  const closeResult = await invoke(page, closeCommand, { taskThreadId: side.threadId });
  assert.equal(closeResult.ok, true, `task/close 调用失败：${JSON.stringify(closeResult.error)}`);
  const closedThreadIds = assertSideChatCloseReceipt(
    closeResult.value,
    side.threadId,
    expectedThreadIds,
  );
  if (lifecycle.closedThreadIds !== undefined) {
    assert.deepEqual(
      lifecycle.closedThreadIds,
      closedThreadIds,
      "UI 关闭 ACK 与幂等重试返回了不同的关闭范围",
    );
  }
  const read = await readThread(page, side.threadId);
  const closed = read.ok !== true || ["closed", "deleted"].includes(read.value?.status);
  assert.equal(
    closed,
    true,
    `侧聊关闭后仍可读取 Thread：${JSON.stringify(read.error ?? read.value?.status)}`,
  );
  return {
    clicked: true,
    lifecycle,
    command: closeCommand,
    closedThreadIds,
    uiReadCode: uiRead.error?.code ?? uiRead.value?.status ?? "closed",
    readCode: read.error?.code ?? read.value?.status ?? "closed",
  };
}

/** 通过现有 RPC frame channel 只收集 task/closed，核对全局观察者通知与关闭 ACK。 */
async function subscribeTaskClosedEvents(page) {
  return page.evaluate(async () => {
    const internals = globalThis.__TAURI_INTERNALS__;
    if (typeof internals?.transformCallback !== "function" || typeof internals.invoke !== "function") {
      throw new Error("task/closed 验收缺少 Tauri event bridge");
    }
    globalThis.__JA_SIDE_CHAT_CLOSE_EVENTS__ = [];
    const handler = internals.transformCallback((value) => {
      const payload = value !== null && typeof value === "object" && value.payload !== undefined
        ? value.payload
        : value;
      if (payload?.method !== "task/closed") return;
      const events = globalThis.__JA_SIDE_CHAT_CLOSE_EVENTS__;
      if (Array.isArray(events) && events.length < 32) events.push(payload);
    });
    return internals.invoke("plugin:event|listen", {
      event: "ja://rpc/frame",
      target: { kind: "Any" },
      handler,
    });
  });
}

/** 在同一隔离窗口观察服务端通知，容许通知早于 UI ACK 到达。 */
async function waitForTaskClosedEvent(page, rootThreadId, taskThreadId) {
  let event;
  await waitForCondition("task/closed 侧聊清理通知", async () => {
    event = await page.evaluate(({ expectedRootThreadId, expectedTaskThreadId }) => {
      const events = globalThis.__JA_SIDE_CHAT_CLOSE_EVENTS__;
      if (!Array.isArray(events)) return undefined;
      const notification = events.find((candidate) =>
        candidate?.method === "task/closed" &&
        candidate.params?.rootThreadId === expectedRootThreadId &&
        candidate.params?.taskThreadId === expectedTaskThreadId,
      );
      if (notification === undefined) return undefined;
      const params = notification.params;
      const metadataNames = new Set([
        "serverInstanceId",
        "eventId",
        "sequence",
        "occurredAt",
        "generation",
      ]);
      const businessNames = new Set(["rootThreadId", "taskThreadId", "closedThreadIds"]);
      const names = Object.keys(params).sort();
      return {
        rootThreadId: params.rootThreadId,
        taskThreadId: params.taskThreadId,
        closedThreadIds: params.closedThreadIds,
        serverInstanceId: params.serverInstanceId,
        eventId: params.eventId,
        sequence: params.sequence,
        occurredAt: params.occurredAt,
        generation: params.generation,
        metadataFields: names.filter((name) => metadataNames.has(name)).sort(),
        businessFields: names.filter((name) => businessNames.has(name)).sort(),
        unexpectedFields: names.filter((name) => !metadataNames.has(name) && !businessNames.has(name)),
      };
    }, { expectedRootThreadId: rootThreadId, expectedTaskThreadId: taskThreadId });
    return event !== undefined;
  }, 30_000);
  return event;
}

/** 关闭观察者时释放精确 Tauri listener，避免跨阶段保留 callback。 */
async function unsubscribeTaskClosedEvents(page, eventId) {
  await page.evaluate(async (listenerId) => {
    await globalThis.__TAURI_INTERNALS__?.invoke?.("plugin:event|unlisten", {
      event: "ja://rpc/frame",
      eventId: listenerId,
    });
    delete globalThis.__JA_SIDE_CHAT_CLOSE_EVENTS__;
  }, eventId);
}

/** 从服务端 Task Tree 读取确切委派对象；不从 Timeline 卡片标题合成 Thread identity。@author kongweiguang */
async function readOwnedApprovalTask(page, rootThreadId, taskName) {
  const result = requireInvoke(
    await invoke(page, "ja_runtime_task_list", { rootThreadId }),
    "审批回归任务树",
  );
  return result.items?.find(
    (task) =>
      task.taskKind === "subagent" &&
      task.parentThreadId === rootThreadId &&
      task.rootThreadId === rootThreadId &&
      task.taskName === taskName,
  );
}

/**
 * Task 与父 Thread Activity 必须同时到达同一状态；Thread snapshot 返回 activity history，
 * 所以父投影按 activitySequence 取最新一条，避免旧事件冒充当前状态。
 * @author kongweiguang
 */
async function waitForOwnedApprovalState(page, rootThreadId, taskThreadId, expectedState, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let observed;
  while (Date.now() < deadline) {
    const taskResult = await invoke(page, "ja_runtime_task_read", { taskThreadId });
    const ownerSnapshot = threadFacts(await readThread(page, rootThreadId));
    const ownerActivity = ownerSnapshot?.taskActivities
      .filter((entry) => entry.task.taskThreadId === taskThreadId)
      .reduce(
        (latest, entry) =>
          latest === undefined ||
          entry.activity.activitySequence > latest.activity.activitySequence
            ? entry
            : latest,
        undefined,
      );
    observed = {
      task: taskResult.ok === true ? taskResult.value?.task : undefined,
      ownerTask: ownerActivity?.task,
      ownerActivity: ownerActivity?.activity,
    };
    if (
      observed.task?.state === expectedState &&
      observed.ownerTask?.state === expectedState &&
      observed.ownerActivity?.taskThreadId === taskThreadId &&
      observed.ownerActivity?.rootThreadId === rootThreadId
    )
      return observed;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(
    `Task 与委派方 Activity 未收敛到 ${expectedState}：${JSON.stringify({
      taskState: observed?.task?.state,
      ownerState: observed?.ownerTask?.state,
      activityKind: observed?.ownerActivity?.kind,
    })}`,
  );
}

/** Task Activity 点击只重开既有 Task tab；状态与审批正文仍必须来自真实 Child Transcript。@author kongweiguang */
async function openApprovalTaskDetail(page, task) {
  const stateLabels = {
    queued: "排队中",
    running: "运行中",
    waiting_approval: "等待确认",
    suspended: "已挂起",
    completed: "已完成",
    failed: "失败",
    cancelled: "已取消",
  };
  const taskTabKey = `subagent:${task.taskThreadId}`;
  const card = page.getByRole("button", {
    name: `打开${task.taskName}，${stateLabels[task.state] ?? task.state}`,
    exact: true,
  });
  await card.waitFor({ state: "visible", timeout: 30_000 });
  await card.click();
  const tab = page.locator(`[data-workbench-tab="${taskTabKey}"]`);
  await tab.waitFor({ state: "visible", timeout: 30_000 });
  await waitForCondition(
    `子任务详情 ${task.taskName}`,
    () => page.locator(".ja-task-detail:visible").last().getAttribute("aria-label").then((label) => label === task.taskName),
  );
  return {
    tab,
    region: page.locator(".ja-task-detail:visible").last(),
  };
}

/** 关闭只解除该 Subagent 的展示入口；随后用父 Activity 卡重开验证持久审批仍可见。@author kongweiguang */
async function closeAndReopenApprovalTask(page, task) {
  const shell = page.locator(`.ja-workbench-tab-shell[data-tab="subagent:${task.taskThreadId}"]`);
  await shell.getByRole("button", { name: `关闭${task.taskName}`, exact: true }).click();
  await shell.waitFor({ state: "detached", timeout: 20_000 });
  const reopened = await openApprovalTaskDetail(page, task);
  return { reopened: true, ...reopened };
}

/** 读取 Core 持久的 pending Approval identity，UI 只能以该 callId/Thread 承载决定。@author kongweiguang */
async function pendingExecApproval(page, taskThreadId) {
  const snapshot = requireInvoke(await readThread(page, taskThreadId), "子任务审批历史");
  const approvals = (snapshot.items ?? []).filter(
    (item) => item.kind === "approval" && item.toolName === "exec_command" && item.decision === null,
  );
  assert.equal(approvals.length, 1, "子任务历史必须只有一个真实待处理 exec_command 审批");
  return approvals[0];
}

/** 已挂起的 Turn 可保留关闭审批历史，但 UI 不得再暴露允许或拒绝操作。@author kongweiguang */
async function assertNoActiveApprovalActions(region, stage) {
  const cards = region.locator(".ja-approval-card");
  assert.equal(
    await cards.getByRole("button", { name: "批准", exact: true }).count(),
    0,
    `${stage}仍显示批准操作`,
  );
  assert.equal(
    await cards.getByRole("button", { name: "拒绝", exact: true }).count(),
    0,
    `${stage}仍显示拒绝操作`,
  );
}

/** 等待唯一 fixture 工具结果进入下一个模型请求，并只保留可供验收的布尔摘要。@author kongweiguang */
async function waitForApprovalToolResult(provider, marker, callId, afterSequence) {
  let request;
  await waitForCondition(
    `${marker} 工具审批结果回传 Provider`,
    () => {
      request = provider.requests.find(
        (candidate) =>
          candidate.marker === marker &&
          candidate.sequence > afterSequence &&
          candidate.toolResults.some((result) => result.callId === callId),
      );
      return request !== undefined;
    },
    60_000,
  );
  return request.toolResults.find((result) => result.callId === callId);
}

/** 通过 root Thread 的模型原生 spawn_agent 创建 Child；权限上限由结构化 Provider fixture 明确收紧。@author kongweiguang */
async function spawnApprovalChild(page, provider, rootComposer, rootThreadId, fixture) {
  const before = provider.requests.length;
  await sendComposer(rootComposer, fixture.spawnMarker, "JA_SUBAGENT_APPROVAL_SPAWN_DONE");
  const spawnRequest = await waitForRequest(provider, `approval_spawn_${fixture.key}`, before);
  let task;
  await waitForCondition(
    `${fixture.taskName} 创建并归属 root`,
    async () => {
      task = await readOwnedApprovalTask(page, rootThreadId, fixture.taskName);
      return task !== undefined;
    },
    60_000,
  );
  assert.equal(task.parentThreadId, rootThreadId, "子任务 parentThreadId 错误");
  assert.equal(task.rootThreadId, rootThreadId, "子任务 lineage root 错误");
  assert.equal(task.taskKind, "subagent");
  assert.ok(spawnRequest.sequence > before, "子任务不是由真实 Provider spawn_agent 委派");
  return task;
}

/** 以真实审批卡和文件回读覆盖 allow；新开与重开都必须保留同一个 pending Approval identity。@author kongweiguang */
async function approveApprovalChild(page, provider, rootThreadId, task, fixture) {
  const childRequest = await waitForRequest(provider, `approval_${fixture.key}`);
  await waitForOwnedApprovalState(page, rootThreadId, task.taskThreadId, "waiting_approval");
  let detail = await openApprovalTaskDetail(page, { ...task, state: "waiting_approval" });
  let approval = await pendingExecApproval(page, task.taskThreadId);
  await detail.region.locator(".ja-approval-card").getByRole("button", { name: "批准", exact: true }).waitFor({ state: "visible" });
  const firstApprovalId = approval.approvalId;
  detail = await closeAndReopenApprovalTask(page, { ...task, state: "waiting_approval" });
  approval = await pendingExecApproval(page, task.taskThreadId);
  assert.equal(approval.approvalId, firstApprovalId, "重开子任务后审批 identity 被替换");
  const card = detail.region.locator(".ja-approval-card");
  await card.getByRole("button", { name: "批准", exact: true }).click();
  await card.getByRole("heading", { name: "工具调用已批准", exact: true }).waitFor({ state: "visible" });
  const toolResult = await waitForApprovalToolResult(provider, "approval_allow", fixture.callId, childRequest.sequence);
  const filePath = join(approvalWriteDirectory, fixture.fileName);
  const contents = await readFile(filePath, "utf8");
  assert.equal(contents, fixture.fileValue, "允许后真实隔离文件没有写入预期内容");
  assert.equal(toolResult.isError, false, "获准的 shell ToolResult 被报告为失败");
  assert.equal(toolResult.containsVerification, true, "shell 没有回读新文件内容");
  const terminal = await waitForOwnedApprovalState(page, rootThreadId, task.taskThreadId, "completed");
  return {
    taskThreadId: task.taskThreadId,
    parentThreadId: task.parentThreadId,
    approvalId: approval.approvalId,
    callId: fixture.callId,
    approvedAfterReopen: true,
    fileWrittenAndReadBack: true,
    toolResultIsError: toolResult.isError,
    ownerState: terminal.ownerTask.state,
    ownerActivity: terminal.ownerActivity.kind,
  };
}

/** 拒绝必须返回失败 ToolResult 给原模型调用方，且临时文件不存在、父 Activity 收敛完成。@author kongweiguang */
async function denyApprovalChild(page, provider, rootThreadId, task, fixture) {
  const childRequest = await waitForRequest(provider, `approval_${fixture.key}`);
  await waitForOwnedApprovalState(page, rootThreadId, task.taskThreadId, "waiting_approval");
  const detail = await openApprovalTaskDetail(page, { ...task, state: "waiting_approval" });
  const approval = await pendingExecApproval(page, task.taskThreadId);
  const card = detail.region.locator(".ja-approval-card");
  await card.getByRole("button", { name: "拒绝", exact: true }).click();
  await card.getByRole("heading", { name: "工具调用已拒绝", exact: true }).waitFor({ state: "visible" });
  const toolResult = await waitForApprovalToolResult(
    provider,
    "approval_deny",
    fixture.callId,
    childRequest.sequence,
  );
  assert.equal(toolResult.isError, true, "拒绝的 ToolResult 没有保持失败结果");
  assert.equal(toolResult.containsVerification, false, "拒绝后模型收到了写入成功结果");
  await assert.rejects(access(join(approvalWriteDirectory, fixture.fileName)), { code: "ENOENT" });
  const terminal = await waitForOwnedApprovalState(page, rootThreadId, task.taskThreadId, "completed");
  return {
    taskThreadId: task.taskThreadId,
    parentThreadId: task.parentThreadId,
    approvalId: approval.approvalId,
    callId: fixture.callId,
    denied: true,
    fileAbsent: true,
    toolResultIsError: toolResult.isError,
    ownerState: terminal.ownerTask.state,
    ownerActivity: terminal.ownerActivity.kind,
  };
}

/**
 * pending Approval 时从 Task UI 显式取消；Core 用 typed close reason 收敛审批而不伪造用户决定。
 * 遇到 revision conflict 先重读 Task 与审批身份，再由 UI 确认；最终要求 Task/父 Activity 取消、审批 decision 保持 null 且无副作用。
 * @author kongweiguang
 */
async function cancelApprovalChild(page, provider, rootThreadId, task, fixture) {
  await waitForRequest(provider, `approval_${fixture.key}`);
  await waitForOwnedApprovalState(page, rootThreadId, task.taskThreadId, "waiting_approval");
  let currentTask = { ...task, state: "waiting_approval" };
  let detail = await openApprovalTaskDetail(page, currentTask);
  const approval = await pendingExecApproval(page, task.taskThreadId);
  const confirmCancellation = async () => {
    const approvalCard = detail.region.locator(".ja-approval-card");
    await approvalCard
      .getByRole("button", { name: "批准", exact: true })
      .waitFor({ state: "visible" });
    await detail.region.getByRole("button", { name: "取消任务", exact: true }).click();
    const confirmation = detail.region.getByRole("group", { name: "确认取消任务" });
    await confirmation.waitFor({ state: "visible" });
    const scopeText = await confirmation.locator("span").textContent();
    assert.equal(
      scopeText?.trim(),
      "将同时取消 0 个未结束后代",
      "取消前必须确认该隔离 fixture 最新详情仍没有子任务后代",
    );
    await confirmation.getByRole("button", { name: "确认取消", exact: true }).click();
    let outcome;
    await waitForCondition(
      "审批取消或关闭完成，或显示 revision conflict",
      async () => {
        if (
          (await approvalCard.getByRole("heading", { name: "审批已取消", exact: true }).count()) ||
          (await approvalCard.getByRole("heading", { name: "审批请求已关闭", exact: true }).count())
        ) {
          outcome = "approval_closed";
          return true;
        }
        if (
          await detail.region
            .getByRole("alert")
            .getByText("任务状态已更新，请查看最新任务及取消范围后重新确认。", {
              exact: true,
            })
            .count()
        ) {
          outcome = "revision_conflict";
          return true;
        }
        return false;
      },
      30_000,
    );
    return outcome;
  };

  let outcome = await confirmCancellation();
  let retriedAfterConflict = false;
  if (outcome === "revision_conflict") {
    const refreshed = requireInvoke(
      await invoke(page, "ja_runtime_task_read", { taskThreadId: task.taskThreadId }),
      "取消冲突后重新读取 Task",
    );
    currentTask = refreshed.task;
    assert.equal(currentTask.taskThreadId, task.taskThreadId);
    assert.equal(currentTask.parentThreadId, rootThreadId);
    assert.equal(currentTask.rootThreadId, rootThreadId);
    assert.equal(
      currentTask.state,
      "waiting_approval",
      "冲突后 Task 已变化，不得基于旧审批继续重试取消",
    );
    const refreshedApproval = await pendingExecApproval(page, task.taskThreadId);
    assert.equal(refreshedApproval.approvalId, approval.approvalId);
    assert.equal(refreshedApproval.callId, approval.callId);
    assert.equal(refreshedApproval.decision, null);
    detail = await closeAndReopenApprovalTask(page, currentTask);
    outcome = await confirmCancellation();
    retriedAfterConflict = true;
  }
  assert.equal(outcome, "approval_closed", "最新 Task 重确认后审批仍未关闭");
  const terminal = await waitForOwnedApprovalState(page, rootThreadId, task.taskThreadId, "cancelled");
  assert.equal(
    await detail.region.locator(".ja-approval-card").getByRole("button", { name: "批准", exact: true }).count(),
    0,
    "Task 取消后审批卡仍显示批准操作",
  );
  assert.equal(
    await detail.region.locator(".ja-approval-card").getByRole("button", { name: "拒绝", exact: true }).count(),
    0,
    "Task 取消后审批卡仍显示拒绝操作",
  );
  const cancelledHistory = requireInvoke(
    await readThread(page, task.taskThreadId),
    "取消后回读审批历史",
  );
  const closedApproval = (cancelledHistory.items ?? []).filter(
    (item) =>
      item.kind === "approval" &&
      item.approvalId === approval.approvalId &&
      item.callId === fixture.callId,
  );
  assert.equal(closedApproval.length, 1, "取消后历史必须保留同一审批 identity");
  assert.equal(
    closedApproval[0].decision,
    null,
    "Task cancel 必须关闭 pending Tool，但不能写入用户未作出的审批决定",
  );
  await assert.rejects(access(join(approvalWriteDirectory, fixture.fileName)), { code: "ENOENT" });
  assert.equal(
    provider.requests.some((request) => request.marker === "approval_cancel" && request.toolResults.some((result) => result.callId === fixture.callId)),
    false,
    "取消后 pending shell 意外进入了 Provider ToolResult 续答",
  );
  return {
    taskThreadId: task.taskThreadId,
    parentThreadId: task.parentThreadId,
    approvalId: approval.approvalId,
    callId: fixture.callId,
    cancelledWhileWaiting: true,
    retriedAfterConflict,
    cancelledWithoutUserDecision: true,
    fileAbsent: true,
    ownerState: terminal.ownerTask.state,
    ownerActivity: terminal.ownerActivity.kind,
  };
}

/** 审批自然到期后核对权威挂起状态与无活动决策按钮，再重开并继续运行，不要求历史过期卡仍可见。@author kongweiguang */
async function resumeExpiredApprovalChild(page, provider, rootThreadId, task, fixture) {
  const childRequest = await waitForRequest(provider, `approval_${fixture.key}`);
  const waiting = await waitForOwnedApprovalState(
    page,
    rootThreadId,
    task.taskThreadId,
    "waiting_approval",
  );
  const detail = await openApprovalTaskDetail(page, { ...task, ...waiting.task });
  const approval = await pendingExecApproval(page, task.taskThreadId);
  await detail
    .region.locator(".ja-approval-card")
    .getByRole("button", { name: "批准", exact: true })
    .waitFor({ state: "visible" });
  const remaining = Date.parse(approval.expiresAt) - Date.now();
  if (!Number.isFinite(remaining) || remaining < 0 || remaining > 6 * 60_000)
    throw new Error("审批到期时间不在隔离回归的六分钟期限内");
  const suspended = await waitForOwnedApprovalState(
    page,
    rootThreadId,
    task.taskThreadId,
    "suspended",
    Math.max(30_000, remaining + 60_000),
  );
  let expiredHistory;
  await waitForCondition(
    "过期审批对应的 Thread Turn 投影挂起",
    async () => {
      expiredHistory = requireInvoke(
        await readThread(page, task.taskThreadId),
        "自然到期后回读子线程",
      );
      return expiredHistory.turns.some(
        (turn) => turn.turnId === approval.turnId && turn.status === "suspended",
      );
    },
    60_000,
  );
  const closedApproval = (expiredHistory.items ?? []).filter(
    (item) =>
      item.kind === "approval" &&
      item.approvalId === approval.approvalId &&
      item.callId === fixture.callId,
  );
  assert.equal(closedApproval.length, 1, "到期后应保留同一审批历史 identity");
  assert.equal(closedApproval[0].decision, null, "系统到期不能伪造用户审批决定");
  await assertNoActiveApprovalActions(detail.region, "审批到期挂起后");
  const reopened = await closeAndReopenApprovalTask(page, { ...task, ...suspended.task });
  await assertNoActiveApprovalActions(reopened.region, "重开已挂起任务后");
  await reopened.region.getByRole("button", { name: "继续运行", exact: true }).click();
  const toolResult = await waitForApprovalToolResult(
    provider,
    "approval_resume",
    fixture.callId,
    childRequest.sequence,
  );
  assert.equal(toolResult.isError, true, "过期审批恢复后不应伪造已执行 shell 成功");
  assert.equal(toolResult.containsVerification, false);
  await assert.rejects(access(join(approvalWriteDirectory, fixture.fileName)), { code: "ENOENT" });
  const terminal = await waitForOwnedApprovalState(
    page,
    rootThreadId,
    task.taskThreadId,
    "completed",
    60_000,
  );
  return {
    taskThreadId: task.taskThreadId,
    parentThreadId: task.parentThreadId,
    approvalId: approval.approvalId,
    callId: fixture.callId,
    expiredToSuspended: true,
    threadTurnSuspended: true,
    expiredApprovalDecisionNull: true,
    noActiveApprovalActions: true,
    resumedAfterReopen: true,
    fileAbsent: true,
    toolResultIsError: toolResult.isError,
    ownerState: terminal.ownerTask.state,
    ownerActivity: terminal.ownerActivity.kind,
  };
}

/** 独立审批 phase 复用本地 Provider 与真实 Thread UI，覆盖父归属、允许/拒绝/取消、到期恢复及重开 pending 审批。@author kongweiguang */
async function runApprovals(page, provider) {
  await page.locator(".ja-composer").first().waitFor({ state: "visible", timeout: 60_000 });
  await configureLoopbackProvider(page, provider);
  const newConversation = page.getByRole("button", { name: "新会话", exact: true });
  if (await newConversation.count()) await newConversation.click();
  const rootComposer = page.locator(".ja-conversation .ja-composer").first();
  await chooseModel(rootComposer, PRIMARY_MODEL);
  await sendComposer(rootComposer, ROOT_MARKER, "JA_SIDE_CHAT_PARENT_DONE");
  const rootThreadId = await currentThreadId(page);
  const outcomes = {};
  for (const key of ["allow", "deny", "cancel", "resume"]) {
    const fixture = APPROVAL_FIXTURES.find((candidate) => candidate.key === key);
    const task = await spawnApprovalChild(page, provider, rootComposer, rootThreadId, fixture);
    const childBefore = await waitForRequest(provider, `approval_${key}`);
    if (key === "allow")
      outcomes.allow = await approveApprovalChild(page, provider, rootThreadId, task, fixture);
    else if (key === "deny")
      outcomes.deny = await denyApprovalChild(page, provider, rootThreadId, task, fixture);
    else if (key === "cancel")
      outcomes.cancel = await cancelApprovalChild(page, provider, rootThreadId, task, fixture);
    else
      outcomes.resume = await resumeExpiredApprovalChild(page, provider, rootThreadId, task, fixture);
    outcomes[key].childStartSequence = childBefore.sequence;
    await capture(page, `approval-${key}-settled`);
  }
  const rootActivities = threadFacts(await readThread(page, rootThreadId)).taskActivities;
  const latestActivityByTask = new Map();
  for (const entry of rootActivities) {
    const taskThreadId = entry.task.taskThreadId;
    const current = latestActivityByTask.get(taskThreadId);
    if (current === undefined || entry.activity.activitySequence > current.activity.activitySequence)
      latestActivityByTask.set(taskThreadId, entry);
  }
  const ownedTasks = [...latestActivityByTask.values()].map((entry) => ({
    taskThreadId: entry.task.taskThreadId,
    parentThreadId: entry.task.parentThreadId,
    rootThreadId: entry.task.rootThreadId,
    state: entry.task.state,
    activity: entry.activity.kind,
  }));
  assert.equal(ownedTasks.length, 4, "委派方 Thread 必须只保留四种审批场景的直接 Subagent 入口");
  assert.ok(ownedTasks.every((entry) => entry.parentThreadId === rootThreadId));
  assert.ok(ownedTasks.every((entry) => entry.rootThreadId === rootThreadId));
  const report = {
    status: "approvals_passed",
    evidenceScope: "isolated Windows Tauri/WebView2, local Provider, Native/JVM selected by runner, real Task Activity and ApprovalCard UI",
    rootThreadId,
    outcomes,
    ownerProjection: ownedTasks,
    providerRequestCount: provider.requests.length,
  };
  await writeFile(join(evidenceDirectory, "side-chat-approvals.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  await capture(page, "approval-parent-final");
  return report;
}

/** 初始阶段执行真实交互；子任务专项只验本轮委派、归属、完成和级联关闭，全量模式再验证侧聊其余能力。 */
async function runInitial(page, provider) {
  await page.locator(".ja-composer").first().waitFor({ state: "visible", timeout: 60_000 });
  await configureLoopbackProvider(page, provider);
  const newConversation = page.getByRole("button", { name: "新会话", exact: true });
  if (await newConversation.count()) await newConversation.click();
  const rootComposer = page.locator(".ja-conversation .ja-composer").first();
  await chooseModel(rootComposer, PRIMARY_MODEL);
  await sendComposer(rootComposer, ROOT_MARKER, "JA_SIDE_CHAT_PARENT_DONE");
  const rootThreadId = await currentThreadId(page);
  const rootBefore = threadFacts(await readThread(page, rootThreadId));
  assert.equal(rootBefore?.turns.length > 0, true, "主任务首轮没有权威 Turn");
  await writeFile(retainedFile, "JA_SIDE_CHAT_RETAINED_FILE\n", "utf8");

  const first = await openSideChat(page);
  const sideBefore = threadFacts(await readThread(page, first.threadId));
  assert.equal(sideBefore?.turns.length, 0, "空侧聊创建时错误启动 Provider");
  await sendComposer(first.composer, SIDE_FIRST_MARKER, "JA_SIDE_CHAT_FIRST_DONE");
  const firstRequest = await waitForRequest(provider, "side_first");
  assert.equal(firstRequest.hasParentMarker, true, "侧聊没有继承创建时已提交的主上下文");
  const rootAfterFirst = threadFacts(await readThread(page, rootThreadId));
  assert.equal(
    rootAfterFirst?.turns.length,
    rootBefore.turns.length,
    "侧聊完成后主任务出现自动回流 Turn",
  );
  assert.equal(
    (await page.locator(".ja-conversation").innerText()).includes(SIDE_FIRST_MARKER),
    false,
    "侧聊正文回流到主 Timeline",
  );
  const sideText = await first.region.innerText();
  assert.equal(
    /继承自主任务 revision \d+/u.test(sideText),
    false,
    "侧聊 UI 展示了不应暴露的父 revision",
  );

  const visual = await inspectVisualParity(page);
  const mailbox = await exerciseMailbox(page, provider, rootThreadId, first);
  const running = await exerciseRunningMailbox(page, provider, rootThreadId, first);
  const childTask = await createSideOwnedChild(page, first);
  const rootActivities = threadFacts(await readThread(page, rootThreadId)).taskActivities;
  assert.equal(
    rootActivities.some((item) => item.task.taskThreadId === childTask.taskThreadId),
    false,
    "侧聊子任务活动穿透到主任务",
  );
  const sideActivities = threadFacts(await readThread(page, first.threadId)).taskActivities;
  assert.equal(
    sideActivities.some((item) => item.task.taskThreadId === childTask.taskThreadId),
    true,
    "侧聊未收到实际委派活动",
  );

  if (subagentOnly) {
    await waitForCondition("子任务完成正文", async () => {
      const child = requireInvoke(await readThread(page, childTask.taskThreadId), "子任务历史");
      return JSON.stringify(child.items).includes("JA_SIDE_CHAT_SUBAGENT_DONE");
    });
    await capture(page, "03-subagent-completed");
    const close = await closeSideChat(page, first, [first.threadId, childTask.taskThreadId]);
    assert.equal(
      (await readThread(page, childTask.taskThreadId)).ok,
      false,
      "关闭来源侧聊后子任务仍可读取",
    );
    assert.equal((await readThread(page, rootThreadId)).ok, true, "关闭子树误删主会话");
    return {
      status: "subagent_passed",
      evidenceScope:
        "Windows Tauri/WebView2, JVM backend, loopback Provider, structured spawn_agent, completion, owner isolation, close retry and subtree purge",
      rootThreadId,
      sideThreadId: first.threadId,
      childTaskThreadId: childTask.taskThreadId,
      mailbox,
      running,
      close,
      providerRequestCount: provider.requests.length,
    };
  }

  const btw = await exerciseBtw(page, rootComposer, provider, [first.threadId]);
  const second = await openSideChat(page);
  const secondSnapshot = threadFacts(await readThread(page, second.threadId));
  assert.equal(secondSnapshot?.turns.length, 0, "第二个空侧聊创建时唤醒 Provider");
  await rejectMailboxReadFromOtherThread(page, second.threadId, mailbox.itemId);
  const sidePlan = await createSidePlan(page, provider, second);
  await first.tab.click();
  await first.region
    .getByText("JA_SIDE_CHAT_FIRST_DONE", { exact: true })
    .waitFor({ state: "visible", timeout: 20_000 });
  await second.tab.click();
  await second.composer.waitFor({ state: "visible", timeout: 20_000 });

  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  const hide = page.getByRole("button", { name: "隐藏工作区面板", exact: true });
  if (await hide.isVisible().catch(() => false)) {
    await hide.click();
    await waitForCondition(
      "隐藏侧聊只影响观察",
      async () => (await inspector.getAttribute("data-visible")) !== "true",
    );
    assert.equal((await readThread(page, first.threadId)).ok, true, "隐藏右栏错误关闭侧聊");
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click();
  }

  const workspaceId = rootBefore.workspaceId;
  const listed = await listThreads(page, workspaceId);
  assert.equal(
    listed.some((thread) => thread.threadId === rootThreadId),
    true,
    "thread/list 缺少主会话",
  );
  assert.equal(
    listed.some((thread) => thread.threadId === first.threadId),
    true,
    "thread/list 缺少存活侧聊",
  );
  assert.equal(
    listed.some((thread) => thread.threadId === second.threadId),
    true,
    "thread/list 缺少包含独立 Plan 的侧聊",
  );
  const navigationThreadIds = await page
    .locator(".ja-navigation-thread-row button[data-thread-id]")
    .evaluateAll((buttons) =>
      buttons.map((button) => button.getAttribute("data-thread-id")).filter((id) => id !== null),
    );
  for (const sideThreadId of [
    first.threadId,
    second.threadId,
    btw.idle.threadId,
    btw.first.threadId,
  ]) {
    assert.equal(
      navigationThreadIds.includes(sideThreadId),
      false,
      `主导航错误展示临时侧聊：${sideThreadId}`,
    );
  }
  const closeListener = await subscribeTaskClosedEvents(page);
  let close;
  let closeEvent;
  try {
    close = await closeSideChat(page, second);
    closeEvent = await waitForTaskClosedEvent(page, rootThreadId, second.threadId);
    assert.deepEqual(
      closeEvent.metadataFields,
      ["eventId", "generation", "occurredAt", "sequence", "serverInstanceId"],
      "task/closed transport metadata 不符合生产协议闭集",
    );
    assert.deepEqual(
      closeEvent.businessFields,
      ["closedThreadIds", "rootThreadId", "taskThreadId"],
      "task/closed 业务 params 不符合严格字段闭集",
    );
    assert.deepEqual(closeEvent.unexpectedFields, [], "task/closed params 出现协议外字段");
    const runtimeIdentity = requireInvoke(await invoke(page, "ja_runtime_state", {}), "关闭事件 Runtime 身份");
    assert.equal(closeEvent.serverInstanceId, runtimeIdentity.serverInstanceId);
    assert.equal(closeEvent.generation, runtimeIdentity.generation);
    assert.match(closeEvent.eventId, /^evt_[A-Za-z0-9._-]{1,95}$/u);
    assert.ok(Number.isSafeInteger(closeEvent.sequence) && closeEvent.sequence > 0);
    assert.ok(Number.isSafeInteger(closeEvent.generation) && closeEvent.generation > 0);
    assert.ok(Number.isFinite(Date.parse(closeEvent.occurredAt)));
    const eventClosedThreadIds = assertSideChatCloseReceipt(
      { closedThreadIds: closeEvent.closedThreadIds },
      second.threadId,
      close.closedThreadIds,
    );
    assert.deepEqual(
      eventClosedThreadIds,
      close.closedThreadIds,
      "task/closed 通知与 task/close ACK 的清理范围不同",
    );
  } finally {
    await unsubscribeTaskClosedEvents(page, closeListener).catch(() => undefined);
  }
  assert.equal(await access(retainedFile).then(() => true), true, "关闭侧聊误删主 workspace 文件");
  await writeFile(
    join(evidenceDirectory, "side-chat-state.json"),
    `${JSON.stringify({ rootThreadId, sideThreadId: second.threadId, liveSideThreadId: first.threadId, btwSideThreadId: btw.first.threadId, btwIdleSideThreadId: btw.idle.threadId, btwRequestTargetThreadId: btw.requestTargetThreadId, childTaskThreadId: childTask.taskThreadId, workspaceId, sidePlan, close: { closedThreadIds: close.closedThreadIds, eventClosedThreadIds: closeEvent.closedThreadIds } }, null, 2)}\n`,
    "utf8",
  );
  await capture(page, "03-side-chat-after-close");
  return {
    status: "initial_passed",
    rootThreadId,
    workspaceId,
    firstSideThreadId: first.threadId,
    secondSideThreadId: second.threadId,
    childTaskThreadId: childTask.taskThreadId,
    firstRequest,
    mailbox,
    running,
    btw: {
      request: btw.request,
      sideThreadId: btw.first.threadId,
      idleSideThreadId: btw.idle.threadId,
      requestTargetThreadId: btw.requestTargetThreadId,
      rootRequestCountBeforeContent: btw.rootRequestCountBeforeContent,
      rootRequestCountAfterContent: btw.rootRequestCountAfterContent,
    },
    sidePlan,
    visual,
    close,
    closeEvent,
    providerRequestCount: provider.requests.length,
  };
}

/** 重启验证遗留清理后再创建一个空闲侧聊，真实正常退出必须同时清理这次仍存活的侧聊。 */
async function runRestart(page) {
  const state = JSON.parse(await readFile(join(evidenceDirectory, "side-chat-state.json"), "utf8"));
  await page.locator(".ja-composer").first().waitFor({ state: "visible", timeout: 60_000 });
  const root = await readThread(page, state.rootThreadId);
  assert.equal(root.ok, true, "重启后主会话没有恢复");
  const closed = await readThread(page, state.sideThreadId);
  assert.equal(closed.ok, false, `重启后已关闭侧聊仍可读取：${closed.error?.code ?? "readable"}`);
  const listed = await listThreads(page, state.workspaceId);
  for (const ephemeralId of [
    state.liveSideThreadId,
    state.btwSideThreadId,
    state.btwIdleSideThreadId,
    state.childTaskThreadId,
  ]) {
    assert.equal((await readThread(page, ephemeralId)).ok, false, "崩溃重启恢复了未关闭的临时会话");
    assert.equal(
      listed.some((thread) => thread.threadId === ephemeralId),
      false,
    );
  }
  assert.equal(
    listed.some((thread) => thread.threadId === state.sideThreadId),
    false,
    "重启后 thread/list 恢复已关闭侧聊",
  );
  assert.equal(await access(retainedFile).then(() => true), true, "重启后隔离 workspace 文件丢失");
  assert.equal(
    await page
      .locator(
        '[data-workbench-tab="side-task:' +
          state.sideThreadId +
          '"], [data-tab="side-task:' +
          state.sideThreadId +
          '"]',
      )
      .count(),
    0,
    "重启后已关闭侧聊 Tab 被恢复",
  );
  await capture(page, "04-side-chat-after-restart");
  let normalExitSideThreadId;
  if (normalExitRequested) {
    await page
      .locator(`.ja-navigation-thread-row button[data-thread-id="${state.rootThreadId}"]`)
      .click();
    const liveSide = await openSideChat(page);
    normalExitSideThreadId = liveSide.threadId;
    assert.equal(
      (await readThread(page, liveSide.threadId)).ok,
      true,
      "正常退出测试必须有真实存活侧聊",
    );
  }
  const normalExit = normalExitRequested ? await requestNormalExit(page) : undefined;
  return {
    status: "restart_passed",
    closedThreadUnreadable: true,
    rootRestored: true,
    retainedFile: true,
    normalExit,
    normalExitSideThreadId,
  };
}

/** 连接现有隔离 WebView2，不拥有 Tauri/Java 生命周期；launcher 负责两阶段重启和 cleanup。 */
async function main() {
  validateEnvironment();
  await mkdir(evidenceDirectory, { recursive: true });
  const provider = phase === "initial" ? await startLoopbackProvider() : undefined;
  let browser;
  try {
    browser = await chromium.connectOverCDP(endpoint);
    const page = browser
      .contexts()
      .flatMap((context) => context.pages())
      .find((candidate) => !candidate.isClosed());
    if (page === undefined) throw new Error("隔离 WebView2 没有可用页面");
    page.on("pageerror", (error) =>
      process.stderr.write(`JA_SIDE_CHAT_PAGEERROR ${error.message}\n`),
    );
    page.on("console", (message) => {
      if (message.type() === "error")
        process.stderr.write(`JA_SIDE_CHAT_CONSOLE_ERROR ${message.text()}\n`);
    });
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    const report = phase !== "initial"
      ? await runRestart(page)
      : scenario === "approvals"
        ? await runApprovals(page, provider)
        : await runInitial(page, provider);
    await writeFile(
      join(evidenceDirectory, `side-chat-${phase}.json`),
      `${JSON.stringify(report, null, 2)}\n`,
      "utf8",
    );
    process.stdout.write(`JA_SIDE_CHAT_${phase.toUpperCase()}_OK evidence=${evidenceDirectory}\n`);
  } catch (error) {
    // 保留真实失败界面和请求记录，避免超时后只能猜测是产品错误还是脚本定位错误。
    const failedPage = browser
      ?.contexts()
      .flatMap((context) => context.pages())
      .find((candidate) => !candidate.isClosed());
    if (failedPage !== undefined) {
      await capture(failedPage, `failure-${phase}`).catch(() => undefined);
      await writeFile(
        join(evidenceDirectory, `failure-${phase}.json`),
        JSON.stringify(
          {
            error: safeError(error),
            body: await failedPage
              .locator("body")
              .innerText()
              .catch(() => ""),
            requests: provider?.requests ?? [],
          },
          null,
          2,
        ),
        "utf8",
      );
    }
    throw error;
  } finally {
    await browser?.close();
    if (provider !== undefined) {
      provider.server.closeAllConnections();
      await provider.close();
    }
  }
}

await main();
