// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { StringDecoder } from "node:string_decoder";
import {
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
} from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const outputRoot = join(repoRoot, "target", "cli-usage-conpty");
const providerId = "provider_cli_usage_conpty";
const modelId = "model_cli_usage_conpty";
const credentialId = "cred_cli_usage_conpty";
const placeholderSecret = "ja-cli-usage-conpty-placeholder";
const promptMarker = "JA_CLI_USAGE_CONPTY_PROMPT";
const finalText = "用量已回报，中文与 Emoji 显示正常 🙂✅，恢复完成。";
const usageFact = Object.freeze({
  input_tokens: 24_000,
  input_tokens_details: { cached_tokens: 22_080 },
  output_tokens: 449,
  total_tokens: 24_449,
});
const execFileAsync = promisify(execFile);
const delay = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));

/** 优先采用调用方指定制品，否则测试当前 debug 构建；Java 工具同源派生以保证 JDK25 编译和运行一致，并记录而不钉死构建哈希。 @author kongweiguang */
export async function resolveArtifacts() {
  assert.equal(process.platform, "win32", "this acceptance requires Windows ConPTY");
  const cli = resolve(process.env.JA_TEST_CLI ?? join(repoRoot, "target", "debug", "ja.exe"));
  const jar = resolve(
    process.env.JA_TEST_JAR ?? join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar"),
  );
  const java = resolve(
    process.env.JA_TEST_JAVA ?? "C:\\Users\\24052\\.jdks\\liberica-25.0.2\\bin\\java.exe",
  );
  const jdkBin = dirname(java);
  const artifacts = {
    cli,
    jar,
    java,
    javac: join(jdkBin, "javac.exe"),
    jarTool: join(jdkBin, "jar.exe"),
  };
  for (const path of Object.values(artifacts)) {
    const metadata = await stat(path);
    assert.ok(metadata.isFile() && metadata.size > 0, `required artifact is missing or empty: ${basename(path)}`);
  }
  const version = await execFileAsync(artifacts.java, ["-version"], {
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 64 * 1024,
  });
  assert.match(`${version.stdout}\n${version.stderr}`, /version\s+"25(?:[.]|"|\s)/u);
  const cliSha256 = createHash("sha256").update(await readFile(artifacts.cli)).digest("hex").toUpperCase();
  const jarBytes = await readFile(artifacts.jar);
  const jarSha256 = createHash("sha256").update(jarBytes).digest("hex").toUpperCase();
  return {
    ...artifacts,
    javaVersion: `${version.stdout}\n${version.stderr}`.trim(),
    cliSha256,
    jarSha256,
    jarBytes: jarBytes.byteLength,
  };
}

/** 创建保留证据的唯一 run 目录；用户 profile 只写入其专属子目录并在最终清理阶段删除。 @author kongweiguang */
export async function createRunDirectories() {
  await mkdir(outputRoot, { recursive: true });
  const runRoot = await mkdtemp(join(outputRoot, "run-"));
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
  return { runRoot, profileRoot, directories };
}

/** 只接受新建 run 下恰好名为 profile 的路径，避免递归清理扩展到证据或调用方目录。 @author kongweiguang */
export async function removeOwnedProfile(runRoot, profileRoot) {
  const expected = join(resolve(runRoot), "profile");
  assert.equal(resolve(profileRoot), expected, "refusing to remove a profile outside this run directory");
  await rm(profileRoot, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
}

/** 避免错误载荷和用户正文进入验收输出；仅将稳定 RPC 分类写入失败诊断。 @author kongweiguang */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 失败时复用 JsonlSession 已脱敏且有界的 stderr 尾部，让 profile 清理后仍留下可诊断证据。 @author kongweiguang */
function withSessionDiagnostic(error, session, endpoint) {
  const original = error instanceof Error ? error : new Error(String(error));
  const message = original.message
    .replaceAll(placeholderSecret, "<redacted>")
    .replaceAll(endpoint, "<redacted>");
  const stderr = session.stderr.trim().slice(-2_000);
  if (stderr.length === 0) return original;
  return new Error(`${message}\nApp Server stderr (redacted by JsonlSession):\n${stderr}`, { cause: original });
}

/** 在 loopback Provider ready 后初始化隔离 Thread，并等待 seed owner 正常退出后才让 CLI 复用 SQLite；忽略 shutdown 会把未释放的存储误诊为恢复失败。 @author kongweiguang */
export async function seedThread(artifacts, directories, endpoint) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  let ready = false;
  let primaryFailure;
  let seededThread;
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
    ready = true;

    const current = rpcResult(await session.request("configuration/read", {}), "configuration/read");
    const document = providerConfigurationDocument({
      endpoint,
      name: "Ja CLI usage ConPTY loopback",
      api: "openai_responses",
      model: "gpt-6-luna",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
      reasoningLevel: "xhigh",
    });
    document.providers[0].models[0].capabilities.context_window_tokens = 1_000_000;
    document.providers[0].agent_defaults.context.auto_compact = false;
    rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: current.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    rpcResult(
      await session.request("credential/set", {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: current.cas.credentialVersion,
      }),
      "credential/set",
    );
    const workspace = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Ja CLI usage ConPTY",
      }),
      "workspace/open",
    );
    rpcResult(
      await session.request("workspace/set-trust", {
        workspaceId: workspace.workspaceId,
        trust: "trusted",
      }),
      "workspace/set-trust",
    );
    const thread = rpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Ja CLI 用量 ConPTY 验收",
        providerId,
        modelId,
        reasoningLevel: "xhigh",
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    assert.match(thread.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    seededThread = { threadId: thread.threadId, workspaceId: workspace.workspaceId, seedAppServerExitCode: null };
    return seededThread;
  } catch (error) {
    primaryFailure = withSessionDiagnostic(error, session, endpoint);
    throw primaryFailure;
  } finally {
    if (ready) {
      try {
        const result = session.exited ? session.exitResult : await session.shutdown();
        assert.equal(result?.code, 0, "seed App Server must exit cleanly before the CLI reopens its profile");
        if (seededThread) seededThread.seedAppServerExitCode = result.code;
      } catch (error) {
        const shutdownFailure = withSessionDiagnostic(error, session, endpoint);
        await session.forceClose();
        if (primaryFailure) {
          primaryFailure.message = `${primaryFailure.message}\nSeed App Server shutdown failed: ${shutdownFailure.message}`;
        } else {
          throw shutdownFailure;
        }
      }
    }
    await session.forceClose();
  }
}

/** 只接收 loopback Responses 请求；助手完整用量与独立标题小样本分开，hold/release 暴露估算态。 @author kongweiguang */
export async function startUsageProvider() {
  const { createServer } = await import("node:http");
  let releaseGate;
  let notifyRequest;
  let responseCount = 0;
  let requestCount = 0;
  const requestMetadata = [];
  const requestReceived = new Promise((resolvePromise) => (notifyRequest = resolvePromise));
  const responseGate = new Promise((resolvePromise) => (releaseGate = resolvePromise));
  const server = createServer(async (request, response) => {
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        response.writeHead(404).end();
        return;
      }
      if (request.headers.authorization !== `Bearer ${placeholderSecret}`) {
        response.writeHead(401).end();
        return;
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 8 * 1024 * 1024) throw new Error("provider request exceeds the local fixture limit");
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      requestCount += 1;
      const markerOccurrences = JSON.stringify(payload).split(promptMarker).length - 1;
      const requestPurpose = [
        payload.purpose,
        payload.metadata?.purpose,
        payload.metadata?.requestPurpose,
        payload.metadata?.jaPurpose,
      ].find((value) => typeof value === "string" && /^[a-z][a-z0-9_-]{0,31}$/iu.test(value)) ?? null;
      requestMetadata.push({
        requestNumber: requestCount,
        requestBytes: size,
        model: typeof payload.model === "string" ? payload.model : null,
        providerBodyPurpose: requestPurpose,
        promptMarkerOccurrences: markerOccurrences,
        usageSample: requestCount === 1 ? "assistant-complete-cache-sample" : "automatic-title-small-sample",
      });
      if (requestCount === 1) {
        notifyRequest({
          requestCount,
          requestBytes: size,
          markerFound: markerOccurrences > 0,
        });
      }
      await responseGate;
      const text = requestCount === 1 ? finalText : "Ja CLI 用量验收";
      const stream = addUsageToCompletedResponse(
        providerStream("openai_responses", `resp_ja_cli_usage_${requestCount}`, undefined, text),
        requestCount === 1
          ? usageFact
          : { input_tokens: 120, input_tokens_details: { cached_tokens: 0 }, output_tokens: 24, total_tokens: 144 },
      );
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "keep-alive",
      });
      response.end(stream, () => {
        responseCount += 1;
      });
    } catch {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: "isolated loopback fixture rejected the request" } }));
    }
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return {
    endpoint: `http://127.0.0.1:${address.port}`,
    requestReceived,
    get requestCount() { return requestCount; },
    get responseCount() { return responseCount; },
    get requestMetadata() { return structuredClone(requestMetadata); },
    release() { releaseGate(); },
    async close() {
      releaseGate();
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(() => resolvePromise()));
    },
  };
}

/** 在完整 Responses 流的 response.completed 终态添加固定账本用量，不改事件排序和文本输出。 @author kongweiguang */
function addUsageToCompletedResponse(stream, usage) {
  const frames = stream.split("\n\n");
  const completionIndex = frames.findLastIndex((frame) => frame.includes("response.completed"));
  assert.ok(completionIndex >= 0, "loopback Responses stream requires a completed frame");
  const lines = frames[completionIndex].split("\n");
  const dataIndex = lines.findIndex((line) => line.startsWith("data: "));
  assert.ok(dataIndex >= 0, "Responses completion must contain a JSON data line");
  const payload = JSON.parse(lines[dataIndex].slice("data: ".length));
  payload.response.usage = structuredClone(usage);
  lines[dataIndex] = `data: ${JSON.stringify(payload)}`;
  frames[completionIndex] = lines.join("\n");
  return frames.join("\n\n");
}

const conPtyHarnessSource = String.raw`// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import io.github.kongweiguang.ja.platform.windows.WindowsJobObject;
import io.github.kongweiguang.ja.platform.windows.WindowsProcessLauncher;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 通过已校验 JAR 中的 Windows 进程 owner 建立独立 ConPTY；Job Object 保持子进程树的唯一回收边界。 */
public final class CliUsageConPty {
    private CliUsageConPty() {}

    /** 启动固定 CLI、逐块记录原始 VT，并限制控制通道为按键、窗口尺寸、状态和终止动作。 */
    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        Files.createDirectories(options.log().toAbsolutePath().getParent());
        WindowsJobObject job = WindowsJobObject.create();
        Process child = null;
        Thread outputPump = null;
        try {
            child = WindowsProcessLauncher.launch(
                    options.command(), options.cwd(), Map.copyOf(System.getenv()), job, true);
            Process ownedChild = child;
            BufferedOutputStream capture = new BufferedOutputStream(Files.newOutputStream(
                    options.log(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE));
            outputPump = new Thread(() -> copyOutput(ownedChild, capture), "ja-cli-usage-conpty-capture");
            outputPump.setDaemon(true);
            outputPump.start();
            System.out.println("CHILD_PID=" + child.pid());
            System.out.println("LOG=" + options.log().toAbsolutePath());
            System.out.flush();
            controlLoop(child, job);
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

    /** 先关 Job 再退出 harness 的失败路径也会停止目标进程树；输出文件在每个 native 读块后 flush。 */
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

    /** 仅接受短键串和有限尺寸；ResizePseudoConsole 直接改变真实 ConPTY 尺寸供 Ratatui 收到 resize 事件。 */
    private static void controlLoop(Process child, WindowsJobObject job) throws IOException {
        try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = commands.readLine()) != null) {
                if (line.equals("STATUS")) {
                    System.out.println("CHILD_ALIVE=" + child.isAlive());
                    if (!child.isAlive()) System.out.println("CHILD_EXIT_CODE=" + child.exitValue());
                    System.out.flush();
                } else if (line.equals("CLOSE")) {
                    if (child.isAlive()) System.out.println("CLOSE_REFUSED=child_still_running");
                    else {
                        System.out.println("CLOSE_ACCEPTED=true");
                        System.out.flush();
                        return;
                    }
                    System.out.flush();
                } else if (line.equals("KILL")) {
                    if (child.isAlive()) job.close();
                    System.out.println("JOB_CLOSED=true");
                    System.out.flush();
                } else if (line.startsWith("RESIZE ")) {
                    String[] dimensions = line.substring(7).split(" ", -1);
                    if (dimensions.length != 2) {
                        System.out.println("RESIZE_REJECTED=shape");
                    } else {
                        int width = Integer.parseInt(dimensions[0]);
                        int height = Integer.parseInt(dimensions[1]);
                        if (width < 1 || width > 240 || height < 1 || height > 100) {
                            System.out.println("RESIZE_REJECTED=range");
                        } else {
                            resizePseudoConsole(child, width, height);
                            System.out.println("RESIZED=" + width + "x" + height);
                        }
                    }
                    System.out.flush();
                } else if (line.startsWith("SEND ")) {
                    if (!child.isAlive()) System.out.println("SEND_REJECTED=child_exited");
                    else {
                        byte[] bytes = Base64.getDecoder().decode(line.substring(5));
                        if (bytes.length > 4096) System.out.println("SEND_REJECTED=too_long");
                        else {
                            child.getOutputStream().write(bytes);
                            child.getOutputStream().flush();
                            System.out.println("SENT_BYTES=" + bytes.length);
                        }
                    }
                    System.out.flush();
                } else {
                    System.out.println("COMMAND_REJECTED=unknown");
                    System.out.flush();
                }
            }
        }
    }

    /** 用固定 owner 持有的 HPCON 句柄调用 Win32 ResizePseudoConsole；反射只读取本次测试进程的私有句柄。 */
    private static void resizePseudoConsole(Process child, int width, int height) {
        try {
            Field field = child.getClass().getDeclaredField("pseudoConsole");
            field.setAccessible(true);
            MemorySegment pseudoConsole = (MemorySegment) field.get(child);
            try (Arena arena = Arena.ofConfined()) {
                var lookup = SymbolLookup.libraryLookup("kernel32", arena);
                MemorySegment address = lookup.find("ResizePseudoConsole")
                        .orElseThrow(() -> new IllegalStateException("resize_pseudo_console_missing"));
                MethodHandle resize = Linker.nativeLinker().downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                int result = (int) resize.invoke(pseudoConsole, (height << 16) | width);
                if (result < 0) throw new IllegalStateException("resize_pseudo_console_failed_" + result);
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("resize_pseudo_console_failed", failure);
        }
    }

    /** 子命令在 -- 后逐项传递，不经过 shell；cwd 和证据日志必须显式给出。 */
    private record Options(Path cwd, Path log, List<String> command) {
        private static Options parse(String[] arguments) {
            Path cwd = null;
            Path log = null;
            List<String> command = new ArrayList<>();
            boolean commandStarted = false;
            for (int index = 0; index < arguments.length; index++) {
                String value = arguments[index];
                if (!commandStarted && value.equals("--")) commandStarted = true;
                else if (!commandStarted && value.equals("--cwd") && index + 1 < arguments.length) {
                    cwd = Path.of(arguments[++index]).toAbsolutePath().normalize();
                } else if (!commandStarted && value.equals("--log") && index + 1 < arguments.length) {
                    log = Path.of(arguments[++index]).toAbsolutePath().normalize();
                } else if (commandStarted) command.add(value);
                else throw new IllegalArgumentException("unknown_harness_option");
            }
            if (cwd == null || log == null || command.isEmpty() || !Files.isDirectory(cwd)) {
                throw new IllegalArgumentException("cwd_log_and_child_command_required");
            }
            return new Options(cwd, log, List.copyOf(command));
        }
    }
}`;

/** 从固定 JAR 提取其中随版本校验的原生进程 owner，再以当前 JDK25 编译独立 ConPTY 控制器。 @author kongweiguang */
export async function compileConPtyHarness(artifacts, runRoot) {
  const extracted = join(runRoot, "harness-extracted");
  const classes = join(runRoot, "harness-classes");
  const source = join(runRoot, "CliUsageConPty.java");
  await Promise.all([mkdir(extracted, { recursive: true }), mkdir(classes, { recursive: true })]);
  await execFileAsync(artifacts.jarTool, [
    "--extract", "--file", artifacts.jar, "--dir", extracted,
    "BOOT-INF/classes/io/github/kongweiguang/ja/platform/windows",
  ], { windowsHide: true, timeout: 30_000 });
  await writeFile(source, conPtyHarnessSource, "utf8");
  const appClasses = join(extracted, "BOOT-INF", "classes");
  await execFileAsync(artifacts.javac, [
    "-encoding", "UTF-8", "-cp", appClasses, "-d", classes, source,
  ], { windowsHide: true, timeout: 30_000, maxBuffer: 2 * 1024 * 1024 });
  return { classPath: [appClasses, classes].join(delimiter), source, classes };
}

/** 终端单元格模型回放原始 VT 控制序列；尺寸变化与目标 ConPTY 同步，断言基于实际显示缓冲区。 @author kongweiguang */
export class VtScreen {
  constructor(columns = 120, rows = 30) {
    this.decoder = new StringDecoder("utf8");
    this.columns = columns;
    this.rowCount = rows;
    this.screen = this.makeScreen(rows, columns);
    this.primaryScreen = undefined;
    this.alternate = false;
    this.cursorX = 0;
    this.cursorY = 0;
    this.savedCursor = [0, 0];
    this.wrapPending = false;
  }

  /** 每个单元格只保存可见字符，不保留样式或控制序列。 */
  makeScreen(rows, columns) {
    return Array.from({ length: rows }, () => Array(columns).fill(" "));
  }

  /** 处理从 ConPTY 文件新到达的完整 UTF-8 前缀并更新可见终端状态。 */
  write(bytes) {
    this.consume(this.decoder.write(bytes));
  }

  /** 对一段 VT 文本解释定位、清除、滚屏与字符输出。 */
  consume(input) {
    for (let index = 0; index < input.length;) {
      const character = input[index];
      if (character === "\u001b") {
        const next = input[index + 1];
        if (next === "[") {
          let end = index + 2;
          while (end < input.length && !(input.charCodeAt(end) >= 0x40 && input.charCodeAt(end) <= 0x7e)) end += 1;
          if (end >= input.length) break;
          this.csi(input.slice(index + 2, end), input[end]);
          index = end + 1;
        } else if (next === "]") {
          let end = index + 2;
          while (end < input.length && input[end] !== "\u0007" && !(input[end] === "\u001b" && input[end + 1] === "\\")) end += 1;
          if (end >= input.length) break;
          index = input[end] === "\u0007" ? end + 1 : end + 2;
        } else if (next === "7") {
          this.savedCursor = [this.cursorX, this.cursorY];
          index += 2;
        } else if (next === "8") {
          [this.cursorX, this.cursorY] = this.savedCursor;
          index += 2;
        } else {
          index += Math.min(2, input.length - index);
        }
        continue;
      }
      if (character === "\r") {
        this.cursorX = 0;
        this.wrapPending = false;
        index += 1;
        continue;
      }
      if (character === "\n") {
        this.lineFeed();
        index += 1;
        continue;
      }
      if (character === "\b") {
        this.cursorX = Math.max(0, this.cursorX - 1);
        this.wrapPending = false;
        index += 1;
        continue;
      }
      if (character === "\t") {
        this.cursorX = Math.min(this.columns - 1, (Math.floor(this.cursorX / 8) + 1) * 8);
        index += 1;
        continue;
      }
      const codePoint = input.codePointAt(index);
      const glyph = String.fromCodePoint(codePoint);
      this.put(glyph);
      index += glyph.length;
    }
  }

  /** 仅覆盖对 Ratatui 输出有影响的 CSI；未知控制码保持无副作用。 */
  csi(raw, final) {
    const privateMode = raw.startsWith("?");
    const parts = raw.replace(/^[?>!]/u, "").split(";");
    const number = (position, fallback = 1) => {
      const value = Number.parseInt(parts[position] ?? "", 10);
      return Number.isFinite(value) && value > 0 ? value : fallback;
    };
    if (privateMode && (final === "h" || final === "l") && parts.includes("1049")) {
      if (final === "h" && !this.alternate) {
        this.primaryScreen = this.screen;
        this.screen = this.makeScreen(this.rowCount, this.columns);
        this.alternate = true;
        this.cursorX = 0;
        this.cursorY = 0;
      } else if (final === "l" && this.alternate) {
        this.screen = this.primaryScreen ?? this.makeScreen(this.rowCount, this.columns);
        this.primaryScreen = undefined;
        this.alternate = false;
        this.cursorX = 0;
        this.cursorY = 0;
      }
      return;
    }
    switch (final) {
      case "H": case "f":
        this.cursorY = Math.min(this.rowCount - 1, number(0) - 1);
        this.cursorX = Math.min(this.columns - 1, number(1) - 1);
        this.wrapPending = false;
        break;
      case "A": this.cursorY = Math.max(0, this.cursorY - number(0)); break;
      case "B": this.cursorY = Math.min(this.rowCount - 1, this.cursorY + number(0)); break;
      case "C": this.cursorX = Math.min(this.columns - 1, this.cursorX + number(0)); break;
      case "D": this.cursorX = Math.max(0, this.cursorX - number(0)); break;
      case "E": this.cursorY = Math.min(this.rowCount - 1, this.cursorY + number(0)); this.cursorX = 0; break;
      case "F": this.cursorY = Math.max(0, this.cursorY - number(0)); this.cursorX = 0; break;
      case "G": this.cursorX = Math.min(this.columns - 1, number(0) - 1); break;
      case "d": this.cursorY = Math.min(this.rowCount - 1, number(0) - 1); break;
      case "J": this.eraseDisplay(Number.parseInt(parts[0] ?? "0", 10) || 0); break;
      case "K": this.eraseLine(Number.parseInt(parts[0] ?? "0", 10) || 0); break;
      case "X": this.fill(this.cursorY, this.cursorX, Math.min(this.columns, this.cursorX + number(0)), " "); break;
      case "s": this.savedCursor = [this.cursorX, this.cursorY]; break;
      case "u": [this.cursorX, this.cursorY] = this.savedCursor; break;
      case "S": this.scrollUp(number(0)); break;
      case "T": this.scrollDown(number(0)); break;
      case "L": this.insertLines(number(0)); break;
      case "M": this.deleteLines(number(0)); break;
      default: break;
    }
  }

  /** 在当前窗口高度内滚动一行，模拟 ConPTY 的无边框可视缓冲区。 */
  lineFeed() {
    this.wrapPending = false;
    if (this.cursorY >= this.rowCount - 1) this.scrollUp(1);
    else this.cursorY += 1;
  }

  /** 写入一个 Unicode 字符并为中日韩及 Emoji 占据两个终端列。 */
  put(glyph) {
    if (glyph === "\u0000" || glyph === "\u0007") return;
    const width = displayWidth(glyph);
    if (width === 0) {
      const previous = Math.max(0, this.cursorX - 1);
      this.screen[this.cursorY][previous] += glyph;
      return;
    }
    if (this.wrapPending || this.cursorX + width > this.columns) {
      this.cursorX = 0;
      this.lineFeed();
    }
    this.screen[this.cursorY][this.cursorX] = glyph;
    if (width === 2 && this.cursorX + 1 < this.columns) this.screen[this.cursorY][this.cursorX + 1] = "";
    this.cursorX += width;
    this.wrapPending = this.cursorX >= this.columns;
    this.cursorX = Math.min(this.cursorX, this.columns - 1);
  }

  /** 缩放 VT 缓冲区并裁切/扩展实际行列，不用静态模板伪造窄矮窗口。 */
  resize(columns, rows) {
    const next = this.makeScreen(rows, columns);
    const old = this.screen;
    for (let row = 0; row < Math.min(rows, old.length); row++) {
      for (let column = 0; column < Math.min(columns, old[row].length); column++) next[row][column] = old[row][column];
    }
    this.screen = next;
    if (this.alternate && this.primaryScreen) {
      const primary = this.makeScreen(rows, columns);
      for (let row = 0; row < Math.min(rows, this.primaryScreen.length); row++) {
        for (let column = 0; column < Math.min(columns, this.primaryScreen[row].length); column++) primary[row][column] = this.primaryScreen[row][column];
      }
      this.primaryScreen = primary;
    }
    this.columns = columns;
    this.rowCount = rows;
    this.cursorX = Math.min(this.cursorX, columns - 1);
    this.cursorY = Math.min(this.cursorY, rows - 1);
    this.wrapPending = false;
  }

  /** 清空指定行区间并复用所有字符落点的单一实现。 */
  fill(row, from, to, value) {
    if (!this.screen[row]) return;
    for (let column = Math.max(0, from); column < Math.min(this.columns, to); column++) this.screen[row][column] = value;
  }

  /** CSI K 擦除当前行指定范围。 */
  eraseLine(mode) {
    if (mode === 0) this.fill(this.cursorY, this.cursorX, this.columns, " ");
    else if (mode === 1) this.fill(this.cursorY, 0, this.cursorX + 1, " ");
    else if (mode === 2) this.fill(this.cursorY, 0, this.columns, " ");
  }

  /** CSI J 擦除屏幕指定范围。 */
  eraseDisplay(mode) {
    if (mode === 0) {
      this.eraseLine(0);
      for (let row = this.cursorY + 1; row < this.rowCount; row++) this.fill(row, 0, this.columns, " ");
    } else if (mode === 1) {
      for (let row = 0; row < this.cursorY; row++) this.fill(row, 0, this.columns, " ");
      this.eraseLine(1);
    } else if (mode === 2 || mode === 3) {
      for (let row = 0; row < this.rowCount; row++) this.fill(row, 0, this.columns, " ");
    }
  }

  /** 将完整屏幕向上滚动指定行数。 */
  scrollUp(count) {
    for (let index = 0; index < count; index++) {
      this.screen.shift();
      this.screen.push(Array(this.columns).fill(" "));
    }
  }

  /** 将完整屏幕向下滚动指定行数。 */
  scrollDown(count) {
    for (let index = 0; index < count; index++) {
      this.screen.pop();
      this.screen.unshift(Array(this.columns).fill(" "));
    }
  }

  /** 在光标行插入空行并裁掉末尾行。 */
  insertLines(count) {
    for (let index = 0; index < count; index++) {
      this.screen.splice(this.cursorY, 0, Array(this.columns).fill(" "));
      this.screen.pop();
    }
  }

  /** 从光标行删除行并在底部补入空行。 */
  deleteLines(count) {
    for (let index = 0; index < count; index++) {
      this.screen.splice(this.cursorY, 1);
      this.screen.push(Array(this.columns).fill(" "));
    }
  }

  /** 返回当前活跃主/备用缓冲区的真实可见文本行。 */
  lines() {
    return this.screen.map((row) => row.join("").trimEnd());
  }

  /** 提供便于测试的整屏可见文字，不包含 CSI/OSC 控制序列。 */
  text() {
    return this.lines().join("\n");
  }
}

/** 以常见终端列宽规则处理 CJK、组合字符与 Emoji，供 VT 缓冲区计算光标列。 */
function displayWidth(glyph) {
  const point = glyph.codePointAt(0);
  if (point === undefined) return 0;
  if ((point >= 0x0300 && point <= 0x036f) || (point >= 0x1ab0 && point <= 0x1aff)) return 0;
  if (
    point >= 0x1100 && (
      point <= 0x115f || point === 0x2329 || point === 0x232a ||
      (point >= 0x2e80 && point <= 0xa4cf) || (point >= 0xac00 && point <= 0xd7a3) ||
      (point >= 0xf900 && point <= 0xfaff) || (point >= 0xfe10 && point <= 0xfe6f) ||
      (point >= 0xff00 && point <= 0xff60) || (point >= 0xffe0 && point <= 0xffe6) ||
      (point >= 0x1f300 && point <= 0x1faff) || (point >= 0x20000 && point <= 0x3fffd)
    )
  ) return 2;
  return 1;
}

/** 编译后以真实 ConPTY 启动 Ja；可验证父 Shell 恢复或清除 debug 覆盖以运行已安装 bundled-native 发行包。 @author kongweiguang */
export function startConPtyCli(artifacts, directories, harness, threadId, prompt, logPath, launchCommand, useBundledSidecar = false) {
  const command = launchCommand ?? [
    artifacts.cli,
    "resume",
    threadId,
    ...(prompt === undefined ? [] : ["--prompt", prompt]),
  ];
  assert.ok(
    Array.isArray(command) && command.length > 0 && command.every((argument) => typeof argument === "string"),
    "ConPTY launch command must be a non-empty string argument array",
  );
  const environment = {
    ...process.env,
    JA_HOME: directories.home,
    JA_DEBUG_JAVA: artifacts.java,
    JA_DEBUG_JAR: artifacts.jar,
    TERM: "xterm-256color",
  };
  delete environment.NO_COLOR;
  delete environment.JA_REAL_PROVIDER_API_KEY;
  delete environment.JA_REAL_PROVIDER_BASE_URL;
  delete environment.JA_REAL_PROVIDER_MODEL;
  delete environment.JA_REAL_PROVIDER_NAME;
  delete environment.JA_REAL_PROVIDER_API;
  delete environment.JA_REAL_PROVIDER_REASONING_LEVEL;
  delete environment.JA_REAL_PROVIDER_AUTHORIZED;
  if (useBundledSidecar) {
    delete environment.JA_DEBUG_JAVA;
    delete environment.JA_DEBUG_JAR;
  }
  const child = spawn(artifacts.java, [
    "--enable-native-access=ALL-UNNAMED", "-cp", harness.classPath, "CliUsageConPty",
    "--cwd", directories.workspace, "--log", logPath, "--", ...command,
  ], {
    cwd: directories.workspace,
    env: environment,
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  const screen = new VtScreen();
  const messages = [];
  const waiters = new Set();
  let partial = "";
  let stderr = "";
  let logOffset = 0;
  let pollPromise;
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
    stderr = `${stderr}${chunk}`.slice(-4096);
  });
  const exitPromise = new Promise((resolvePromise) => {
    child.once("exit", (code, signal) => resolvePromise({ code, signal }));
  });

  /** 读取原始 VT 文件新增字节；从同一文件偏移增量回放，避免重复绘制。 */
  async function pullVt() {
    if (pollPromise) return pollPromise;
    pollPromise = (async () => {
      try {
        const all = await readFile(logPath);
        if (all.byteLength > logOffset) {
          screen.write(all.subarray(logOffset));
          logOffset = all.byteLength;
        }
      } catch (error) {
        if (error?.code !== "ENOENT") throw error;
      }
    })();
    try {
      await pollPromise;
    } finally {
      pollPromise = undefined;
    }
  }

  /** 按终端目前可见缓冲区轮询断言，并同时返回真实 VT 屏幕行快照。 */
  async function waitForScreen(predicate, label, timeoutMs = 30_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await pullVt();
      const visible = screen.text();
      if (predicate(visible, screen.lines())) return { text: visible, lines: screen.lines() };
      if (child.exitCode !== null) throw new Error(`CLI exited before ${label}: ${stderr.slice(-500)}`);
      await delay(80);
    }
    await pullVt();
    throw new Error(`timed out waiting for ${label}; last visible screen:\n${screen.text()}`);
  }

  /** 先在缓冲区作相同 resize，再对真实 ConPTY 发出 Win32 尺寸变更并等待确认。 */
  async function resize(columns, rows) {
    screen.resize(columns, rows);
    const start = messages.length;
    child.stdin.write(`RESIZE ${columns} ${rows}\n`);
    await waitForMessage(`RESIZED=${columns}x${rows}`, start, 5_000);
    await delay(150);
    await pullVt();
  }

  /** 查找已有控制响应后再注册有限 waiter，防止 ACK 与测试代码的 await 顺序竞争。 */
  function waitForMessage(prefix, afterIndex = 0, timeoutMs = 10_000) {
    const existing = messages.findIndex((line, index) => index >= afterIndex && line.startsWith(prefix));
    if (existing >= 0) return Promise.resolve(messages[existing]);
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        waiters.delete(waiter);
        rejectPromise(new Error(`ConPTY harness did not emit ${prefix}`));
      }, timeoutMs);
      const waiter = {
        check(line, index) {
          if (index < afterIndex || !line.startsWith(prefix)) return;
          clearTimeout(timer);
          waiters.delete(waiter);
          resolvePromise(line);
        },
      };
      waiters.add(waiter);
    });
  }

  /** 向专用控制管道发送 UTF-8 键值，不经过 Windows shell 或代码页转换。 */
  function sendKeys(value) {
    child.stdin.write(`SEND ${Buffer.from(value, "utf8").toString("base64")}\n`);
  }

  /** 每次状态查询收齐状态与退出码，避免跨块漏读；保留 Win32 DWORD 经 Java int 读取后的符号以诊断异常退出。 @author kongweiguang */
  async function waitForChildExit(timeoutMs = 10_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const start = messages.length;
      child.stdin.write("STATUS\n");
      let line;
      try {
        line = await waitForMessage("CHILD_ALIVE=", start, Math.min(350, deadline - Date.now()));
      } catch {
        await delay(60);
        continue;
      }
      if (line === "CHILD_ALIVE=false") {
        const exitLine = await waitForMessage("CHILD_EXIT_CODE=", start, Math.min(1_000, Math.max(1, deadline - Date.now())));
        const rawExitCode = exitLine.slice("CHILD_EXIT_CODE=".length);
        assert.match(rawExitCode, /^-?\d+$/u, "the ConPTY harness must report the exited CLI process code");
        const exitCode = Number(rawExitCode);
        assert.ok(Number.isSafeInteger(exitCode),
          "the reported CLI process code must be represented as a safe integer");
        return exitCode;
      }
    }
    return undefined;
  }

  /** 先发空草稿下的 Ctrl+D，再尝试确认式 Ctrl+C；只有均无效才关闭独有的 Job Object，并保留自然退出的真实退出码。 @author kongweiguang */
  async function close() {
    let forced = false;
    sendKeys("\u0004");
    let exitCode = await waitForChildExit(1_500);
    if (exitCode === undefined) {
      sendKeys("\u0003");
      await delay(300);
      sendKeys("\u0003");
      exitCode = await waitForChildExit(6_000);
    }
    if (exitCode === undefined) {
      forced = true;
      child.stdin.write("KILL\n");
      exitCode = await waitForChildExit(5_000);
    }
    const closeStart = messages.length;
    child.stdin.write("CLOSE\n");
    await waitForMessage("CLOSE_ACCEPTED=true", closeStart, 5_000);
    const harnessResult = await Promise.race([exitPromise, delay(7_000).then(() => undefined)]);
    if (!harnessResult) {
      child.kill();
      await Promise.race([exitPromise, delay(3_000)]);
    }
    await pullVt();
    return { childExitCode: exitCode ?? null, forced, harnessExitCode: harnessResult?.code ?? null };
  }

  return {
    child,
    messages,
    stderr: () => stderr,
    exitPromise,
    screen,
    pullVt,
    waitForScreen,
    waitForMessage,
    sendKeys,
    resize,
    close,
    harnessReady: waitForMessage("CHILD_PID=", 0, 15_000),
  };
}

/** 使用有限期限等待异步 Provider、RPC 与 ConPTY 操作，失败名不包含正文或凭据。 @author kongweiguang */
async function withTimeout(promise, timeoutMs, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, rejectPromise) => {
        timer = setTimeout(() => rejectPromise(new Error(`${label} timed out`)), timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** 在首个 Provider 请求前同时检查真实 ConPTY 错误屏，避免 storage 初始化失败被误报成 45 秒 Provider 超时。 @author kongweiguang */
async function waitForInitialProviderRequest(cli, provider, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await cli.pullVt();
    const failureLine = cli.screen.lines().find((line) => line.includes("请求失败"));
    if (failureLine) {
      const code = failureLine.match(/\((-?\d+)\)/u)?.[1] ?? "unknown";
      const method = failureLine.match(/\b([a-z][a-z0-9_-]*\/[a-z][a-z0-9_-]*)\b/iu)?.[1] ?? "unknown";
      throw new Error(`CLI displayed an RPC failure before reaching the loopback Provider (code ${code}, method ${method})`);
    }
    const childExit = cli.messages.find((message) => message.startsWith("CHILD_EXIT_CODE="));
    if (childExit) {
      throw new Error(`CLI exited before reaching the loopback Provider (${childExit})`);
    }
    if (provider.requestCount > 0) return await provider.requestReceived;
    await delay(80);
  }
  throw new Error("loopback Provider request timed out");
}

/** 仅保留隔离 profile 中与异常相关的有界日志项，并擦除本地 endpoint、临时路径及 fixture 正文后写入证据目录。 @author kongweiguang */
function sanitizeFailureLog(text, secrets) {
  let safeText = text;
  for (const secret of secrets) {
    if (typeof secret === "string" && secret.length > 0) safeText = safeText.replaceAll(secret, "<redacted>");
  }
  safeText = safeText
    .replace(/(authorization\s*[:=]\s*bearer\s+)[^\s,;]+/giu, "$1<redacted>")
    .replace(/((?:api[_-]?key|credential|secret|token)\s*[:=]\s*)[^\s,;]+/giu, "$1<redacted>");
  const entries = safeText
    .split(/(?=^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} )/mu)
    .filter((entry) => /\bERROR\b|Exception|Caused by|SQLite|SQLITE|database is locked|no such (?:table|column)|migration|storage unavailable/iu.test(entry));
  return entries.slice(-20).join("").slice(-24 * 1024);
}

/** Provider 启动失败时先退出自有 TUI，再从隔离目录复制错误日志诊断；不读取宿主 profile 或归档正常运行日志。 @author kongweiguang */
async function captureFailureDiagnostics(isolated, cli, provider) {
  const secrets = [
    placeholderSecret,
    provider?.endpoint,
    promptMarker,
    finalText,
    isolated.profileRoot,
    isolated.directories.home,
    isolated.directories.workspace,
  ];
  const diagnostics = {
    capturedAtUtc: new Date().toISOString(),
    cliHarnessStderrTail: sanitizeFailureLog(cli?.stderr?.() ?? "", secrets),
    appServerLogs: {},
  };
  for (const fileName of ["app-server-error.log", "app-server.log"]) {
    const path = join(isolated.directories.logs, fileName);
    try {
      const content = await readFile(path);
      const tail = content.subarray(Math.max(0, content.byteLength - 256 * 1024)).toString("utf8");
      diagnostics.appServerLogs[fileName] = {
        bytesRead: Math.min(content.byteLength, 256 * 1024),
        matchingEntries: sanitizeFailureLog(tail, secrets),
      };
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
      diagnostics.appServerLogs[fileName] = { status: "not-created" };
    }
  }
  const path = join(isolated.runRoot, "failure-diagnostics.json");
  await writeFile(path, `${JSON.stringify(diagnostics, null, 2)}\n`, "utf8");
  return path;
}

/** 读取 Java 权威摘要和 Thread 快照；正文不落盘，错误保留现有脱敏 stderr，标题用量与 assistant Thread ledger 分账。 @author kongweiguang */
async function readUsageLedger(artifacts, directories, endpoint, threadId, requestMetadata) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  let ready = false;
  try {
    const initialized = rpcResult(await session.request("runtime/initialize", initializeParams()), "runtime/initialize ledger");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;
    const summary = rpcResult(await session.request("thread/usage/read", { threadId }), "thread/usage/read");
    const history = rpcResult(await session.request("thread/read", { threadId, tail: true }), "thread/read");
    const items = Array.isArray(history.items) ? history.items : [];
    const userInputs = items.filter((item) => item?.kind === "user_input");
    const markerCount = (value) => JSON.stringify(value).split(promptMarker).length - 1;
    const userInputEvidence = userInputs.map((item) => ({
      itemId: item.itemId ?? null,
      turnId: item.turnId ?? null,
      markerOccurrences: markerCount(item.content),
    }));
    const context = history.contextUsage;
    return {
      threadIdentityMatches: summary.threadId === threadId && history.threadId === threadId,
      threadTitle: typeof history.title === "string" ? history.title : null,
      providerRequestMetadata: requestMetadata,
      summary: {
        threadId: summary.threadId ?? null,
        requestCount: summary.requestCount ?? null,
        measuredRequestCount: summary.measuredRequestCount,
        newInputTokens: summary.newInputTokens,
        outputTokens: summary.outputTokens,
        cacheReadTokens: summary.cacheReadTokens,
        cacheCompleteInputTokens: summary.cacheCompleteInputTokens,
        cacheCompleteReadTokens: summary.cacheCompleteReadTokens,
      },
      contextUsage: {
        purpose: context?.purpose ?? null,
        certainty: context?.certainty ?? null,
        turnId: context?.turnId ?? null,
        modelRequestId: context?.modelRequestId ?? null,
        inputTokens: context?.inputTokens ?? null,
        outputTokens: context?.outputTokens ?? null,
        totalTokens: context?.totalTokens ?? null,
        contextEstimate: context?.contextEstimate ?? null,
        contextWindowTokens: context?.profile?.contextWindowTokens ?? null,
      },
      turns: Array.isArray(history.turns)
        ? history.turns.map((turn) => ({
          turnId: turn.turnId ?? null,
          status: turn.status ?? null,
          sourceMessageId: turn.sourceMessageId ?? null,
        }))
        : [],
      userInputs: userInputEvidence,
      markerUserInputCount: userInputEvidence.filter((item) => item.markerOccurrences > 0).length,
      items: items.map((item) => ({
        itemId: item.itemId ?? null,
        kind: item.kind ?? null,
        turnId: item.turnId ?? null,
        modelRound: item.modelRound ?? null,
        markerOccurrences: markerCount(item.content ?? item.text ?? null),
      })),
      threadRevision: history.threadRevision,
    };
  } catch (error) {
    throw withSessionDiagnostic(error, session, endpoint);
  } finally {
    if (ready && !session.exited) await session.shutdown().catch(() => undefined);
    await session.forceClose();
  }
}

/** 比对累积屏幕中的实际用户可见行，并把受控快照保存到本次唯一的验收目录。 @author kongweiguang */
export async function writeScreenEvidence(runRoot, name, screen) {
  const lines = screen.lines();
  await writeFile(join(runRoot, `${name}.txt`), `${lines.join("\n")}\n`, "utf8");
  return lines;
}

/** 串起估算、账本、/status、恢复、F5 和两种受限 ConPTY 尺寸；finally 回收所有本次进程和临时 profile。 @author kongweiguang */
async function runAcceptance() {
  await mkdir(outputRoot, { recursive: true });
  const artifacts = await resolveArtifacts();
  const isolated = await createRunDirectories();
  let provider;
  let firstCli;
  let restoredCli;
  let profileRemoved = false;
  let primaryFailure;
  const cleanup = {};
  const evidence = {
    status: "running",
    runtime: "fixed ja.exe + isolated JDK 25 App Server JAR + real Windows ConPTY",
    artifactFingerprint: {
      cliSha256: artifacts.cliSha256,
      appServerJarSha256: artifacts.jarSha256,
      appServerJarBytes: artifacts.jarBytes,
      javaVersion: artifacts.javaVersion,
    },
    provider: "loopback-only OpenAI Responses fixture; no paid or real Provider request",
    expectedUsage: {
      perProviderRequest: {
        newInputTokens: 1_920,
        outputTokens: 449,
        cacheReadTokens: 22_080,
        cacheHitPercent: 92,
        contextWindowTokens: 1_000_000,
      },
    },
    transcript: {},
  };
  try {
    provider = await startUsageProvider();
    const thread = await seedThread(artifacts, isolated.directories, provider.endpoint);
    evidence.threadId = thread.threadId;
    evidence.seedAppServerExitCode = thread.seedAppServerExitCode;
    assert.equal(thread.seedAppServerExitCode, 0, "the Thread seed owner must release the isolated runtime before CLI resume");
    const harness = await compileConPtyHarness(artifacts, isolated.runRoot);
    const firstLog = join(isolated.runRoot, "initial-turn.vt");
    firstCli = startConPtyCli(
      artifacts,
      isolated.directories,
      harness,
      thread.threadId,
      promptMarker,
      firstLog,
    );
    await firstCli.harnessReady;
    const request = await waitForInitialProviderRequest(firstCli, provider, 45_000);
    assert.equal(request.markerFound, true, "the isolated Provider must receive the exact resume prompt");
    assert.ok(request.requestBytes > 0);
    const estimated = await firstCli.waitForScreen((visible) =>
      ["累计", "输入", "输出", "含缓存读", "上下文"].every((label) => visible.includes(label))
        && /上下文(?:\d+(?:\.\d+)?k|\d+)\/1\.0M\s+(?:<0\.1|\d+(?:\.\d+)?)%/u.test(visible),
    "local context estimate with stable usage fields");
    const estimateFooterLine = estimated.lines.find((line) => line.includes("累计") && line.includes("上下文"));
    assert.ok(estimateFooterLine, "the estimated wide footer must keep all five fixed fields on one line");
    assert.match(estimateFooterLine, /上下文(?:\d+(?:\.\d+)?k|\d+)\/1\.0M\s+(?:<0\.1|\d+(?:\.\d+)?)%/u,
      "an in-flight local estimate must show its context count, window, and share without a status qualifier");
    assert.doesNotMatch(estimateFooterLine, /约|已报告|正在读取|待刷新|未知/u,
      "the footer must remain numeric and stable while detailed status belongs to /status");
    evidence.transcript.estimatedWide = await writeScreenEvidence(isolated.runRoot, "estimated-wide", firstCli.screen);

    provider.release();
    await withTimeout((async () => {
      while (provider.responseCount < 1) await delay(50);
      return provider.responseCount;
    })(), 30_000, "loopback Provider response");
    await firstCli.waitForScreen((visible) => visible.includes("工作用时") && !visible.includes("正在工作"), "turn idle after usage reports");
    await withTimeout((async () => {
      while (provider.responseCount < provider.requestCount) await delay(50);
      return provider.responseCount;
    })(), 15_000, "all usage response streams");
    const actualKnownScreen = await firstCli.waitForScreen(
      (visible) => ["累计24k", "输入24k", "输出449", "含缓存读22k", "上下文24k/1.0M"].every((value) => visible.includes(value)),
      "known usage footer",
    );
    evidence.transcript.knownWide = await writeScreenEvidence(isolated.runRoot, "known-wide", firstCli.screen);
    assert.ok(actualKnownScreen.text.includes(finalText), "Chinese and Emoji response must render in the real ConPTY");
    const knownFooterLine = actualKnownScreen.lines.find((line) => line.includes("累计24k") && line.includes("上下文24k/1.0M"));
    assert.ok(knownFooterLine, "the measured wide footer must keep all five fixed fields on one line");
    assert.notEqual(estimateFooterLine, knownFooterLine, "the estimate-to-measurement transition must update values without changing footer labels");
    const footerLabels = ["累计", "输入", "输出", "含缓存读", "上下文"];
    const estimateLabelPositions = footerLabels.map((label) => estimateFooterLine.indexOf(label));
    const measuredLabelPositions = footerLabels.map((label) => knownFooterLine.indexOf(label));
    assert.ok(estimateLabelPositions.every((position) => position >= 0), "the estimate must expose each fixed field");
    assert.deepEqual(measuredLabelPositions, estimateLabelPositions,
      "reported values must update in place without shifting any of the five field labels");
    const knownFooter = knownFooterLine.replaceAll(/\s/gu, "");
    assert.doesNotMatch(knownFooterLine, /约|已报告|正在读取|待刷新|未知/u,
      "the completed footer must not expose transient status wording");
    assert.match(knownFooter, /累计24k/u, "the footer must show the total reported usage");
    assert.match(knownFooter, /输入24k/u, "reported input must include cached tokens");
    assert.match(knownFooter, /输出449/u, "the footer must show reported output");
    assert.match(knownFooter, /含缓存读22k/u, "cache reads must be labeled as a subset of input");
    assert.match(knownFooter, /上下文24k\/1\.0M2\.4%/u, "measured context must use the assistant request input count");

    firstCli.sendKeys("/status\r");
    const statusView = await firstCli.waitForScreen((visible) =>
      visible.includes("缓存命中") && visible.includes("上下文"),
    "/status usage details");
    evidence.transcript.status = await writeScreenEvidence(isolated.runRoot, "status", firstCli.screen);
    const statusText = statusView.text.replaceAll(/\s+/gu, " ");
    assert.match(statusText, /输入（含缓存）：24,000 tokens/u);
    assert.match(statusText, /新输入（未缓存）：1,920 tokens/u);
    assert.match(statusText, /输出：449 tokens/u);
    assert.match(statusText, /总计（输入含缓存 \+ 输出）：24,449 tokens/u);
    assert.match(statusView.text, /缓存命中：22,080\/24,000 tokens（92\.0%）/u);
    assert.match(statusText, /缓存明细（已含在输入中，不重复累加）：读取 22,080 tokens/u,
      "status details must explain that cache reads are included in input, not additional tokens");
    assert.match(statusText, /最近请求上下文：24,449 \/ 1,000,000 tokens（Provider 实测/u);
    assert.match(statusText, /发送前显示本地输入估算，Provider 实测返回后\s*校正/u,
      "status details must distinguish the pending estimate from the completed Provider measurement");
    firstCli.sendKeys("\u001b");
    await firstCli.waitForScreen((visible) => !visible.includes("会话状态"), "close /status details");
    cleanup.initial = await firstCli.close();
    firstCli = undefined;
    assert.equal(cleanup.initial.forced, false, "initial CLI should close through its normal terminal control path");
    assert.equal(cleanup.initial.childExitCode, 0, "initial CLI must exit naturally with code zero");

    evidence.ledger = await readUsageLedger(
      artifacts,
      isolated.directories,
      provider.endpoint,
      thread.threadId,
      provider.requestMetadata,
    );
    evidence.providerCounts = { requests: provider.requestCount, completedResponses: provider.responseCount };
    assert.equal(evidence.ledger.threadIdentityMatches, true);
    assert.equal(provider.responseCount, provider.requestCount, "all loopback request streams should finish");
    assert.equal(
      evidence.ledger.markerUserInputCount,
      1,
      `resume --prompt should persist one user item; observed ${evidence.ledger.markerUserInputCount} matching items across ${evidence.ledger.turns.length} Turns`,
    );
    assert.equal(evidence.ledger.turns.length, 1, "resume --prompt should create one completed Turn");
    assert.equal(evidence.ledger.items.filter((item) => item.kind === "final_answer").length, 1);
    assert.equal(evidence.ledger.contextUsage.purpose, "assistant");
    assert.equal(evidence.ledger.contextUsage.certainty, "known");
    assert.equal(evidence.ledger.contextUsage.inputTokens, 24_000);
    assert.equal(evidence.ledger.contextUsage.outputTokens, 449);
    assert.equal(evidence.ledger.contextUsage.totalTokens, 24_449);
    assert.equal(evidence.ledger.contextUsage.contextWindowTokens, 1_000_000);
    assert.equal(evidence.ledger.summary.requestCount, 1, "one assistant request should be recorded in the Thread ledger");
    assert.equal(evidence.ledger.summary.measuredRequestCount, 1);
    assert.equal(
      evidence.ledger.providerRequestMetadata.filter((request) => request.usageSample === "assistant-complete-cache-sample").length,
      1,
      "the fixture must deliver exactly one complete usage sample",
    );
    const auxiliaryTitleRequestCount = provider.requestCount - evidence.ledger.summary.requestCount;
    assert.equal(
      auxiliaryTitleRequestCount,
      1,
      "the first successful Turn should produce one independently accounted automatic title request",
    );
    evidence.providerRequestReconciliation = {
      assistantThreadUsage: {
        requestCount: evidence.ledger.summary.requestCount,
        purpose: evidence.ledger.contextUsage.purpose,
        modelRequestId: evidence.ledger.contextUsage.modelRequestId,
      },
      automaticTitle: {
        purpose: "first-turn automatic title generation, accounted by its own server ledger",
        providerRequestCount: auxiliaryTitleRequestCount,
        accounting: "separate automatic-title ledger; excluded from thread/usage/read",
      },
    };
    assert.equal(evidence.ledger.summary.newInputTokens, 1_920);
    assert.equal(evidence.ledger.summary.outputTokens, 449);
    assert.equal(evidence.ledger.summary.cacheReadTokens, 22_080);
    assert.equal(evidence.ledger.summary.cacheCompleteInputTokens, 24_000);
    assert.equal(evidence.ledger.summary.cacheCompleteReadTokens, 22_080);
    const restoreLog = join(isolated.runRoot, "restored-thread.vt");
    restoredCli = startConPtyCli(
      artifacts,
      isolated.directories,
      harness,
      thread.threadId,
      undefined,
      restoreLog,
    );
    await restoredCli.harnessReady;
    const restored = await restoredCli.waitForScreen((visible) =>
      visible.includes("累计24k") && visible.includes("上下文24k/1.0M") && visible.includes("2.4%"),
    "restored Thread usage");
    evidence.transcript.restored = await writeScreenEvidence(isolated.runRoot, "restored", restoredCli.screen);
    assert.match(restored.text.replaceAll(/\s/gu, ""), /累计24k.*上下文24k\/1\.0M2\.4%/u);

    const draftText = "F5草稿保留🙂";
    restoredCli.sendKeys(draftText);
    const typedDraft = await restoredCli.waitForScreen((visible) => visible.includes(draftText), "draft before F5");
    evidence.transcript.draftBeforeF5 = await writeScreenEvidence(isolated.runRoot, "draft-before-f5", restoredCli.screen);
    assert.ok(typedDraft.text.includes(draftText));
    restoredCli.sendKeys("\u001b[15~");
    const refreshNotice = await waitForRawText(restoreLog, /后台状态已更新/u, 15_000);
    evidence.transcript.f5RefreshNotice = refreshNotice;
    const refreshed = await restoredCli.waitForScreen((visible) =>
      visible.includes("累计24k") && visible.includes("上下文24k/1.0M") && visible.includes("2.4%") && visible.includes(draftText),
    "usage after F5 read refresh");
    evidence.transcript.f5 = await writeScreenEvidence(isolated.runRoot, "f5-refreshed", restoredCli.screen);
    assert.match(refreshed.text.replaceAll(/\s/gu, ""), /累计24k/u);
    assert.ok(refreshed.text.includes(draftText), "F5 must preserve the active draft and cursor buffer");
    restoredCli.sendKeys("\u007f".repeat(Array.from(draftText).length));
    await restoredCli.waitForScreen((visible) => !visible.includes(draftText), "clear F5 verification draft");

    await restoredCli.resize(34, 12);
    const normal34 = await restoredCli.waitForScreen((visible, lines) => {
      const footer = lines.slice(-4).join(" ");
      return footer.includes("累计24k") && footer.includes("上下文2.4%");
    }, "34-column cumulative and context percentage footer");
    evidence.transcript.columns34 = await writeScreenEvidence(isolated.runRoot, "columns-34", restoredCli.screen);
    const footer34 = normal34.lines.slice(-4).join("").replaceAll(/\s/gu, "");
    assert.match(footer34, /累计24k/u);
    assert.match(footer34, /上下文2\.4%/u);
    assert.doesNotMatch(footer34, /输入24k|输出449|缓存读22k|上下文24k\/1\.0M/u,
      "34 columns use the fixed cumulative-plus-percentage layout");

    await restoredCli.resize(32, 12);
    const compact32 = await restoredCli.waitForScreen((visible, lines) => {
      const footer = lines.slice(-4).join(" ");
      return footer.includes("累计24k") && footer.includes("上下文2.4%");
    }, "32-column compact usage footer");
    evidence.transcript.columns32 = await writeScreenEvidence(isolated.runRoot, "columns-32", restoredCli.screen);
    const compactFooter = compact32.lines.slice(-4).join("").replaceAll(/\s/gu, "");
    assert.match(compactFooter, /累计24k/u, "the cumulative total should survive the compact layout");
    assert.match(compactFooter, /上下文2\.4%/u, "compact context should preserve its measured percentage");
    assert.doesNotMatch(compactFooter, /输入24k|输出449|缓存读22k/u,
      "the compact layout should remove lower-priority per-direction and cache details");
    assert.doesNotMatch(compactFooter, /上下文24k\/1\.0M/u,
      "32 columns use the fixed cumulative-plus-percentage layout");

    await restoredCli.resize(24, 12);
    await delay(350);
    await restoredCli.pullVt();
    const columns24Text = restoredCli.screen.text().replaceAll(/\s/gu, "");
    evidence.transcript.columns24 = await writeScreenEvidence(isolated.runRoot, "columns-24", restoredCli.screen);
    assert.match(columns24Text, /上下文2\.4%/u, "a 24-column terminal should retain only the measured context percentage");
    assert.doesNotMatch(columns24Text, /输入24k|输出449|缓存读22k|上下文24k\/1\.0M/u,
      "24 columns should omit input/output details and the context token count/window");
    assert.doesNotMatch(columns24Text, /累计24k/u, "the fixed 24-column layout shows context percentage without cumulative usage");

    await restoredCli.resize(20, 12);
    await delay(350);
    await restoredCli.pullVt();
    const columns20Text = restoredCli.screen.text().replaceAll(/\s/gu, "");
    evidence.transcript.columns20 = await writeScreenEvidence(isolated.runRoot, "columns-20", restoredCli.screen);
    assert.match(columns20Text, /上下文2\.4%/u, "a 20-column terminal should retain only the measured context percentage");
    assert.doesNotMatch(columns20Text, /累计24k|输入24k|输出449|缓存读22k|上下文24k\/1\.0M/u,
      "the context percentage should be the only usage value at the smallest width");

    await restoredCli.resize(20, 6);
    await delay(350);
    await restoredCli.pullVt();
    const shortHeightLines = restoredCli.screen.lines();
    evidence.transcript.shortHeight = await writeScreenEvidence(isolated.runRoot, "short-height", restoredCli.screen);
    assert.ok(shortHeightLines.some((line) => line.trim() === "›"),
      "a short terminal must preserve the Composer prompt when the usage row cannot fit");
    assert.doesNotMatch(restoredCli.screen.text(), /累计24k|上下文2\.4%/u,
      "Composer takes priority over the usage row at a short 20x6 height");
    cleanup.restored = await restoredCli.close();
    restoredCli = undefined;
    assert.equal(cleanup.restored.forced, false, "restored CLI should close through its normal terminal control path");
    assert.equal(cleanup.restored.childExitCode, 0, "restored CLI must exit naturally with code zero");
    evidence.cleanup = cleanup;
    evidence.providerCounts = { requests: provider.requestCount, completedResponses: provider.responseCount };
    evidence.status = "completed";
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
    evidence.status = "failed";
    evidence.failure = primaryFailure.message;
    const failedCli = firstCli ?? restoredCli;
    if (firstCli) {
      cleanup.initial = await firstCli.close().catch((failure) => ({ error: failure.message }));
      firstCli = undefined;
    }
    if (restoredCli) {
      cleanup.restored = await restoredCli.close().catch((failure) => ({ error: failure.message }));
      restoredCli = undefined;
    }
    try {
      evidence.failureDiagnosticsFile = await captureFailureDiagnostics(isolated, failedCli, provider);
    } catch (diagnosticError) {
      evidence.failureDiagnosticsCaptureError = diagnosticError instanceof Error ? diagnosticError.message : String(diagnosticError);
    }
    evidence.cleanup = cleanup;
  } finally {
    if (provider) {
      await provider.close().catch((error) => { cleanup.providerCloseError = String(error); });
    }
    try {
      await removeOwnedProfile(isolated.runRoot, isolated.profileRoot);
      profileRemoved = true;
    } catch (error) {
      cleanup.profileRemovalError = error instanceof Error ? error.message : String(error);
      if (!primaryFailure) primaryFailure = error instanceof Error ? error : new Error(String(error));
      evidence.status = "failed";
    }
    evidence.cleanup = { ...cleanup, profileRemoved };
    await writeFile(join(isolated.runRoot, "acceptance.json"), `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  if (primaryFailure) {
    process.stderr.write(`CLI usage ConPTY acceptance failed. Evidence: ${isolated.runRoot}\n`);
    throw primaryFailure;
  }
  process.stdout.write(`${JSON.stringify({ status: evidence.status, evidenceDirectory: isolated.runRoot, artifactFingerprint: evidence.artifactFingerprint, usage: evidence.expectedUsage, cleanup: evidence.cleanup }, null, 2)}\n`);
}

/** 仅轮询原始 VT 文件文本，供 /status/F5 人类反馈断言使用；正式画面断言使用 VtScreen。 @author kongweiguang */
export async function waitForRawText(path, pattern, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const bytes = await readFile(path);
      const text = bytes.toString("utf8").replace(/\u001b\[[0-?]*[ -/]*[@-~]/gu, "");
      if (pattern.test(text)) return text.match(pattern)?.[0] ?? "matched";
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    await delay(80);
  }
  throw new Error(`terminal VT evidence did not show expected feedback ${String(pattern)}`);
}

/** 固定 Responses 用量契约完整样本附加于成功终态，故输入、缓存命中、输出与 total 必须闭合。 @author kongweiguang */
function validateUsageFixture() {
  assert.equal(usageFact.input_tokens - usageFact.input_tokens_details.cached_tokens, 1_920);
  assert.equal(usageFact.input_tokens_details.cached_tokens / usageFact.input_tokens, 0.92);
  assert.equal(usageFact.input_tokens + usageFact.output_tokens, usageFact.total_tokens);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  validateUsageFixture();
  await runAcceptance();
}
