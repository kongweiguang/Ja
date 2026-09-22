// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { execFile, spawn } from "node:child_process";
import { mkdtemp, mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { createServer as createHttpServer } from "node:http";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { chromium, expect } from "@playwright/test";

const root = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const javaHome = process.env.JA_E2E_JAVA_HOME?.trim() || "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const java = join(javaHome, "bin", "java.exe");
const jar = resolve(root, "app-server", "target", "ja-app-server.jar");
const execFileAsync = promisify(execFile);

/** 仅预留回环端口，避免测试连接到用户进程或其它外部服务。 */
async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  server.close();
  if (address === null || typeof address === "string") throw new Error("无法预留回环端口");
  return address.port;
}

/**
 * 给隔离 App Server 写入仅含 loopback Provider 的最小配置和受限凭据文件。
 *
 * Windows CredentialStore 会拒绝继承了宽松 ACL 的 auth.json；夹具显式收紧为当前账户独占，
 * 以真实覆盖凭据读取边界，同时不接触用户的任何配置或外部 Provider。
 */
async function writeSettings(home, catalogBaseUrl) {
  const jaHome = join(home, ".ja");
  const authPath = join(jaHome, "auth.json");
  await mkdir(jaHome, { recursive: true });
  await writeFile(
    join(jaHome, "config.toml"),
    [
      "schema_version = 2",
      "config_revision = 1",
      'default_access_mode = "approval_required"',
      'default_provider_id = "provider_models_e2e"',
      'default_model_id = "model_primary"',
      "default_reasoning_level = { __ja_null = true }",
      "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
      "mcp_servers = []",
      "skills = []",
      "",
      "[[providers]]",
      'provider_id = "provider_models_e2e"',
      'name = "Models Overview Fixture"',
      'api = "openai_responses"',
      `base_url = "${catalogBaseUrl}/v1"`,
      'credential_id = "cred_models_e2e"',
      "[providers.network_timeouts]",
      "connect_timeout_ms = 1000",
      "request_timeout_ms = 1000",
      "[providers.agent_defaults]",
      "[providers.agent_defaults.context]",
      "auto_compact = true",
      "[providers.agent_defaults.turn_limits]",
      "max_model_rounds = 1",
      "max_tool_calls = 1",
      "wall_timeout_ms = 1000",
      "[[providers.models]]",
      'model_id = "model_primary"',
      'name = "Primary"',
      'model = "gpt-5.6-sol"',
      'reasoning_level_map = { high = "high" }',
      'default_reasoning_level = "high"',
      "[providers.models.capabilities]",
      "context_window_tokens = 256000",
      "max_output_tokens = 32000",
      "[[providers.models]]",
      'model_id = "model_secondary"',
      'name = "Secondary"',
      'model = "gpt-5.6-mini"',
      "reasoning_level_map = {}",
      "default_reasoning_level = { __ja_null = true }",
      "[providers.models.capabilities]",
      "context_window_tokens = 128000",
      "max_output_tokens = 8192",
      "",
    ].join("\n"),
    "utf8",
  );
  await writeFile(authPath, '{"cred_models_e2e":"loopback-only"}\n', "utf8");
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("无法解析 Windows 当前账户以设置凭据 ACL");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 512 * 1024,
  });
  return jaHome;
}

/** 写入可读取但不可投影的用户配置，让真窗仅通过公开恢复流程重建该临时文件。 */
async function writeCorruptSettings(home) {
  const jaHome = join(home, ".ja");
  await mkdir(jaHome, { recursive: true });
  await writeFile(join(jaHome, "config.toml"), "schema_version = 1\nproviders = [\n", "utf8");
  return jaHome;
}

/** 提供只属于本轮的 `/v1/models` 回环响应，验证真窗按钮不会触碰用户 Provider 或外部网络。 */
async function startModelCatalogFixture() {
  const requests = [];
  const server = createHttpServer((request, response) => {
    requests.push({
      method: request.method,
      path: request.url,
      authorization: request.headers.authorization,
    });
    if (request.method !== "GET" || request.url !== "/v1/models") {
      response.writeHead(404).end();
      return;
    }
    response
      .writeHead(200, { "content-type": "application/json" })
      .end(JSON.stringify({ data: [{ id: "gpt-5.6-sol" }, { id: "gpt-e2e-catalog" }] }));
  });
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (address === null || typeof address === "string")
    throw new Error("模型目录 fixture 未取得回环端口");
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    requests,
    close: () =>
      new Promise((resolvePromise, reject) =>
        server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
      ),
  };
}

/** 只生成临时 Tauri overlay，身份、配置、WebView UDF 和端口均属于本轮实例。 */
async function writeTauriConfig(directory, frontendPort) {
  const base = JSON.parse(await readFile(join(root, "src-tauri", "tauri.conf.json"), "utf8"));
  const windows = JSON.parse(
    await readFile(join(root, "src-tauri", "tauri.windows.conf.json"), "utf8"),
  );
  const origin = `http://127.0.0.1:${frontendPort}`;
  const websocket = `ws://127.0.0.1:${frontendPort}`;
  const config = {
    ...base,
    identifier: `io.github.kongweiguang.ja.e2e.models${frontendPort}`,
    build: { beforeDevCommand: "pnpm dev", devUrl: origin },
    app: {
      ...base.app,
      windows: windows.app.windows,
      security: {
        ...base.app.security,
        devCsp: base.app.security.devCsp
          .replaceAll("http://localhost:1420", origin)
          .replaceAll("ws://localhost:1420", websocket),
      },
    },
  };
  const path = join(directory, "tauri.e2e.conf.json");
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 等待本轮 WebView2 自己发布 CDP `/json/version`，并拒绝非回环地址。 */
async function waitForCdp(port, child) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Tauri 提前退出 code=${child.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) {
        const version = await response.json();
        const endpoint = new URL(version.webSocketDebuggerUrl);
        if (endpoint.hostname !== "127.0.0.1" && endpoint.hostname !== "localhost") {
          throw new Error("WebView2 CDP 不是回环地址");
        }
        return `http://127.0.0.1:${port}`;
      }
    } catch (error) {
      if (error instanceof Error && error.message.includes("不是回环")) throw error;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("隔离 WebView2 CDP 在 120 秒内未启动");
}

/** 首次 WebView2 启动先完成 UDF 落盘，避免 CDP 参数在 profile 初始化期间被忽略。 */
async function waitForWebViewProfileReady(webview, child) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`WebView2 预热提前退出 code=${child.exitCode}`);
    try {
      const [localState, preferences] = await Promise.all([
        stat(join(webview, "EBWebView", "Local State")),
        stat(join(webview, "EBWebView", "Default", "Preferences")),
      ]);
      if (
        localState.isFile() &&
        localState.size > 0 &&
        preferences.isFile() &&
        preferences.size > 0
      )
        return;
    } catch {
      // UDF 文件分阶段创建，期限内继续观察即可。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("WebView2 profile 预热超时");
}

/** 预热进程被清理后确认 Vite 端口已释放，避免正式窗口连接到即将退出的服务。 */
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
  throw new Error(`Vite 端口 ${port} 未在预热后释放`);
}

/** 直接运行仓库锁定的 Tauri CLI，绕开 cmd 对隔离配置路径和环境变量的二次解析。 */
function launchTauri(configPath, environment) {
  const tauriCli = join(root, "node_modules", "@tauri-apps", "cli", "tauri.js");
  return spawn(process.execPath, [tauriCli, "dev", "--no-watch", "--config", configPath], {
    cwd: root,
    env: environment,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
}

/** 只选择已完成初始导航的 Ja 页面，避免 CDP 连接后误操作临时空白页。 */
async function findTauriPage(browser, frontendPort) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        const url = page.url();
        if (
          url.includes(`127.0.0.1:${frontendPort}`) ||
          url.includes(`localhost:${frontendPort}`)
        ) {
          return page;
        }
      }
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("隔离 Ja 页面未完成初始导航");
}

/** 在真实 WebView2 中验证模型列表的菜单、确认和焦点闭环，并保存浅/深/窄屏截图。 */
async function verify(page, evidence, catalog) {
  const openSettings = page.getByRole("button", { name: "设置", exact: true });
  await expect(openSettings).toBeEnabled();
  await openSettings.click();
  await page
    .getByRole("region", { name: "设置页面", exact: true })
    .waitFor({ state: "visible", timeout: 60_000 });
  const settings = page.locator(".ja-settings");
  await settings.waitFor({ state: "visible", timeout: 60_000 });
  await settings.getByRole("tab", { name: "模型", exact: true }).click();
  // 设置页先渲染壳层再异步读取 App Server 配置；这里等待权威模型投影，
  // 避免把 Windows 冷启动期间的短暂空态误判为模型目录回归。
  try {
    await expect(settings.getByText("gpt-5.6-sol", { exact: true })).toBeVisible({
      timeout: 60_000,
    });
  } catch (error) {
    // 真实启动失败时保留渲染态，避免 stdout/stderr 只有宿主进程信息而丢失用户可见错误。
    await page.screenshot({
      path: join(evidence, "models-load-failed.png"),
      animations: "disabled",
    });
    throw error;
  }
  await expect(settings.getByText("gpt-5.6-mini", { exact: true })).toBeVisible({
    timeout: 60_000,
  });
  await expect(settings.getByText("当前默认", { exact: true })).toHaveCount(1);
  await expect(settings.getByText("上下文 128,000 tokens", { exact: true })).toBeVisible();
  await page.screenshot({ path: join(evidence, "models-light-wide.png"), animations: "disabled" });

  const primary = settings.locator("article.ja-models-row").filter({ hasText: "gpt-5.6-sol" });
  const secondary = settings.locator("article.ja-models-row").filter({ hasText: "gpt-5.6-mini" });
  const more = secondary.getByRole("button", { name: /更多操作/ });
  await more.click();
  await expect(page.getByRole("menuitem", { name: "上移", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(more).toBeFocused();

  await more.click();
  await page.getByRole("menuitem", { name: "删除", exact: true }).click();
  const confirmation = page.getByRole("alertdialog", { name: "删除 Secondary？" });
  await confirmation.getByRole("button", { name: "取消", exact: true }).click();
  await expect(confirmation).toBeHidden();
  await expect(more).toBeFocused();

  await primary.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/ }).click();
  const sheet = page.getByRole("dialog", { name: "编辑供应商" });
  await expect(sheet.getByLabel("上游模型标识").first()).toBeFocused();
  await sheet.getByLabel("API Key", { exact: true }).fill("fixture-only");
  await sheet.getByRole("button", { name: "保存更改", exact: true }).click();
  await expect(sheet).toBeHidden();

  await primary.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/ }).click();
  const reopenedSheet = page.getByRole("dialog", { name: "编辑供应商" });
  const modelActions = reopenedSheet.getByRole("group", { name: "模型操作", exact: true });
  await expect(modelActions.getByRole("button")).toHaveText(["从上游获取", "手动添加"]);
  const discover = modelActions.getByRole("button", { name: "从上游获取", exact: true });
  await expect(discover).toBeEnabled();
  await discover.click();
  await expect(reopenedSheet.getByRole("status")).toContainText(
    "已添加 1 个上游模型，保存后生效。",
  );
  await expect(reopenedSheet.getByLabel("上游模型标识")).toHaveCount(3);
  await expect
    .poll(() => catalog.requests)
    .toEqual([{ method: "GET", path: "/v1/models", authorization: "Bearer fixture-only" }]);
  await page.screenshot({
    path: join(evidence, "models-light-editor.png"),
    animations: "disabled",
  });
  await page.setViewportSize({ width: 600, height: 720 });
  await expect
    .poll(() => reopenedSheet.evaluate((element) => element.scrollWidth <= element.clientWidth))
    .toBe(true);
  await page.screenshot({
    path: join(evidence, "models-light-editor-narrow.png"),
    animations: "disabled",
  });
  await page.keyboard.press("Escape");
  await expect(primary.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/ })).toBeFocused();
  await expect(settings.getByText("gpt-e2e-catalog", { exact: true })).toHaveCount(0);

  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.screenshot({ path: join(evidence, "models-dark-wide.png"), animations: "disabled" });
  await page.setViewportSize({ width: 720, height: 640 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await expect
    .poll(() => settings.evaluate((element) => element.scrollWidth <= element.clientWidth))
    .toBe(true);
  await page.screenshot({
    path: join(evidence, "models-light-narrow.png"),
    animations: "disabled",
  });
}

/**
 * 恢复验收只通过用户可见入口新建 Provider 并保存，证明损坏文件没有把应用锁在错误页，
 * 且不会借助测试进程直接改写配置。回环目录和凭据均属于本次临时 profile。
 */
async function verifyConfigurationRecovery(page, evidence, catalog) {
  const openSettings = page.getByRole("button", { name: "设置", exact: true });
  await expect(openSettings).toBeEnabled();
  await openSettings.click();
  const settings = page.locator(".ja-settings");
  await settings.waitFor({ state: "visible", timeout: 60_000 });
  await expect(page.getByRole("status", { name: "配置恢复模式" })).toContainText("原文件尚未修改");
  await expect(page.getByRole("heading", { name: "设置暂时不可用" })).toHaveCount(0);
  await page.getByRole("button", { name: "配置服务商", exact: true }).click();
  await expect(settings.getByRole("tab", { name: "模型", exact: true })).toHaveAttribute(
    "data-state",
    "active",
  );
  await settings.getByRole("button", { name: "新增供应商", exact: true }).click();
  const sheet = page.getByRole("dialog", { name: "新增供应商" });
  await sheet.getByLabel("供应商名称", { exact: true }).fill("Recovery Fixture");
  await sheet.getByLabel("Base URL", { exact: true }).fill(`${catalog.baseUrl}/v1`);
  await sheet.getByLabel("API Key", { exact: true }).fill("fixture-only");
  await sheet.getByLabel("上游模型标识").first().fill("gpt-5.6-sol");
  await sheet.getByRole("button", { name: "保存供应商", exact: true }).click();
  await expect(sheet).toBeHidden();
  await expect(settings.getByText("gpt-5.6-sol", { exact: true })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole("status", { name: "配置恢复模式" })).toHaveCount(0);
  await page.screenshot({
    path: join(evidence, "configuration-recovered.png"),
    animations: "disabled",
  });
}

/** 本轮只清理自己创建的 cmd 进程树，永不按名称结束用户已有 Ja。 */
async function stopOwnedProcess(child) {
  if (child.exitCode !== null || child.pid === undefined) return;
  await new Promise((resolvePromise) => {
    const killer = spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore",
    });
    killer.once("exit", resolvePromise);
  });
}

/** 将 profile、App Server、loopback Provider 和 CDP 统一限定到本轮临时根，避免验收触碰用户数据。 */
async function main() {
  if (process.platform !== "win32" || !isAbsolute(javaHome))
    throw new Error("仅支持 Windows JDK25 真窗");
  await readFile(java);
  await readFile(jar);
  const hostUserProfile = process.env.USERPROFILE;
  if (hostUserProfile === undefined || hostUserProfile.trim() === "") {
    throw new Error("真窗验收需要宿主 Rustup profile");
  }
  const catalog = await startModelCatalogFixture();
  try {
    const recoveryMode = process.env.JA_E2E_CONFIG_RECOVERY === "1";
    const directory = await mkdtemp(join(tmpdir(), "ja-models-overview-"));
    const home = join(directory, "home");
    const webview = join(directory, "webview");
    const runtime = join(directory, "runtime");
    const workspace = join(directory, "workspace");
    await Promise.all([mkdir(home), mkdir(webview), mkdir(runtime), mkdir(workspace)]);
    if (recoveryMode) await writeCorruptSettings(home);
    else await writeSettings(home, catalog.baseUrl);
    const frontendPort = await reservePort();
    const cdpPort = await reservePort();
    const configPath = await writeTauriConfig(directory, frontendPort);
    const evidence = join(directory, "evidence");
    await mkdir(evidence);
    const env = {
      ...process.env,
      APPDATA: join(directory, "appdata"),
      LOCALAPPDATA: join(directory, "local"),
      USERPROFILE: home,
      HOME: home,
      JA_E2E_DEV_PORT: String(frontendPort),
      VITE_JA_E2E_PROJECT_PATH: workspace,
      JA_E2E_RUNTIME_ROOT: runtime,
      JA_E2E_EXIT_TRACE_PATH: join(runtime, "exit-trace.jsonl"),
      JA_DEBUG_JAVA: java,
      JA_DEBUG_JAR: jar,
      // Tauri 构建复用仓库 target，避免真窗验收因重复编译而超时；临时 USERPROFILE 只隔离应用配置。
      RUSTUP_HOME: process.env.RUSTUP_HOME ?? join(hostUserProfile, ".rustup"),
      CARGO_HOME: process.env.CARGO_HOME ?? join(hostUserProfile, ".cargo"),
      WEBVIEW2_USER_DATA_FOLDER: webview,
      WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS:
        "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection " +
        "--autoplay-policy=no-user-gesture-required " +
        `--remote-debugging-address=127.0.0.1 --remote-debugging-port=${cdpPort}`,
      JAVA_HOME: javaHome,
      PATH: `${join(javaHome, "bin")};${process.env.PATH ?? ""}`,
    };
    const primeEnvironment = { ...env };
    delete primeEnvironment.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;
    const prime = launchTauri(configPath, primeEnvironment);
    try {
      await waitForWebViewProfileReady(webview, prime);
    } finally {
      await stopOwnedProcess(prime);
    }
    await waitForPortAvailable(frontendPort);

    const child = launchTauri(configPath, env);
    const diagnostics = [];
    child.stdout?.on("data", (chunk) => diagnostics.push(String(chunk).slice(-2000)));
    child.stderr?.on("data", (chunk) => diagnostics.push(String(chunk).slice(-2000)));
    try {
      const endpoint = await waitForCdp(cdpPort, child);
      const browser = await chromium.connectOverCDP(endpoint);
      try {
        const page = await findTauriPage(browser, frontendPort);
        page.setDefaultTimeout(20_000);
        if (recoveryMode) await verifyConfigurationRecovery(page, evidence, catalog);
        else await verify(page, evidence, catalog);
        const marker = recoveryMode ? "JA_CONFIGURATION_RECOVERY_OK" : "JA_MODELS_OVERVIEW_OK";
        process.stdout.write(`${marker} evidence=${evidence} cdp=${endpoint}\n`);
      } finally {
        await browser.close();
      }
    } catch (error) {
      throw new Error(
        `${error instanceof Error ? error.message : String(error)} diagnostics=${diagnostics.join(" ")}`,
      );
    } finally {
      await stopOwnedProcess(child);
    }
  } finally {
    await catalog.close();
  }
}

await main();
