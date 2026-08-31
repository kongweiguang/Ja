// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 通过生产 JAR 验证 Ja Kernel 的启动、配置 CAS、SQLite、Skill、Thread 与关闭链路。
 *
 * Provider、MCP、Tool 与取消由各自专用 smoke 覆盖；本门禁不再保留 fake runtime，也不重复实现
 * JSONL transport，而是复用真实 Provider smoke 已审计的进程/关联/错误目录边界。
 */

import { execFile } from "node:child_process";
import { mkdir, mkdtemp, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  JsonlSession,
  initializeParams,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const temporaryPrefix = "ja-kernel-loop-";
const credentialId = "cred_kernel_smoke";
const requestTimeoutMs = 30_000;

/** 只接受显式环境或当前唯一 app-server 构建目录中的非空 fat JAR。 */
async function resolveJar() {
  const jar = resolve(process.env.JA_KERNEL_SMOKE_JAR
    ?? join(repoRoot, "app-server", "target", "ja-app-server.jar"));
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0) throw new Error("Ja App Server JAR is unavailable");
  return jar;
}

/** 解析项目规定的 Java 25 入口；PATH 仅作为 CI 已完成预检后的最后选择。 */
function resolveJava() {
  if (process.env.JA_KERNEL_SMOKE_JAVA) return process.env.JA_KERNEL_SMOKE_JAVA;
  if (process.env.JAVA_HOME) {
    return join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/** 在启动 JAR 前独立确认主版本，防止默认 JDK 21 被误用。 */
async function requireJava25(java) {
  const { stderr, stdout } = await execFileAsync(java, ["-version"], {
    windowsHide: true,
    timeout: 10_000,
  });
  const output = `${stdout}\n${stderr}`;
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25) {
    throw new Error("kernel loop smoke requires Java major version 25");
  }
}

/** 创建五个互不别名的 Unicode 根，以覆盖 Windows Base64URL argv 合同。 */
async function createDirectories() {
  const root = await mkdtemp(join(tmpdir(), temporaryPrefix));
  const home = join(root, "home-家");
  const data = join(root, "data-数据");
  const run = join(root, "run-运行");
  const logs = join(root, "log-日志");
  const workspace = join(root, "workspace-工作区");
  await Promise.all([home, data, run, logs, workspace].map((path) => mkdir(path)));
  await writeFile(join(workspace, "AGENTS.md"), "# Ja Kernel smoke\n", "utf8");
  return { root, home, data, run, logs, workspace };
}

/** 仅删除本次 mkdtemp 的直接子目录，解析后的父目录和前缀缺一不可。 */
async function cleanupDirectories(root) {
  const target = resolve(root);
  if (dirname(target) !== resolve(tmpdir())
      || !target.split(/[\\/]/u).at(-1)?.startsWith(temporaryPrefix)) {
    throw new Error("refusing to clean a non-owned kernel smoke directory");
  }
  await rm(target, { recursive: true, force: false });
}

/** 构造当前唯一严格空配置，不携带 Provider 或旧配置键。 */
function emptyConfiguration() {
  return {
    schema_version: 2,
    config_revision: 0,
    permission_mode: "full_access",
    default_profile_id: null,
    profiles: [],
    mcp_servers: [],
    skills: [],
  };
}

/** 校验结构化 JA-RPC 错误后只暴露稳定 errorCode。 */
function success(frame, operation) {
  if (frame?.error !== undefined) {
    throw new Error(`${operation} failed: ${requireRpcErrorCode(frame.error)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 执行不依赖网络和 fake adapter 的生产 JAR 生命周期。 */
export async function runSmoke({ command, prefixArgs, silent = false } = {}) {
  const directories = await createDirectories();
  const java = command ?? resolveJava();
  let session;
  try {
    if (command === undefined) await requireJava25(java);
    const jar = command === undefined ? await resolveJar() : undefined;
    session = new JsonlSession({
      command: java,
      prefixArgs: prefixArgs ?? ["-jar", jar],
      directories,
      apiKey: "",
      endpoint: "",
    });

    const initialized = success(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    if (initialized?.runtime?.engine !== "ja-kernel") throw new Error("runtime identity is invalid");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      requestTimeoutMs,
    );

    const configuration = success(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const cas = configuration?.cas;
    if (cas?.userVersion !== "cfg_missing" || cas?.credentialVersion !== "cfg_missing") {
      throw new Error("fresh smoke home did not report missing CAS versions");
    }
    const configured = success(await session.request("configuration/replace", {
      scope: "user",
      expectedVersion: cas.userVersion,
      document: emptyConfiguration(),
    }), "configuration/replace");
    if (configured?.accepted !== true || configured?.scope !== "user"
        || typeof configured?.version !== "string") {
      throw new Error("configuration/replace returned an invalid result");
    }

    const credential = success(await session.request("credential/set", {
      credentialId,
      secret: "kernel-smoke-placeholder",
      expectedVersion: cas.credentialVersion,
    }), "credential/set");
    if (credential?.configured !== true || credential?.credentialId !== credentialId) {
      throw new Error("credential/set returned an invalid redacted result");
    }

    const workspace = success(await session.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Ja Kernel smoke",
    }), "workspace/open");
    if (typeof workspace?.workspaceId !== "string" || !workspace.workspaceId.startsWith("ws_")) {
      throw new Error("workspace identity is invalid");
    }

    const health = success(await session.request("runtime/health", {}), "runtime/health");
    const componentStates = new Map(
      Array.isArray(health?.components)
        ? health.components.map((component) => [component?.name, component?.status])
        : [],
    );
    if (health?.status !== "ready" || componentStates.get("sqlite") !== "healthy"
        || componentStates.get("kernel") !== "healthy") {
      throw new Error("production Kernel or SQLite health is not ready");
    }

    const skills = success(await session.request("skill/list", {}), "skill/list");
    if (!Array.isArray(skills?.items)
        || !skills.items.some((skill) => skill?.name === "coding" && skill?.status === "healthy")) {
      throw new Error("builtin coding Skill is unavailable");
    }

    const created = success(await session.request("thread/create", {
      cwd: directories.workspace,
      title: "Ja Kernel smoke",
    }), "thread/create");
    if (typeof created?.threadId !== "string" || !created.threadId.startsWith("thr_")
        || created.workspaceId !== workspace.workspaceId) {
      throw new Error("thread/create returned an invalid Java-owned identity");
    }
    const history = success(
      await session.request("thread/read", { threadId: created.threadId }),
      "thread/read",
    );
    if (history?.threadId !== created.threadId || !Number.isSafeInteger(history?.revision)
        || !Array.isArray(history?.items)) {
      throw new Error("fresh Thread replay is invalid");
    }

    const deleted = success(await session.request("credential/delete", {
      credentialId,
      expectedVersion: credential.version,
    }), "credential/delete");
    if (deleted?.configured !== false || deleted?.credentialId !== credentialId) {
      throw new Error("credential/delete returned an invalid redacted result");
    }

    const exit = await session.shutdown();
    if (exit.code !== 0 || exit.signal !== null) throw new Error("Ja App Server did not exit cleanly");
    const logMetadata = await stat(join(directories.logs, "app-server.log"));
    if (!logMetadata.isFile() || logMetadata.size === 0) {
      throw new Error("app-server.log was not persisted");
    }
    if (Buffer.byteLength(session.stderr) !== 0) throw new Error("Ja App Server wrote unexpected stderr");

    const report = {
      status: "passed",
      engine: initialized.runtime.engine,
      configurationCas: "explicit",
      sqlite: "healthy",
      threadPersistence: "passed",
      shutdown: "clean",
      logBytes: logMetadata.size,
      stderrBytes: 0,
    };
    if (!silent) process.stdout.write(`${JSON.stringify(report)}\n`);
    return report;
  } finally {
    await session?.forceClose();
    await cleanupDirectories(directories.root);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await runSmoke().catch((error) => {
    process.stderr.write(`kernel-loop-smoke failed: ${error?.message ?? error}\n`);
    process.exitCode = 1;
  });
}
