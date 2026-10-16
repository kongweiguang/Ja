// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { execFile } from "node:child_process";
import { copyFile, lstat, mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  compileConPtyHarness,
  removeOwnedProfile,
  startConPtyCli,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import { initializeParams, JsonlSession } from "./real-provider-smoke.mjs";

const execFileAsync = promisify(execFile);
const nativeSidecarName = "ja-app-server-x86_64-pc-windows-msvc.exe";
const expectedProviderId = "provider_migrated_fd8586ce8ceafbd77fb9b933";
const expectedModelId = "model_5bff936c5d7d45ac8297069cf9d686a7";
const restrictedEnvironmentNames = [
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
];
const delay = (milliseconds) => new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));

/** 真实模型验收必须显式绑定安装 CLI、相邻 Native、JDK25、模型配置副本和 ACL 隔离的证据目录，避免误连用户 JA_HOME。 @author kongweiguang */
export function parseArguments(argv = process.argv.slice(2)) {
  const options = {};
  options.preflightOnly = false;
  const names = new Map([
    ["--cli", "cli"],
    ["--native-executable", "nativeExecutable"],
    ["--jar", "jar"],
    ["--java", "java"],
    ["--config", "config"],
    ["--auth", "auth"],
    ["--evidence-directory", "evidenceDirectory"],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === "--preflight-only") {
      if (options.preflightOnly) throw new Error("--preflight-only can only be specified once");
      options.preflightOnly = true;
      continue;
    }
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
  for (const key of names.values()) {
    if (options[key] === undefined) throw new Error(`missing --${key.replace(/[A-Z]/gu, (letter) => `-${letter.toLowerCase()}`)}`);
  }
  return options;
}

/** 校验运行时身份和相邻 sidecar 关系；摘要仅记录安装制品哈希与安全版本号，不读取任何用户配置。 @author kongweiguang */
async function inspectArtifacts(options) {
  assert.equal(process.platform, "win32", "real-model CLI acceptance requires Windows ConPTY");
  assert.equal(basename(options.config), "config.toml", "--config must name the Ja user configuration file");
  assert.equal(basename(options.auth), "auth.json", "--auth must name the Ja auth file");
  assert.equal(resolve(options.nativeExecutable), join(dirname(options.cli), "sidecars", nativeSidecarName),
    "the Native binary must be the sidecar installed next to the explicitly selected CLI");
  const cliRealPath = await realpath(options.cli);
  const nativeRealPath = await realpath(options.nativeExecutable);
  const paths = {
    cli: cliRealPath,
    nativeExecutable: nativeRealPath,
    jar: options.jar,
    java: options.java,
    javac: join(dirname(options.java), "javac.exe"),
    jarTool: join(dirname(options.java), "jar.exe"),
    config: options.config,
    auth: options.auth,
  };
  assert.equal(nativeRealPath.toLowerCase(), join(dirname(cliRealPath), "sidecars", nativeSidecarName).toLowerCase(),
    "the resolved Native binary must remain adjacent to the resolved installed CLI");
  const inputs = {};
  for (const [name, path] of Object.entries(paths)) {
    const metadata = await stat(path);
    assert.ok(metadata.isFile() && metadata.size > 0, `${name} is missing or empty`);
    if (name !== "config" && name !== "auth") {
      inputs[name] = {
        path,
        sizeBytes: metadata.size,
        sha256: createHash("sha256").update(await readFile(path)).digest("hex").toUpperCase(),
      };
    } else {
      inputs[`${name}Bytes`] = metadata.size;
    }
  }
  const version = await execFileAsync(paths.java, ["-version"], {
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 64 * 1024,
  });
  const javaVersion = `${version.stdout}\n${version.stderr}`.trim();
  assert.match(javaVersion, /version\s+"25(?:[.]|"|\s)/u, "this acceptance must use JDK 25");
  const cliVersion = await execFileAsync(paths.cli, ["--version"], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
  assert.match(`${cliVersion.stdout}\n${cliVersion.stderr}`, /0\.2\.1/u, "unexpected installed CLI version");
  return { paths, inputs, javaVersion, cliVersion: `${cliVersion.stdout}\n${cliVersion.stderr}`.trim() };
}

/** 建立唯一运行目录；profile 位于调用方提供且已 ACL 限制的证据根下，并将宿主配置原样复制进空 JA_HOME。 @author kongweiguang */
async function createIsolatedRun(evidenceDirectory, configSource, authSource) {
  await mkdir(evidenceDirectory, { recursive: true });
  const runRoot = await mkdtemp(join(evidenceDirectory, "run-"));
  const profileRoot = join(runRoot, "profile");
  const directories = {
    root: profileRoot,
    home: join(profileRoot, "home"),
    data: join(profileRoot, "home", "data"),
    run: join(profileRoot, "home", "run"),
    logs: join(profileRoot, "home", "logs", "java"),
    workspace: join(profileRoot, "workspace"),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  const configTarget = join(directories.home, "config.toml");
  const authTarget = join(directories.home, "auth.json");
  await Promise.all([copyFile(configSource, configTarget), copyFile(authSource, authTarget)]);
  await protectPrivateAuthCopy(authTarget);
  const [sourceConfig, copiedConfig, sourceAuth, copiedAuth] = await Promise.all([
    stat(configSource), stat(configTarget), stat(authSource), stat(authTarget),
  ]);
  assert.equal(copiedConfig.size, sourceConfig.size, "configuration copy is incomplete");
  assert.equal(copiedAuth.size, sourceAuth.size, "auth copy is incomplete");
  return { runRoot, profileRoot, directories, copiedConfigBytes: copiedConfig.size, copiedAuthBytes: copiedAuth.size };
}

/** auth.json 的运行期密钥读取要求文件 DACL 显式受保护；仅把 profile 放在私有目录下不足以满足 App Server。 @author kongweiguang */
async function protectPrivateAuthCopy(authPath) {
  const identity = await execFileAsync("whoami.exe", [], { windowsHide: true, timeout: 5_000, maxBuffer: 4_096 });
  const principal = `${identity.stdout.trim()} :F`.replace(/\s+:/u, ":");
  assert.match(principal, /^[^\r\n:]+\\[^\r\n:]+:F$/u, "Windows user identity must be explicit for the isolated auth ACL");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", principal], {
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 4_096,
  });
  const quotedPath = `'${authPath.replaceAll("'", "''")}'`;
  const validationCommand = [
    `$acl=Get-Acl -LiteralPath ${quotedPath}`,
    "$currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value",
    "$ownerSid=([Security.Principal.NTAccount]::new($acl.Owner)).Translate([Security.Principal.SecurityIdentifier]).Value",
    "$rules=@($acl.Access)",
    "$ruleSid=if($rules.Count -eq 1){$rules[0].IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value}else{''}",
    "$valid=($ownerSid -eq $currentSid) -and $acl.AreAccessRulesProtected -and ($rules.Count -eq 1) -and ($ruleSid -eq $currentSid) -and (-not $rules[0].IsInherited) -and ($rules[0].AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow) -and ($rules[0].FileSystemRights -eq [Security.AccessControl.FileSystemRights]::FullControl) -and ($rules[0].InheritanceFlags -eq [Security.AccessControl.InheritanceFlags]::None) -and ($rules[0].PropagationFlags -eq [Security.AccessControl.PropagationFlags]::None)",
    "if($valid){'AUTH_DACL_VALID=true'}else{exit 1}",
  ].join("; ");
  const validation = await execFileAsync(process.env.JA_TEST_PWSH ?? "pwsh.exe", ["-NoLogo", "-NoProfile", "-Command", validationCommand], {
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 4_096,
  });
  assert.equal(validation.stdout.trim(), "AUTH_DACL_VALID=true", "isolated auth DACL must be protected with one owner-only FullControl ACE");
}

/** 临时注入唯一隔离 JA_HOME 后立即恢复宿主 Node 环境；Native 子进程不会继承用户 JA_HOME 或调试/测试 Provider 覆盖。 @author kongweiguang */
function startPrivateNativeSession(nativePath, directories) {
  const savedEnvironment = new Map(["JA_HOME", ...restrictedEnvironmentNames]
    .map((name) => [name, process.env[name]]));
  process.env.JA_HOME = directories.home;
  for (const name of restrictedEnvironmentNames) delete process.env[name];
  try {
    return new JsonlSession({
      command: nativePath,
      prefixArgs: [],
      directories,
      apiKey: "",
      endpoint: "",
    });
  } finally {
    for (const [name, value] of savedEnvironment) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
}

/** 仅用脱敏 effective 投影核实真实默认模型；父 Thread 允许委派，测试 prompt 再把两个 child 显式收紧为审批模式。 @author kongweiguang */
async function seedThreads(nativePath, directories) {
  const session = startPrivateNativeSession(nativePath, directories);
  let ready = false;
  try {
    const initialized = rpcResult(await session.request("runtime/initialize", initializeParams(), 30_000), "runtime/initialize");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;
    const configuration = rpcResult(await session.request("configuration/read", {}), "configuration/read");
    const effective = configuration.effective;
    assert.equal(effective?.default_provider_id, expectedProviderId, "isolated profile did not retain the verified default provider");
    assert.equal(effective?.default_model_id, expectedModelId, "isolated profile did not retain the verified default model");
    assert.equal(effective?.default_reasoning_level, "max", "isolated profile did not retain the verified default reasoning effort");
    assert.equal(effective?.subagents?.enabled, true, "subagent delegation must remain enabled from the copied config");
    for (const override of ["provider_id", "model_id", "reasoning_level"]) {
      assert.equal(effective?.subagents?.[override] ?? null, null, `unexpected subagent override: ${override}`);
    }
    const workspace = rpcResult(await session.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Ja real model CLI acceptance",
    }), "workspace/open");
    const trust = rpcResult(await session.request("workspace/set-trust", {
      workspaceId: workspace.workspaceId,
      trust: "trusted",
    }), "workspace/set-trust");
    assert.equal(trust.accepted, true);
    const createMain = async (title) => rpcResult(await session.request("thread/create", {
      cwd: directories.workspace,
      title,
      providerId: effective.default_provider_id,
      modelId: effective.default_model_id,
      reasoningLevel: effective.default_reasoning_level,
      accessMode: "full_access",
      collaborationMode: "default",
    }), `thread/create ${title}`);
    const parent = await createMain("Real model CLI subagent approval");
    const sideParent = await createMain("Real model CLI side chat");
    assert.match(parent.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    assert.match(sideParent.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    const exit = await session.shutdown();
    assert.equal(exit?.code, 0, "seed Native must exit before installed CLI takes the isolated JA_HOME");
    return {
      workspaceId: workspace.workspaceId,
      parentThreadId: parent.threadId,
      sideParentThreadId: sideParent.threadId,
      configVerified: {
        providerId: effective.default_provider_id,
        modelId: effective.default_model_id,
        reasoningLevel: effective.default_reasoning_level,
        subagentsEnabled: effective.subagents.enabled,
        subagentOverridesNull: true,
      },
    };
  } catch (error) {
    if (ready && !session.exited) await session.shutdown().catch(() => undefined);
    await session.forceClose();
    throw error;
  } finally {
    await session.forceClose();
  }
}

/** 把 RPC 错误缩为稳定分类，避免未经授权的配置或响应文本进入日志。 @author kongweiguang */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 从实际 screen buffer 轮询状态，并按分钟输出进度；失败时只保存指定快照，不把全文塞入命令日志。 @author kongweiguang */
async function waitForVisible(cli, predicate, label, runRoot, timeoutMs = 240_000) {
  const started = Date.now();
  const deadline = started + timeoutMs;
  let progressAt = started + 60_000;
  while (Date.now() < deadline) {
    await cli.pullVt();
    const visible = cli.screen.text();
    const lines = cli.screen.lines();
    if (predicate(visible, lines)) return { text: visible, lines, elapsedMs: Date.now() - started };
    if (Date.now() >= progressAt) {
      process.stdout.write(`WAITING phase=${label} elapsedSeconds=${Math.floor((Date.now() - started) / 1_000)}\n`);
      progressAt += 60_000;
    }
    if (cli.child.exitCode !== null) throw new Error(`CLI exited before ${label}`);
    await delay(120);
  }
  await cli.pullVt();
  const path = join(runRoot, `${safeName(label)}-timeout.txt`);
  await writeScreenEvidence(runRoot, `${safeName(label)}-timeout`, cli.screen);
  throw new Error(`timed out waiting for ${label}; screen=${basename(path)}`);
}

/** 记录本轮 ConPTY 控制器的有限 stderr 与退出状态；诊断不读取或落盘任何环境变量和认证文件。 @author kongweiguang */
async function recordConPtyFailure(report, cli) {
  if (cli === undefined) return;
  const exit = await Promise.race([cli.exitPromise, delay(250).then(() => undefined)]);
  report.conPtyDiagnostic = {
    controllerPid: Number.isSafeInteger(cli.child.pid) ? cli.child.pid : null,
    controllerExit: exit === undefined ? null : { code: exit.code, signal: exit.signal },
    controllerStderrTail: cli.stderr().trim().slice(-2_000),
    childPidMessageReceived: cli.messages.some((message) => message.startsWith("CHILD_PID=")),
    closeAcceptedMessageReceived: cli.messages.some((message) => message === "CLOSE_ACCEPTED=true"),
  };
}

/** 将阶段名压为安全文件分量，防止证据命名携带终端控制字符。 @author kongweiguang */
function safeName(value) {
  return String(value).replace(/[^A-Za-z0-9._-]+/gu, "-").replace(/^-+|-+$/gu, "").slice(0, 80) || "phase";
}

/** 通过 bracketed paste 明确标记整段正文，随后独立发 Enter；模型流程还须等待 thread/read 的新 Turn 回执。 @author kongweiguang */
async function submitComposer(cli, text, visibleMarker, label, runRoot) {
  const bracketedPaste = `\u001b[200~${text}\u001b[201~`;
  assert.ok(Buffer.byteLength(bracketedPaste, "utf8") <= 4_096, "one ConPTY paste must stay within the harness input frame limit");
  cli.sendKeys(bracketedPaste);
  const markerWithoutWraps = visibleMarker.replace(/\s+/gu, "");
  await waitForVisible(cli, (visible) => visible.replace(/\s+/gu, "").includes(markerWithoutWraps),
    `${label}-text`, runRoot, 15_000);
  await delay(160);
  cli.sendKeys("\r");
}

/** 只在当前唯一隔离 JA_HOME 上执行 CLI 辅助命令，并净化真实模型变量与调试覆盖。 @author kongweiguang */
async function runIsolatedCommand(command, args, directories, timeoutMs = 30_000) {
  const environment = { ...process.env, JA_HOME: directories.home };
  for (const name of restrictedEnvironmentNames) delete environment[name];
  return new Promise((resolveCommand, rejectCommand) => {
    const child = execFile(command, args, {
      cwd: directories.workspace,
      env: environment,
      windowsHide: true,
      timeout: timeoutMs,
      maxBuffer: 64 * 1024,
    }, (error, stdout, stderr) => {
      if (error && error.code !== 0) {
        resolveCommand({ code: typeof error.code === "number" ? error.code : null, stdout, stderr });
      } else if (error) rejectCommand(new Error("isolated CLI helper could not complete"));
      else resolveCommand({ code: 0, stdout, stderr });
    });
    child.once("error", () => rejectCommand(new Error("could not start isolated CLI helper")));
  });
}

/** 正常退出 TUI 后在 ConPTY Job 释放前停止它创建的共享 Native，并确认隔离 endpoint 收据已删除。 @author kongweiguang */
async function stopIsolatedDaemon(cliPath, directories) {
  const endpointPath = join(directories.run, "app-server.endpoint.json");
  try {
    await stat(endpointPath);
  } catch (error) {
    if (error?.code === "ENOENT") return { endpointAbsent: true, serverWasAlreadyStopped: true };
    throw new Error("isolated server endpoint receipt could not be checked");
  }
  const before = await runIsolatedCommand(cliPath, ["server", "status"], directories);
  assert.equal(before.code, 0, "the isolated shared server must remain queryable before stop");
  const stopped = await runIsolatedCommand(cliPath, ["server", "stop"], directories);
  assert.equal(stopped.code, 0, "the isolated shared server must confirm stop");
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      await stat(endpointPath);
    } catch (error) {
      if (error?.code === "ENOENT") return { endpointAbsent: true, stopExitCode: stopped.code };
      throw new Error("isolated server endpoint receipt could not be checked after stop");
    }
    await delay(100);
  }
  throw new Error("isolated server stop was acknowledged but endpoint receipt remained");
}

/** 连接本轮 CLI 已创建的 loopback owner 作只读观测；不启动第二个 Native、不保存 token 或对话全文。 @author kongweiguang */
async function connectCliTurnObserver(directories) {
  const endpointPath = join(directories.run, "app-server.endpoint.json");
  const deadline = Date.now() + 30_000;
  let endpoint;
  while (Date.now() < deadline) {
    try {
      const metadata = await lstat(endpointPath);
      assert.ok(metadata.isFile() && !metadata.isSymbolicLink() && metadata.size <= 4_096,
        "isolated JA-RPC endpoint must be a small regular file");
      const candidate = JSON.parse(await readFile(endpointPath, "utf8"));
      assert.equal(candidate.host, "127.0.0.1");
      assert.ok(Number.isSafeInteger(candidate.port) && candidate.port > 0 && candidate.port <= 65_535);
      assert.equal(candidate.protocolMajor, 2);
      assert.equal(candidate.protocolMinor, 0);
      assert.match(candidate.serverInstanceId, /^srv_[A-Za-z0-9._-]{1,97}$/u);
      assert.ok(Number.isSafeInteger(candidate.runtimeGeneration) && candidate.runtimeGeneration > 0);
      assert.match(candidate.token, /^[0-9a-f]{64}$/u);
      endpoint = candidate;
      break;
    } catch (error) {
      if (error?.code !== "ENOENT" && !(error instanceof SyntaxError)) throw error;
      await delay(80);
    }
  }
  assert.ok(endpoint, "installed CLI did not publish its shared App Server endpoint");
  assert.equal(typeof WebSocket, "function", "Node 24 must provide a WebSocket client for read-only RPC observation");
  const socket = new WebSocket(`ws://${endpoint.host}:${endpoint.port}/ja-rpc`);
  const pending = new Map();
  const eventWaiters = [];
  let requestSequence = 0;
  let closedByPeer = false;
  let observerPhase = "websocket-open";

  /** RPC waiter 只记录安全错误码和请求名，拒绝保留可能包含正文或认证字段的原始 frame。 @author kongweiguang */
  function rejectPending(error) {
    for (const entry of pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    pending.clear();
    for (const waiter of eventWaiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }

  /** 仅分派 v2 response 与 ready 通知，不把任何非预期字段写到验收报告。 @author kongweiguang */
  function receiveFrame(data) {
    let frame;
    try {
      frame = JSON.parse(typeof data === "string" ? data : String(data));
    } catch {
      rejectPending(new Error("shared App Server observer received invalid JSON"));
      return;
    }
    if (frame?.jsonrpc !== "2.0") {
      rejectPending(new Error("shared App Server observer received an invalid JA-RPC frame"));
      return;
    }
    if (frame.id !== undefined) {
      const entry = pending.get(String(frame.id));
      if (!entry) return;
      pending.delete(String(frame.id));
      clearTimeout(entry.timer);
      if (frame.error) {
        const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
        entry.reject(new Error(`${entry.method} failed (${String(code)})`));
      } else if (frame.result === undefined) {
        entry.reject(new Error(`${entry.method} returned no result`));
      } else {
        entry.resolve(frame.result);
      }
      return;
    }
    if (frame.method !== "runtime/status-changed") return;
    for (const waiter of [...eventWaiters]) {
      if (!waiter.predicate(frame)) continue;
      eventWaiters.splice(eventWaiters.indexOf(waiter), 1);
      clearTimeout(waiter.timer);
      waiter.resolve(frame.params);
      return;
    }
  }

  /** 请求 ID 遵守 JaRpcCodec 的 c: 前缀合同；deadline 与方法名错误避免回显 Thread 正文或 endpoint token。 @author kongweiguang */
  function request(method, params, timeoutMs = 30_000) {
    if (socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error("shared App Server observer is not connected"));
    const id = `c:real_cli_${++requestSequence}`;
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        rejectPromise(new Error(`${method} observer request timed out`));
      }, timeoutMs);
      pending.set(id, { method, timer, resolve: resolvePromise, reject: rejectPromise });
      socket.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });
  }

  /** ready token 在发通知前先注册 waiter；仅接受与 endpoint instance/generation 完全一致的 owner。 @author kongweiguang */
  function waitForReady(readyToken) {
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        const index = eventWaiters.findIndex((waiter) => waiter.timer === timer);
        if (index >= 0) eventWaiters.splice(index, 1);
        rejectPromise(new Error("shared App Server observer did not become ready"));
      }, 30_000);
      eventWaiters.push({
        timer,
        resolve: resolvePromise,
        reject: rejectPromise,
        predicate: (frame) => frame.params?.status === "ready"
          && frame.params?.serverInstanceId === endpoint.serverInstanceId
          && frame.params?.generation === endpoint.runtimeGeneration
          && frame.params?.readyToken === readyToken,
      });
    });
  }

  socket.addEventListener("message", (event) => receiveFrame(event.data));
  socket.addEventListener("close", () => {
    closedByPeer = true;
    rejectPending(new Error(`shared App Server observer closed during ${observerPhase}`));
  });
  socket.addEventListener("error", () => rejectPending(new Error(`shared App Server observer transport failed during ${observerPhase}`)));
  try {
    await Promise.race([
      new Promise((resolvePromise, rejectPromise) => {
        socket.addEventListener("open", resolvePromise, { once: true });
        socket.addEventListener("error", () => rejectPromise(new Error("could not connect to isolated App Server")), { once: true });
      }),
      delay(10_000).then(() => { throw new Error("isolated App Server WebSocket open timed out"); }),
    ]);
    observerPhase = "endpoint-authentication";
    socket.send(JSON.stringify({ token: endpoint.token }));
    observerPhase = "runtime-initialize";
    const initialized = await request("runtime/initialize", initializeParams());
    assert.equal(initialized.protocolMajor, 2);
    assert.equal(initialized.protocolMinor, 0);
    assert.equal(initialized.serverInstanceId, endpoint.serverInstanceId);
    const readyToken = randomBytes(16).toString("hex");
    const ready = waitForReady(readyToken);
    observerPhase = "runtime-ready";
    socket.send(JSON.stringify({ jsonrpc: "2.0", method: "runtime/initialized", params: { readyToken } }));
    await ready;
    observerPhase = "context-register";
    const context = await request("runtime/context/register", {
      environment: { JA_HOME: directories.home },
      shell: null,
      clientProcessId: process.pid,
      clientLifetimeId: `native_${randomBytes(16).toString("hex")}`,
    });
    assert.match(context.contextId, /^ctx_[0-9a-f]{32}$/u);
  } catch (error) {
    if (socket.readyState !== WebSocket.CLOSED) socket.close();
    throw error;
  }

  /** 权威恢复快照只经 thread/observe + thread/read 取得，不在 watcher 内启动或恢复任何 Thread。 @author kongweiguang */
  async function readThread(threadId) {
    observerPhase = "thread-observe";
    const observation = await request("thread/observe", { threadId });
    assert.equal(observation.accepted, true);
    assert.equal(observation.threadId, threadId);
    observerPhase = "thread-read";
    const snapshot = await request("thread/read", { threadId, tail: true, limit: 200 });
    assert.equal(snapshot.threadId, threadId);
    assert.ok(Array.isArray(snapshot.turns) && Array.isArray(snapshot.items));
    return snapshot;
  }

  return {
    request,
    readThread,
    async close() {
      if (!closedByPeer && socket.readyState !== WebSocket.CLOSED) socket.close();
      await Promise.race([new Promise((resolvePromise) => socket.addEventListener("close", resolvePromise, { once: true })), delay(2_000)]);
    },
  };
}

/** 等待 Enter 真正创建 baseline 之后的新 Turn，不能把写入 ConPTY 控制管道视为模型请求已发送。 @author kongweiguang */
async function waitForTurnAdmission(observer, threadId, baseline, timeoutMs = 45_000) {
  const priorTurnIds = new Set((baseline.turns ?? []).map((turn) => turn.turnId));
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const snapshot = await observer.readThread(threadId);
    const turn = [...snapshot.turns].reverse().find((candidate) => !priorTurnIds.has(candidate.turnId));
    if (turn) return { threadId, turnId: turn.turnId, status: String(turn.status).toLowerCase(), revision: snapshot.revision };
    await delay(180);
  }
  throw new Error("Enter did not create a new Thread Turn before the admission deadline");
}

/** 先验证 baseline 后的新 Turn 获准进入，再只从其 final_answer Item 取真实助手结果。 @author kongweiguang */
async function waitForNewAssistantTurn(observer, threadId, baseline, expectedMarker, timeoutMs = 240_000) {
  const admission = await waitForTurnAdmission(observer, threadId, baseline, Math.min(timeoutMs, 45_000));
  return waitForAssistantTurn(observer, threadId, admission.turnId, expectedMarker, timeoutMs);
}

/** 审批后的等待按预先观察到的 turnId 对账；必须同时见该 Turn completed 与 wire kind 为 final_answer 的 Item。 @author kongweiguang */
async function waitForAssistantTurn(observer, threadId, turnId, expectedMarker, timeoutMs = 240_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const snapshot = await observer.readThread(threadId);
    const turn = snapshot.turns.find((candidate) => candidate.turnId === turnId);
    if (turn) {
      const status = String(turn.status).toLowerCase();
      const assistantItems = snapshot.items.filter((item) => item.turnId === turnId && item.kind === "final_answer");
      const assistantText = assistantItems.map((item) => item.text ?? "").join("\n");
      if (status === "completed") {
        assert.ok(assistantItems.length > 0, "completed Turn must contain a final_answer Item");
        assert.ok(assistantText.includes(expectedMarker), "completed final_answer Item must contain its unique marker");
        return { threadId, turnId, status, revision: snapshot.revision, assistantItemIds: assistantItems.map((item) => item.itemId), assistantText };
      }
      if (["failed", "cancelled", "canceled", "rejected"].includes(status)) {
        throw new Error(`observed child Turn reached ${status}${turn.errorCode ? ` (${turn.errorCode})` : ""}`);
      }
    }
    await delay(180);
  }
  throw new Error("observed child Turn did not complete with its assistant marker before the deadline");
}

/** /btw 的临时 child 没有稳定 taskName；按首轮 taskId 基线查新增 side_task，避免误认主 Task 或既有侧聊。 @author kongweiguang */
async function waitForNewSideTask(observer, rootThreadId, baselineTaskIds, timeoutMs = 45_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await observer.request("task/list", { rootThreadId });
    const task = (result.items ?? []).find((item) => item.taskKind === "side_task" && !baselineTaskIds.has(item.taskThreadId));
    if (task) return task;
    await delay(180);
  }
  throw new Error("no new temporary side_task appeared under the requested parent Thread");
}

/** 等待 /back 的关闭回执反映到权威 Task 列表；不能用会自动变化的 TUI 标题推断父会话。 @author kongweiguang */
async function waitForClosedSideTask(observer, rootThreadId, taskThreadId, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await observer.request("task/list", { rootThreadId });
    const remainsOpen = (result.items ?? []).some((task) =>
      task.taskKind === "side_task" && task.taskThreadId === taskThreadId);
    if (!remainsOpen) return;
    await delay(180);
  }
  throw new Error("/back did not remove the exact temporary side_task from its parent Task tree");
}

/** 父会话返航以其自身已完成的子任务卡和空 Composer 识别，避免依赖会随终答自动变化的标题。 @author kongweiguang */
function parentShowsCompletedTasks(lines, taskNames) {
  if (!lines.some((line) => line.trim() === "›")) return false;
  return taskNames.every((taskName) => {
    const cardIndex = lines.findIndex((line) => line.includes("子任务") && line.includes(taskName));
    return cardIndex >= 0 && lines.slice(cardIndex, cardIndex + 3).some((line) => line.includes("状态：已完成"));
  });
}

/** 委派结果的 Thread 身份只从权威 Task 目录读取；模型生成的标题邻近文本不参与 ID 解析。 @author kongweiguang */
async function waitForSubagentTask(observer, parentThreadId, taskName, timeoutMs = 45_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await observer.request("task/list", { rootThreadId: parentThreadId });
    const matches = (result.items ?? []).filter((task) =>
      task.taskKind === "subagent" && task.parentThreadId === parentThreadId && task.taskName === taskName);
    assert.ok(matches.length <= 1, "one delegated task name must map to at most one child Thread under its parent");
    if (matches.length === 1) {
      const task = matches[0];
      assert.match(task.taskThreadId, /^thr_task_[0-9a-f]{32}$/u);
      return task;
    }
    await delay(180);
  }
  throw new Error("no authoritative subagent Task matched the unique requested name under its parent Thread");
}

/** /btw 正文只能由实际 user_input snapshot 验证；全文留在内存且报告仅保留布尔事实。 @author kongweiguang */
function sideTaskContainsBody(snapshot, expectedText, expectedMarker) {
  const inputs = snapshot.items.filter((item) => item.kind === "user_input");
  return inputs.some((item) => {
    const serializedContent = JSON.stringify(item.content ?? []);
    return serializedContent.includes(expectedText) && serializedContent.includes(expectedMarker);
  });
}

/** 等待 Task 子 Thread 的真实 unresolved Approval Item，记录 Turn 身份后再让 TUI 用户按键裁决。 @author kongweiguang */
async function waitForPendingChildApproval(observer, threadId, timeoutMs = 240_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const snapshot = await observer.readThread(threadId);
    const approval = snapshot.items.find((item) => item.kind === "approval" && item.decision == null);
    if (approval) return { turnId: approval.turnId, approvalId: approval.approvalId, callId: approval.callId, toolName: approval.toolName };
    await delay(180);
  }
  throw new Error("child Thread did not publish an unresolved approval item");
}

/** 仅针对当前侧聊请求 /resume 候选、查看具体 child approval 并真实按 Enter 或 ArrowDown+Enter 裁决。 @author kongweiguang */
async function selectAndDecideChild(cli, taskName, decision, marker, runRoot) {
  await submitComposer(cli, "/resume", "/resume", `${taskName}-resume`, runRoot);
  await waitForVisible(cli, (visible) => visible.includes("恢复会话") && visible.includes("搜索标题"),
    `${taskName}-selector`, runRoot, 30_000);
  cli.sendKeys(taskName);
  const candidate = await waitForVisible(cli, (_visible, lines) => lines.some((line) =>
    /^\s*(?:›\s*)?\d+\.\s*/u.test(line) && line.includes(taskName) && line.includes("子任务") && line.includes("等待审批")),
  `${taskName}-visible-candidate`, runRoot, 45_000);
  await writeScreenEvidence(runRoot, `${taskName}-candidate`, cli.screen);
  await delay(160);
  cli.sendKeys("\r");
  const approval = await waitForVisible(cli, (visible) =>
    visible.includes("需要审批") && visible.includes("exec_command") && visible.includes("允许") && visible.includes("拒绝"),
  `${taskName}-exec-approval`, runRoot, 30_000);
  assert.ok(approval.text.includes(marker), "selected child approval must show the expected unique command marker");
  await writeScreenEvidence(runRoot, `${taskName}-approval`, cli.screen);
  await delay(150);
  if (decision === "deny") {
    cli.sendKeys("\u001b[B");
    await delay(120);
  }
  cli.sendKeys("\r");
  return { candidateElapsedMs: candidate.elapsedMs, approvalElapsedMs: approval.elapsedMs };
}

/** 按批准或拒绝要求创建真实子任务；Parent 保持空闲于 Task API 管理，避免测试脚本伪造模型 Tool 调用。 @author kongweiguang */
function spawnPrompt(taskName, childToken, filePath, commandMarker, parentToken, outcome) {
  const literalPath = filePath.replaceAll("'", "''");
  const command = `Set-Content -LiteralPath '${literalPath}' -Value '${commandMarker}' -NoNewline; Write-Output '${commandMarker}'`;
  const brief = [
    `In this isolated Ja workspace, call exec_command exactly once with this exact PowerShell command: ${command}`,
    "Set yield_time_ms to 10000 and timeout_ms to 30000. If it returns a live session, call write_stdin only with that same session_id and wait until it exits; never launch another process.",
    `If the call succeeds, your final answer must contain exactly this marker: ${childToken}.`,
    `If the command is denied, stop without retrying or asking for another command; report the denial and include ${childToken} in your final answer.`,
  ].join(" ");
  return [
    "Use real native Task tools; do not describe or simulate a tool call in text.",
    `Call spawn_agent exactly once with taskName ${JSON.stringify(taskName)}, accessMode "approval_required", and the brief below. Then call list_agents exactly once and report the child threadId and initial status. Do not call wait_agent in this turn.`,
    `Child brief: ${brief}`,
    `This is the ${outcome} approval case. The only permitted external operation is that single exact command in the temporary workspace.`,
    `After those tool calls, include ${parentToken} in your final answer.`,
  ].join(" ");
}

/** 用短期 JA_HOME 和当前安装 Native 冷读权威 Task/Thread 历史；仅返回身份、状态和 Tool 名称摘要，不保存任何消息或 Tool 参数正文。 @author kongweiguang */
async function readFinalState(nativePath, directories, seed, expected) {
  const session = startPrivateNativeSession(nativePath, directories);
  try {
    const initialized = rpcResult(await session.request("runtime/initialize", initializeParams(), 30_000), "runtime/initialize final read");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    const taskList = rpcResult(await session.request("task/list", { rootThreadId: seed.parentThreadId }, 30_000), "task/list final parent");
    const tasks = taskList.items ?? [];
    const taskByName = new Map(tasks.filter((task) => task.taskKind === "subagent").map((task) => [task.taskName, task]));
    const children = {};
    for (const taskName of [expected.allowTaskName, expected.denyTaskName]) {
      const summary = taskByName.get(taskName);
      assert.ok(summary, `${taskName} must appear in the authoritative parent Task tree`);
      assert.equal(summary.state, "completed", `${taskName} must complete after its approval decision`);
      assert.equal(summary.parentThreadId, seed.parentThreadId);
      assert.equal(summary.taskThreadId, taskName === expected.allowTaskName ? expected.allowChildId : expected.denyChildId,
        `${taskName} identity must match the ID returned in the real model conversation`);
      const detail = rpcResult(await session.request("task/read", { taskThreadId: summary.taskThreadId, limit: 200 }, 30_000), `task/read ${taskName}`);
      assert.equal(detail.task?.taskThreadId, summary.taskThreadId);
      assert.equal(detail.task?.taskKind, "subagent");
      assert.equal(detail.task?.parentThreadId, seed.parentThreadId);
      assert.equal(detail.thread?.workspaceId, seed.workspaceId);
      assert.equal(detail.task?.state, "completed");
      const history = rpcResult(await session.request("thread/read", {
        threadId: summary.taskThreadId,
        cursor: null,
        limit: 200,
      }, 30_000), `thread/read ${taskName}`);
      const calls = (history.items ?? []).filter((item) => item.kind === "tool_call" && item.toolName === "exec_command");
      assert.equal(calls.length, 1, `${taskName} must have exactly one exec_command call`);
      const successfulCalls = calls.filter((item) => item.presentation?.status === "success");
      if (taskName === expected.allowTaskName) {
        assert.equal(successfulCalls.length, 1, "allowed child exec_command must complete successfully exactly once");
        const contents = await readFile(expected.allowPath, "utf8");
        assert.equal(contents, expected.allowMarker, "allowed child must write exactly the expected marker once");
      } else {
        assert.equal(successfulCalls.length, 0, "denied child exec_command must not be reported as successful");
        await assertAbsent(expected.denyPath);
      }
      assert.equal(history.turns?.length, 1, `${taskName} must keep one original child turn`);
      assert.equal(history.turns[0]?.status, "completed");
      children[taskName] = {
        threadId: summary.taskThreadId,
        state: detail.task.state,
        taskKind: detail.task.taskKind,
        parentThreadId: detail.task.parentThreadId,
        execCommandCalls: calls.length,
        successfulExecCommandCalls: successfulCalls.length,
        turnStatuses: history.turns.map((turn) => turn.status),
      };
    }
    const parentHistory = rpcResult(await session.request("thread/read", {
      threadId: seed.parentThreadId,
      cursor: null,
      limit: 200,
    }, 30_000), "thread/read final parent");
    const parentToolNames = (parentHistory.items ?? [])
      .filter((item) => item.kind === "tool_call")
      .map((item) => item.toolName)
      .filter((name) => ["spawn_agent", "list_agents", "wait_agent"].includes(name));
    assert.equal(parentToolNames.filter((name) => name === "spawn_agent").length, 2,
      "the real provider must create exactly the two requested child tasks");
    assert.equal(parentToolNames.filter((name) => name === "wait_agent").length, 1,
      "the real provider must wait once for both completed children");
    assert.ok(parentToolNames.filter((name) => name === "list_agents").length >= 3,
      "the real provider must read the delegated task list before and after waiting");
    for (const toolName of ["spawn_agent", "list_agents", "wait_agent"]) {
      assert.ok(parentToolNames.includes(toolName), `real provider must invoke native ${toolName}`);
    }
    for (const taskName of [expected.allowTaskName, expected.denyTaskName]) {
      const activity = (parentHistory.taskActivities ?? [])
        .filter((pair) => pair.task?.taskName === taskName)
        .sort((left, right) => Number(right.activity?.activitySequence ?? 0) - Number(left.activity?.activitySequence ?? 0))[0];
      assert.equal(activity?.task?.state, "completed", `${taskName} parent activity must reach completed`);
    }
    const sideTasks = rpcResult(await session.request("task/list", { rootThreadId: seed.sideParentThreadId }, 30_000), "task/list final side chats");
    assert.equal((sideTasks.items ?? []).some((task) => task.taskKind === "side_task"), false,
      "/back must close both temporary side chats and leave no side_task rows");
    const sideParentHistory = rpcResult(await session.request("thread/read", {
      threadId: seed.sideParentThreadId,
      cursor: null,
      limit: 200,
    }, 30_000), "thread/read empty side-chat parent");
    assert.equal(sideParentHistory.turns?.length, 0, "both /btw flows must leave their source MAIN Thread empty");
    const shutdown = await session.shutdown();
    assert.equal(shutdown?.code, 0, "final Native readback must exit cleanly");
    return {
      children,
      parentThreadId: seed.parentThreadId,
      parentToolNames,
      parentTurnStatuses: parentHistory.turns?.map((turn) => turn.status) ?? [],
      sideParentThreadId: seed.sideParentThreadId,
      sideTasksRemaining: 0,
      sideParentTurnCount: sideParentHistory.turns?.length ?? 0,
    };
  } catch (error) {
    await session.forceClose();
    throw error;
  } finally {
    await session.forceClose();
  }
}

/** 只将缺失视为拒绝写入的预期结果；权限错误或其他 I/O 故障仍作为验收失败。 @author kongweiguang */
async function assertAbsent(path) {
  try {
    await stat(path);
    assert.fail("denied command unexpectedly created its target file");
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
}

/** 执行真实 CLI 子任务审批和 /btw 终端闭环；只在用户隔离配置副本上调用当前 Provider，失败保留最小状态与屏幕证据。 @author kongweiguang */
export async function runAcceptance(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  const artifacts = await inspectArtifacts(options);
  const run = await createIsolatedRun(options.evidenceDirectory, options.config, options.auth);
  const report = {
    status: "running",
    runtime: "explicit installed Ja CLI + adjacent installed Native + actual configured model + real Windows ConPTY",
    artifactInputs: artifacts.inputs,
    javaVersion: artifacts.javaVersion,
    cliVersion: artifacts.cliVersion,
    copiedSecrets: { configBytes: run.copiedConfigBytes, authBytes: run.copiedAuthBytes, valuesLogged: false },
    model: { label: "gpt-6-luna", reasoningEffort: "max", userConfigurationChanged: false },
    phases: [],
    screens: {},
    cleanup: {},
  };
  let currentPhase = "seed";
  let cli;
  let turnObserver;
  let daemonStopped = false;
  let failure;
  try {
    const seed = await seedThreads(artifacts.paths.nativeExecutable, run.directories);
    report.seed = seed;
    report.phases.push("copied_default_model_configuration_and_seeded_isolated_threads");
    const harness = await compileConPtyHarness(artifacts.paths, run.runRoot);
    report.phases.push("compiled_jdk25_conpty_harness");

    currentPhase = "subagent approvals";
    const nonce = randomBytes(4).toString("hex");
    const allowTaskName = `real-cli-allow-${nonce}`;
    const denyTaskName = `real-cli-deny-${nonce}`;
    const allowMarker = `REALCLI_ALLOW_FILE_${nonce}`;
    const denyMarker = `REALCLI_DENY_FILE_${nonce}`;
    const allowPath = join(run.directories.workspace, `approved-${nonce}.txt`);
    const denyPath = join(run.directories.workspace, `denied-${nonce}.txt`);
    const allowParentToken = `REALCLI_PARENT_ALLOW_${nonce}`;
    const denyParentToken = `REALCLI_PARENT_DENY_${nonce}`;
    const allowChildToken = `REALCLI_CHILD_ALLOW_${nonce}`;
    const denyChildToken = `REALCLI_CHILD_DENY_${nonce}`;
    const conPtyArtifacts = {
      cli: artifacts.paths.cli,
      java: artifacts.paths.java,
      jar: artifacts.paths.jar,
      javac: artifacts.paths.javac,
      jarTool: artifacts.paths.jarTool,
    };
    cli = startConPtyCli(
      conPtyArtifacts,
      run.directories,
      harness,
      "",
      undefined,
      join(run.runRoot, "subagent-approval.vt"),
      [artifacts.paths.cli, "resume", seed.parentThreadId],
      true,
    );
    try {
      await cli.harnessReady;
    } catch (error) {
      await recordConPtyFailure(report, cli);
      const endpointPath = join(run.directories.run, "app-server.endpoint.json");
      try {
        await stat(endpointPath);
      } catch (endpointError) {
        if (endpointError?.code === "ENOENT") {
          report.cleanup.profileHadNoSharedEndpoint = true;
          daemonStopped = true;
        }
      }
      if (cli.child.exitCode !== null) {
        report.cleanup.conPtyControllerExitedBeforeReady = true;
        cli = undefined;
      }
      throw error;
    }
    const cliPidMessage = cli.messages.find((message) => message.startsWith("CHILD_PID="));
    report.cliProcess = { harnessPid: cli.child.pid, childPidMessage: cliPidMessage ?? null };
    await waitForVisible(cli, (visible) => visible.includes("›"), "parent-composer-ready", run.runRoot, 30_000);
    report.screens.parentInitial = await writeScreenEvidence(run.runRoot, "parent-initial", cli.screen);
    turnObserver = await connectCliTurnObserver(run.directories);
    await turnObserver.readThread(seed.parentThreadId);

    if (options.preflightOnly) {
      await turnObserver.close();
      turnObserver = undefined;
      const preflightCli = cli;
      cli = undefined;
      report.cleanup.preflightCli = await preflightCli.close({
        beforeHarnessClose: async () => {
          report.cleanup.preflightDaemon = await stopIsolatedDaemon(artifacts.paths.cli, run.directories);
          daemonStopped = report.cleanup.preflightDaemon.endpointAbsent === true;
        },
      });
      assert.equal(report.cleanup.preflightCli.forced, false, "preflight TUI must close naturally");
      assert.equal(report.cleanup.preflightCli.childExitCode, 0);
      report.status = "preflight_completed";
      report.phases.push("installed_cli_conpty_started_and_stopped_without_provider_request");
      process.stdout.write(`REAL_MODEL_CLI_PREFLIGHT_COMPLETED evidence=${run.runRoot}\n`);
      return report;
    }

    currentPhase = "allow child spawn";
    const allowPrompt = spawnPrompt(
      allowTaskName,
      allowChildToken,
      allowPath,
      allowMarker,
      allowParentToken,
      "allow",
    );
    const allowBaseline = await turnObserver.readThread(seed.parentThreadId);
    await submitComposer(cli, allowPrompt, allowParentToken, "allow-parent-prompt", run.runRoot);
    const allowAdmission = await waitForTurnAdmission(turnObserver, seed.parentThreadId, allowBaseline);
    process.stdout.write(`REAL_MODEL_CLI_FIRST_REQUEST_STARTED task=${allowTaskName} cliPid=${cliPidMessage ?? "unknown"} evidence=${run.runRoot}\n`);
    const allowParentFinal = await waitForAssistantTurn(turnObserver, seed.parentThreadId, allowAdmission.turnId, allowParentToken);
    await cli.pullVt(); // RPC completion does not advance the independent VT replay buffer.
    report.screens.parentAfterAllowSpawn = await writeScreenEvidence(run.runRoot, "parent-after-allow-spawn", cli.screen);
    const allowTask = await waitForSubagentTask(turnObserver, seed.parentThreadId, allowTaskName);
    const allowChildId = allowTask.taskThreadId;
    report.childThreadIds ??= {};
    report.childThreadIds[allowTaskName] = allowChildId;
    report.turns ??= [];
    report.turns.push({ threadId: allowParentFinal.threadId, turnId: allowParentFinal.turnId, status: allowParentFinal.status });
    report.phases.push("real_model_spawn_agent_and_list_agents_allow_child");

    const allowApproval = await waitForPendingChildApproval(turnObserver, allowChildId);
    await waitForVisible(cli, (visible) => visible.includes(allowTaskName) && visible.includes("等待审批"),
      "allow-child-exec-awaiting-approval-rendered", run.runRoot, 240_000);
    report.screens.parentAllowWaitingApproval = await writeScreenEvidence(run.runRoot, "parent-allow-waiting-approval", cli.screen);
    await selectAndDecideChild(cli, allowTaskName, "allow", allowMarker, run.runRoot);
    const allowChildFinal = await waitForAssistantTurn(turnObserver, allowChildId, allowApproval.turnId, allowChildToken);
    report.turns.push({ threadId: allowChildFinal.threadId, turnId: allowChildFinal.turnId, status: allowChildFinal.status });
    await cli.pullVt();
    report.screens.allowChildCompleted = await writeScreenEvidence(run.runRoot, "allow-child-completed", cli.screen);
    const allowContents = await readFile(allowPath, "utf8");
    assert.equal(allowContents, allowMarker, "the allowed child must write the isolated marker file");
    await submitComposer(cli, "/back", "/back", "return-from-allow-child", run.runRoot);
    await waitForVisible(cli, (_visible, lines) => parentShowsCompletedTasks(lines, [allowTaskName]),
      "return-to-parent-after-allow", run.runRoot, 30_000);
    report.screens.parentAfterAllow = await writeScreenEvidence(run.runRoot, "parent-after-allow", cli.screen);

    currentPhase = "deny child spawn";
    const denyPrompt = spawnPrompt(
      denyTaskName,
      denyChildToken,
      denyPath,
      denyMarker,
      denyParentToken,
      "deny",
    );
    const denyBaseline = await turnObserver.readThread(seed.parentThreadId);
    await submitComposer(cli, denyPrompt, denyParentToken, "deny-parent-prompt", run.runRoot);
    const denyAdmission = await waitForTurnAdmission(turnObserver, seed.parentThreadId, denyBaseline);
    const denyParentFinal = await waitForAssistantTurn(turnObserver, seed.parentThreadId, denyAdmission.turnId, denyParentToken);
    await cli.pullVt();
    report.screens.parentAfterDenySpawn = await writeScreenEvidence(run.runRoot, "parent-after-deny-spawn", cli.screen);
    const denyTask = await waitForSubagentTask(turnObserver, seed.parentThreadId, denyTaskName);
    const denyChildId = denyTask.taskThreadId;
    report.childThreadIds[denyTaskName] = denyChildId;
    report.turns.push({ threadId: denyParentFinal.threadId, turnId: denyParentFinal.turnId, status: denyParentFinal.status });
    await assertAbsent(denyPath);
    const denyApproval = await waitForPendingChildApproval(turnObserver, denyChildId);
    await waitForVisible(cli, (visible) => visible.includes(denyTaskName) && visible.includes("等待审批"),
      "deny-child-exec-awaiting-approval-rendered", run.runRoot, 240_000);
    report.screens.parentDenyWaitingApproval = await writeScreenEvidence(run.runRoot, "parent-deny-waiting-approval", cli.screen);
    await selectAndDecideChild(cli, denyTaskName, "deny", denyMarker, run.runRoot);
    const denyChildFinal = await waitForAssistantTurn(turnObserver, denyChildId, denyApproval.turnId, denyChildToken);
    report.turns.push({ threadId: denyChildFinal.threadId, turnId: denyChildFinal.turnId, status: denyChildFinal.status });
    await cli.pullVt();
    report.screens.denyChildCompleted = await writeScreenEvidence(run.runRoot, "deny-child-completed", cli.screen);
    await assertAbsent(denyPath);
    await submitComposer(cli, "/back", "/back", "return-from-deny-child", run.runRoot);
    await waitForVisible(cli, (_visible, lines) => parentShowsCompletedTasks(lines, [allowTaskName, denyTaskName]),
      "return-to-parent-after-deny", run.runRoot, 30_000);

    currentPhase = "parent wait and read tools";
    const waitPrompt = [
      "Use native Task tools on the two direct child tasks already delegated by this Thread.",
      `Call list_agents, identify ${allowTaskName} and ${denyTaskName}, call wait_agent once with both exact taskThreadIds and timeoutMs 10000, then call list_agents once more.`,
      `Report the actual final statuses and include ${`REALCLI_PARENT_WAITED_${nonce}`} in the final answer. Do not invent a result and do not call any other tools.`,
    ].join(" ");
    const waitedToken = `REALCLI_PARENT_WAITED_${nonce}`;
    const waitBaseline = await turnObserver.readThread(seed.parentThreadId);
    await submitComposer(cli, waitPrompt, waitedToken, "parent-wait-prompt", run.runRoot);
    const waitAdmission = await waitForTurnAdmission(turnObserver, seed.parentThreadId, waitBaseline);
    const parentWaitFinal = await waitForAssistantTurn(turnObserver, seed.parentThreadId, waitAdmission.turnId, waitedToken);
    report.turns.push({ threadId: parentWaitFinal.threadId, turnId: parentWaitFinal.turnId, status: parentWaitFinal.status });
    await cli.pullVt();
    report.screens.parentWaitCompleted = await writeScreenEvidence(run.runRoot, "parent-wait-completed", cli.screen);
    report.phases.push("real_model_allow_and_deny_decisions_and_wait_agent_completed");

    const approvalCli = cli;
    cli = undefined;
    await turnObserver.close();
    turnObserver = undefined;
    report.cleanup.approvalCli = await approvalCli.close({
      beforeHarnessClose: async () => {
        report.cleanup.approvalDaemon = await stopIsolatedDaemon(artifacts.paths.cli, run.directories);
        daemonStopped = report.cleanup.approvalDaemon.endpointAbsent === true;
      },
    });
    assert.equal(report.cleanup.approvalCli.forced, false, "approval TUI must close naturally");
    assert.equal(report.cleanup.approvalCli.childExitCode, 0);

    currentPhase = "btw body";
    cli = startConPtyCli(
      conPtyArtifacts,
      run.directories,
      harness,
      "",
      undefined,
      join(run.runRoot, "side-chat.vt"),
      [artifacts.paths.cli, "resume", seed.sideParentThreadId],
      true,
    );
    await cli.harnessReady;
    turnObserver = await connectCliTurnObserver(run.directories);
    await turnObserver.readThread(seed.sideParentThreadId);
    const body = `请原样回显这段正文，然后输出 REALCLI_BTW_BODY_OK_${nonce}。正文：实测正文  中文🙂  保留两处空格。`;
    const bodyTaskBaseline = await turnObserver.request("task/list", { rootThreadId: seed.sideParentThreadId });
    const bodyTaskIds = new Set((bodyTaskBaseline.items ?? []).map((task) => task.taskThreadId));
    await submitComposer(cli, `/btw ${body}`, "REALCLI_BTW_BODY_OK", "btw-body-command", run.runRoot);
    const bodyTask = await waitForNewSideTask(turnObserver, seed.sideParentThreadId, bodyTaskIds);
    const bodyReply = await waitForNewAssistantTurn(turnObserver, bodyTask.taskThreadId, { turns: [] }, `REALCLI_BTW_BODY_OK_${nonce}`);
    const bodySnapshot = await turnObserver.readThread(bodyTask.taskThreadId);
    assert.equal(sideTaskContainsBody(bodySnapshot, "实测正文  中文🙂  保留两处空格。", `REALCLI_BTW_BODY_OK_${nonce}`), true,
      "/btw must persist the complete original command body as the side Thread user input");
    report.turns.push({ threadId: bodyReply.threadId, turnId: bodyReply.turnId, status: bodyReply.status });
    await cli.pullVt();
    report.screens.btwBodyReply = await writeScreenEvidence(run.runRoot, "btw-body-reply", cli.screen);
    await submitComposer(cli, "/back", "/back", "back-from-btw-body", run.runRoot);
    await waitForClosedSideTask(turnObserver, seed.sideParentThreadId, bodyTask.taskThreadId);
    await waitForVisible(cli, (_visible, lines) => lines.some((line) => line.trim() === "›"),
      "back-from-body-side-chat-composer", run.runRoot, 45_000);
    report.screens.parentAfterBodyBack = await writeScreenEvidence(run.runRoot, "parent-after-body-back", cli.screen);

    currentPhase = "empty btw";
    const emptyTaskBaseline = await turnObserver.request("task/list", { rootThreadId: seed.sideParentThreadId });
    const emptyTaskIds = new Set((emptyTaskBaseline.items ?? []).map((task) => task.taskThreadId));
    await submitComposer(cli, "/btw", "/btw", "empty-btw-command", run.runRoot);
    const emptyTask = await waitForNewSideTask(turnObserver, seed.sideParentThreadId, emptyTaskIds);
    const emptySide = await waitForVisible(cli, (visible, lines) =>
      visible.includes("侧聊") && lines.some((line) => line.trim() === "›") && !visible.includes("正在工作"),
    "empty-btw-idle-composer", run.runRoot, 45_000);
    report.screens.emptyBtwIdle = await writeScreenEvidence(run.runRoot, "empty-btw-idle", cli.screen);
    assert.ok(emptySide.lines.some((line) => line.trim() === "›"), "empty /btw must focus an empty side-chat composer");
    const emptyIdleSnapshot = await turnObserver.readThread(emptyTask.taskThreadId);
    assert.equal(emptyIdleSnapshot.turns.length, 0, "empty /btw must create a ready Thread before its first Provider request");
    const emptyPrompt = `请不要调用工具，只回复标记 REALCLI_BTW_EMPTY_OK_${nonce}。`;
    const emptyBaseline = await turnObserver.readThread(emptyTask.taskThreadId);
    await submitComposer(cli, emptyPrompt, "REALCLI_BTW_EMPTY_OK", "empty-side-chat-first-input", run.runRoot);
    const emptyFinal = await waitForNewAssistantTurn(turnObserver, emptyTask.taskThreadId, emptyBaseline, `REALCLI_BTW_EMPTY_OK_${nonce}`);
    report.turns.push({ threadId: emptyFinal.threadId, turnId: emptyFinal.turnId, status: emptyFinal.status });
    await cli.pullVt();
    report.screens.emptyBtwFirstReply = await writeScreenEvidence(run.runRoot, "empty-btw-first-reply", cli.screen);
    await submitComposer(cli, "/back", "/back", "back-from-empty-btw", run.runRoot);
    await waitForClosedSideTask(turnObserver, seed.sideParentThreadId, emptyTask.taskThreadId);
    await waitForVisible(cli, (_visible, lines) => lines.some((line) => line.trim() === "›"),
      "back-from-empty-side-chat-composer", run.runRoot, 45_000);
    report.screens.parentAfterEmptyBack = await writeScreenEvidence(run.runRoot, "parent-after-empty-back", cli.screen);
    const sideCli = cli;
    cli = undefined;
    await turnObserver.close();
    turnObserver = undefined;
    report.cleanup.sideCli = await sideCli.close({
      beforeHarnessClose: async () => {
        report.cleanup.sideDaemon = await stopIsolatedDaemon(artifacts.paths.cli, run.directories);
        daemonStopped = report.cleanup.sideDaemon.endpointAbsent === true;
      },
    });
    assert.equal(report.cleanup.sideCli.forced, false, "side-chat TUI must close naturally");
    assert.equal(report.cleanup.sideCli.childExitCode, 0);
    report.phases.push("real_model_btw_body_empty_first_request_back_and_cleanup");

    currentPhase = "authoritative final read";
    report.authoritative = await readFinalState(artifacts.paths.nativeExecutable, run.directories, seed, {
      allowTaskName,
      denyTaskName,
      allowChildId,
      denyChildId,
      allowPath,
      denyPath,
      allowMarker,
    });
    report.phases.push("native_task_read_thread_read_and_side_task_purge_verified");
    report.status = "completed";
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    report.status = "failed";
    report.failure = `${currentPhase}: ${failure.message}`.slice(0, 1_000);
    if (cli) {
      await writeScreenEvidence(run.runRoot, `failure-${safeName(currentPhase)}`, cli.screen).catch(() => undefined);
    }
  } finally {
    if (turnObserver) {
      try {
        await turnObserver.close();
      } catch (error) {
        report.cleanup.observerCloseError = String(error?.message ?? "observer close failed").slice(0, 300);
      }
      turnObserver = undefined;
    }
    if (cli) {
      const activeCli = cli;
      cli = undefined;
      try {
        report.cleanup.activeCli = await activeCli.close({
          beforeHarnessClose: async () => {
            report.cleanup.activeDaemon = await stopIsolatedDaemon(artifacts.paths.cli, run.directories);
            daemonStopped = report.cleanup.activeDaemon.endpointAbsent === true;
          },
        });
      } catch (error) {
        report.cleanup.activeCliError = String(error?.message ?? "CLI close failed").slice(0, 500);
      }
    }
    if (daemonStopped) {
      for (const fileName of ["config.toml", "auth.json"]) {
        await rm(join(run.directories.home, fileName), { force: true }).catch(() => undefined);
      }
      try {
        await removeOwnedProfile(run.runRoot, run.profileRoot);
        report.cleanup.privateProfileRemoved = true;
      } catch (error) {
        report.cleanup.privateProfileRemoved = false;
        report.cleanup.profileRemovalError = String(error?.message ?? "profile cleanup failed").slice(0, 500);
      }
    } else {
      report.cleanup.privateProfileRemoved = false;
      report.cleanup.privateProfileRetainedForOwnedServer = true;
    }
    report.evidenceDirectory = run.runRoot;
    report.failurePhase = failure ? currentPhase : null;
    await writeFile(join(run.runRoot, "acceptance.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  }
  process.stdout.write(`REAL_MODEL_CLI_ACCEPTANCE_${report.status.toUpperCase()} evidence=${run.runRoot}\n`);
  if (failure) throw new Error(`real-model CLI acceptance failed at ${currentPhase}; evidence=${run.runRoot}`);
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  runAcceptance().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : "real-model CLI acceptance failed"}\n`);
    process.exitCode = 1;
  });
}
