// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { spawn } from "node:child_process";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";

const root = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const javaHome = process.env.JA_E2E_JAVA_HOME?.trim() || "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const java = join(javaHome, "bin", "java.exe");
const jar = resolve(root, "app-server", "target", "ja-app-server.jar");

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

/** 给隔离 App Server 写入只含 loopback Provider 的最小配置，不发起任何付费请求。 */
async function writeSettings(home) {
  const jaHome = join(home, ".ja");
  await mkdir(jaHome, { recursive: true });
  await writeFile(
    join(jaHome, "config.toml"),
    [
      "schema_version = 1",
      "config_revision = 1",
      'default_access_mode = "approval_required"',
      'default_provider_id = "provider_models_e2e"',
      'default_model_id = "model_primary"',
      "default_reasoning_level = { __ja_null = true }",
      "mcp_servers = []",
      "skills = []",
      "",
      "[[providers]]",
      'provider_id = "provider_models_e2e"',
      'name = "Models Overview Fixture"',
      'api = "openai_responses"',
      'base_url = "http://127.0.0.1:9/v1"',
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
  await writeFile(join(jaHome, "auth.json"), '{"cred_models_e2e":"fixture-only"}\n', "utf8");
  return jaHome;
}

/** 只生成临时 Tauri overlay，身份、配置、WebView UDF 和端口均属于本轮实例。 */
async function writeTauriConfig(directory, frontendPort) {
  const base = JSON.parse(await readFile(join(root, "src-tauri", "tauri.conf.json"), "utf8"));
  const windows = JSON.parse(
    await readFile(join(root, "src-tauri", "tauri.windows.conf.json"), "utf8"),
  );
  const origin = `http://127.0.0.1:${frontendPort}`;
  const config = {
    ...base,
    identifier: `io.github.kongweiguang.ja.e2e.models${frontendPort}`,
    build: { beforeDevCommand: "pnpm dev", devUrl: origin },
    app: { ...base.app, windows: windows.app.windows, security: base.app.security },
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

/** 在真实 WebView2 中验证模型列表的菜单、确认和焦点闭环，并保存浅/深/窄屏截图。 */
async function verify(page, evidence) {
  await page.reload();
  const settings = page.locator(".ja-settings");
  await settings.waitFor({ state: "visible", timeout: 60_000 });
  await settings.getByRole("tab", { name: "模型", exact: true }).click();
  await expect(settings.getByText("gpt-5.6-sol", { exact: true })).toBeVisible();
  await expect(settings.getByText("gpt-5.6-mini", { exact: true })).toBeVisible();
  await expect(settings.getByText("当前默认", { exact: true })).toHaveCount(1);
  await expect(settings.getByText("上下文 128,000 tokens", { exact: true })).toBeVisible();
  await page.screenshot({ path: join(evidence, "models-light-wide.png"), animations: "disabled" });

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

  await secondary.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/ }).click();
  const sheet = page.getByRole("dialog", { name: "编辑供应商" });
  await expect(sheet.getByDisplayValue("gpt-5.6-sol")).toBeFocused();
  await page.screenshot({
    path: join(evidence, "models-light-editor.png"),
    animations: "disabled",
  });
  await page.keyboard.press("Escape");
  await expect(secondary.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/ })).toBeFocused();

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

async function main() {
  if (process.platform !== "win32" || !isAbsolute(javaHome))
    throw new Error("仅支持 Windows JDK25 真窗");
  await readFile(java);
  await readFile(jar);
  const directory = await mkdtemp(join(tmpdir(), "ja-models-overview-"));
  const home = join(directory, "home");
  const webview = join(directory, "webview");
  const runtime = join(directory, "runtime");
  const workspace = join(directory, "workspace");
  await Promise.all([mkdir(home), mkdir(webview), mkdir(runtime), mkdir(workspace)]);
  await writeSettings(home);
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
    WEBVIEW2_USER_DATA_FOLDER: webview,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${cdpPort}`,
    JAVA_HOME: javaHome,
    PATH: `${join(javaHome, "bin")};${process.env.PATH ?? ""}`,
  };
  const command = env.ComSpec ?? env.COMSPEC ?? "cmd.exe";
  const child = spawn(
    command,
    ["/d", "/s", "/c", `"pnpm.cmd tauri dev --no-watch --config "${configPath}""`],
    {
      cwd: root,
      env,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  const diagnostics = [];
  child.stdout?.on("data", (chunk) => diagnostics.push(String(chunk).slice(-2000)));
  child.stderr?.on("data", (chunk) => diagnostics.push(String(chunk).slice(-2000)));
  try {
    const endpoint = await waitForCdp(cdpPort, child);
    const browser = await chromium.connectOverCDP(endpoint);
    try {
      const page = browser.contexts().flatMap((context) => context.pages())[0];
      if (page === undefined) throw new Error("隔离 WebView2 页面缺失");
      page.setDefaultTimeout(20_000);
      await verify(page, evidence);
      process.stdout.write(`JA_MODELS_OVERVIEW_OK evidence=${evidence} cdp=${endpoint}\n`);
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
}

await main();
