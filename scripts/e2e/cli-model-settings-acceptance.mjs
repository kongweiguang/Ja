// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { createServer } from "node:http";
import { Socket } from "node:net";
import { promisify } from "node:util";
import { appendFile, mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import {
  basename,
  delimiter as pathDelimiter,
  dirname,
  join,
  relative,
  resolve,
  sep,
} from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  createIsolatedDirectories,
  JsonlSession,
  initializeParams,
  providerConfigurationDocument,
} from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";
import { normalizeJsonlOutcome } from "./cli-loopback-acceptance.mjs";
import {
  assertDatabaseLeaseReleased,
  compileDatabaseLeaseProbe,
} from "./isolated-database-lease.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const execFileAsync = promisify(execFile);
const apis = [
  { api: "openai_responses", suffix: "responses", route: "/v1/responses" },
  {
    api: "openai_chat_completions",
    suffix: "chat",
    route: "/v1/chat/completions",
  },
  { api: "anthropic_messages", suffix: "anthropic", route: "/v1/messages" },
];
const fixedReply = "JA_MODEL_SETTINGS_LOOPBACK_REPLY";
const testSecret = "ja-model-settings-local-fixture-secret";
const timeoutMs = 120_000;

/** 只接受本地 loopback 请求并按实际协议生成 SSE，避免验收访问真实账户或产生费用。 @author kongweiguang */
async function startProviderFixture() {
  const observations = [];
  let requestSequence = 0;
  const server = createServer(async (request, response) => {
    const selected = apis.find((entry) => entry.route === request.url);
    if (request.method !== "POST" || selected === undefined) {
      response.writeHead(404);
      response.end();
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const authenticated =
        selected.api === "anthropic_messages"
          ? request.headers["x-api-key"] === testSecret
          : request.headers.authorization === `Bearer ${testSecret}`;
      const requestOrdinal = ++requestSequence;
      const messages = Array.isArray(payload?.messages) ? payload.messages : [];
      observations.push({
        requestOrdinal,
        api: selected.api,
        model: payload?.model ?? null,
        authenticated,
        messageRoles: messages.map((message) =>
          typeof message?.role === "string" ? message.role : "unknown",
        ),
        bodyFingerprint: createHash("sha256").update(JSON.stringify(payload)).digest("hex"),
      });
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(
        providerStream(selected.api, `settings_${requestOrdinal}`, undefined, fixedReply),
      );
    } catch {
      response.writeHead(400, { "content-type": "application/json" });
      response.end('{"error":"local_fixture_rejected_request"}');
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    endpoint: `http://127.0.0.1:${address.port}/v1`,
    observations,
    close: () =>
      new Promise((resolvePromise, rejectPromise) => {
        server.close((error) => (error ? rejectPromise(error) : resolvePromise()));
      }),
  };
}

/** 限制本机 Provider fixture 请求体，避免损坏或意外的大请求拖住验收进程。 @author kongweiguang */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) throw new Error("fixture request exceeded its local bound");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 校验目标构建物并强制使用 JDK 25，避免验收误连旧 sidecar 或默认 JDK。 @author kongweiguang */
async function resolveArtifacts(requireCli) {
  const java =
    process.env.JA_MODEL_SETTINGS_JAVA ??
    (process.env.JAVA_HOME
      ? join(process.env.JAVA_HOME, "bin", "java.exe")
      : "C:\\Users\\24052\\.jdks\\liberica-25.0.2\\bin\\java.exe");
  const jar = resolve(
    process.env.JA_MODEL_SETTINGS_JAR ??
      join(repoRoot, "ja-agent/ja-app-server/target/ja-app-server.jar"),
  );
  const cli = resolve(process.env.JA_MODEL_SETTINGS_CLI ?? join(repoRoot, "target/debug/ja.exe"));
  const javac = join(dirname(java), "javac.exe");
  const jarTool = join(dirname(java), "jar.exe");
  for (const artifact of requireCli ? [jar, cli, javac, jarTool] : [jar]) {
    const metadata = await stat(artifact);
    if (!metadata.isFile() || metadata.size === 0) {
      throw new Error("the requested Ja CLI or App Server build artifact is missing");
    }
  }
  const javaVersion = await execFileAsync(java, ["-version"], {
    windowsHide: true,
    maxBuffer: 64 * 1024,
  }).catch(() => {
    throw new Error("the configured JDK 25 executable could not be started");
  });
  const versionText = `${javaVersion.stdout}\n${javaVersion.stderr}`;
  if (!/version\s+"25(?:\.|"|\s)/u.test(versionText)) {
    throw new Error("model settings acceptance requires JDK 25");
  }
  return { java, javac, jarTool, jar, cli };
}

/** 建立 CLI 原生 JA_HOME 布局，让直接 JA-RPC 写入与稍后的 ja.exe 共用隔离数据。 @author kongweiguang */
async function createCliProfile() {
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

/** 暂时剥离所有既有 JA profile/Provider 授权变量，保证子进程只能使用本地隔离配置。 @author kongweiguang */
function installIsolatedEnvironment(home, java, jar) {
  const keys = [
    "JA_HOME",
    "JA_DEBUG_JAVA",
    "JA_DEBUG_JAR",
    "JA_REAL_PROVIDER_API_KEY",
    "JA_REAL_PROVIDER_BASE_URL",
    "JA_REAL_PROVIDER_MODEL",
    "JA_REAL_PROVIDER_NAME",
    "JA_REAL_PROVIDER_API",
    "JA_REAL_PROVIDER_REASONING_LEVEL",
    "JA_REAL_PROVIDER_AUTHORIZED",
    "JA_REAL_PROVIDER_PERSIST_HOME",
  ];
  const prior = new Map(keys.map((key) => [key, process.env[key]]));
  for (const key of keys) delete process.env[key];
  process.env.JA_HOME = home;
  process.env.JA_DEBUG_JAVA = java;
  process.env.JA_DEBUG_JAR = jar;
  return () => {
    for (const [key, value] of prior) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  };
}

/** 通过与生产一致的完整 v2 握手启动独立 App Server JSONL 会话。 @author kongweiguang */
async function startConfigurationSession(artifacts, directories) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: testSecret,
    endpoint: "http://127.0.0.1/fixture-only",
  });
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    return session;
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** 把 JA-RPC 错误缩减到稳定错误码，避免把服务端自由文本带入验收报告。 @author kongweiguang */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = Number.isInteger(frame.error.code) ? frame.error.code : "unknown";
    throw new Error(`${operation} failed with RPC code ${code}`);
  }
  if (frame?.result === null || typeof frame?.result !== "object") {
    throw new Error(`${operation} returned no result object`);
  }
  return frame.result;
}

/** 从桌面现行严格 provider 文档提取实体字段，确保三协议都使用配置合同中的默认值。 @author kongweiguang */
function providerPatch(api, endpoint, suffix) {
  const providerId = `provider_model_settings_${suffix}`;
  const modelId = `model_model_settings_${suffix}`;
  const credentialId = `cred_model_settings_${suffix}`;
  const document = providerConfigurationDocument({
    endpoint,
    name: `Model settings ${suffix}`,
    api,
    model: `ja-model-settings-${suffix}`,
    selectedProviderId: providerId,
    selectedModelId: modelId,
    selectedCredentialId: credentialId,
    reasoningLevel: "medium",
  });
  const provider = document.providers[0];
  const model = provider.models[0];
  const { provider_id: _providerId, models: _models, ...providerFields } = provider;
  const { model_id: _modelId, ...modelFields } = model;
  return {
    api,
    providerId,
    modelId,
    credentialId,
    upstreamModel: model.model,
    entity: {
      ...providerFields,
      models: { [modelId]: modelFields },
      model_order: [modelId],
    },
  };
}

/** 解析脱敏配置结果中的稳定 Provider/Model 标识，不把 key 或配置原文写入证据。 @author kongweiguang */
function findProvider(configuration, providerId) {
  const provider = configuration?.user?.document?.providers?.find(
    (entry) => entry?.provider_id === providerId,
  );
  assert.ok(provider, `configuration/read omitted ${providerId}`);
  return provider;
}

/** 在隔离 TOML 中加入合法未知字段与被投影过滤的坏 Provider，验证稀疏写入不会抹掉原始数据。 @author kongweiguang */
async function injectRawPreservationCases(home, providerId) {
  const path = join(home, "config.toml");
  let content = await readFile(path, "utf8");
  const firstProvider = content.indexOf("[[providers]]");
  assert.notEqual(firstProvider, -1, "user config must contain provider tables");
  content = `future_root_toggle = "keep-root"\n${content}`;
  const firstProviderAfterPrefix = content.indexOf("[[providers]]");
  const firstModels = content.indexOf("[[providers.models]]", firstProviderAfterPrefix);
  assert.notEqual(firstModels, -1, "the first provider must contain its model table");
  content =
    content.slice(0, firstModels) +
    'future_provider_toggle = "keep-provider"\n' +
    content.slice(firstModels);
  const firstModelHeader = content.indexOf("[[providers.models]]", firstProviderAfterPrefix);
  const modelHeaderEnd = content.indexOf("\n", firstModelHeader) + 1;
  content =
    content.slice(0, modelHeaderEnd) +
    'future_model_toggle = "keep-model"\n' +
    content.slice(modelHeaderEnd);
  content +=
    '\n[[providers]]\nprovider_id = "provider_broken_hidden"\nfuture_entry = "keep-broken"\n';
  await writeFile(path, content, "utf8");
  return path;
}

/** 执行 ja.exe 一次命令并限时收集输出；超时只终止该隔离 CLI 子进程。 @author kongweiguang */
async function runCli(cli, args, directories, timeout = timeoutMs) {
  const child = spawn(cli, args, {
    cwd: directories.workspace,
    env: { ...process.env, JA_HOME: directories.home },
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  let overflow = false;
  const collect = (current, chunk) => {
    const next = current + chunk.toString("utf8");
    if (next.length > 8 * 1024 * 1024) {
      overflow = true;
      child.kill();
      return current;
    }
    return next;
  };
  child.stdout.on("data", (chunk) => (stdout = collect(stdout, chunk)));
  child.stderr.on("data", (chunk) => (stderr = collect(stderr, chunk)));
  const result = await new Promise((resolvePromise, rejectPromise) => {
    const timer = setTimeout(() => {
      child.kill();
      rejectPromise(new Error("ja.exe command exceeded its bounded acceptance deadline"));
    }, timeout);
    child.once("error", (error) => {
      clearTimeout(timer);
      rejectPromise(new Error(`ja.exe could not start: ${error.code ?? "spawn error"}`));
    });
    child.once("exit", (code, signal) => {
      clearTimeout(timer);
      resolvePromise({ code, signal });
    });
  });
  if (overflow) throw new Error("ja.exe output exceeded its bounded acceptance limit");
  return { ...result, stdout, stderr };
}

const modelSettingsConPtyHarnessSource = String.raw`// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import io.github.kongweiguang.ja.platform.windows.WindowsJobObject;
import io.github.kongweiguang.ja.platform.windows.WindowsProcessLauncher;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 只创建归属于本次验收的 ConPTY；Shell/CLI 的真实父子关系由传入 argv 保持。 */
public final class ModelSettingsConPty {
    /** 无状态 harness 不提供可复用终端实例。 */
    private ModelSettingsConPty() {
    }

    /** Job 清理终端子树并允许共享后台显式脱离；原始 VT 只写入隔离文件供键盘验收。 */
    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        Files.createDirectories(options.log().toAbsolutePath().getParent());
        WindowsJobObject job = WindowsJobObject.create();
        Process child = null;
        Thread outputPump = null;
        try {
            permitBackgroundOwnerBreakaway(job);
            child = WindowsProcessLauncher.launch(
                    options.command(), options.cwd(), Map.copyOf(System.getenv()), job, true);
            Process ownedChild = child;
            BufferedOutputStream capture = new BufferedOutputStream(Files.newOutputStream(
                    options.log(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE));
            outputPump = new Thread(() -> copyOutput(ownedChild, capture), "ja-model-settings-conpty-capture");
            outputPump.setDaemon(true);
            outputPump.start();
            System.out.println("CHILD_PID=" + child.pid());
            System.out.println("LOG=" + options.log().toAbsolutePath());
            System.out.flush();
            controlLoop(child);
        } finally {
            if (child != null && child.isAlive()) {
                job.close();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            if (child != null) {
                WindowsProcessLauncher.finishPseudoConsole(child);
                if (outputPump != null) outputPump.join(TimeUnit.SECONDS.toMillis(5));
                WindowsProcessLauncher.close(child);
            }
            job.close();
        }
    }

    /** 测试 Job 保留 KILL_ON_CLOSE，只允许显式后台 owner 脱离；不新增生产 Job API 或修改宿主外层 Job。 */
    private static void permitBackgroundOwnerBreakaway(WindowsJobObject job) throws Exception {
        var field = WindowsJobObject.class.getDeclaredField("jobHandle");
        field.setAccessible(true);
        MemorySegment handle = (MemorySegment) field.get(job);
        try (Arena arena = Arena.ofConfined()) {
            var function = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("Kernel32", arena).find("SetInformationJobObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MemorySegment limits = arena.allocate(144, 8);
            limits.fill((byte) 0);
            limits.set(ValueLayout.JAVA_INT, 16, 0x2000 | 0x0800);
            int result;
            try {
                result = (int) function.invokeExact(handle, 9, limits, 144);
            } catch (Throwable failure) {
                throw new IllegalStateException("isolated_job_breakaway_configuration_failed", failure);
            }
            if (result == 0) throw new IllegalStateException("isolated_job_breakaway_configuration_failed");
        }
    }

    /** 以有界块增量捕获 VT 字节，便于外层检查 masked field 与 Shell 返回标记。 */
    private static void copyOutput(Process child, BufferedOutputStream capture) {
        try (capture; BufferedInputStream input = new BufferedInputStream(child.getInputStream())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) continue;
                capture.write(buffer, 0, count);
                capture.flush();
            }
        } catch (IOException failure) {
            System.err.println("CAPTURE_ERROR=" + failure.getClass().getSimpleName());
        }
    }

    /** 控制通道只接受状态、关闭与 Base64 按键，不能执行验收路径之外的任意命令。 */
    private static void controlLoop(Process child) throws IOException {
        try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = commands.readLine()) != null) {
                if (line.equals("STATUS")) {
                    System.out.println("CHILD_ALIVE=" + child.isAlive());
                    if (!child.isAlive()) System.out.println("CHILD_EXIT_CODE=" + child.exitValue());
                    System.out.flush();
                    continue;
                }
                if (line.equals("CLOSE")) {
                    if (child.isAlive()) {
                        System.out.println("CLOSE_REFUSED=child_still_running");
                    } else {
                        System.out.println("CLOSE_ACCEPTED=true");
                        System.out.flush();
                        return;
                    }
                    System.out.flush();
                    continue;
                }
                if (!line.startsWith("SEND ")) {
                    System.out.println("COMMAND_REJECTED=unknown");
                    System.out.flush();
                    continue;
                }
                if (!child.isAlive()) {
                    System.out.println("SEND_REJECTED=child_exited");
                    System.out.flush();
                    continue;
                }
                byte[] bytes = Base64.getDecoder().decode(line.substring(5));
                child.getOutputStream().write(bytes);
                child.getOutputStream().flush();
                System.out.println("SENT_BYTES=" + bytes.length);
                System.out.flush();
            }
        }
    }

    /** 以数组参数启动，避免 shell 解析把绝对路径、空格或测试输入改写。 */
    private record Options(Path cwd, Path log, List<String> command) {
        private static Options parse(String[] arguments) {
            Path cwd = null;
            Path log = null;
            List<String> command = new ArrayList<>();
            boolean commandStarted = false;
            for (int index = 0; index < arguments.length; index++) {
                String value = arguments[index];
                if (!commandStarted && value.equals("--")) {
                    commandStarted = true;
                } else if (!commandStarted && value.equals("--cwd") && index + 1 < arguments.length) {
                    cwd = Path.of(arguments[++index]).toAbsolutePath().normalize();
                } else if (!commandStarted && value.equals("--log") && index + 1 < arguments.length) {
                    log = Path.of(arguments[++index]).toAbsolutePath().normalize();
                } else if (commandStarted) {
                    command.add(value);
                } else {
                    throw new IllegalArgumentException("unknown_harness_option");
                }
            }
            if (cwd == null || log == null || command.isEmpty() || !Files.isDirectory(cwd)) {
                throw new IllegalArgumentException("cwd_log_and_child_command_required");
            }
            return new Options(cwd, log, List.copyOf(command));
        }
    }
}`;

/** 从最新 JAR 提取 ConPTY owner 类并单独编译测试 harness，避免加载工作树旧 class。 @author kongweiguang */
async function compileModelSettingsConPtyHarness(artifacts, directory) {
  const root = join(directory, "model-settings-conpty");
  const extracted = join(root, "app-server");
  const sourceDirectory = join(root, "source");
  const classes = join(root, "classes");
  await Promise.all(
    [extracted, sourceDirectory, classes].map((path) => mkdir(path, { recursive: true })),
  );
  await execFileAsync(
    artifacts.jarTool,
    [
      "--extract",
      "--file",
      artifacts.jar,
      "--dir",
      extracted,
      "BOOT-INF/classes/io/github/kongweiguang/ja/platform/windows",
    ],
    { windowsHide: true, timeout: 30_000 },
  );
  const appClasses = join(extracted, "BOOT-INF", "classes");
  const source = join(sourceDirectory, "ModelSettingsConPty.java");
  await writeFile(source, modelSettingsConPtyHarnessSource, "utf8");
  await execFileAsync(
    artifacts.javac,
    ["-encoding", "UTF-8", "-cp", appClasses, "-d", classes, source],
    { windowsHide: true, timeout: 30_000, maxBuffer: 2 * 1024 * 1024 },
  );
  return { classPath: [appClasses, classes].join(pathDelimiter) };
}

/** 在隔离 workspace 中把真实命令置于 ConPTY，shell wrapper 用于验证 ja setup 退出后的控制权恢复。 @author kongweiguang */
function startModelSettingsConPty(artifacts, directories, harness, command, logPath) {
  const child = spawn(
    artifacts.java,
    [
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      harness.classPath,
      "ModelSettingsConPty",
      "--cwd",
      directories.workspace,
      "--log",
      logPath,
      "--",
      ...command,
    ],
    {
      cwd: directories.workspace,
      env: { ...process.env, JA_HOME: directories.home },
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  const messages = [];
  const waiters = new Set();
  let partial = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk) => {
    partial += chunk;
    while (true) {
      const newline = partial.indexOf("\n");
      if (newline < 0) break;
      const line = partial.slice(0, newline).replace(/\r$/u, "");
      partial = partial.slice(newline + 1);
      messages.push(line);
      for (const waiter of waiters) waiter.check(line, messages.length - 1);
    }
  });
  child.stderr.setEncoding("utf8").on("data", (chunk) => {
    stderr = `${stderr}${chunk}`.slice(-4_096);
  });
  const exitPromise = new Promise((resolvePromise, rejectPromise) => {
    child.once("error", rejectPromise);
    child.once("exit", (code, signal) => resolvePromise({ code, signal }));
  });
  /** 控制行按序号关联，避免旧 ACK 冒充后一次按键已送入伪终端。 */
  function waitForMessage(
    prefix,
    afterIndex,
    timeout = 10_000,
    matches = (line) => line.startsWith(prefix),
  ) {
    const existing = messages.findIndex((line, index) => index >= afterIndex && matches(line));
    if (existing >= 0) return Promise.resolve(messages[existing]);
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        waiters.delete(waiter);
        rejectPromise(new Error(`ConPTY harness did not emit ${prefix}`));
      }, timeout);
      const waiter = {
        check(line, index) {
          if (index < afterIndex || !matches(line)) return;
          clearTimeout(timer);
          waiters.delete(waiter);
          resolvePromise(line);
        },
      };
      waiters.add(waiter);
    });
  }
  return {
    child,
    exitPromise,
    get stderr() {
      return stderr;
    },
    /** 已接受的字节数只用于串行化键入，不代表应用数据已保存。 */
    async sendKeys(value, timeout = 10_000) {
      if (child.exitCode !== null || child.signalCode !== null)
        throw new Error("ConPTY harness already exited");
      const bytes = Buffer.from(value, "utf8");
      const start = messages.length;
      child.stdin.write(`SEND ${bytes.toString("base64")}\n`);
      await waitForMessage(
        "SENT_BYTES=",
        start,
        timeout,
        (line) => line === `SENT_BYTES=${bytes.length}`,
      );
    },
    /** 查询真实子进程状态；Only exited child accepts CLOSE, 避免关掉仍持有终端的 Shell。 */
    async status() {
      const start = messages.length;
      child.stdin.write("STATUS\n");
      const alive = await waitForMessage("CHILD_ALIVE=", start);
      const exitCodeLine = alive.endsWith("false")
        ? await waitForMessage("CHILD_EXIT_CODE=", start + 1)
        : null;
      return {
        alive: alive.endsWith("true"),
        exitCode:
          exitCodeLine === null ? null : Number(exitCodeLine.slice("CHILD_EXIT_CODE=".length)),
      };
    },
    /** 只有命令已退出才释放控制 harness；失败路径由 Java Job Object 回收子进程树。 */
    async close() {
      child.stdin.write("CLOSE\n");
      await waitForMessage("CLOSE_ACCEPTED=true", messages.length);
      child.stdin.end();
      return exitPromise;
    },
  };
}

/** 等待原始 ConPTY VT 流里出现新画面文本；不把静态渲染快照冒充交互验收。 @author kongweiguang */
async function waitForPtyText(logPath, text, timeout = 15_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const output = await readFile(logPath, "utf8").catch((error) => {
      if (error?.code === "ENOENT") return "";
      throw error;
    });
    if (output.includes(text)) return output;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  throw new Error(`ConPTY terminal did not render ${text}`);
}

/** 确认指定画面由最近动作重绘，避免先前 scrollback 文本误判为面板已经返回。 @author kongweiguang */
async function waitForNewPtyText(logPath, text, afterBytes, timeout = 15_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const output = await readFile(logPath).catch((error) => {
      if (error?.code === "ENOENT") return Buffer.alloc(0);
      throw error;
    });
    if (output.length > afterBytes && output.subarray(afterBytes).toString("utf8").includes(text)) {
      return output.toString("utf8");
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  throw new Error(`ConPTY did not redraw ${text} after the requested action`);
}

/** 轮询 shell 输出标记与进程退出，确认设置 TUI 返回后终端仍可执行下一条 Shell 命令。 @author kongweiguang */
async function waitForShellReturn(pty, logPath, marker) {
  await waitForPtyText(logPath, marker, 30_000);
  const deadline = Date.now() + 5_000;
  let status;
  do {
    status = await pty.status();
    if (!status.alive) break;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  } while (Date.now() < deadline);
  assert.equal(
    status.alive,
    false,
    "finite PowerShell acceptance wrapper should exit after its marker",
  );
  assert.equal(status.exitCode, 0, "ja setup and the post-setup Shell marker must both succeed");
  return pty.close();
}

/** PowerShell 单引号字面量不展开变量或反引号，CLI 路径只由测试 artifact 决定。 @author kongweiguang */
function quotePowerShellLiteral(value) {
  return `'${value.replaceAll("'", "''")}'`;
}

/** 空 JA_HOME 中通过键盘创建首个 Responses Provider；检查 Ctrl+S、密钥掩码与 Shell 恢复。 @author kongweiguang */
async function configureFirstProviderWithSetup(artifacts, directories, harness, endpoint) {
  const marker = `JA_SETUP_SHELL_RESTORED_${process.pid}`;
  const logPath = join(directories.root, "setup-empty.conpty.log");
  const script = `& ${quotePowerShellLiteral(artifacts.cli)} setup; if ($LASTEXITCODE -ne 0) { Write-Output 'JA_SETUP_SHELL_FAILED'; exit $LASTEXITCODE }; Write-Output '${marker}'`;
  const pty = startModelSettingsConPty(
    artifacts,
    directories,
    harness,
    ["pwsh.exe", "-NoLogo", "-NoProfile", "-Command", script],
    logPath,
  );
  try {
    await waitForPtyText(logPath, "全局模型设置");
    await waitForPtyText(logPath, "配置已从 App Server 刷新");
    await pty.sendKeys("a");
    await waitForPtyText(logPath, "供应商设置");
    await pty.sendKeys("\t\t");
    await pty.sendKeys(endpoint);
    await pty.sendKeys("\t");
    await pty.sendKeys(testSecret);
    const maskedOutput = await waitForPtyText(logPath, "••••••••");
    assert.ok(
      maskedOutput.includes("API Key"),
      "new-provider form must expose the focused key field",
    );
    assert.ok(!maskedOutput.includes(testSecret), "API key must never appear in ConPTY scrollback");
    await pty.sendKeys("\tja-model-settings-responses\t\t\u0013");
    await waitForPtyText(logPath, "模型配置已保存并核实", 30_000);
    await pty.sendKeys("\u001b");
    const completed = await waitForPtyText(logPath, marker, 30_000);
    assert.ok(
      !completed.includes(testSecret),
      "key must remain masked through setup exit and Shell restoration",
    );
    await waitForShellReturn(pty, logPath, marker);
    return { logPath, shellRestored: true, secretMasked: true };
  } catch (error) {
    if (pty.child.exitCode === null && pty.child.signalCode === null) {
      pty.child.kill();
      await pty.exitPromise.catch(() => undefined);
    }
    throw error;
  }
}

/** 重开 setup 后以刷新后的 VT 文本和真实上游标识确认列表可编辑，避免旧 scrollback 误判。 @author kongweiguang */
async function verifyConfiguredSetupRemainsEditable(
  artifacts,
  directories,
  harness,
  expectedUpstreamModel,
) {
  const marker = `JA_SETUP_EDIT_SHELL_RESTORED_${process.pid}`;
  const logPath = join(directories.root, "setup-configured.conpty.log");
  const script = `& ${quotePowerShellLiteral(artifacts.cli)} setup; if ($LASTEXITCODE -ne 0) { Write-Output 'JA_SETUP_EDIT_SHELL_FAILED'; exit $LASTEXITCODE }; Write-Output '${marker}'`;
  const pty = startModelSettingsConPty(
    artifacts,
    directories,
    harness,
    ["pwsh.exe", "-NoLogo", "-NoProfile", "-Command", script],
    logPath,
  );
  try {
    await waitForPtyText(logPath, "全局模型设置");
    const refreshed = await waitForPtyText(logPath, "配置已从 App Server 刷新");
    assert.ok(
      refreshed.includes(expectedUpstreamModel),
      `refreshed setup must show the upstream model label ${expectedUpstreamModel}`,
    );
    await pty.sendKeys("\r");
    const form = await waitForPtyText(logPath, "供应商设置");
    assert.ok(
      form.includes("更换 API Key"),
      "configured provider must open editable form without asking to re-enter its key",
    );
    const listRedrawStart = (await stat(logPath)).size;
    await pty.sendKeys("\u001b");
    await waitForNewPtyText(logPath, "全局模型设置", listRedrawStart, 10_000);
    await pty.sendKeys("\u001b");
    await waitForPtyText(logPath, marker, 30_000);
    await waitForShellReturn(pty, logPath, marker);
    return { configuredSetupEditable: true, shellRestored: true };
  } catch (error) {
    if (pty.child.exitCode === null && pty.child.signalCode === null) {
      pty.child.kill();
      await pty.exitPromise.catch(() => undefined);
    }
    throw error;
  }
}

/** 用隔离 JA_HOME 中已有凭据只改供应商名称，保存后返回 Shell；不打开或填写密钥字段。 @author kongweiguang */
export async function verifyConfiguredProviderNameEdit(
  artifacts,
  directories,
  expectedName,
  newName,
  secretForScrollbackCheck,
) {
  if (process.platform !== "win32") {
    throw new Error("configured Provider name edit requires Windows ConPTY");
  }
  for (const [label, value] of [
    ["expected provider name", expectedName],
    ["replacement provider name", newName],
  ]) {
    if (
      typeof value !== "string" ||
      value.trim().length === 0 ||
      /[\u0000-\u001f\u007f]/u.test(value)
    ) {
      throw new Error(`${label} must be non-empty visible text`);
    }
  }
  if (expectedName === newName) throw new Error("provider rename must change the displayed name");
  if (
    typeof secretForScrollbackCheck === "string" &&
    secretForScrollbackCheck.length > 0 &&
    secretForScrollbackCheck.includes(expectedName)
  ) {
    throw new Error("scrollback assertion secret must not overlap the provider name");
  }

  const restoreEnvironment = installIsolatedEnvironment(
    directories.home,
    artifacts.java,
    artifacts.jar,
  );
  let pty;
  try {
    const harness = await compileModelSettingsConPtyHarness(artifacts, directories.root);
    const marker = `JA_PROVIDER_RENAME_SHELL_RESTORED_${process.pid}`;
    const logPath = join(
      directories.root,
      `provider-name-edit-${process.pid}-${Date.now()}.conpty.log`,
    );
    const script = `& ${quotePowerShellLiteral(artifacts.cli)} setup; if ($LASTEXITCODE -ne 0) { Write-Output 'JA_PROVIDER_RENAME_SHELL_FAILED'; exit $LASTEXITCODE }; Write-Output '${marker}'`;
    pty = startModelSettingsConPty(
      artifacts,
      directories,
      harness,
      ["pwsh.exe", "-NoLogo", "-NoProfile", "-Command", script],
      logPath,
    );
    await waitForPtyText(logPath, "全局模型设置");
    const refreshed = await waitForPtyText(logPath, "配置已从 App Server 刷新");
    assert.ok(
      refreshed.includes(expectedName),
      "refreshed setup must show the existing Provider name before editing",
    );
    await pty.sendKeys("\r");
    const form = await waitForPtyText(logPath, "供应商设置");
    assert.ok(form.includes(expectedName), "the first Provider form must retain its current name");
    assert.ok(form.includes("更换 API Key"), "existing credential must remain configured");
    assert.ok(form.includes("未更换"), "the API Key field must remain untouched in the form");

    const graphemeCount = Array.from(
      new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(expectedName),
    ).length;
    await pty.sendKeys(`\u001b[H${"\u001b[3~".repeat(graphemeCount)}${newName}`);
    const beforeSaveText = await readFile(logPath, "utf8");
    const beforeSave = Buffer.byteLength(beforeSaveText, "utf8");
    await pty.sendKeys("\u0013");
    const saved = await waitForNewPtyText(logPath, "模型配置已保存并核实", beforeSave, 30_000);
    const savedOutput = saved.slice(beforeSaveText.length);
    assert.ok(savedOutput.includes(newName), "the verified settings list must show the new name");
    await pty.sendKeys("\u001b");
    const completed = await waitForPtyText(logPath, marker, 30_000);
    assert.ok(
      typeof secretForScrollbackCheck !== "string" ||
        secretForScrollbackCheck.length === 0 ||
        !completed.includes(secretForScrollbackCheck),
      "the existing API key must not appear in ConPTY scrollback",
    );
    await waitForShellReturn(pty, logPath, marker);
    const secretScrollbackChecked =
      typeof secretForScrollbackCheck === "string" && secretForScrollbackCheck.length > 0;
    return {
      providerNameChanged: true,
      credentialFieldUntouched: true,
      secretAbsentFromScrollback: secretScrollbackChecked ? true : null,
      shellRestored: true,
    };
  } catch (error) {
    if (pty?.child.exitCode === null && pty.child.signalCode === null) {
      pty.child.kill();
      await pty.exitPromise.catch(() => undefined);
    }
    throw error;
  } finally {
    restoreEnvironment();
  }
}

/** 真实会话进入管理页后用数字快捷键切换模型；shell 包装器用于同时核实退出后的终端可用性。 @author kongweiguang */
async function verifySessionModelManagement(
  artifacts,
  directories,
  harness,
  providerChoiceCount,
  defaultModel,
  targetModel,
) {
  assert.notDeepEqual(
    [targetModel.providerId, targetModel.modelId],
    [defaultModel.providerId, defaultModel.modelId],
    "interactive model switch must select a different model from the global default",
  );
  const marker = `JA_SESSION_MODEL_SHELL_RESTORED_${process.pid}`;
  const logPath = join(directories.root, "session-model-settings.conpty.log");
  const script = `& ${quotePowerShellLiteral(artifacts.cli)}; if ($LASTEXITCODE -ne 0) { Write-Output 'JA_SESSION_MODEL_FAILED'; exit $LASTEXITCODE }; Write-Output '${marker}'`;
  const pty = startModelSettingsConPty(
    artifacts,
    directories,
    harness,
    ["pwsh.exe", "-NoLogo", "-NoProfile", "-Command", script],
    logPath,
  );
  try {
    await waitForPtyText(logPath, "Ja");
    await pty.sendKeys("/model");
    await pty.sendKeys("\r");
    await waitForPtyText(logPath, "管理模型…", 30_000);
    await pty.sendKeys(String(providerChoiceCount + 1));
    await waitForPtyText(logPath, "全局模型设置", 30_000);
    await waitForPtyText(logPath, "配置已从 App Server 刷新", 30_000);
    const pickerRedrawStart = (await stat(logPath)).size;
    await pty.sendKeys("\u001b");
    await waitForNewPtyText(logPath, "管理模型…", pickerRedrawStart, 10_000);
    const modelSwitchRedrawStart = (await stat(logPath)).size;
    await pty.sendKeys("1");
    const modelDisplayHint = targetModel.upstreamModel.split(/[-_/]/u).filter(Boolean).at(-1);
    assert.ok(modelDisplayHint, "the selected fixture model must have a visible upstream label");
    await waitForNewPtyText(logPath, modelDisplayHint, modelSwitchRedrawStart, 15_000);
    await pty.sendKeys("\u0004");
    const completed = await waitForPtyText(logPath, marker, 30_000);
    assert.ok(!completed.includes("JA_SESSION_MODEL_FAILED"));
    await waitForShellReturn(pty, logPath, marker);
    return {
      sessionModelManagementEntry: true,
      returnedToModelPicker: true,
      keyboardSelectedProviderId: targetModel.providerId,
      keyboardSelectedModelId: targetModel.modelId,
      shellRestored: true,
    };
  } catch (error) {
    if (pty.child.exitCode === null && pty.child.signalCode === null) {
      pty.child.kill();
      await pty.exitPromise.catch(() => undefined);
    }
    throw error;
  }
}

/** 缺少默认模型密钥时实际启动 TUI、打开 /model 管理和 /resume 历史入口，避免只凭 Java 创建链推断可用。 @author kongweiguang */
async function verifyNoKeyDefaultStartup(
  artifacts,
  directories,
  harness,
  providerChoiceCount,
  expectedUpstreamModel,
) {
  const marker = `JA_NO_KEY_STARTUP_SHELL_RESTORED_${process.pid}`;
  const logPath = join(directories.root, "no-key-default-startup.conpty.log");
  const script = `& ${quotePowerShellLiteral(artifacts.cli)}; if ($LASTEXITCODE -ne 0) { Write-Output 'JA_NO_KEY_STARTUP_FAILED'; exit $LASTEXITCODE }; Write-Output '${marker}'`;
  const pty = startModelSettingsConPty(
    artifacts,
    directories,
    harness,
    ["pwsh.exe", "-NoLogo", "-NoProfile", "-Command", script],
    logPath,
  );
  try {
    await waitForPtyText(logPath, "Ja");
    await pty.sendKeys("/model");
    await pty.sendKeys("\r");
    await waitForPtyText(logPath, "管理模型…", 30_000);
    await pty.sendKeys(String(providerChoiceCount + 1));
    await waitForPtyText(logPath, "全局模型设置", 30_000);
    const refreshed = await waitForPtyText(logPath, "配置已从 App Server 刷新", 30_000);
    assert.ok(
      refreshed.includes(expectedUpstreamModel),
      "the no-key default model must remain visible in the management surface",
    );
    const pickerRedrawStart = (await stat(logPath)).size;
    await pty.sendKeys("\u001b");
    await waitForNewPtyText(logPath, "管理模型…", pickerRedrawStart, 10_000);
    await pty.sendKeys("\u001b");
    await pty.sendKeys("/resume");
    const historyPickerStart = (await stat(logPath)).size;
    await pty.sendKeys("\r");
    await waitForNewPtyText(logPath, "恢复会话", historyPickerStart, 30_000);
    await pty.sendKeys("\u001b");
    await pty.sendKeys("\u0004");
    const completed = await waitForPtyText(logPath, marker, 30_000);
    assert.ok(!completed.includes("JA_NO_KEY_STARTUP_FAILED"));
    await waitForShellReturn(pty, logPath, marker);
    return {
      startedWithoutDefaultCredential: true,
      modelManagementOpened: true,
      historyPickerOpened: true,
      shellRestored: true,
    };
  } catch (error) {
    if (pty.child.exitCode === null && pty.child.signalCode === null) {
      pty.child.kill();
      await pty.exitPromise.catch(() => undefined);
    }
    throw error;
  }
}

/** 读取端点公开身份字段；令牌仅用于客户端握手，永不进入脚本诊断。 @author kongweiguang */
async function readEndpointIdentity(runDirectory) {
  const endpointPath = join(runDirectory, "app-server.endpoint.json");
  let endpoint;
  try {
    endpoint = JSON.parse(await readFile(endpointPath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return undefined;
    throw error;
  }
  if (
    endpoint?.host !== "127.0.0.1" ||
    !Number.isInteger(endpoint.port) ||
    endpoint.port < 1 ||
    endpoint.port > 65_535 ||
    typeof endpoint.serverInstanceId !== "string" ||
    endpoint.serverInstanceId.length === 0
  ) {
    throw new Error("isolated App Server published an invalid loopback endpoint");
  }
  return {
    host: endpoint.host,
    port: endpoint.port,
    serverInstanceId: endpoint.serverInstanceId,
  };
}

/** 检查唯一 JA_HOME 的 Java owner，通过 SidecarConfig 的 base64 run-dir 参数识别进程。 @author kongweiguang */
async function findIsolatedServerProcesses(runDirectory, jar) {
  const canonical = await realpath(runDirectory);
  const pathCandidates = new Set([runDirectory, canonical]);
  for (const candidate of [...pathCandidates]) {
    pathCandidates.add(
      candidate.startsWith("\\\\?\\") ? candidate.slice(4) : `\\\\?\\${candidate}`,
    );
  }
  const runMarkers = [...pathCandidates].map(
    (candidate) => `--run-dir-base64=${Buffer.from(candidate, "utf8").toString("base64url")}`,
  );
  const markerArray = `@(${runMarkers.map(quotePowerShellLiteral).join(",")})`;
  const script = [
    `$jar = ${quotePowerShellLiteral(jar)}`,
    `$markers = ${markerArray}`,
    `$found = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {`,
    "  $line = $_.CommandLine",
    "  if ([string]::IsNullOrEmpty($line) -or -not $line.Contains($jar)) { return $false }",
    "  foreach ($marker in $markers) { if ($line.Contains($marker)) { return $true } }",
    "  return $false",
    "} | ForEach-Object { [int]$_.ProcessId })",
    "ConvertTo-Json -InputObject $found -Compress",
  ].join("; ");
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
    {
      windowsHide: true,
      timeout: 10_000,
      maxBuffer: 64 * 1024,
    },
  );
  const processIds = JSON.parse(result.stdout.trim());
  if (!Array.isArray(processIds) || processIds.some((pid) => !Number.isInteger(pid) || pid <= 0)) {
    throw new Error("isolated App Server process identity probe returned invalid data");
  }
  return processIds;
}

/** 用无凭据 loopback connect 判断端口是否仍接受连接；只触达隔离 App Server 的本机地址。 @author kongweiguang */
async function isLoopbackPortListening(host, port) {
  return new Promise((resolvePromise, rejectPromise) => {
    const socket = new Socket();
    const timer = setTimeout(() => {
      socket.destroy();
      resolvePromise(false);
    }, 500);
    socket.once("connect", () => {
      clearTimeout(timer);
      socket.destroy();
      resolvePromise(true);
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      if (error.code === "ECONNREFUSED" || error.code === "ECONNRESET") resolvePromise(false);
      else rejectPromise(error);
    });
    socket.connect(port, host);
  });
}

/** 以隔离 run-dir 的真实 Java 进程退出且端口关闭作为停止事实，允许 owner 留下无监听 stale endpoint。 @author kongweiguang */
async function waitForDaemonStopped(runDirectory, jar, expectedEndpoint) {
  const deadline = Date.now() + 20_000;
  let processIds = await findIsolatedServerProcesses(runDirectory, jar);
  while (Date.now() < deadline) {
    const currentEndpoint = await readEndpointIdentity(runDirectory);
    if (
      currentEndpoint !== undefined &&
      currentEndpoint.serverInstanceId !== expectedEndpoint.serverInstanceId
    ) {
      throw new Error("isolated App Server endpoint changed owner during shutdown");
    }
    const listening = await isLoopbackPortListening(expectedEndpoint.host, expectedEndpoint.port);
    if (processIds.length === 0 && !listening) {
      return {
        endpointRemoved: currentEndpoint === undefined,
        ownerProcessExited: true,
        loopbackPortClosed: true,
      };
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
    processIds = await findIsolatedServerProcesses(runDirectory, jar);
  }
  throw new Error("isolated App Server owner process or loopback port remained after stop request");
}

/** 连续终端场景须复用仍在运行的同一 owner，防止测试 Job 强杀后台后以重新启动冒充共享连接。 @author kongweiguang */
async function assertSharedCliOwnerAlive(artifacts, directories, expectedEndpoint) {
  const endpoint = await readEndpointIdentity(directories.run);
  assert.ok(endpoint, "CLI must retain its background owner after terminal exit");
  if (expectedEndpoint !== undefined) {
    assert.equal(endpoint.serverInstanceId, expectedEndpoint.serverInstanceId);
  }
  assert.equal(await isLoopbackPortListening(endpoint.host, endpoint.port), true);
  assert.ok((await findIsolatedServerProcesses(directories.run, artifacts.jar)).length > 0);
  return endpoint;
}

/** 固定 instance ID 后核对进程、端口和数据库锁；停止回执不确定时保留有界诊断。 @author kongweiguang */
async function stopIsolatedCliServer(artifacts, directories) {
  const endpoint = await readEndpointIdentity(directories.run);
  assert.ok(endpoint, "interactive setup must leave its isolated App Server endpoint available");
  const result = await runCli(artifacts.cli, ["server", "stop", "--force"], directories, 30_000);
  const shutdown = await waitForDaemonStopped(directories.run, artifacts.jar, endpoint);
  await assertDatabaseLeaseReleased(
    artifacts.java,
    directories.leaseProbe,
    directories.home,
    "CLI App Server",
  );
  const evidence = {
    stoppedInstanceId: endpoint.serverInstanceId,
    stopCommandAccepted: result.code === 0,
    stopCommandExitCode: result.code,
    stopCommandSignal: result.signal,
    stopCommandDiagnostic:
      result.code === 0
        ? ""
        : `${result.stdout}\n${result.stderr}`.replaceAll(testSecret, "[已隐藏]").slice(-2_000),
    databaseLeaseReleased: true,
    ...shutdown,
  };
  await appendFile(
    join(directories.root, "app-server-shutdown-evidence.jsonl"),
    `${JSON.stringify(evidence)}\n`,
    "utf8",
  );
  return evidence;
}

/** 确认递归清理只作用于 helper 创建的唯一临时根，防止测试路径漂移到用户数据。 @author kongweiguang */
async function cleanupIsolatedProfile(root) {
  const temporaryRoot = await realpath(tmpdir());
  const target = await realpath(root);
  const relativeTarget = relative(temporaryRoot, target);
  if (
    dirname(target) !== temporaryRoot ||
    !basename(target).startsWith("ja-real-provider-") ||
    relativeTarget === "" ||
    relativeTarget === ".." ||
    relativeTarget.startsWith(`..${sep}`)
  ) {
    throw new Error("refusing to remove a profile outside its owned temporary directory");
  }
  await rm(target, { recursive: true, force: false, maxRetries: 5, retryDelay: 250 });
}

/** 运行本机模型闭环，只有 owner 与数据库 lease 结束才返回通过；失败保留隔离证据。 @author kongweiguang */
export async function runAcceptance() {
  if (process.platform !== "win32")
    throw new Error("CLI model settings acceptance requires Windows");
  const rpcOnly = process.env.JA_MODEL_SETTINGS_RPC_ONLY === "1";
  const artifacts = await resolveArtifacts(!rpcOnly);
  const directories = await createCliProfile();
  const restoreEnvironment = installIsolatedEnvironment(
    directories.home,
    artifacts.java,
    artifacts.jar,
  );
  let fixture;
  let session;
  let cliDaemonMayRun = false;
  let setupEvidence;
  let setupShutdownEvidence;
  const finalShutdownEvidence = {};
  let providerNameEditEvidence;
  let sessionUiEvidence;
  let noKeyStartupEvidence;
  let sessionModelSwitchEvidence;
  let noKeyThreadId;
  let cliWorkspaceId;
  let conPtyHarness;
  let currentPhase = "artifact and isolated profile initialization";
  let acceptanceError;
  try {
    directories.leaseProbe = await compileDatabaseLeaseProbe(artifacts.javac, directories.root);
    fixture = await startProviderFixture();
    if (!rpcOnly) {
      currentPhase = "empty-home ja setup ConPTY flow";
      conPtyHarness = await compileModelSettingsConPtyHarness(artifacts, directories.root);
      cliDaemonMayRun = true;
      setupEvidence = await configureFirstProviderWithSetup(
        artifacts,
        directories,
        conPtyHarness,
        fixture.endpoint,
      );
      currentPhase = "stop isolated setup App Server";
      setupShutdownEvidence = await stopIsolatedCliServer(artifacts, directories);
      cliDaemonMayRun = false;
    }
    currentPhase = "start direct JA-RPC configuration session";
    session = await startConfigurationSession(artifacts, directories);
    const entries = apis.map((entry) => providerPatch(entry.api, fixture.endpoint, entry.suffix));
    currentPhase = "read initial user configuration";
    let configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    if (rpcOnly) {
      assert.equal(
        configuration.cas.userVersion,
        "cfg_missing",
        "RPC-only acceptance requires an empty profile",
      );
      assert.equal(configuration.cas.credentialVersion, "cfg_missing");
    } else {
      const setupProvider = configuration.user.document.providers.find((provider) =>
        provider.models.some((model) => model.model === "ja-model-settings-responses"),
      );
      assert.ok(setupProvider, "interactive ja setup must have saved the first upstream model");
      const setupModel = setupProvider.models.find(
        (model) => model.model === "ja-model-settings-responses",
      );
      entries[0] = {
        ...entries[0],
        providerId: setupProvider.provider_id,
        modelId: setupModel.model_id,
        credentialId: setupProvider.credential_id,
      };
      const { provider_id: _providerId, models: _models, ...providerFields } = setupProvider;
      const { model_id: _modelId, ...modelFields } = setupModel;
      entries[0].entity = {
        ...providerFields,
        models: { [setupModel.model_id]: modelFields },
        model_order: [setupModel.model_id],
      };
      assert.equal(setupProvider.api, "openai_responses");
      assert.equal(configuration.user.document.default_provider_id, setupProvider.provider_id);
      assert.equal(configuration.user.document.default_model_id, setupModel.model_id);
      assert.equal(configuration.credentials[setupProvider.credential_id]?.configured, true);
      assert.equal(configuration.user.document.providers.length, 1);
    }

    currentPhase = "JA-RPC add all three protocol providers";
    const providerEntities = Object.fromEntries(
      entries.slice(rpcOnly ? 0 : 1).map((entry) => [entry.providerId, entry.entity]),
    );
    const initialProviderOrder = entries.map((entry) => entry.providerId);
    rpcResult(
      await session.request("configuration/patch", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        patch: {
          default_provider_id: entries[0].providerId,
          default_model_id: entries[0].modelId,
          subagents: {
            enabled: true,
            provider_id: entries[0].providerId,
            model_id: entries[0].modelId,
          },
          providers: providerEntities,
          provider_order: initialProviderOrder,
        },
      }),
      "configuration/patch add providers",
    );
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after add",
    );
    for (let index = 0; index < entries.length; index += 1) {
      const entry = entries[index];
      const provider = findProvider(configuration, entry.providerId);
      assert.equal(
        provider.models.find((model) => model.model_id === entry.modelId)?.model,
        entry.upstreamModel,
        `add ${entry.api} provider ${entry.providerId} with its declared upstream model`,
      );
      if (!rpcOnly && index === 0) {
        assert.equal(configuration.credentials[entry.credentialId]?.configured, true);
      } else {
        assert.notEqual(configuration.credentials[entry.credentialId]?.configured, true);
      }
    }

    if (!rpcOnly) {
      const missingKeyModel = entries[1];
      currentPhase = "set a configured Chat model with no credential as the default";
      const cliWorkspace = rpcResult(
        await session.request("workspace/open", {
          cwd: directories.workspace,
          displayName: "Model settings acceptance",
        }),
        "workspace/open before no-key startup",
      );
      cliWorkspaceId = cliWorkspace.workspaceId;
      rpcResult(
        await session.request("workspace/set-trust", {
          workspaceId: cliWorkspaceId,
          trust: "trusted",
        }),
        "workspace/set-trust before no-key startup",
      );
      const priorSessions = rpcResult(
        await session.request("thread/list", { workspaceId: cliWorkspaceId, limit: 40 }),
        "thread/list before no-key startup",
      );
      const priorSessionIds = new Set(priorSessions.items.map((thread) => thread.threadId));
      rpcResult(
        await session.request("configuration/patch", {
          scope: "user",
          expectedVersion: configuration.cas.userVersion,
          patch: {
            default_provider_id: missingKeyModel.providerId,
            default_model_id: missingKeyModel.modelId,
          },
        }),
        "configuration/patch select the no-key default",
      );
      configuration = rpcResult(
        await session.request("configuration/read", {}),
        "configuration/read before no-key startup",
      );
      assert.equal(configuration.user.document.default_provider_id, missingKeyModel.providerId);
      assert.equal(configuration.user.document.default_model_id, missingKeyModel.modelId);
      assert.notEqual(configuration.credentials[missingKeyModel.credentialId]?.configured, true);
      const beforeNoKeyCli = await session.shutdown();
      session = undefined;
      assert.equal(beforeNoKeyCli.code, 0, "direct session must stop before the CLI owns JA_HOME");

      cliDaemonMayRun = true;
      currentPhase =
        "ja startup, /model management, and history browsing with a missing default key";
      noKeyStartupEvidence = await verifyNoKeyDefaultStartup(
        artifacts,
        directories,
        conPtyHarness,
        entries.length,
        missingKeyModel.upstreamModel,
      );
      currentPhase = "stop the isolated App Server after no-key TUI verification";
      await stopIsolatedCliServer(artifacts, directories);
      cliDaemonMayRun = false;

      currentPhase = "read back the Thread created without a configured key";
      session = await startConfigurationSession(artifacts, directories);
      const sessionsAfterNoKeyStartup = rpcResult(
        await session.request("thread/list", { workspaceId: cliWorkspaceId, limit: 40 }),
        "thread/list after no-key startup",
      );
      const newNoKeyThreads = sessionsAfterNoKeyStartup.items.filter(
        (thread) => !priorSessionIds.has(thread.threadId),
      );
      assert.equal(
        newNoKeyThreads.length,
        1,
        "ja startup must persist one new session Thread without a key",
      );
      const noKeyThread = newNoKeyThreads[0];
      noKeyThreadId = noKeyThread.threadId;
      assert.equal(noKeyThread.preferences.providerId, missingKeyModel.providerId);
      assert.equal(noKeyThread.preferences.modelId, missingKeyModel.modelId);
      const noKeyThreadRead = rpcResult(
        await session.request("thread/read", { threadId: noKeyThreadId, tail: true }),
        "thread/read no-key startup session",
      );
      assert.equal(noKeyThreadRead.threadId, noKeyThreadId);

      configuration = rpcResult(
        await session.request("configuration/read", {}),
        "configuration/read after no-key startup",
      );
      assert.notEqual(configuration.credentials[missingKeyModel.credentialId]?.configured, true);
      currentPhase = "restore the prior global default after no-key startup coverage";
      rpcResult(
        await session.request("configuration/patch", {
          scope: "user",
          expectedVersion: configuration.cas.userVersion,
          patch: {
            default_provider_id: entries[0].providerId,
            default_model_id: entries[0].modelId,
          },
        }),
        "configuration/patch restore the configured Responses default",
      );
      configuration = rpcResult(
        await session.request("configuration/read", {}),
        "configuration/read after restoring the global default",
      );
      assert.equal(configuration.user.document.default_provider_id, entries[0].providerId);
      assert.equal(configuration.user.document.default_model_id, entries[0].modelId);
    }

    const first = entries[0];
    const credentialRecovery = entries[rpcOnly ? 0 : 1];
    currentPhase = "JA-RPC credential CAS conflict and recovery";
    const staleCredential = await session.request("credential/set", {
      credentialId: credentialRecovery.credentialId,
      secret: testSecret,
      expectedVersion: "cfg_stale",
    });
    assert.equal(staleCredential?.error?.code, -32011, "stale credential CAS must conflict");
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after credential conflict",
    );
    assert.ok(
      findProvider(configuration, credentialRecovery.providerId),
      "config success must survive a key conflict",
    );
    assert.notEqual(configuration.credentials[credentialRecovery.credentialId]?.configured, true);

    for (const entry of entries.slice(rpcOnly ? 0 : 1)) {
      currentPhase = `JA-RPC credential write and readback for ${entry.api}`;
      configuration = rpcResult(
        await session.request("configuration/read", {}),
        "configuration/read before credential write",
      );
      rpcResult(
        await session.request("credential/set", {
          credentialId: entry.credentialId,
          secret: testSecret,
          expectedVersion: configuration.cas.credentialVersion,
        }),
        "credential/set",
      );
      configuration = rpcResult(
        await session.request("configuration/read", {}),
        "configuration/read after credential write",
      );
      assert.equal(configuration.credentials[entry.credentialId]?.configured, true);
    }

    currentPhase = "JA-RPC configuration CAS conflict";
    const staleConfiguration = await session.request("configuration/patch", {
      scope: "user",
      expectedVersion: "cfg_stale",
      patch: { providers: { [first.providerId]: { name: "must-not-win" } } },
    });
    assert.equal(staleConfiguration?.error?.code, -32011, "stale configuration CAS must conflict");
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after config conflict",
    );
    assert.notEqual(findProvider(configuration, first.providerId).name, "must-not-win");

    for (const entry of entries) {
      currentPhase = `JA-RPC model/test for ${entry.api}`;
      const tested = rpcResult(
        await session.request("model/test", {
          providerId: entry.providerId,
          modelId: entry.modelId,
        }),
        "model/test",
      );
      assert.equal(typeof tested.responseModel, "string");
      assert.ok(tested.responseModel.length > 0);
    }
    assert.equal(fixture.observations.length, entries.length);
    for (let index = 0; index < entries.length; index += 1) {
      assert.equal(fixture.observations[index]?.api, apis[index].api);
      assert.equal(
        fixture.observations[index]?.model,
        entries[index].upstreamModel,
        `model/test ${entries[index].api} sent its configured upstream model`,
      );
      assert.equal(
        fixture.observations[index]?.authenticated,
        true,
        "loopback request must use stored key",
      );
    }

    currentPhase = "sparse configuration patch preserves unknown and malformed raw TOML";
    const configPath = await injectRawPreservationCases(directories.home, first.providerId);
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after raw preservation fixture",
    );
    const preservedProvider = findProvider(configuration, first.providerId);
    const preservePatch = {
      providers: {
        [first.providerId]: {
          name: "Updated loopback Provider",
          models: { [first.modelId]: { name: "Updated model alias" } },
        },
      },
    };
    rpcResult(
      await session.request("configuration/patch", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        patch: preservePatch,
      }),
      "configuration/patch preserve raw entries",
    );
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after raw patch",
    );
    assert.equal(findProvider(configuration, first.providerId).name, "Updated loopback Provider");
    assert.equal(
      findProvider(configuration, first.providerId).models.find(
        (model) => model.model_id === first.modelId,
      )?.name,
      "Updated model alias",
    );
    const persisted = await readFile(configPath, "utf8");
    for (const marker of ["keep-root", "keep-provider", "keep-model", "keep-broken"]) {
      assert.ok(persisted.includes(marker), `sparse patch lost ${marker}`);
    }
    assert.ok(persisted.includes(preservedProvider.provider_id));

    const replacement = entries[1];
    currentPhase = "atomic default and subagent reference repair while deleting a provider";
    rpcResult(
      await session.request("configuration/patch", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        patch: {
          providers: { [first.providerId]: null },
          provider_order: entries.slice(1).map((entry) => entry.providerId),
          default_provider_id: replacement.providerId,
          default_model_id: replacement.modelId,
          subagents: { provider_id: replacement.providerId, model_id: replacement.modelId },
        },
      }),
      "configuration/patch delete and repair references",
    );
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after atomic delete",
    );
    assert.equal(configuration.user.document.default_provider_id, replacement.providerId);
    assert.equal(configuration.user.document.default_model_id, replacement.modelId);
    assert.equal(configuration.user.document.subagents.provider_id, replacement.providerId);
    assert.equal(configuration.user.document.subagents.model_id, replacement.modelId);
    assert.ok(
      !configuration.user.document.providers.some(
        (provider) => provider.provider_id === first.providerId,
      ),
    );
    const afterDelete = await readFile(configPath, "utf8");
    for (const marker of ["keep-root", "keep-broken"]) {
      assert.ok(afterDelete.includes(marker), `delete/order patch lost ${marker}`);
    }

    const workspace = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Model settings acceptance",
      }),
      "workspace/open",
    );
    assert.equal(typeof workspace.workspaceId, "string");
    if (cliWorkspaceId !== undefined) {
      assert.equal(
        workspace.workspaceId,
        cliWorkspaceId,
        "the CLI must reuse its trusted isolated workspace",
      );
    }
    cliWorkspaceId = workspace.workspaceId;
    rpcResult(
      await session.request("workspace/set-trust", {
        workspaceId: workspace.workspaceId,
        trust: "trusted",
      }),
      "workspace/set-trust",
    );
    const stop = await session.shutdown();
    session = undefined;
    assert.equal(stop.code, 0, "direct App Server should stop cleanly after configuration checks");
    if (rpcOnly) {
      return {
        finalAppServerShutdown: finalShutdownEvidence,
        configurationPatch: true,
        credentialCasRecovery: true,
        configurationCasConflict: true,
        protocolsVerified: fixture.observations.map((entry) => entry.api),
        unknownAndBrokenRawEntriesPreserved: true,
        deleteDefaultAndSubagentReferencesAtomically: true,
        cliModelOverrideAndTurnCompleted: "not-run (JA_MODEL_SETTINGS_RPC_ONLY=1)",
      };
    }

    currentPhase = "restore the deleted Responses fixture after verifying its atomic deletion";
    session = await startConfigurationSession(artifacts, directories);
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read before restoring deleted fixture for CLI turns",
    );
    const existingProviderIds = configuration.user.document.providers.map(
      (provider) => provider.provider_id,
    );
    rpcResult(
      await session.request("configuration/patch", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        patch: {
          providers: { [first.providerId]: first.entity },
          provider_order: [
            first.providerId,
            ...existingProviderIds.filter((providerId) => providerId !== first.providerId),
          ],
        },
      }),
      "configuration/patch restore deleted fixture for all CLI protocol turns",
    );
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after restoring deleted fixture for CLI turns",
    );
    assert.equal(
      findProvider(configuration, first.providerId).models.find(
        (model) => model.model_id === first.modelId,
      )?.model,
      first.upstreamModel,
      "restored Responses fixture must reuse its original provider/model identity",
    );
    if (configuration.credentials[first.credentialId]?.configured !== true) {
      rpcResult(
        await session.request("credential/set", {
          credentialId: first.credentialId,
          secret: testSecret,
          expectedVersion: configuration.cas.credentialVersion,
        }),
        "credential/set restore original Responses fixture credential",
      );
      configuration = rpcResult(
        await session.request("configuration/read", {}),
        "configuration/read after restoring original Responses fixture credential",
      );
    }
    assert.equal(configuration.credentials[first.credentialId]?.configured, true);
    const sessionsBeforeModelSwitch = rpcResult(
      await session.request("thread/list", { workspaceId: cliWorkspaceId, limit: 40 }),
      "thread/list before interactive model switch",
    );
    const threadIdsBeforeModelSwitch = new Set(
      sessionsBeforeModelSwitch.items.map((thread) => thread.threadId),
    );
    const globalDefaultBeforeModelSwitch = {
      providerId: configuration.user.document.default_provider_id,
      modelId: configuration.user.document.default_model_id,
    };
    assert.deepEqual(globalDefaultBeforeModelSwitch, {
      providerId: entries[1].providerId,
      modelId: entries[1].modelId,
    });
    const restoreStop = await session.shutdown();
    session = undefined;
    assert.equal(restoreStop.code, 0, "restore session must stop before CLI turns start");

    cliDaemonMayRun = true;
    currentPhase = "CLI ConPTY provider-name edit with existing credential untouched";
    providerNameEditEvidence = await verifyConfiguredProviderNameEdit(
      artifacts,
      directories,
      first.entity.name,
      "CLI acceptance renamed Provider",
      testSecret,
    );
    const sharedInteractiveOwner = await assertSharedCliOwnerAlive(artifacts, directories);
    currentPhase = "configured ja setup remains editable in ConPTY";
    const setupEditableEvidence = await verifyConfiguredSetupRemainsEditable(
      artifacts,
      directories,
      conPtyHarness,
      entries[0].upstreamModel,
    );
    await assertSharedCliOwnerAlive(artifacts, directories, sharedInteractiveOwner);
    currentPhase = "session /model management and real keyboard model switch in ConPTY";
    sessionUiEvidence = await verifySessionModelManagement(
      artifacts,
      directories,
      conPtyHarness,
      entries.length,
      entries[1],
      entries[0],
    );
    await assertSharedCliOwnerAlive(artifacts, directories, sharedInteractiveOwner);

    currentPhase =
      "stop the interactive App Server before authoritative Thread preference readback";
    await stopIsolatedCliServer(artifacts, directories);
    cliDaemonMayRun = false;

    currentPhase = "verify interactive model preference changed and global default stayed fixed";
    session = await startConfigurationSession(artifacts, directories);
    const sessionsAfterModelSwitch = rpcResult(
      await session.request("thread/list", { workspaceId: cliWorkspaceId, limit: 40 }),
      "thread/list after interactive model switch",
    );
    const switchedThreads = sessionsAfterModelSwitch.items.filter(
      (thread) => !threadIdsBeforeModelSwitch.has(thread.threadId),
    );
    assert.equal(
      switchedThreads.length,
      1,
      "interactive ja startup must create one new session Thread",
    );
    const switchedThread = switchedThreads[0];
    assert.equal(switchedThread.preferences.providerId, entries[0].providerId);
    assert.equal(switchedThread.preferences.modelId, entries[0].modelId);
    assert.notDeepEqual(
      [switchedThread.preferences.providerId, switchedThread.preferences.modelId],
      [globalDefaultBeforeModelSwitch.providerId, globalDefaultBeforeModelSwitch.modelId],
      "keyboard model switch must change the Thread preference without changing global default",
    );
    const switchedThreadRead = rpcResult(
      await session.request("thread/read", { threadId: switchedThread.threadId, tail: true }),
      "thread/read after interactive model switch",
    );
    assert.equal(switchedThreadRead.threadId, switchedThread.threadId);
    assert.equal(switchedThreadRead.revision, switchedThread.revision);
    configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after interactive model switch",
    );
    assert.equal(
      configuration.user.document.default_provider_id,
      globalDefaultBeforeModelSwitch.providerId,
    );
    assert.equal(
      configuration.user.document.default_model_id,
      globalDefaultBeforeModelSwitch.modelId,
    );
    sessionModelSwitchEvidence = {
      ...sessionUiEvidence,
      threadId: switchedThread.threadId,
      threadReadRevision: switchedThreadRead.revision,
      threadPreference: {
        providerId: switchedThread.preferences.providerId,
        modelId: switchedThread.preferences.modelId,
      },
      globalDefault: globalDefaultBeforeModelSwitch,
      globalDefaultUnchanged: true,
      operationOrder: [
        "open /model",
        "open management",
        "return to the picker",
        "select Responses with keyboard shortcut 1",
        "read Thread preferences and configuration from App Server",
      ],
    };
    const switchReadbackStop = await session.shutdown();
    session = undefined;
    assert.equal(
      switchReadbackStop.code,
      0,
      "Thread preference readback session must stop cleanly",
    );

    cliDaemonMayRun = true;
    const dialogueProtocolsCompleted = [];
    for (const [index, selectedModel] of entries.entries()) {
      currentPhase = `ja exec ${selectedModel.api} model ${selectedModel.modelId}`;
      const observationsBeforeTurn = fixture.observations.length;
      const cli = await runCli(
        artifacts.cli,
        [
          "exec",
          "-C",
          directories.workspace,
          "--json",
          "--model",
          selectedModel.modelId,
          "Reply with the fixed loopback result and do not call tools.",
        ],
        directories,
      );
      assert.equal(cli.code, 0, cli.stderr.slice(-2_000));
      const records = cli.stdout
        .split(/\r?\n/u)
        .filter((line) => line.length > 0)
        .map((line) => JSON.parse(line));
      const outcome = normalizeJsonlOutcome(records);
      assert.equal(
        outcome.errors.length,
        0,
        `ja exec must not report a ${selectedModel.api} model/configuration error`,
      );
      assert.equal(outcome.terminals.length, 1, "ja exec must emit exactly one terminal result");
      assert.equal(outcome.terminals[0].state, "completed");
      assert.ok(outcome.terminals[0].finalText.includes(fixedReply));
      const turnObservations = fixture.observations.slice(observationsBeforeTurn);
      assert.ok(
        turnObservations.some(
          (observed) =>
            observed.api === selectedModel.api && observed.model === selectedModel.upstreamModel,
        ),
        `--model ${selectedModel.modelId} must send the completed user turn to its configured upstream model; observed=${JSON.stringify(turnObservations)}`,
      );
      // Completed turns can schedule one asynchronous title request; its response may land in the next turn's observation window.
      for (const observed of turnObservations) {
        const configuredModel = entries.find((entry) => entry.api === observed.api);
        assert.ok(
          configuredModel,
          `fixture observed an API outside this isolated model set: ${JSON.stringify(observed)}`,
        );
        assert.equal(
          observed.authenticated,
          true,
          `fixture request for ${observed.api} was not authenticated with the isolated credential`,
        );
        assert.equal(
          observed.model,
          configuredModel.upstreamModel,
          `fixture request for ${observed.api} did not use its configured upstream model`,
        );
      }
      dialogueProtocolsCompleted.push(selectedModel.api);
    }

    return {
      finalAppServerShutdown: finalShutdownEvidence,
      configurationPatch: true,
      credentialCasRecovery: true,
      configurationCasConflict: true,
      protocolsVerified: fixture.observations.map((entry) => entry.api),
      unknownAndBrokenRawEntriesPreserved: true,
      deleteDefaultAndSubagentReferencesAtomically: true,
      cliModelOverrideAndTurnCompleted: dialogueProtocolsCompleted,
      noKeyDefaultStartupConPty: { ...noKeyStartupEvidence, threadId: noKeyThreadId },
      setupNewProviderConPty: setupEvidence,
      setupAppServerShutdown: setupShutdownEvidence,
      configuredProviderNameEditConPty: providerNameEditEvidence,
      configuredSetupEditableConPty: setupEditableEvidence,
      sessionModelManagementAndSwitchConPty: sessionModelSwitchEvidence,
      sharedBackgroundOwnerAcrossTerminals: true,
    };
  } catch (error) {
    acceptanceError =
      error instanceof Error ? error : new Error("CLI model settings acceptance failed");
    acceptanceError.message = `acceptance phase [${currentPhase}]: ${acceptanceError.message}`;
    if (fixture !== undefined) {
      await writeFile(
        join(directories.root, "provider-fixture-observations.json"),
        `${JSON.stringify(fixture.observations, null, 2)}\n`,
        "utf8",
      ).catch(() => undefined);
    }
    throw acceptanceError;
  } finally {
    let cleanupFailure;
    if (session !== undefined) {
      try {
        await session.forceClose();
        await assertDatabaseLeaseReleased(
          artifacts.java,
          directories.leaseProbe,
          directories.home,
          "direct App Server",
        );
      } catch (error) {
        cleanupFailure = new Error(
          `direct App Server session cleanup failed: ${String(error?.message ?? error).slice(0, 500)}`,
        );
      }
    }
    let cliServerStopped = !cliDaemonMayRun && cleanupFailure === undefined;
    if (cliDaemonMayRun) {
      try {
        Object.assign(finalShutdownEvidence, await stopIsolatedCliServer(artifacts, directories));
        cliServerStopped = true;
      } catch (error) {
        cleanupFailure = new Error(
          `isolated App Server shutdown was not confirmed: ${String(error?.message ?? error).slice(0, 500)}`,
        );
      }
    }
    await fixture?.close().catch((error) => {
      cleanupFailure ??= new Error(
        `local Provider fixture shutdown failed: ${error?.code ?? "error"}`,
      );
    });
    if (cliServerStopped && cleanupFailure === undefined) {
      await assertDatabaseLeaseReleased(
        artifacts.java,
        directories.leaseProbe,
        directories.home,
        "final App Server",
      )
        .then(() => Object.assign(finalShutdownEvidence, { databaseLeaseReleased: true }))
        .catch(() => {
          cleanupFailure = new Error("final isolated App Server database lease was not released");
        });
    }
    restoreEnvironment();
    if (cliServerStopped && acceptanceError === undefined && cleanupFailure === undefined) {
      await cleanupIsolatedProfile(directories.root).catch((error) => {
        cleanupFailure ??= new Error(
          `isolated profile cleanup failed: ${error?.code ?? "filesystem error"}`,
        );
      });
    } else if (acceptanceError !== undefined) {
      acceptanceError.message = `${acceptanceError.message}; isolated diagnostics retained at ${directories.root}`;
    }
    if (cleanupFailure !== undefined) {
      if (acceptanceError === undefined) {
        cleanupFailure.message = `${cleanupFailure.message}; isolated diagnostics retained at ${directories.root}`;
        throw cleanupFailure;
      }
      acceptanceError.message = `${acceptanceError.message}; cleanup also failed: ${cleanupFailure.message}`;
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  runAcceptance()
    .then((result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`))
    .catch((error) => {
      process.stderr.write(
        `CLI model settings acceptance failed: ${String(error?.message ?? error).slice(0, 2_000)}\n`,
      );
      process.exitCode = 1;
    });
}
