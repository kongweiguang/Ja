// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import {
  copyFile,
  mkdir,
  readdir,
  readFile,
  realpath,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  createIsolatedDirectories,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";
import { assertThreadHistory, startCollaborationProvider } from "./coding-agent-collaboration.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const providerId = "provider_coding_agent_collaboration";
const modelId = "model_coding_agent_collaboration";
const credentialId = "cred_coding_agent_collaboration";
const placeholderSecret = "coding-agent-collaboration-loopback-placeholder";
const requestTimeoutMs = 2_000;
const cliTimeoutMs = 180_000;
const serverStopTimeoutMs = 30_000;
const artifactReadPageCharacters = 16_384;
const maximumArtifactCharacters = 1_000_000;
const mainMarker = "JA_CODING_AGENT_COLLABORATION_MAIN";
const mainFinalMarker = "CODING_AGENT_COLLABORATION_MAIN_FINAL_OK";
const childFinalMarker = "CODING_AGENT_COLLABORATION_CHILD_FINAL_OK";
const childFileMarker = "CHILD_WORKSPACE_WRITE_OK";
const mainFileMarker = "MAIN_WORKSPACE_WRITE_OK";
const expectedMainCalls = Object.freeze([
  ["call_collaboration_warm_read", "read"],
  ["call_collaboration_spawn", "spawn_agent"],
  ["call_collaboration_wait", "wait_agent"],
  ["call_collaboration_list", "list_agents"],
  ["call_collaboration_read_child", "read"],
  ["call_collaboration_main_write", "write"],
]);
const expectedChildCalls = Object.freeze([["call_collaboration_child_write", "write"]]);
const allowedJsonlTypes = new Set([
  "accepted",
  "notification",
  "requires_input",
  "terminal",
  "error",
]);

/** 仅接受明确指定的 CLI、Native server 与仓库外证据目录，避免环境默认值串入验收。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const options = {};
  const names = new Map([
    ["--cli", "cli"],
    ["--native-executable", "nativeExecutable"],
    ["--evidence-directory", "evidenceDirectory"],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const key = names.get(argv[index]);
    if (key === undefined) throw new Error(`unknown argument: ${argv[index]}`);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--") || value.trim() === "") {
      throw new Error(`${argv[index]} requires a path`);
    }
    if (options[key] !== undefined) throw new Error(`${argv[index]} can only be specified once`);
    options[key] = resolve(value);
    index += 1;
  }
  for (const [flag, key] of names) {
    if (options[key] === undefined) throw new Error(`missing ${flag}`);
  }
  return options;
}

/** 严格解析 CLI 的 stdout JSONL；普通文本不能冒充 exec --json 的可机读结果。 */
export function parseCliJsonl(stdout) {
  const lines = String(stdout)
    .split(/\r?\n/u)
    .filter((line) => line.trim() !== "");
  if (lines.length === 0) throw new Error("CLI JSONL stdout was empty");
  return lines.map((line) => {
    let record;
    try {
      record = JSON.parse(line);
    } catch {
      throw new Error("CLI --json stdout contained a non-JSON line");
    }
    if (
      record === null ||
      typeof record !== "object" ||
      record.version !== 2 ||
      !allowedJsonlTypes.has(record.type)
    ) {
      throw new Error("CLI --json stdout contained an invalid v2 record");
    }
    return record;
  });
}

/** 接受唯一成功终态，并核对 CLI 实际收到父 Thread 全部协作工具的成功事件。 */
export function assertCliJsonlCompletion(records) {
  const accepted = records.filter((record) => record.type === "accepted");
  const terminals = records.filter((record) => record.type === "terminal");
  if (
    accepted.length !== 1 ||
    terminals.length !== 1 ||
    records.some((record) => record.type === "error" || record.type === "requires_input")
  ) {
    throw new Error("CLI JSONL did not converge to one accepted completed Turn");
  }
  const start = accepted[0];
  const terminal = terminals[0];
  if (
    typeof start.serverInstanceId !== "string" ||
    typeof start.threadId !== "string" ||
    typeof start.turnId !== "string" ||
    terminal.serverInstanceId !== start.serverInstanceId ||
    terminal.threadId !== start.threadId ||
    terminal.turnId !== start.turnId ||
    terminal.state !== "completed" ||
    typeof terminal.finalText !== "string" ||
    !terminal.finalText.includes(mainFinalMarker)
  ) {
    throw new Error("CLI JSONL acceptance and terminal identities or outcome did not match");
  }

  const notifications = records.filter((record) => record.type === "notification");
  const calls = notifications
    .filter((record) => record.method === "assistant/model-step-committed")
    .flatMap((record) => (Array.isArray(record.params?.toolCalls) ? record.params.toolCalls : []));
  const results = notifications
    .filter((record) => record.method === "tool/batch-committed")
    .flatMap((record) => (Array.isArray(record.params?.results) ? record.params.results : []));
  for (const [callId, toolName] of expectedMainCalls) {
    if (calls.filter((call) => call.callId === callId && call.toolName === toolName).length !== 1) {
      throw new Error(`CLI JSONL did not report exactly one ${toolName} call`);
    }
    const matching = results.filter((result) => result.callId === callId);
    if (
      matching.length !== 1 ||
      matching[0].outcome !== "succeeded" ||
      matching[0].presentation?.status !== "success"
    ) {
      throw new Error(`CLI JSONL did not report one successful ${toolName} result`);
    }
  }
  return {
    serverInstanceId: start.serverInstanceId,
    threadId: start.threadId,
    turnId: start.turnId,
    state: terminal.state,
    notificationCount: notifications.length,
    parentToolCallCount: expectedMainCalls.length,
  };
}

/** 以串流 SHA-256 绑定被复制和实际执行的二进制，不在报告中保存程序内容。 */
async function sha256File(path) {
  return new Promise((resolveDigest, rejectDigest) => {
    const digest = createHash("sha256");
    const stream = createReadStream(path);
    stream.on("data", (chunk) => digest.update(chunk));
    stream.once("error", rejectDigest);
    stream.once("end", () => resolveDigest(digest.digest("hex")));
  });
}

/** 检查显式产物为非空普通文件并冻结其 SHA 与长度，后续复制必须逐字节一致。 */
async function inspectArtifact(path, label) {
  const absolutePath = resolve(path);
  const metadata = await stat(absolutePath);
  if (!metadata.isFile() || metadata.size === 0)
    throw new Error(`${label} artifact is unavailable`);
  return {
    path: absolutePath,
    fileName: basename(absolutePath),
    sizeBytes: metadata.size,
    sha256: await sha256File(absolutePath),
  };
}

/** 只复制当前已打包的 fd/rg 与许可证，且逐项对照 tools manifest 防止 PATH 偶然补齐。 */
async function copyPackagedTools(destination) {
  const source = join(repoRoot, "apps", "desktop", "src-tauri", "sidecars", "tools");
  const manifestPath = join(source, "tools-manifest.json");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  if (
    manifest?.schemaVersion !== 1 ||
    manifest?.product !== "Ja" ||
    manifest?.stagingMode !== "copy" ||
    manifest?.target?.targetTriple !== "x86_64-pc-windows-msvc" ||
    !Array.isArray(manifest.tools) ||
    manifest.tools.length !== 2
  ) {
    throw new Error("packaged fd/rg manifest does not match Windows x64 production resources");
  }
  await mkdir(destination, { recursive: true });
  const entries = await readdir(source, { withFileTypes: true });
  const copied = [];
  for (const entry of entries) {
    if (!entry.isFile()) throw new Error("packaged search tools must be flat regular files");
    await copyFile(join(source, entry.name), join(destination, entry.name));
  }
  for (const tool of manifest.tools) {
    const relativePath = tool?.binary?.relativePath;
    const fileName = basename(relativePath ?? "");
    if (
      typeof relativePath !== "string" ||
      relativePath !== `tools/${fileName}` ||
      fileName !== `${tool.name}.exe`
    ) {
      throw new Error("packaged search tool manifest contains an unexpected resource path");
    }
    const sourcePath = join(source, fileName);
    const stagedPath = join(destination, fileName);
    const sourceStat = await stat(sourcePath);
    const stagedStat = await stat(stagedPath);
    const sourceHash = await sha256File(sourcePath);
    const stagedHash = await sha256File(stagedPath);
    if (
      sourceStat.size !== tool.binary.sizeBytes ||
      sourceHash !== tool.binary.sha256 ||
      stagedStat.size !== sourceStat.size ||
      stagedHash !== sourceHash
    ) {
      throw new Error(`packaged ${tool.name} identity does not match its manifest`);
    }
    copied.push({ name: tool.name, sizeBytes: stagedStat.size, sha256: stagedHash });
  }
  return copied;
}

/** 组装隔离便携目录；CLI 只从该目录 sidecars/ 读取指定 Native 和其 fd/rg 资源。 */
async function stageCliPackage(cliArtifact, nativeArtifact, root) {
  const packageDirectory = join(root, "cli-package");
  const sidecarDirectory = join(packageDirectory, "sidecars");
  const cliPath = join(packageDirectory, "ja.exe");
  const nativePath = join(sidecarDirectory, "ja-app-server-x86_64-pc-windows-msvc.exe");
  await mkdir(sidecarDirectory, { recursive: true });
  await copyFile(cliArtifact.path, cliPath);
  await copyFile(nativeArtifact.path, nativePath);
  const [cliHash, nativeHash] = await Promise.all([sha256File(cliPath), sha256File(nativePath)]);
  if (cliHash !== cliArtifact.sha256 || nativeHash !== nativeArtifact.sha256) {
    throw new Error("isolated CLI package binary identity changed while copying");
  }
  const tools = await copyPackagedTools(join(sidecarDirectory, "tools"));
  return {
    packageDirectory,
    cliPath,
    nativePath,
    tools,
  };
}

/** 为 App Server 建立标准 JA_HOME 四目录，并把所有内容限定在本次物理临时 profile。 */
async function createAuditDirectories() {
  const isolated = await createIsolatedDirectories();
  const directories = {
    ...isolated,
    data: join(isolated.home, "data"),
    run: join(isolated.home, "run"),
    logs: join(isolated.home, "logs", "java"),
  };
  await Promise.all(
    [directories.data, directories.run, directories.logs].map((path) =>
      mkdir(path, { recursive: true }),
    ),
  );
  return directories;
}

/** 把 JA-RPC 错误压缩为合同中的稳定码，拒绝将 Native 任意错误正文写入证据。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined)
    throw new Error(`${operation} failed: ${requireRpcErrorCode(frame.error)}`);
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** Native bootstrap 和重启回读共用同一 JA_HOME，且只接受真实 Kernel ready 回执。 */
async function startReadySession(nativePath, directories) {
  const session = new JsonlSession({
    command: nativePath,
    prefixArgs: [],
    directories,
    apiKey: placeholderSecret,
    endpoint: "http://127.0.0.1",
  });
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams(), 30_000),
      "runtime/initialize",
    );
    if (initialized?.runtime?.engine !== "ja-kernel") {
      throw new Error("Native bootstrap did not start the Ja Kernel runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    return session;
  } catch (error) {
    await session.forceClose();
    if (!session.exited && error !== null && typeof error === "object") {
      error.nativeSessionMayExist = true;
    }
    throw error;
  }
}

/** 只在隔离 JA_HOME 写入 loopback Provider、占位凭据与 workspace trust，不读取用户 profile。 */
async function seedIsolatedProfile(session, fixture, directories) {
  const current = rpcResult(
    await session.request("configuration/read", {}, 20_000),
    "configuration/read",
  );
  const document = providerConfigurationDocument({
    endpoint: fixture.baseUrl,
    name: "Ja Subagent CLI acceptance loopback fixture",
    api: "openai_responses",
    model: "coding-agent-collaboration-fixture",
    selectedProviderId: providerId,
    selectedModelId: modelId,
    selectedCredentialId: credentialId,
  });
  document.providers[0].network_timeouts.request_timeout_ms = requestTimeoutMs;
  const configured = rpcResult(
    await session.request(
      "configuration/replace",
      { scope: "user", expectedVersion: current.cas.userVersion, document },
      20_000,
    ),
    "configuration/replace",
  );
  assert.equal(configured.accepted, true);
  const credential = rpcResult(
    await session.request(
      "credential/set",
      {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: current.cas.credentialVersion,
      },
      20_000,
    ),
    "credential/set",
  );
  assert.equal(credential.configured, true);
  const workspace = rpcResult(
    await session.request(
      "workspace/open",
      { cwd: directories.workspace, displayName: "Ja Subagent CLI acceptance" },
      20_000,
    ),
    "workspace/open",
  );
  assert.ok(typeof workspace.workspaceId === "string" && workspace.workspaceId.startsWith("ws_"));
  const trusted = rpcResult(
    await session.request(
      "workspace/set-trust",
      { workspaceId: workspace.workspaceId, trust: "trusted" },
      20_000,
    ),
    "workspace/set-trust",
  );
  assert.equal(trusted.accepted, true);
  return workspace.workspaceId;
}

/** 执行一个有界独立子进程并捕获 stdout/stderr；超时或超量输出时不继续堆积内存。 */
async function runProcess(
  command,
  args,
  { cwd, env, timeoutMs, maximumOutputBytes = 8 * 1024 * 1024 },
) {
  return new Promise((resolveProcess, rejectProcess) => {
    const child = spawn(command, args, {
      cwd,
      env,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const output = { stdout: [], stderr: [], bytes: 0, timedOut: false, oversized: false };
    let settled = false;
    let timer;
    const finishError = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      rejectProcess(error);
    };
    const collect = (key, chunk) => {
      output.bytes += chunk.length;
      if (output.bytes > maximumOutputBytes) {
        output.oversized = true;
        child.kill();
        return;
      }
      output[key].push(chunk);
    };
    child.stdout.on("data", (chunk) => collect("stdout", chunk));
    child.stderr.on("data", (chunk) => collect("stderr", chunk));
    child.once("error", () => finishError(new Error("could not start isolated CLI process")));
    timer = setTimeout(() => {
      output.timedOut = true;
      child.kill();
    }, timeoutMs);
    child.once("close", (code, signal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (output.timedOut) {
        rejectProcess(new Error("isolated CLI process exceeded its bounded deadline"));
      } else if (output.oversized) {
        rejectProcess(new Error("isolated CLI process exceeded its bounded output limit"));
      } else {
        resolveProcess({
          code,
          signal,
          stdout: Buffer.concat(output.stdout).toString("utf8"),
          stderr: Buffer.concat(output.stderr).toString("utf8"),
        });
      }
    });
  });
}

/** CLI 子进程显式绑定本次 JA_HOME，并清掉仅用于开发或付费 Provider smoke 的覆盖项。 */
function isolatedCliEnvironment(home) {
  const environment = { ...process.env, JA_HOME: home };
  for (const key of [
    "JA_DEBUG_JAVA",
    "JA_DEBUG_JAR",
    "JA_E2E_APP_SERVER_JAR",
    "JA_E2E_RUNTIME_ROOT",
    "JA_REAL_PROVIDER_API_KEY",
    "JA_REAL_PROVIDER_BASE_URL",
    "JA_REAL_PROVIDER_MODEL",
    "JA_REAL_PROVIDER_NAME",
    "JA_REAL_PROVIDER_API",
    "JA_REAL_PROVIDER_REASONING_LEVEL",
    "JA_REAL_PROVIDER_AUTHORIZED",
  ]) {
    delete environment[key];
  }
  return environment;
}

/** JSONL accepted 证明 CLI 已启动后，stop 仅使用隔离 JA_HOME 并等待 endpoint 原子清理。 */
async function stopIsolatedDaemon(cliPath, directories) {
  const endpointPath = join(directories.run, "app-server.endpoint.json");
  const stopped = await runProcess(cliPath, ["server", "stop"], {
    cwd: directories.workspace,
    env: isolatedCliEnvironment(directories.home),
    timeoutMs: serverStopTimeoutMs,
  });
  if (stopped.code !== 0) throw new Error("isolated ja server stop was not confirmed");
  const deadline = Date.now() + serverStopTimeoutMs;
  while (Date.now() < deadline) {
    try {
      await stat(endpointPath);
    } catch (error) {
      if (error?.code === "ENOENT") return;
      throw new Error("isolated server endpoint could not be checked");
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  throw new Error("isolated server stop was acknowledged but endpoint remained published");
}

/** 轮询 endpoint 不存在，关闭确认前绝不重启同一 JA_HOME 的历史读取进程。 */
async function assertEndpointAbsent(path) {
  try {
    await stat(path);
    throw new Error("isolated runtime endpoint unexpectedly exists before CLI exec");
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
}

/** 从同一 Thread snapshot 定位唯一工具调用，并按需用 artifact RPC 回读完整持久结果。 */
async function readExactToolOutput(session, threadId, turnId, item) {
  const presentation = item?.presentation;
  if (presentation?.artifactId !== undefined) {
    let offsetCharacters = 0;
    let content = "";
    for (let pageNumber = 0; pageNumber < 64; pageNumber += 1) {
      const page = rpcResult(
        await session.request(
          "tool/artifact/read",
          {
            threadId,
            turnId,
            callId: item.callId,
            artifactId: presentation.artifactId,
            offsetCharacters,
            limitCharacters: artifactReadPageCharacters,
          },
          20_000,
        ),
        "tool/artifact/read",
      );
      if (
        page.artifactId !== presentation.artifactId ||
        page.offsetCharacters !== offsetCharacters ||
        typeof page.content !== "string" ||
        !Number.isSafeInteger(page.totalCharacters) ||
        page.totalCharacters > maximumArtifactCharacters
      ) {
        throw new Error("tool artifact page identity or size did not match");
      }
      content += page.content;
      if (page.nextOffsetCharacters === null) {
        if (Array.from(content).length !== page.totalCharacters) {
          throw new Error("tool artifact history did not return its exact character count");
        }
        return content;
      }
      if (
        !Number.isSafeInteger(page.nextOffsetCharacters) ||
        page.nextOffsetCharacters <= offsetCharacters
      ) {
        throw new Error("tool artifact cursor did not advance");
      }
      offsetCharacters = page.nextOffsetCharacters;
    }
    throw new Error("tool artifact exceeded the bounded history page count");
  }
  if (presentation?.truncated === true || typeof presentation?.outputPreview !== "string") {
    throw new Error("tool result had no complete persisted preview or artifact");
  }
  return presentation.outputPreview;
}

/** 在 Task Tool 回执中按真实 Thread ID 查找记录，不从名称或请求顺序合成身份。 */
function findTaskRecord(value, threadId) {
  if (value !== null && typeof value === "object") {
    if (value.threadId === threadId) return value;
    for (const nested of Object.values(value)) {
      const found = findTaskRecord(nested, threadId);
      if (found !== null) return found;
    }
  }
  if (Array.isArray(value)) {
    for (const nested of value) {
      const found = findTaskRecord(nested, threadId);
      if (found !== null) return found;
    }
  }
  return null;
}

/** 从真实 spawn 回执中找出其返回的 child identity，并支持结构化与 JSON 文本载荷。 */
function findThreadId(value) {
  if (typeof value === "string") {
    const match = value.match(/\bthr_[A-Za-z0-9_-]{8,128}\b/u);
    if (match !== null) return match[0];
    try {
      return findThreadId(JSON.parse(value));
    } catch {
      return null;
    }
  }
  if (Array.isArray(value)) {
    for (const nested of value) {
      const found = findThreadId(nested);
      if (found !== null) return found;
    }
    return null;
  }
  if (value !== null && typeof value === "object") {
    if (typeof value.threadId === "string" && /^thr_[A-Za-z0-9_-]{8,128}$/u.test(value.threadId)) {
      return value.threadId;
    }
    for (const nested of Object.values(value)) {
      const found = findThreadId(nested);
      if (found !== null) return found;
    }
  }
  return null;
}

/** 等待期限只结束观察；保留超时的真实状态，最终完成仍由后续 list、历史和文件共同核验。 */
export function assertWaitReceipt(receipt, childThreadId) {
  const child = findTaskRecord(receipt, childThreadId);
  const valid = typeof receipt?.timedOut === "boolean"
    && (receipt.timedOut
      ? ["queued", "running", "completed"].includes(child?.status)
      : child?.status === "completed");
  if (!valid) throw new Error("wait_agent exact result lost its authoritative child status");
  return { timedOut: receipt.timedOut, childStatus: child.status };
}

/** 子结果从 child 历史确认；持久 Tool 正文是原始 JSON，Provider 投影才附加审计尾注，两者不能混用解析器。 */
async function verifyToolResults(session, mainHistory, childHistory, identity, childThreadId) {
  const mainMeta = assertThreadHistory(
    mainHistory,
    identity.threadId,
    expectedMainCalls,
    mainFinalMarker,
  );
  const childMeta = assertThreadHistory(
    childHistory,
    childThreadId,
    expectedChildCalls,
    childFinalMarker,
  );
  const results = {};
  const readResults = async (history, threadId, turnId, expectedCalls, role) => {
    const turnItems = history.items.filter(
      (item) => item.kind === "tool_call" && item.turnId === turnId,
    );
    for (const [callId, toolName] of expectedCalls) {
      const matches = turnItems.filter(
        (item) => item.callId === callId && item.toolName === toolName,
      );
      if (matches.length !== 1)
        throw new Error(`${role} history did not contain one ${toolName} result`);
      const text = await readExactToolOutput(session, threadId, turnId, matches[0]);
      let waitObservation;
      if (role === "main" && callId === "call_collaboration_spawn") {
        if (findThreadId(text) !== childThreadId)
          throw new Error("spawn result lost its child identity");
      } else if (role === "main" && callId === "call_collaboration_wait") {
        const receipt = JSON.parse(text);
        waitObservation = assertWaitReceipt(receipt, childThreadId);
      } else if (role === "main" && callId === "call_collaboration_list") {
        const receipt = JSON.parse(text);
        if (findTaskRecord(receipt, childThreadId)?.status !== "completed") {
          throw new Error("list_agents exact result did not include the completed child");
        }
      } else if (role === "main" && callId === "call_collaboration_read_child") {
        if (!text.includes(childFileMarker))
          throw new Error("parent read result missed the child's written marker");
      }
      results[callId] = {
        ...(waitObservation === undefined ? {} : { waitObservation }),
        role,
        toolName,
        status: matches[0].presentation.status,
        characters: Array.from(text).length,
        sha256: createHash("sha256").update(text, "utf8").digest("hex"),
      };
    }
  };
  await readResults(mainHistory, identity.threadId, mainMeta.turnId, expectedMainCalls, "main");
  await readResults(childHistory, childThreadId, childMeta.turnId, expectedChildCalls, "child");
  return { main: mainMeta, child: childMeta, toolResults: results };
}

/** 验收失败时仍输出短证据；替换临时目录、Provider URL 与占位密钥后再写外部 JSON。 */
async function persistEvidence(directory, report, redactions) {
  const target = resolve(directory);
  const relativePath = relative(repoRoot, target);
  const targetIsInsideRepository =
    !isAbsolute(relativePath) && relativePath !== ".." && !relativePath.startsWith(`..${sep}`);
  if (relativePath === "" || targetIsInsideRepository) {
    throw new Error("evidence directory must be outside the source repository");
  }
  await mkdir(target, { recursive: true });
  const file = join(
    target,
    `subagent-cli-acceptance-${new Date().toISOString().replaceAll(/[:.]/gu, "-")}.json`,
  );
  let json = JSON.stringify(report, null, 2);
  for (const value of redactions) {
    if (typeof value === "string" && value !== "") json = json.replaceAll(value, "[redacted]");
  }
  await writeFile(file, `${json}\n`, "utf8");
  return file;
}

/** 只清除本 runner 建立且仍位于物理系统临时目录的隔离 profile。 */
async function cleanupOwnedProfile(root) {
  const physicalTemp = await realpath(tmpdir());
  const target = resolve(root);
  if (dirname(target) !== physicalTemp || !basename(target).startsWith("ja-real-provider-")) {
    throw new Error("refusing to clean a profile not owned by this acceptance runner");
  }
  await rm(target, { recursive: true, force: false });
}

/** 执行一次 Native seed、真实 CLI exec 与停服后历史回读，并始终保留仓库外结果证据。 */
export async function runAcceptance(argv = process.argv.slice(2)) {
  if (process.platform !== "win32" || process.arch !== "x64") {
    throw new Error("subagent CLI acceptance requires Windows x64");
  }
  const options = parseArguments(argv);
  const cliArtifact = await inspectArtifact(options.cli, "CLI");
  const nativeArtifact = await inspectArtifact(options.nativeExecutable, "Native App Server");
  const directories = await createAuditDirectories();
  const report = {
    status: "running",
    evidenceScope:
      "Windows x64 CLI exec --json + Native JA-RPC spawn/wait/list + persisted parent/child history; no TUI claim",
    cli: {
      fileName: cliArtifact.fileName,
      sizeBytes: cliArtifact.sizeBytes,
      sha256: cliArtifact.sha256,
    },
    nativeAppServer: {
      fileName: nativeArtifact.fileName,
      sizeBytes: nativeArtifact.sizeBytes,
      sha256: nativeArtifact.sha256,
    },
    phases: [],
    cleanup: {},
  };
  const redactions = [placeholderSecret, directories.root, directories.home, directories.workspace];
  let currentStage = "stage_isolated_package";
  let packagePaths;
  let fixture;
  let session;
  let cliDaemonMayExist = false;
  let nativeSessionMayExist = false;
  try {
    packagePaths = await stageCliPackage(cliArtifact, nativeArtifact, directories.root);
    const stagedNativeStat = await stat(packagePaths.nativePath);
    report.cli.sha256AfterCopy = await sha256File(packagePaths.cliPath);
    report.nativeAppServer.sha256AfterCopy = await sha256File(packagePaths.nativePath);
    report.tools = packagePaths.tools;
    assert.equal(report.cli.sha256AfterCopy, cliArtifact.sha256);
    assert.equal(report.nativeAppServer.sha256AfterCopy, nativeArtifact.sha256);
    assert.equal(stagedNativeStat.size, nativeArtifact.sizeBytes);
    report.phases.push(currentStage);

    currentStage = "prepare_workspace_and_loopback_provider";
    await mkdir(join(directories.workspace, "src"), { recursive: true });
    await writeFile(
      join(directories.workspace, "README.md"),
      "Coding Agent collaboration acceptance.\n",
      "utf8",
    );
    fixture = await startCollaborationProvider();
    redactions.push(fixture.baseUrl);
    report.phases.push(currentStage);

    currentStage = "seed_profile_and_trust";
    session = await startReadySession(packagePaths.nativePath, directories);
    nativeSessionMayExist = true;
    report.workspaceId = await seedIsolatedProfile(session, fixture, directories);
    const seedExit = await session.shutdown();
    session = undefined;
    nativeSessionMayExist = false;
    if (seedExit.code !== 0) throw new Error("Native profile bootstrap did not shut down cleanly");
    report.phases.push(currentStage);

    currentStage = "run_cli_exec_json";
    await assertEndpointAbsent(join(directories.run, "app-server.endpoint.json"));
    cliDaemonMayExist = true;
    const cliResult = await runProcess(
      packagePaths.cliPath,
      [
        "exec",
        "-C",
        directories.workspace,
        "--json",
        "--permissions",
        "full-access",
        `Read the workspace, delegate a workspace write, wait, list, and read it. ${mainMarker}`,
      ],
      {
        cwd: directories.workspace,
        env: isolatedCliEnvironment(directories.home),
        timeoutMs: cliTimeoutMs,
      },
    );
    report.cliProcess = { exitCode: cliResult.code, signal: cliResult.signal };
    const cliRecords = parseCliJsonl(cliResult.stdout);
    const identity = assertCliJsonlCompletion(cliRecords);
    if (cliResult.code !== 0) throw new Error("CLI exec --json exited unsuccessfully");
    report.exec = identity;
    report.jsonlRecordCount = cliRecords.length;
    const childThreadId = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "wait",
    )?.childThreadId;
    if (typeof childThreadId !== "string" || !childThreadId.startsWith("thr_")) {
      throw new Error("Provider fixture did not observe the real spawn_agent child identity");
    }
    report.childThreadId = childThreadId;
    const warmReadAt = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "warm_read",
    )?.atMs;
    const spawnAt = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "spawn",
    )?.atMs;
    if (!Number.isSafeInteger(warmReadAt) || !Number.isSafeInteger(spawnAt)) {
      throw new Error("Provider fixture did not observe parent continuation stages");
    }
    report.parentContinuationMs = spawnAt - warmReadAt;
    if (report.parentContinuationMs <= requestTimeoutMs) {
      throw new Error("parent continuation did not cross the configured Provider request window");
    }
    const observedStages = new Set(
      fixture.observations.map((item) => `${item.role}:${item.stage}`),
    );
    for (const required of [
      "child:write",
      "child:final",
      "main:wait_result",
      "main:list_result",
      "main:read_child",
      "main:main_write",
      "main:final",
    ]) {
      if (!observedStages.has(required)) throw new Error(`Provider fixture missed ${required}`);
    }
    if (fixture.failure() !== null)
      throw new Error("loopback Provider fixture observed an invalid request");
    report.fixture = {
      requestTimeoutMs,
      parentContinuationMs: report.parentContinuationMs,
      observations: fixture.observations.map((item) => ({
        role: item.role,
        stage: item.stage,
        ...(item.childThreadId === undefined ? {} : { childThreadId: item.childThreadId }),
        ...(item.childStatus === undefined ? {} : { childStatus: item.childStatus }),
        ...(item.waitTimedOut === undefined ? {} : { waitTimedOut: item.waitTimedOut }),
      })),
      noFixtureFailure: true,
    };
    report.phases.push(currentStage);

    currentStage = "stop_isolated_cli_daemon";
    await stopIsolatedDaemon(packagePaths.cliPath, directories);
    cliDaemonMayExist = false;
    report.cleanup.cliDaemon = "stop_acknowledged_and_endpoint_removed";
    report.phases.push(currentStage);

    currentStage = "reopen_native_and_read_history";
    session = await startReadySession(packagePaths.nativePath, directories);
    nativeSessionMayExist = true;
    const parentHistory = rpcResult(
      await session.request(
        "thread/read",
        { threadId: identity.threadId, tail: true, limit: 200 },
        20_000,
      ),
      "thread/read parent after CLI daemon stop",
    );
    const childHistory = rpcResult(
      await session.request(
        "thread/read",
        { threadId: childThreadId, tail: true, limit: 200 },
        20_000,
      ),
      "thread/read child after CLI daemon stop",
    );
    const taskTree = rpcResult(
      await session.request("task/list", { rootThreadId: identity.threadId }, 20_000),
      "task/list after CLI daemon stop",
    );
    const matchingTasks = taskTree.items.filter((task) => task.taskThreadId === childThreadId);
    if (
      matchingTasks.length !== 1 ||
      matchingTasks[0].taskKind !== "subagent" ||
      matchingTasks[0].parentThreadId !== identity.threadId ||
      matchingTasks[0].state !== "completed"
    ) {
      throw new Error("persisted task/list did not return the completed child under its parent");
    }
    const mainPostimage = await readFile(
      join(directories.workspace, "src", "collaboration-main.txt"),
      "utf8",
    );
    const childPostimage = await readFile(
      join(directories.workspace, "src", "collaboration-child.txt"),
      "utf8",
    );
    assert.equal(mainPostimage, `${mainFileMarker}\n`);
    assert.equal(childPostimage, `${childFileMarker}\n`);
    report.workspaceWrites = {
      main: { exact: true, sha256: createHash("sha256").update(mainPostimage).digest("hex") },
      child: { exact: true, sha256: createHash("sha256").update(childPostimage).digest("hex") },
    };
    report.history = await verifyToolResults(
      session,
      parentHistory,
      childHistory,
      identity,
      childThreadId,
    );
    report.taskList = {
      count: taskTree.items.length,
      childKind: matchingTasks[0].taskKind,
      childState: matchingTasks[0].state,
      childParentMatches: matchingTasks[0].parentThreadId === identity.threadId,
    };
    report.phases.push(currentStage);
    report.status = "completed";
  } catch (error) {
    report.status = "failed";
    nativeSessionMayExist ||= error?.nativeSessionMayExist === true;
    report.failure = {
      stage: currentStage,
      message: String(error?.message ?? error).slice(0, 400),
    };
  } finally {
    if (session !== undefined) {
      try {
        const exit = await session.shutdown();
        nativeSessionMayExist = false;
        report.cleanup.historySession = exit.code === 0 ? "shutdown" : "nonzero_exit";
        if (exit.code !== 0) report.status = "failed";
      } catch {
        await session.forceClose().catch(() => undefined);
        nativeSessionMayExist = !session.exited;
        report.cleanup.historySession = nativeSessionMayExist ? "exit_unconfirmed" : "force_closed";
        report.status = "failed";
      }
    }
    if (cliDaemonMayExist && packagePaths !== undefined) {
      try {
        await stopIsolatedDaemon(packagePaths.cliPath, directories);
        cliDaemonMayExist = false;
        report.cleanup.cliDaemon = "stop_acknowledged_and_endpoint_removed";
      } catch {
        report.cleanup.cliDaemon = "stop_unconfirmed";
      }
    }
    if (fixture !== undefined) {
      try {
        await fixture.close();
        report.cleanup.loopbackProvider = "closed";
      } catch {
        report.cleanup.loopbackProvider = "close_failed";
        report.status = "failed";
      }
    }
    if (cliDaemonMayExist || nativeSessionMayExist) {
      report.cleanup.profile = "retained_because_runtime_stop_was_not_confirmed";
      report.status = "failed";
    } else {
      try {
        await cleanupOwnedProfile(directories.root);
        report.cleanup.profile = "removed";
      } catch {
        report.cleanup.profile = "removal_failed";
        report.status = "failed";
      }
    }
    try {
      report.evidenceFile = await persistEvidence(options.evidenceDirectory, report, redactions);
    } catch (error) {
      report.evidenceFile = null;
      report.evidenceFailure = String(error?.message ?? error).slice(0, 240);
      report.status = "failed";
    }
  }
  return report;
}

/** 控制台仅打印状态、产物摘要与外部证据路径，不回显请求正文、环境或工具输出。 */
async function main() {
  const report = await runAcceptance();
  process.stdout.write(
    `${JSON.stringify({
      status: report.status,
      evidenceFile: report.evidenceFile ?? null,
      cliSha256: report.cli?.sha256 ?? null,
      nativeSha256: report.nativeAppServer?.sha256 ?? null,
      failure: report.failure?.stage ?? report.evidenceFailure ?? null,
    })}\n`,
  );
  if (report.status !== "completed") process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(
      `Subagent CLI acceptance failed: ${String(error?.message ?? error).slice(0, 400)}\n`,
    );
    process.exitCode = 1;
  });
}
