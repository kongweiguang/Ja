// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Windows 真窗专用 Tauri runner：Cargo 仍负责编译，EdgeDriver 只接管最终 WebView2
 * 进程启动与调试通道。该文件不进入产品 composition，也不暴露新的 Tauri command。
 */

import { spawn } from "node:child_process";
import { rename, writeFile } from "node:fs/promises";
import process from "node:process";
import { join, resolve } from "node:path";

/** 只接受 runner 自己生成或 Tauri CLI 传入的本机整数端口，拒绝任意 URL。 */
function requiredPort(name) {
  const port = Number(process.env[name]);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new Error(`${name} 必须是有效端口`);
  }
  return port;
}

/** 读取绝对文件路径；所有外部依赖都由父 smoke runner 预先解析并验证。 */
function requiredPath(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} 未配置`);
  return resolve(value);
}

/**
 * 把 Tauri CLI 的 `cargo run` 参数收窄为同配置的 `cargo build`；应用参数不属于编译
 * 输入，故在 `--` 处分离，避免把运行期值误传给 rustc。
 */
function cargoBuildArguments(args) {
  if (args[0] !== "run") throw new Error("EdgeDriver runner 只接受 Tauri cargo run 合同");
  const separator = args.indexOf("--");
  const compileArgs = separator < 0 ? args.slice(1) : args.slice(1, separator);
  return ["build", ...compileArgs];
}

/** 继承 Tauri 已构造的编译环境并等待 Cargo 完整退出，失败码不做宽化映射。 */
async function buildApplication(cargo, args) {
  const child = spawn(cargo, cargoBuildArguments(args), {
    cwd: process.cwd(),
    env: process.env,
    stdio: "inherit",
    windowsHide: true,
  });
  const result = await new Promise((resolvePromise) => {
    child.once("exit", (code, signal) => resolvePromise({ code, signal }));
  });
  if (result.code !== 0)
    throw new Error(`Cargo build 失败 code=${result.code} signal=${result.signal ?? "none"}`);
}

/** 在有界期限内等待本机 EdgeDriver HTTP 服务，launcher 提前退出时立即失败。 */
async function waitForDriver(driver, port) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (driver.exitCode !== null || driver.signalCode !== null)
      throw new Error("EdgeDriver 启动前退出");
    try {
      const response = await fetch(`http://127.0.0.1:${port}/status`, {
        signal: AbortSignal.timeout(1_000),
      });
      if (response.ok) return;
    } catch {
      // 服务进程与 HTTP listener 分阶段就绪；统一在短期限内重试。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("EdgeDriver HTTP 服务未就绪");
}

/**
 * 仅投影 WebDriver 响应的结构与有界标量形状，便于定位当前官方驱动合同漂移；
 * 不回显任意错误正文、二进制路径或未经信任的 capability 值。
 */
function sessionResponseShape(response, body) {
  const value = body?.value;
  const capabilities = value?.capabilities;
  const address = capabilities?.["ms:edgeOptions"]?.debuggerAddress;
  return {
    httpStatus: response.status,
    bodyKeys: Object.keys(body ?? {}).sort(),
    valueKeys: Object.keys(value ?? {}).sort(),
    capabilityKeys: Object.keys(capabilities ?? {}).sort(),
    sessionIdType: typeof value?.sessionId,
    sessionIdLength: typeof value?.sessionId === "string" ? value.sessionId.length : null,
    sessionIdAlphabet:
      typeof value?.sessionId === "string"
        ? /^[a-f0-9]+$/u.test(value.sessionId)
          ? "lower-hex"
          : /^[A-Fa-f0-9-]+$/u.test(value.sessionId)
            ? "hex-with-separators"
            : "other"
        : "absent",
    browserName: capabilities?.browserName,
    debuggerAddressShape:
      typeof address === "string"
        ? /^(?:localhost|127\.0\.0\.1):[1-9][0-9]{0,4}$/u.test(address)
          ? address.startsWith("localhost:")
            ? "localhost-port"
            : "ipv4-loopback-port"
          : "other"
        : typeof address,
    processIdType: typeof capabilities?.["goog:processID"],
  };
}

/**
 * 让官方 EdgeDriver 以 WebView2 模式启动刚编译的 Ja；profile 必须交给 WebView2
 * 环境而不是宿主进程参数，否则 Tauri 不会消费该路径，driver 会等待错误的 profile。
 * debugger address 必须是 loopback 数字端口，防止 session 响应扩大连接边界。
 */
async function createWebViewSession(driverPort, binary, dataDirectory) {
  const response = await fetch(`http://127.0.0.1:${driverPort}/session`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      capabilities: {
        alwaysMatch: {
          "ms:edgeChromium": true,
          browserName: "webview2",
          // WebView2 launch 模式下 `args` 只会传给 Ja 宿主；官方 capability 要求通过
          // webviewOptions 注入用户数据目录，EdgeDriver 才能和 Tauri 共享调试 profile。
          "ms:edgeOptions": {
            binary,
            webviewOptions: { userDataFolder: dataDirectory },
          },
        },
      },
    }),
    // 外层 CDP admission 总预算为 120 秒；session 留出 10 秒给 ACK 原子发布与 owner 复验，
    // 避免 WebView2 已启动但 Java 冷启动较慢时由内部更短 deadline 提前杀死真实窗口。
    signal: AbortSignal.timeout(110_000),
  });
  const body = await response.json();
  const value = body?.value;
  const sessionId = value?.sessionId;
  const capabilities = value?.capabilities;
  const address = capabilities?.["ms:edgeOptions"]?.debuggerAddress;
  const match = /^(?:localhost|127\.0\.0\.1):([1-9][0-9]{0,4})$/u.exec(address ?? "");
  const debuggerPort = Number(match?.[1]);
  const appPid = Number(capabilities?.["goog:processID"]);
  if (
    !response.ok ||
    typeof sessionId !== "string" ||
    !/^[a-f0-9]{16,128}$/u.test(sessionId) ||
    capabilities?.browserName !== "webview2" ||
    !Number.isSafeInteger(debuggerPort) ||
    debuggerPort > 65_535 ||
    !Number.isSafeInteger(appPid) ||
    appPid < 1
  ) {
    throw new Error(
      `EdgeDriver session 响应不符合闭合合同 ${JSON.stringify(sessionResponseShape(response, body))}`,
    );
  }
  return {
    sessionId,
    debuggerPort,
    appPid,
    browserVersion: String(capabilities.browserVersion ?? "").slice(0, 64),
  };
}

/** 原子发布 session ACK；主 runner 只会看到完整 JSON，不读取半写入的调试地址。 */
async function publishSession(path, session) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(session)}\n`, "utf8");
  await rename(temporary, path);
}

/** 只在精确 session 上请求退出；失败由后续进程树 cleanup 继续兜底。 */
async function deleteSession(driverPort, sessionId) {
  await fetch(`http://127.0.0.1:${driverPort}/session/${sessionId}`, {
    method: "DELETE",
    signal: AbortSignal.timeout(5_000),
  }).catch(() => undefined);
}

/**
 * 监控 EdgeDriver 启动的 Ja identity；窗口退出后主动删除 session 并结束 driver，避免
 * Tauri CLI runner 因 HTTP server 常驻而把一次正常 WM_CLOSE 误判为产品泄漏。
 */
async function supervise(driver, driverPort, session) {
  let stopping = false;
  const stop = async () => {
    if (stopping) return;
    stopping = true;
    await deleteSession(driverPort, session.sessionId);
    if (driver.exitCode === null && driver.signalCode === null) driver.kill();
  };
  process.once("SIGINT", () => void stop());
  process.once("SIGTERM", () => void stop());
  while (!stopping && driver.exitCode === null && driver.signalCode === null) {
    try {
      process.kill(session.appPid, 0);
    } catch (error) {
      if (error?.code === "ESRCH") await stop();
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  if (!stopping) await stop();
  await new Promise((resolvePromise) => {
    if (driver.exitCode !== null || driver.signalCode !== null) resolvePromise();
    else driver.once("exit", resolvePromise);
  });
}

/** 串起编译、官方 driver、session ACK 与退出监督，任一阶段失败都保持非零退出。 */
async function main() {
  const cargo = requiredPath("JA_E2E_CARGO_COMMAND");
  const edgeDriver = requiredPath("JA_E2E_EDGEDRIVER_PATH");
  const sessionPath = requiredPath("JA_E2E_EDGEDRIVER_SESSION_PATH");
  const dataDirectory = requiredPath("JA_E2E_WEBVIEW_DATA_DIR");
  const driverPort = requiredPort("JA_E2E_EDGEDRIVER_PORT");
  await buildApplication(cargo, process.argv.slice(2));
  const targetDirectory = resolve(process.env.CARGO_TARGET_DIR ?? join(process.cwd(), "target"));
  const binary = join(targetDirectory, "debug", "ja.exe");
  const driverArguments =
    process.env.JA_E2E_EDGEDRIVER_VERBOSE === "1"
      ? [`--port=${driverPort}`, "--verbose"]
      : [`--port=${driverPort}`, "--log-level=WARNING"];
  const driver = spawn(edgeDriver, driverArguments, {
    cwd: process.cwd(),
    env: process.env,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  driver.stdout?.on("data", (chunk) => process.stderr.write(String(chunk).slice(0, 2_000)));
  driver.stderr?.on("data", (chunk) => process.stderr.write(String(chunk).slice(0, 2_000)));
  await waitForDriver(driver, driverPort);
  const session = await createWebViewSession(driverPort, binary, dataDirectory);
  await publishSession(sessionPath, session);
  await supervise(driver, driverPort, session);
}

await main();
