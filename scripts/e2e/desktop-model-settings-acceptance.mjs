// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { createConnection, createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { chromium, expect } from "@playwright/test";
import { verifyConfiguredProviderNameEdit } from "./cli-model-settings-acceptance.mjs";
import { JsonlSession, initializeParams } from "./real-provider-smoke.mjs";
import {
  assertDatabaseLeaseReleased,
  compileDatabaseLeaseProbe,
} from "./isolated-database-lease.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const defaultJavaHome = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const defaultJar = join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar");
const execFileAsync = promisify(execFile);
const providerId = "provider_desktop_model_settings_e2e";
const secondProviderId = "provider_desktop_model_settings_second";
const primaryModelId = "model_desktop_primary";
const secondaryModelId = "model_desktop_secondary";
const hiddenProviderId = "provider_hidden_raw_desktop_e2e";
const fixtureCredentialSecret = "ja-desktop-model-settings-fixture-secret";

/** 仅在 runner 所有临时数据树内分配回环端口，避免连接用户进程或外部服务。 @author kongweiguang */
async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  await new Promise((resolvePromise, reject) =>
    server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
  );
  if (address === null || typeof address === "string") throw new Error("无法预留回环端口");
  return address.port;
}

/** 用最小有效配置加未知字段和不可投影 Provider，证明 UI 的可见投影不会替换原始配置数组。 @author kongweiguang */
async function writeFixture(jaHome) {
  await mkdir(jaHome, { recursive: true });
  const lines = [
    'future_root_toggle = "keep-root"',
    "schema_version = 2",
    "config_revision = 1",
    'default_access_mode = "approval_required"',
    `default_provider_id = "${providerId}"`,
    `default_model_id = "${primaryModelId}"`,
    'default_reasoning_level = "high"',
    "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
    "mcp_servers = []",
    "disabled_skills = []",
    "",
    "[[providers]]",
    `provider_id = "${providerId}"`,
    'name = "Desktop Model Settings Fixture"',
    'api = "openai_responses"',
    'base_url = "http://127.0.0.1:9/v1"',
    'credential_id = "cred_desktop_model_settings"',
    'future_provider_toggle = "keep-provider"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 1000",
    "request_timeout_ms = 1000",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[[providers.models]]",
    `model_id = "${primaryModelId}"`,
    'name = "Primary"',
    'model = "upstream-primary"',
    'future_model_toggle = "keep-model"',
    'reasoning_level_map = { high = "high" }',
    'default_reasoning_level = "high"',
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "[[providers.models]]",
    `model_id = "${secondaryModelId}"`,
    'name = "Secondary"',
    'model = "upstream-secondary"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 64000",
    "max_output_tokens = 4096",
    "",
    "[[providers]]",
    `provider_id = "${secondProviderId}"`,
    'name = "Second Fixture"',
    'api = "openai_chat_completions"',
    'base_url = "http://127.0.0.1:9/v1"',
    'credential_id = "cred_desktop_model_settings_second"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 1000",
    "request_timeout_ms = 1000",
    "[[providers.models]]",
    'model_id = "model_desktop_second_provider"',
    'name = "Second Provider Model"',
    'model = "upstream-second-provider"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 32000",
    "max_output_tokens = 2048",
    "",
    "[[providers]]",
    `provider_id = "${hiddenProviderId}"`,
    'future_hidden_toggle = "keep-hidden-provider"',
    "",
  ];
  const path = join(jaHome, "config.toml");
  await writeFile(path, `${lines.join("\n")}\n`, "utf8");
  return path;
}

/** 只为该 runner 写 Tauri overlay；隐藏且不聚焦主窗，页面仍由隔离 WebView2 CDP 驱动。 @author kongweiguang */
async function writeTauriConfig(directory, frontendPort) {
  const base = JSON.parse(
    await readFile(join(repoRoot, "apps", "desktop", "src-tauri", "tauri.conf.json"), "utf8"),
  );
  const windows = JSON.parse(
    await readFile(
      join(repoRoot, "apps", "desktop", "src-tauri", "tauri.windows.conf.json"),
      "utf8",
    ),
  );
  const origin = `http://127.0.0.1:${frontendPort}`;
  const config = {
    ...base,
    identifier: `io.github.kongweiguang.ja.e2e.desktopmodel${frontendPort}`,
    build: {
      ...base.build,
      beforeDevCommand: { ...base.build.beforeDevCommand, cwd: repoRoot },
      devUrl: origin,
    },
    app: {
      ...base.app,
      windows: windows.app.windows.map((window) => ({
        ...window,
        // WebView2 delays renderer creation for a never-shown window; park the test window off-screen
        // without taking focus or adding a taskbar button so it remains isolated from the user UI.
        visible: true,
        focus: false,
        skipTaskbar: true,
        x: -32_000,
        y: -32_000,
      })),
      security: {
        ...base.app.security,
        devCsp: base.app.security.devCsp
          .replaceAll("http://localhost:1420", origin)
          .replaceAll("ws://localhost:1420", `ws://127.0.0.1:${frontendPort}`),
      },
    },
  };
  const path = join(directory, "tauri.desktop-model-settings.e2e.json");
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 以仓库锁定的 Tauri CLI 从当前源码启动并构建 dev app，不直接运行可能陈旧的 target/debug exe。 @author kongweiguang */
function launchTauri(configPath, environment) {
  const tauriCli = join(repoRoot, "node_modules", "@tauri-apps", "cli", "tauri.js");
  return spawn(process.execPath, [tauriCli, "dev", "--no-watch", "--config", configPath], {
    cwd: repoRoot,
    env: environment,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
}

/** CDP 只接受本轮端口的 WebView2 endpoint，编译时间也纳入 bounded 冷启动期限。 @author kongweiguang */
async function waitForCdp(port, child) {
  const deadline = Date.now() + 600_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Tauri 提前退出 code=${child.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) {
        const payload = await response.json();
        const endpoint = new URL(payload.webSocketDebuggerUrl);
        if (!["127.0.0.1", "localhost"].includes(endpoint.hostname))
          throw new Error("WebView2 CDP 不是回环地址");
        return `http://127.0.0.1:${port}`;
      }
    } catch (error) {
      if (error instanceof Error && error.message.includes("不是回环")) throw error;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("当前源码构建的 WebView2 CDP 在 600 秒内未启动");
}

/** 新建 UDF 时 WebView2 可能忽略首轮调试参数，预热 profile 后再启动正式验收窗。 @author kongweiguang */
async function waitForWebViewProfile(webviewDirectory, child) {
  const deadline = Date.now() + 600_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`WebView2 预热提前退出 code=${child.exitCode}`);
    try {
      const profile = join(webviewDirectory, "EBWebView");
      const [localState, preferences] = await Promise.all([
        stat(join(profile, "Local State")),
        stat(join(profile, "Default", "Preferences")),
      ]);
      if (localState.size > 0 && preferences.size > 0) return;
    } catch {
      // WebView2 分阶段写 profile；只在期限内继续等待。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("WebView2 隔离 profile 预热超时");
}

/** 仅回收当前 runner 启动的进程树，不按可执行文件名枚举或结束其它 Ja 进程。 @author kongweiguang */
async function stopOwnedProcess(child) {
  if (child.exitCode !== null || child.pid === undefined) return;
  await new Promise((resolvePromise, reject) => {
    const killer = spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore",
    });
    killer.once("error", reject);
    killer.once("exit", resolvePromise);
  });
}

/** 仅接受 Tauri 关闭请求后的正常进程退出，保证 WebView 卸载与 runtime owner 清理先完成。 @author kongweiguang */
async function waitForProcessExit(child, timeoutMs = 60_000) {
  if (child.exitCode !== null) return { code: child.exitCode, signal: child.signalCode };
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => {
      child.off("exit", onExit);
      reject(new Error("isolated Tauri owner did not exit after its normal close request"));
    }, timeoutMs);
    const onExit = (code, signal) => {
      clearTimeout(timer);
      resolvePromise({ code, signal });
    };
    child.once("exit", onExit);
  });
}

/** 先探测监听状态再有界等待端口关闭；所有目标必须是本轮 endpoint 文件中的 loopback 端口。 @author kongweiguang */
async function isLoopbackPortListening(host, port) {
  return new Promise((resolvePromise, reject) => {
    const socket = createConnection({ host, port });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error("loopback endpoint probe timed out"));
    }, 1_000);
    socket.once("connect", () => {
      clearTimeout(timer);
      socket.destroy();
      resolvePromise(true);
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      if (["ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH"].includes(error.code))
        resolvePromise(false);
      else reject(new Error("loopback endpoint probe failed"));
    });
  });
}

/** 端口关闭是 App Server listener 已结束的外部事实；不枚举或终止其它进程。 @author kongweiguang */
async function waitForLoopbackPortClosed(endpoint, label) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (!(await isLoopbackPortListening(endpoint.host, endpoint.port))) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} loopback endpoint remained open after owner shutdown`);
}

/** 读取本轮运行目录的非敏感端点身份，不返回或打印 App Server 认证 token。 @author kongweiguang */
async function readOwnedEndpoint(runDirectory) {
  let raw;
  try {
    raw = JSON.parse(await readFile(join(runDirectory, "app-server.endpoint.json"), "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return undefined;
    throw error;
  }
  if (
    raw?.host !== "127.0.0.1" ||
    !Number.isInteger(raw.port) ||
    raw.port < 1 ||
    raw.port > 65_535 ||
    typeof raw.serverInstanceId !== "string"
  ) {
    throw new Error("isolated App Server endpoint identity is invalid");
  }
  return { host: raw.host, port: raw.port, serverInstanceId: raw.serverInstanceId };
}

/** 用已验证的 CLI server stop 关闭其隔离 App Server，并以端口与数据库 lease 双重回读确认释放。 @author kongweiguang */
async function stopCliOwner(artifacts, jaHome) {
  const runDirectory = join(jaHome, "run");
  const endpoint = await readOwnedEndpoint(runDirectory);
  if (endpoint !== undefined && (await isLoopbackPortListening(endpoint.host, endpoint.port))) {
    const environment = Object.fromEntries(
      Object.entries(process.env).filter(([key]) => !key.startsWith("JA_REAL_PROVIDER_")),
    );
    Object.assign(environment, {
      JA_HOME: jaHome,
      JA_DEBUG_JAVA: artifacts.java,
      JA_DEBUG_JAR: artifacts.jar,
    });
    await execFileAsync(artifacts.cli, ["server", "stop", "--force"], {
      cwd: repoRoot,
      env: environment,
      windowsHide: true,
      timeout: 30_000,
      maxBuffer: 256 * 1024,
    }).catch(() => {
      throw new Error("isolated CLI App Server stop command failed");
    });
    await waitForLoopbackPortClosed(endpoint, "CLI App Server");
  }
  return endpoint;
}

/** 通过设置页将当前隔离实例的关闭策略设为退出，再验证 renderer、owner、loopback 端口和数据库 lease 全部结束。 @author kongweiguang */
async function closeDesktopOwnerNormally(
  page,
  child,
  browser,
  ports,
  runtime,
  java,
  leaseProbe,
  jaHome,
  artifacts,
) {
  const endpoint = await readOwnedEndpoint(runtime);
  assert.ok(endpoint, "desktop App Server must publish its isolated loopback identity");
  const settings = page.locator(".ja-settings");
  await settings.getByRole("tab", { name: "通用", exact: true }).click();
  const closeBehavior = page.getByRole("combobox", { name: "关闭窗口时", exact: true });
  await closeBehavior.click();
  await page.getByRole("option", { name: "退出 Ja", exact: true }).click();
  await expect(closeBehavior).toContainText("退出 Ja");

  const pageClosed = page.waitForEvent("close", { timeout: 60_000 });
  await page.locator('.ja-window-controls [data-window-action="close"]').click();
  await pageClosed;
  const result = await waitForProcessExit(child);
  assert.equal(result.code, 0, "Tauri must complete its native exit path successfully");
  const stoppedOwner = await stopCliOwner(artifacts, jaHome);
  if (stoppedOwner !== undefined) {
    assert.equal(
      stoppedOwner.serverInstanceId,
      endpoint.serverInstanceId,
      "shutdown must target the desktop owner captured before the normal close",
    );
  }
  await waitForLoopbackPortClosed(endpoint, "desktop App Server");
  await browser.close();
  await Promise.all(ports.map(waitForPortAvailable));
  await assertDatabaseLeaseReleased(java, leaseProbe, jaHome, "desktop App Server");
  return { ownerExitedNormally: true, loopbackPortClosed: true, databaseLeaseReleased: true };
}

/** 等待 Vite 释放预热端口，防止第二个 Tauri 窗连接到已经退出的 dev server。 @author kongweiguang */
async function waitForPortAvailable(port) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const server = createServer();
    try {
      await new Promise((resolvePromise, reject) => {
        server.once("error", reject);
        server.listen(port, "127.0.0.1", resolvePromise);
      });
      await new Promise((resolvePromise, reject) =>
        server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
      );
      return;
    } catch {
      server.close();
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
    }
  }
  throw new Error("Vite 预热端口未释放");
}

/** 从隔离 CDP target 选择本轮 Tauri 的 Vite 页面，不接触已打开的用户窗口。 @author kongweiguang */
async function findTauriPage(browser, frontendPort) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        if (page.url().includes(`127.0.0.1:${frontendPort}`)) return page;
      }
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("本轮隔离 Ja 页面未完成初始导航");
}

/** 从桌面 adapter 读取纯配置字段，既不捕获凭据也不序列化整份含敏感投影的响应。 @author kongweiguang */
async function readDesktopSnapshot(page) {
  return page.evaluate(async () => {
    const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
    const loaded = await new TauriSettingsAdapter().snapshot();
    return {
      providerOrder: loaded.userDocument.providers.map((provider) => provider.providerId),
      providers: loaded.userDocument.providers.map((provider) => ({
        providerId: provider.providerId,
        name: provider.name,
        credentialConfigured: provider.credentialConfigured,
        modelOrder: provider.models.map((model) => model.modelId),
        models: provider.models.map((model) => ({
          modelId: model.modelId,
          name: model.name,
          model: model.model,
        })),
      })),
      defaultSelection: loaded.userDocument.defaultSelection,
      userVersion: loaded.cas.userVersion,
    };
  });
}

/** 使用 App Server credential/set CAS 写入隔离占位密钥，避免验收 runner 伪造 auth.json。 @author kongweiguang */
async function provisionFixtureCredential(java, jar, jaHome, directory) {
  const session = await startIndependentRpc(java, jar, jaHome, directory);
  try {
    const configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read before fixture credential",
    );
    rpcResult(
      await session.request("credential/set", {
        credentialId: "cred_desktop_model_settings",
        secret: fixtureCredentialSecret,
        expectedVersion: configuration.cas.credentialVersion,
      }),
      "credential/set fixture placeholder",
    );
    const readback = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after fixture credential",
    );
    assert.equal(readback.credentials.cred_desktop_model_settings?.configured, true);
    return true;
  } finally {
    const stopped = await session.shutdown();
    if (stopped.code !== 0) throw new Error("credential fixture App Server did not stop cleanly");
  }
}

/** 等待设置列表和 adapter 投影都收敛到同一版本，证明保存经过真实 React/IPC/App Server 链路。 @author kongweiguang */
async function waitForDesktopSnapshot(page, predicate, label) {
  let snapshot;
  await expect
    .poll(
      async () => {
        snapshot = await readDesktopSnapshot(page);
        return predicate(snapshot);
      },
      { timeout: 60_000, message: label },
    )
    .toBe(true);
  return snapshot;
}

/** 通过 UI 修改 Provider、Model、两级排序和默认选择，并逐项检查 renderer 权威回读。 @author kongweiguang */
async function verifyDesktopEdits(page, evidenceDirectory) {
  const settingsButton = page.getByRole("button", { name: "设置", exact: true });
  await expect(settingsButton).toBeEnabled();
  await settingsButton.click();
  const settings = page.locator(".ja-settings");
  await expect(settings).toBeVisible({ timeout: 60_000 });
  await settings.getByRole("tab", { name: "模型", exact: true }).click();
  await expect(settings.getByText("upstream-primary", { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await expect(settings.getByText("upstream-hidden", { exact: true })).toHaveCount(0);
  await expect(settings.getByText("keep-hidden-provider", { exact: true })).toHaveCount(0);
  await page.screenshot({
    path: join(evidenceDirectory, "desktop-model-settings-before.png"),
    animations: "disabled",
  });

  const providerSidebar = settings.locator('[aria-label="Provider 列表"]');
  const secondProviderChoice = providerSidebar
    .getByRole("button")
    .filter({ hasText: "Second Fixture" });
  await secondProviderChoice.click();
  const secondProviderCard = settings.locator(`[data-setting-id="provider-${secondProviderId}"]`);
  await secondProviderCard
    .getByRole("button", { name: "供应商 Second Fixture 更多操作", exact: true })
    .click();
  await page.getByRole("menuitem", { name: "上移", exact: true }).click();
  await waitForDesktopSnapshot(
    page,
    (value) => value.providerOrder[0] === secondProviderId,
    "Provider order readback",
  );

  await providerSidebar
    .getByRole("button")
    .filter({ hasText: "Desktop Model Settings Fixture" })
    .click();
  const primaryProviderCard = settings.locator(`[data-setting-id="provider-${providerId}"]`);
  const secondaryRow = primaryProviderCard.locator(
    `[data-setting-id="model-${providerId}-${secondaryModelId}"]`,
  );
  await secondaryRow.getByRole("button", { name: /更多操作/ }).click();
  await page.getByRole("menuitem", { name: "上移", exact: true }).click();
  await waitForDesktopSnapshot(
    page,
    (value) =>
      value.providers.find((provider) => provider.providerId === providerId)?.modelOrder[0] ===
      secondaryModelId,
    "Model order readback",
  );

  const primaryRow = primaryProviderCard.locator(
    `[data-setting-id="model-${providerId}-${primaryModelId}"]`,
  );
  await primaryRow.getByRole("button", { name: /编辑模型 upstream-primary/ }).click();
  const editor = page.getByRole("dialog", { name: "编辑供应商" });
  await editor.getByLabel("供应商名称", { exact: true }).fill("Desktop Edited Provider");
  await editor
    .locator(`[data-model-id="${primaryModelId}"]`)
    .getByLabel("上游模型标识")
    .fill("upstream-primary-desktop-edited");
  await page.screenshot({
    path: join(evidenceDirectory, "desktop-model-settings-editor.png"),
    animations: "disabled",
  });
  await editor.getByRole("button", { name: "保存更改", exact: true }).click();
  await expect(editor).toBeHidden({ timeout: 60_000 });
  let snapshot = await waitForDesktopSnapshot(
    page,
    (value) => {
      const edited = value.providers.find((provider) => provider.providerId === providerId);
      return (
        edited?.name === "Desktop Edited Provider" &&
        edited.models.find((model) => model.modelId === primaryModelId)?.model ===
          "upstream-primary-desktop-edited"
      );
    },
    "Provider and model edit readback",
  );

  const reorderedSecondary = settings.locator(
    `[data-setting-id="model-${providerId}-${secondaryModelId}"]`,
  );
  await reorderedSecondary.getByRole("button", { name: "设为默认", exact: true }).click();
  snapshot = await waitForDesktopSnapshot(
    page,
    (value) =>
      value.defaultSelection?.providerId === providerId &&
      value.defaultSelection?.modelId === secondaryModelId,
    "Default model readback",
  );
  await expect(settings.getByText("当前默认", { exact: true })).toHaveCount(1);
  await page.screenshot({
    path: join(evidenceDirectory, "desktop-model-settings-saved.png"),
    animations: "disabled",
  });
  return snapshot;
}

/** 新建独立 JA-RPC 会话并完成 v2 初始化；数据/runtime 目录独立，只有权威用户配置共享。 @author kongweiguang */
async function startIndependentRpc(java, jar, jaHome, directory) {
  const directories = {
    home: jaHome,
    data: join(directory, "rpc-data"),
    run: join(directory, "rpc-run"),
    logs: join(directory, "rpc-logs"),
    workspace: join(directory, "rpc-workspace"),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  const session = new JsonlSession({
    command: java,
    prefixArgs: ["-jar", jar],
    directories,
    apiKey: "local-only-no-provider-request",
    endpoint: "http://127.0.0.1/never-called",
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
      60_000,
    );
    return session;
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** 只呈现稳定 RPC 方法与错误码，不让自由文本或配置内容进入诊断输出。 @author kongweiguang */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = Number.isInteger(frame.error.code) ? frame.error.code : "unknown";
    throw new Error(`${operation} failed with RPC code ${code}`);
  }
  if (frame?.result === null || typeof frame?.result !== "object")
    throw new Error(`${operation} returned no result object`);
  return frame.result;
}

/** 使用另一条真实 JA-RPC 写入 Provider 与模型，再由现有桌面 Server/adapter 重新读取。 @author kongweiguang */
async function verifyIndependentRpcReadback(page, java, jar, jaHome, directory, evidenceDirectory) {
  const session = await startIndependentRpc(java, jar, jaHome, directory);
  try {
    let read = rpcResult(await session.request("configuration/read", {}), "configuration/read");
    const currentProvider = read.user.document.providers.find(
      (provider) => provider.provider_id === providerId,
    );
    assert.ok(currentProvider, "independent JA-RPC omitted the UI-created Provider");
    const expectedVersion = read.cas.userVersion;
    rpcResult(
      await session.request("configuration/patch", {
        scope: "user",
        expectedVersion,
        patch: {
          providers: {
            [providerId]: {
              name: "Independent RPC Updated Provider",
              models: { [primaryModelId]: { name: "RPC Updated Model Alias" } },
            },
          },
        },
      }),
      "configuration/patch from independent JA-RPC",
    );
    read = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read after RPC",
    );
    const provider = read.user.document.providers.find((entry) => entry.provider_id === providerId);
    assert.equal(provider?.name, "Independent RPC Updated Provider");
    assert.equal(
      provider?.models.find((model) => model.model_id === primaryModelId)?.name,
      "RPC Updated Model Alias",
    );

    const snapshot = await waitForDesktopSnapshot(
      page,
      (value) => {
        const updated = value.providers.find((entry) => entry.providerId === providerId);
        return (
          updated?.name === "Independent RPC Updated Provider" &&
          updated.models.find((model) => model.modelId === primaryModelId)?.name ===
            "RPC Updated Model Alias"
        );
      },
      "desktop adapter readback after independent JA-RPC patch",
    );
    const settings = page.locator(".ja-settings");
    await expect(
      settings.getByText("Independent RPC Updated Provider", { exact: true }),
    ).toHaveCount(2);
    await expect(settings.getByText("RPC Updated Model Alias", { exact: true })).toBeVisible();
    await page.screenshot({
      path: join(evidenceDirectory, "desktop-model-settings-after-independent-rpc.png"),
      animations: "disabled",
    });
    return snapshot;
  } finally {
    const stopped = await session.shutdown();
    if (stopped.code !== 0) throw new Error("independent JA-RPC App Server did not stop cleanly");
  }
}

/** 验证配置字段与未知 TOML 项在 UI sparse patch 后仍存在，隐藏 Provider 也不能被投影写回删除。 @author kongweiguang */
async function assertRawPreservation(configPath, expectedProviderName) {
  const raw = await readFile(configPath, "utf8");
  for (const marker of ["keep-root", "keep-provider", "keep-model", "keep-hidden-provider"]) {
    assert.ok(raw.includes(marker), `desktop settings save removed raw marker ${marker}`);
  }
  assert.ok(
    raw.includes(hiddenProviderId),
    "desktop settings save removed hidden Provider identity",
  );
  assert.ok(raw.includes(expectedProviderName));
}

/** 验收真实 Tauri/WebView2、用户层写入与跨端回读；退出及 owner 释放确认后才打印成功标记。 @author kongweiguang */
async function main() {
  if (process.platform !== "win32") throw new Error("桌面模型设置验收只支持 Windows");
  const javaHome = resolve(process.env.JA_DESKTOP_MODEL_SETTINGS_JAVA_HOME ?? defaultJavaHome);
  const java = join(javaHome, "bin", "java.exe");
  const cliPath = resolve(
    process.env.JA_DESKTOP_MODEL_SETTINGS_CLI ??
      join(repoRoot, "target", "cli-model-settings-ui", "debug", "ja.exe"),
  );
  const jarOverride =
    process.env.JA_DEBUG_JAR?.trim() || process.env.JA_DESKTOP_MODEL_SETTINGS_JAR?.trim();
  const jar = resolve(jarOverride || defaultJar);
  await readFile(java);
  await readFile(jar);
  const cliMetadata = await stat(cliPath);
  assert.ok(
    cliMetadata.isFile() && cliMetadata.size > 0,
    "frozen CLI acceptance binary is required",
  );
  const cliSha256 = createHash("sha256")
    .update(await readFile(cliPath))
    .digest("hex")
    .toUpperCase();
  const expectedCliSha256 =
    process.env.JA_DESKTOP_MODEL_SETTINGS_EXPECTED_CLI_SHA256?.trim().toUpperCase();
  if (expectedCliSha256 !== undefined && expectedCliSha256 !== "") {
    assert.match(expectedCliSha256, /^[0-9A-F]{64}$/u);
    assert.equal(
      cliSha256,
      expectedCliSha256,
      "CLI build does not match the requested frozen hash",
    );
  }
  const version = await execFileAsync(java, ["-version"], {
    windowsHide: true,
    maxBuffer: 64 * 1024,
  });
  assert.match(`${version.stdout}\n${version.stderr}`, /version\s+"25(?:\.|"|\s)/u);
  const userProfile = process.env.USERPROFILE;
  if (userProfile === undefined || userProfile.trim() === "")
    throw new Error("当前 Windows profile 缺少 Rustup/Cargo 工具链路径");

  const directory = await mkdtemp(join(tmpdir(), "ja-desktop-model-settings-"));
  const jaHome = join(directory, "ja-home");
  const webview = join(directory, "webview");
  const runtime = join(jaHome, "run");
  const data = join(directory, "data");
  const run = join(directory, "run");
  const logs = join(directory, "logs");
  const workspace = join(directory, "workspace");
  const cargoTarget = join(repoRoot, "target", "desktop-model-settings-acceptance");
  const evidenceDirectory = join(directory, "evidence");
  await Promise.all(
    [jaHome, webview, runtime, data, run, logs, workspace, evidenceDirectory, cargoTarget].map(
      (path) => mkdir(path, { recursive: true }),
    ),
  );
  const configPath = await writeFixture(jaHome);
  await provisionFixtureCredential(java, jar, jaHome, directory);
  const leaseProbe = await compileDatabaseLeaseProbe(join(javaHome, "bin", "javac.exe"), directory);
  const frontendPort = await reservePort();
  const cdpPort = await reservePort();
  const overlay = await writeTauriConfig(directory, frontendPort);
  const environment = {
    ...process.env,
    APPDATA: join(directory, "appdata"),
    LOCALAPPDATA: join(directory, "local"),
    USERPROFILE: join(directory, "user-profile"),
    HOME: join(directory, "user-profile"),
    JA_HOME: jaHome,
    JA_E2E_DEV_PORT: String(frontendPort),
    JA_E2E_RUNTIME_ROOT: runtime,
    JA_E2E_EXIT_TRACE_PATH: join(runtime, "exit-trace.jsonl"),
    VITE_JA_E2E_PROJECT_PATH: workspace,
    JA_DEBUG_JAVA: java,
    JA_DEBUG_JAR: jar,
    CARGO_TARGET_DIR: cargoTarget,
    TAURI_DEV_HOST: "127.0.0.1",
    RUSTUP_HOME: process.env.RUSTUP_HOME ?? join(userProfile, ".rustup"),
    CARGO_HOME: process.env.CARGO_HOME ?? join(userProfile, ".cargo"),
    WEBVIEW2_USER_DATA_FOLDER: webview,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:
      "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection " +
      `--remote-debugging-address=127.0.0.1 --remote-debugging-port=${cdpPort}`,
    JAVA_HOME: javaHome,
    PATH: `${join(javaHome, "bin")};${process.env.PATH ?? ""}`,
  };
  await mkdir(environment.APPDATA, { recursive: true });
  await mkdir(environment.LOCALAPPDATA, { recursive: true });
  await mkdir(environment.USERPROFILE, { recursive: true });
  const primeEnvironment = { ...environment };
  delete primeEnvironment.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;
  const prime = launchTauri(overlay, primeEnvironment);
  try {
    await waitForWebViewProfile(webview, prime);
  } finally {
    await stopOwnedProcess(prime);
  }
  await waitForPortAvailable(frontendPort);

  let child = launchTauri(overlay, environment);
  let browser;
  let page;
  let endpoint;
  let phase = "initial_desktop_startup";
  const diagnostics = [];
  const consoleErrors = [];
  const watchChild = (processChild) => {
    processChild.stdout?.on("data", (chunk) => diagnostics.push(String(chunk).slice(-2_000)));
    processChild.stderr?.on("data", (chunk) => diagnostics.push(String(chunk).slice(-2_000)));
  };
  const watchPage = (page) => {
    page.setDefaultTimeout(20_000);
    page.on("pageerror", (error) => consoleErrors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
  };
  watchChild(child);
  try {
    endpoint = await waitForCdp(cdpPort, child);
    browser = await chromium.connectOverCDP(endpoint);
    page = await findTauriPage(browser, frontendPort);
    watchPage(page);
    assert.ok(page.url().includes(`127.0.0.1:${frontendPort}`));
    assert.equal(await page.title(), "Ja · Coding Harness");
    await page.screenshot({
      path: join(evidenceDirectory, "desktop-model-settings-startup.png"),
      animations: "disabled",
    });
    try {
      await expect(page.getByRole("button", { name: "设置", exact: true })).toBeVisible({
        timeout: 60_000,
      });
    } catch (error) {
      const bodyText = (await page.locator("body").innerText()).slice(0, 1_000);
      throw new Error(
        `desktop first screen did not render: title=${await page.title()} body=${JSON.stringify(bodyText)} console=${consoleErrors.slice(-12).join(" | ")} cause=${String(error)}`,
      );
    }
    const before = await readDesktopSnapshot(page);
    assert.deepEqual(before.providerOrder, [providerId, secondProviderId]);
    assert.equal(before.defaultSelection?.modelId, primaryModelId);
    assert.equal(
      before.providers.find((provider) => provider.providerId === providerId)?.credentialConfigured,
      true,
      "fixture credential must be configured before opening the CLI editor",
    );
    assert.equal(
      before.providers.some((provider) => provider.providerId === hiddenProviderId),
      false,
    );

    phase = "desktop_settings_edit";
    const afterDesktopEdits = await verifyDesktopEdits(page, evidenceDirectory);
    assert.deepEqual(afterDesktopEdits.providerOrder, [secondProviderId, providerId]);
    const editedProvider = afterDesktopEdits.providers.find(
      (provider) => provider.providerId === providerId,
    );
    assert.equal(editedProvider?.name, "Desktop Edited Provider");
    assert.deepEqual(editedProvider?.modelOrder, [secondaryModelId, primaryModelId]);
    assert.equal(afterDesktopEdits.defaultSelection?.modelId, secondaryModelId);

    // The CLI helper edits the first Provider; restore the tested sort order so that row is the configured fixture.
    const settings = page.locator(".ja-settings");
    const providerSidebar = settings.locator('[aria-label="Provider 列表"]');
    await providerSidebar
      .getByRole("button")
      .filter({ hasText: "Desktop Edited Provider" })
      .click();
    const primaryProviderCard = settings.locator(`[data-setting-id="provider-${providerId}"]`);
    await primaryProviderCard
      .getByRole("button", { name: "供应商 Desktop Edited Provider 更多操作", exact: true })
      .click();
    await page.getByRole("menuitem", { name: "上移", exact: true }).click();
    const beforeCli = await waitForDesktopSnapshot(
      page,
      (value) => value.providerOrder[0] === providerId,
      "restore fixture Provider as the first CLI setup row",
    );
    assert.deepEqual(beforeCli.providerOrder, [providerId, secondProviderId]);

    phase = "first_desktop_shutdown";
    const firstDesktopShutdown = await closeDesktopOwnerNormally(
      page,
      child,
      browser,
      [frontendPort, cdpPort],
      runtime,
      java,
      leaseProbe,
      jaHome,
      { java, jar, cli: cliPath },
    );
    assert.deepEqual(firstDesktopShutdown, {
      ownerExitedNormally: true,
      loopbackPortClosed: true,
      databaseLeaseReleased: true,
    });
    browser = undefined;

    // CLI uses the same JA_HOME after the desktop owner has released its SQLite lease.
    phase = "cli_settings_edit";
    const cliEdit = await verifyConfiguredProviderNameEdit(
      {
        java,
        javac: join(javaHome, "bin", "javac.exe"),
        jarTool: join(javaHome, "bin", "jar.exe"),
        jar,
        cli: cliPath,
      },
      { root: directory, home: jaHome, workspace },
      "Desktop Edited Provider",
      "CLI Updated Provider",
      fixtureCredentialSecret,
    );
    assert.deepEqual(cliEdit, {
      providerNameChanged: true,
      credentialFieldUntouched: true,
      secretAbsentFromScrollback: true,
      shellRestored: true,
    });
    phase = "cli_owner_shutdown";
    const cliEndpoint = await stopCliOwner({ java, jar, cli: cliPath }, jaHome);
    if (cliEndpoint !== undefined) await waitForLoopbackPortClosed(cliEndpoint, "CLI App Server");
    await assertDatabaseLeaseReleased(java, leaseProbe, jaHome, "CLI App Server");
    await assertRawPreservation(configPath, "CLI Updated Provider");

    // Restart a fresh desktop owner only after CLI shutdown, then read the saved CLI patch in the real settings UI.
    phase = "desktop_restart";
    await Promise.all([frontendPort, cdpPort].map(waitForPortAvailable));
    child = launchTauri(overlay, environment);
    watchChild(child);
    endpoint = await waitForCdp(cdpPort, child);
    browser = await chromium.connectOverCDP(endpoint);
    page = await findTauriPage(browser, frontendPort);
    watchPage(page);
    assert.equal(await page.title(), "Ja · Coding Harness");
    await expect(page.getByRole("button", { name: "设置", exact: true })).toBeVisible({
      timeout: 60_000,
    });
    phase = "restart_open_settings";
    await page.getByRole("button", { name: "设置", exact: true }).click();
    const refreshedSettings = page.locator(".ja-settings");
    await expect(refreshedSettings).toBeVisible({ timeout: 60_000 });
    await refreshedSettings.getByRole("tab", { name: "模型", exact: true }).click();
    const afterCliReadback = await waitForDesktopSnapshot(
      page,
      (value) =>
        value.providers.find((provider) => provider.providerId === providerId)?.name ===
        "CLI Updated Provider",
      "desktop adapter readback after real CLI configuration patch",
    );
    assert.deepEqual(afterCliReadback.providerOrder, [providerId, secondProviderId]);
    assert.equal(
      afterCliReadback.providers.find((provider) => provider.providerId === providerId)
        ?.credentialConfigured,
      true,
      "CLI provider edit must preserve the configured credential",
    );
    await expect(refreshedSettings.getByText("CLI Updated Provider", { exact: true })).toHaveCount(
      2,
    );
    await expect(
      refreshedSettings.getByText("Desktop Edited Provider", { exact: true }),
    ).toHaveCount(0);
    await page.screenshot({
      path: join(evidenceDirectory, "desktop-model-settings-after-cli.png"),
      animations: "disabled",
    });

    phase = "independent_rpc_readback";
    const afterRpc = await verifyIndependentRpcReadback(
      page,
      java,
      jar,
      jaHome,
      directory,
      evidenceDirectory,
    );
    await assertRawPreservation(configPath, "Independent RPC Updated Provider");
    assert.deepEqual(afterRpc.providerOrder, [providerId, secondProviderId]);
    assert.equal(afterRpc.defaultSelection?.modelId, secondaryModelId);
    assert.deepEqual(
      consoleErrors,
      [],
      `renderer console reported errors: ${consoleErrors.join(" | ")}`,
    );

    phase = "final_desktop_shutdown";
    const finalDesktopShutdown = await closeDesktopOwnerNormally(
      page,
      child,
      browser,
      [frontendPort, cdpPort],
      runtime,
      java,
      leaseProbe,
      jaHome,
      { java, jar, cli: cliPath },
    );
    assert.deepEqual(finalDesktopShutdown, {
      ownerExitedNormally: true,
      loopbackPortClosed: true,
      databaseLeaseReleased: true,
    });
    browser = undefined;
    process.stdout.write(
      `JA_DESKTOP_MODEL_SETTINGS_PASS evidence=${evidenceDirectory} cdp=${endpoint} source=tauri-dev jar=${jar} cliSha256=${cliSha256} cliExpectedSha256=${expectedCliSha256 || "not-set"}\n`,
    );
  } catch (error) {
    // 失败页只能来自本轮隔离实例；掩住密码控件并保留阶段/控制台，避免重启偶发失败失去证据。
    let body = "unavailable";
    if (page !== undefined && !page.isClosed()) {
      body = await page
        .locator("body")
        .innerText()
        .catch(() => "unavailable");
      await page
        .screenshot({
          path: join(evidenceDirectory, "desktop-model-settings-failure.png"),
          animations: "disabled",
          mask: [page.locator('input[type="password"]')],
        })
        .catch(() => undefined);
    }
    const failureEvidence = {
      phase,
      body: body.replaceAll(fixtureCredentialSecret, "[已隐藏]").slice(0, 2_000),
      consoleErrors: consoleErrors
        .slice(-12)
        .map((value) => value.replaceAll(fixtureCredentialSecret, "[已隐藏]").slice(0, 1_000)),
    };
    await writeFile(
      join(evidenceDirectory, "desktop-model-settings-failure.json"),
      `${JSON.stringify(failureEvidence, null, 2)}\n`,
      "utf8",
    ).catch(() => undefined);
    throw new Error(
      `phase=${phase} ${(error instanceof Error ? error.message : String(error)).replaceAll(fixtureCredentialSecret, "[已隐藏]")} evidence=${evidenceDirectory} console=${failureEvidence.consoleErrors.join(" | ")} diagnostics=${diagnostics.join(" ").replaceAll(fixtureCredentialSecret, "[已隐藏]").slice(-6_000)}`,
    );
  } finally {
    if (browser !== undefined) await browser.close().catch(() => undefined);
    await stopOwnedProcess(child);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(
      `Desktop model settings acceptance failed: ${String(error?.message ?? error).slice(0, 6_000)}\n`,
    );
    process.exitCode = 1;
  });
}
