// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 侧聊 Windows/WebView2 两阶段真窗 runner。
 *
 * 本 runner 为一次验收创建独立 profile、workspace、Vite 端口、WebView2 CDP
 * 端口和 Cargo target；initial driver 完成侧聊创建/运行/关闭并留下最小状态，
 * 随后在同一 profile 上重启 Tauri，由 restart driver 验证主会话和文件可恢复、
 * 已关闭侧聊不可恢复。进程清理只按本轮 launcher、专属二进制路径和专属
 * WebView2 profile 进行，不触碰其它 Ja 或用户进程。
 */

import { execFile, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:net";
import { createReadStream } from "node:fs";
import {
  access,
  copyFile,
  lstat,
  mkdtemp,
  mkdir,
  readdir,
  realpath,
  readFile,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, resolve, win32 } from "node:path";
import process from "node:process";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  readRuntimeEndpointMetadata,
  requestRunScopedRuntimeShutdown,
} from "./windows-desktop-smoke.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const sideChatScript = join(repoRoot, "scripts", "e2e", "side-chat-webview2.mjs");
const defaultTargetDirectory = join(repoRoot, "target", "side-chat");
const edgeDebugArguments =
  "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-address=127.0.0.1 --remote-debugging-port=";
const realModelDriver = join(repoRoot, "scripts", "e2e", "real-model-desktop-webview2.mjs");
const privateDesktopLauncher = join(repoRoot, "scripts", "e2e", "private-desktop-launcher.ps1");

/** 只接受明确的最终桌面 EXE、Native sidecar 与 backend mode，避免误把 JVM dev smoke 当作安装版证据。@author kongweiguang */
export function parseSideChatRunnerArguments(argumentsList) {
  const options = { backendMode: "jvm", scenario: "full" };
  const seen = new Set();
  for (let index = 0; index < argumentsList.length; index += 1) {
    const name = argumentsList[index];
    if (
      ![
        "--backend-mode",
        "--desktop-executable",
        "--native-executable",
        "--scenario",
        "--source-ja-home",
        "--run-root",
      ].includes(name)
    )
      throw new Error(`未知侧聊验收参数：${name}`);
    if (seen.has(name)) throw new Error(`侧聊验收参数重复：${name}`);
    seen.add(name);
    const value = argumentsList[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`侧聊验收参数缺少值：${name}`);
    index += 1;
    if (name === "--backend-mode") {
      if (!new Set(["jvm", "native"]).has(value)) throw new Error(`不支持的侧聊后端模式：${value}`);
      options.backendMode = value;
    } else if (name === "--scenario") {
      if (!new Set(["full", "approvals", "real-model", "real-model-smoke"]).has(value))
        throw new Error(`不支持的侧聊场景：${value}`);
      options.scenario = value;
    } else {
      if (!isAbsolute(value)) throw new Error(`${name} 必须使用绝对路径`);
      const optionName = {
        "--desktop-executable": "desktopExecutable",
        "--native-executable": "nativeExecutable",
        "--source-ja-home": "sourceJaHome",
        "--run-root": "runRoot",
      }[name];
      options[optionName] = resolve(value);
    }
  }
  const hasDesktop = options.desktopExecutable !== undefined;
  const hasNative = options.nativeExecutable !== undefined;
  if (options.backendMode === "native" && (!hasDesktop || !hasNative))
    throw new Error("Native 安装版验收必须同时提供 --desktop-executable 与 --native-executable");
  if (options.backendMode !== "native" && (hasDesktop || hasNative))
    throw new Error("最终桌面或 Native EXE 只能与 --backend-mode native 一起使用");
  if (options.scenario.startsWith("real-model")) {
    if (options.backendMode !== "native" || options.sourceJaHome === undefined || options.runRoot === undefined)
      throw new Error("真实模型验收需要 Native 安装版、--source-ja-home 与 --run-root");
  } else if (options.sourceJaHome !== undefined || options.runRoot !== undefined) {
    throw new Error("--source-ja-home 与 --run-root 仅用于真实模型桌面验收");
  }
  return options;
}

/** 只解析私有 Desktop launcher 的稳定安全标记，不把 launcher 其它 stdout 带入证据。@author kongweiguang */
export function parsePrivateDesktopMarker(line) {
  const prefix = "JA_PRIVATE_DESKTOP_STARTED ";
  if (typeof line !== "string" || !line.startsWith(prefix)) return undefined;
  let value;
  try {
    value = JSON.parse(line.slice(prefix.length));
  } catch {
    throw new Error("私有 Desktop launcher marker 无效");
  }
  if (
    !Number.isSafeInteger(value?.pid) ||
    value.pid < 1 ||
    typeof value.desktop !== "string" ||
    !/^JaE2E_[0-9a-f]{32}$/u.test(value.desktop) ||
    value.privateDesktop !== true ||
    value.inputDesktopDistinct !== true ||
    value.switchDesktopUsed !== false
  ) {
    throw new Error("私有 Desktop launcher 未证明隔离桌面启动");
  }
  return {
    pid: value.pid,
    desktop: value.desktop,
    privateDesktop: true,
    inputDesktopDistinct: true,
    switchDesktopUsed: false,
  };
}

/** 流式计算二进制 SHA-256，避免为了验收大型安装包把完整 sidecar 常驻内存。@author kongweiguang */
export async function sha256File(path) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest("hex");
}

/** 复制 Tauri 已安装包的 sidecar 资源树并拒绝链接节点，防止隔离 stage 跟随包外路径。@author kongweiguang */
async function copyResourceTree(source, destination) {
  const sourceInfo = await lstat(source);
  if (sourceInfo.isSymbolicLink())
    throw new Error(`Tauri sidecar resource 不允许链接：${basename(source)}`);
  if (sourceInfo.isDirectory()) {
    await mkdir(destination, { recursive: true });
    for (const name of (await readdir(source)).sort())
      await copyResourceTree(join(source, name), join(destination, name));
    return;
  }
  if (!sourceInfo.isFile())
    throw new Error(`Tauri sidecar resource 不是普通文件：${basename(source)}`);
  await mkdir(dirname(destination), { recursive: true });
  await copyFile(source, destination);
}

/** 复制生产 EXE 与资源到本轮私有资源根，仅替换受信任固定名称的 Native sidecar 并核对字节身份。@author kongweiguang */
export async function stageNativeDesktopPackage({
  desktopExecutable,
  nativeExecutable,
  packageRoot,
}) {
  const desktopSource = resolve(desktopExecutable);
  const nativeSource = resolve(nativeExecutable);
  const destinationRoot = resolve(packageRoot);
  if (!desktopSource.toLowerCase().endsWith(".exe") || !nativeSource.toLowerCase().endsWith(".exe"))
    throw new Error("Native 安装版验收需要 Windows .exe 输入");
  if (desktopSource === nativeSource || destinationRoot === dirname(desktopSource))
    throw new Error("隔离 stage 路径与生产二进制冲突");
  const [desktopInfo, nativeInfo] = await Promise.all([lstat(desktopSource), lstat(nativeSource)]);
  if (
    !desktopInfo.isFile() ||
    desktopInfo.isSymbolicLink() ||
    !nativeInfo.isFile() ||
    nativeInfo.isSymbolicLink()
  )
    throw new Error("Native 安装版验收二进制必须是普通文件");
  const sourceResources = join(dirname(desktopSource), "sidecars");
  const sourceResourceInfo = await lstat(sourceResources);
  if (sourceResourceInfo.isSymbolicLink() || !sourceResourceInfo.isDirectory())
    throw new Error("Tauri 生产资源必须是桌面 EXE 同级的 sidecars 目录");
  const sidecarDirectory = join(destinationRoot, "sidecars");
  const stagedDesktop = join(destinationRoot, basename(desktopSource));
  const stagedNative = join(sidecarDirectory, "ja-app-server-x86_64-pc-windows-msvc.exe");
  await mkdir(destinationRoot, { recursive: true });
  await copyFile(desktopSource, stagedDesktop);
  await copyResourceTree(sourceResources, sidecarDirectory);
  await copyFile(nativeSource, stagedNative);
  const [desktopSha256, stagedDesktopSha256, nativeSha256, stagedNativeSha256] = await Promise.all([
    sha256File(desktopSource),
    sha256File(stagedDesktop),
    sha256File(nativeSource),
    sha256File(stagedNative),
  ]);
  if (desktopSha256 !== stagedDesktopSha256 || nativeSha256 !== stagedNativeSha256)
    throw new Error("隔离 stage 的桌面或 Native 二进制 SHA-256 不匹配");
  return {
    executable: stagedDesktop,
    packageRoot: destinationRoot,
    sidecarResourcePath: "sidecars",
    sidecar: stagedNative,
    desktop: {
      fileName: basename(desktopSource),
      sizeBytes: desktopInfo.size,
      sha256: desktopSha256,
    },
    native: { fileName: basename(nativeSource), sizeBytes: nativeInfo.size, sha256: nativeSha256 },
  };
}

/** 从非动态端口区申请一个本轮专用 loopback 端口，避免接管其它应用 listener。 */
async function reservePort(excluded = new Set()) {
  for (let port = 41_000; port < 49_000; port += 1) {
    if (excluded.has(port)) continue;
    const server = createServer();
    const bound = await new Promise((resolvePromise) => {
      server.once("error", () => resolvePromise(false));
      server.listen(port, "127.0.0.1", () => resolvePromise(true));
    });
    if (!bound) {
      server.close();
      continue;
    }
    await new Promise((resolvePromise) => server.close(resolvePromise));
    return port;
  }
  throw new Error("无法申请侧聊真窗 loopback 端口");
}

/** 写入最小隔离配置，使应用可以进入真实 Composer，再由 driver 配置 loopback Provider。@author kongweiguang */
async function writeIsolatedHome(homeRoot) {
  const jaHome = join(homeRoot, ".ja");
  await mkdir(jaHome, { recursive: true });
  const config = [
    "schema_version = 2",
    "config_revision = 1",
    'default_access_mode = "full_access"',
    'default_provider_id = "provider_side_chat"',
    'default_model_id = "model_side_chat"',
    "default_reasoning_level = { __ja_null = true }",
    "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
    "mcp_servers = []",
    "disabled_skills = []",
    "[interaction]",
    "clarification_enabled = true",
    "",
    "[[providers]]",
    'provider_id = "provider_side_chat"',
    'name = "Side Chat Bootstrap"',
    'api = "anthropic_messages"',
    'base_url = "http://127.0.0.1:9"',
    'credential_id = "cred_side_chat"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 1000",
    "request_timeout_ms = 30000",
    "[providers.agent_defaults]",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[[providers.models]]",
    'model_id = "model_side_chat"',
    'name = "Side Chat Bootstrap Model"',
    'model = "ja-side-bootstrap"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
  await writeFile(join(jaHome, "config.toml"), config, "utf8");
  const authPath = join(jaHome, "auth.json");
  await writeFile(
    join(jaHome, "auth.json"),
    '{"cred_side_chat":"isolated-bootstrap-token"}\n',
    "utf8",
  );
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("无法确定 Windows 隔离 profile ACL 用户");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
}

/** 构造只含当前 SID 单条 FullControl ACE 的凭据 ACL 校验脚本，避免 App Server 因额外 SYSTEM ACE 拒绝读取。@author kongweiguang */
export function buildRealModelAuthAclScript(targetPath) {
  if (!isAbsolute(targetPath)) throw new Error("真实模型 auth ACL 需要绝对文件路径");
  const encodedPath = Buffer.from(resolve(targetPath), "utf8").toString("base64");
  return [
    "$ErrorActionPreference = 'Stop'",
    `$target = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedPath}'))`,
    "$currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User",
    "$acl = Get-Acl -LiteralPath $target",
    "$acl.SetOwner($currentSid)",
    "$acl.SetAccessRuleProtection($true, $false)",
    "foreach ($existing in @($acl.Access)) { $acl.RemoveAccessRuleAll($existing) | Out-Null }",
    "$rule = [System.Security.AccessControl.FileSystemAccessRule]::new($currentSid, [System.Security.AccessControl.FileSystemRights]::FullControl, [System.Security.AccessControl.AccessControlType]::Allow)",
    "$acl.AddAccessRule($rule) | Out-Null",
    "Set-Acl -LiteralPath $target -AclObject $acl",
    "$verified = Get-Acl -LiteralPath $target",
    "$rules = @($verified.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))",
    "$ownerSid = $verified.GetOwner([System.Security.Principal.SecurityIdentifier]).Value",
    "if (-not $verified.AreAccessRulesProtected -or $ownerSid -ne $currentSid.Value -or $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $currentSid.Value -or $rules[0].AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow -or $rules[0].FileSystemRights -ne [System.Security.AccessControl.FileSystemRights]::FullControl -or $rules[0].InheritanceFlags -ne [System.Security.AccessControl.InheritanceFlags]::None -or $rules[0].PropagationFlags -ne [System.Security.AccessControl.PropagationFlags]::None -or $rules[0].IsInherited) { throw 'auth_acl_contract_mismatch' }",
    "[pscustomobject]@{ ownerIsCurrentSid = $true; protected = $verified.AreAccessRulesProtected; accessRuleCount = $rules.Count; onlyFullControl = $true } | ConvertTo-Json -Compress | ForEach-Object { Write-Output ('JA_REAL_MODEL_AUTH_ACL ' + $_) }",
  ].join("\n");
}

/** 将用户明确指定的配置与凭据原样复制到 ACL 私有 JA_HOME，核对字节及凭据文件唯一授权后才允许启动。@author kongweiguang */
export async function copyRealModelHome(homeRoot, sourceJaHome) {
  const sourceRoot = resolve(sourceJaHome);
  const destination = join(homeRoot, ".ja");
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("无法确定 Windows 隔离 profile ACL 用户");
  const sourceInfo = await lstat(sourceRoot);
  if (!sourceInfo.isDirectory() || sourceInfo.isSymbolicLink())
    throw new Error("真实模型源 JA_HOME 必须是普通目录");
  await mkdir(destination, { recursive: true });
  for (const name of ["config.toml", "auth.json"]) {
    const source = join(sourceRoot, name);
    const target = join(destination, name);
    let info;
    try {
      info = await lstat(source);
    } catch {
      throw new Error(`真实模型隔离配置缺少 ${name}`);
    }
    if (!info.isFile() || info.isSymbolicLink())
      throw new Error(`真实模型隔离配置 ${name} 必须是普通文件`);
    const sourceHashBeforeCopy = await sha256File(source);
    await copyFile(source, target);
    const [sourceHashAfterCopy, targetHash] = await Promise.all([
      sha256File(source),
      sha256File(target),
    ]);
    if (sourceHashBeforeCopy !== sourceHashAfterCopy || targetHash !== sourceHashAfterCopy)
      throw new Error(`真实模型隔离配置 ${name} 的复制字节校验失败`);
  }
  await execFileAsync(
    "icacls.exe",
    [
      destination,
      "/inheritance:r",
      "/grant:r",
      `${account}:(OI)(CI)(F)`,
      "/grant:r",
      "NT AUTHORITY\\SYSTEM:(OI)(CI)(F)",
    ],
    { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024 },
  );
  await execFileAsync(
    "icacls.exe",
    [
      join(destination, "config.toml"),
      "/inheritance:r",
      "/grant:r",
      `${account}:(F)`,
      "/grant:r",
      "NT AUTHORITY\\SYSTEM:(F)",
    ],
    { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024 },
  );
  const authAcl = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", buildRealModelAuthAclScript(join(destination, "auth.json"))],
    { windowsHide: true, timeout: 15_000, maxBuffer: 64 * 1024 },
  );
  const aclEvidenceLine = String(authAcl.stdout ?? "")
    .split(/\r?\n/u)
    .find((line) => line.startsWith("JA_REAL_MODEL_AUTH_ACL "));
  if (aclEvidenceLine === undefined) throw new Error("隔离 auth.json ACL 未返回校验结果");
  const aclEvidence = JSON.parse(aclEvidenceLine.slice("JA_REAL_MODEL_AUTH_ACL ".length));
  if (
    aclEvidence.ownerIsCurrentSid !== true ||
    aclEvidence.protected !== true ||
    aclEvidence.accessRuleCount !== 1 ||
    aclEvidence.onlyFullControl !== true
  )
    throw new Error("隔离 auth.json ACL 不符合 App Server 凭据读取契约");
  return {
    destination,
    copiedFiles: ["config.toml", "auth.json"],
    byteIdentityVerified: true,
    authAcl: {
      ownerIsCurrentSid: aclEvidence.ownerIsCurrentSid,
      protected: aclEvidence.protected,
      accessRuleCount: aclEvidence.accessRuleCount,
      onlyFullControl: aclEvidence.onlyFullControl,
    },
  };
}

/**
 * 为 Tauri 继承生产窗口事实，只替换 devUrl、隔离 identifier 与本轮 CSP；隐藏且不聚焦窗口，
 * 让 CDP 真窗验收不抢占操作者桌面，同时保留真实 Tauri/WebView2 渲染和原生 IPC。
 */
export async function writeTauriOverlay(runtimeRoot, frontendPort) {
  const [base, windows] = await Promise.all([
    readFile(join(repoRoot, "apps", "desktop", "src-tauri", "tauri.conf.json"), "utf8").then(
      JSON.parse,
    ),
    readFile(
      join(repoRoot, "apps", "desktop", "src-tauri", "tauri.windows.conf.json"),
      "utf8",
    ).then(JSON.parse),
  ]);
  const window = {
    ...base.app.windows.find((candidate) => candidate.label === "main"),
    ...(windows.app?.windows?.find((candidate) => candidate.label === "main") ?? {}),
    visible: false,
    focus: false,
    skipTaskbar: true,
  };
  const origin = `http://127.0.0.1:${frontendPort}`;
  const config = {
    ...base,
    identifier: `io.github.kongweiguang.ja.side_chat_e2e_${frontendPort}`,
    build: {
      ...base.build,
      devUrl: origin,
      beforeDevCommand: { ...base.build.beforeDevCommand, cwd: repoRoot },
    },
    app: {
      ...base.app,
      windows: [window],
      security: {
        ...(base.app?.security ?? {}),
        csp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ws://127.0.0.1:${frontendPort}; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`,
      },
    },
  };
  const path = join(runtimeRoot, "tauri.side-chat.conf.json");
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 在固定 CDP 端口等待本轮 Tauri/WebView2 listener，launcher 提前退出时立即失败。 */
async function waitForCdp(port, launch, deadline) {
  while (Date.now() < deadline) {
    if (launch?.child?.exitCode !== null || launch?.child?.signalCode !== null) {
      throw new Error(`隔离 Tauri 在 CDP 就绪前退出：${launch.output.slice(-4).join("\n")}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(1_000),
      });
      if (response.ok) return;
    } catch {
      // Tauri、renderer 和 WebView2 browser listener 分阶段启动，期限内继续等待。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(`隔离 WebView2 CDP ${port} 未在期限内启动`);
}

/** 等待且校验 root 提供的私有 Desktop 身份；GUI PID 必须仍属于本轮 wrapper 子树。@author kongweiguang */
async function waitForPrivateDesktop(launch, deadline = Date.now() + 30_000) {
  while (Date.now() < deadline) {
    if (launch.startError !== undefined) throw new Error("私有 Desktop launcher 无法启动");
    if (launch.privateDesktopError !== undefined) throw launch.privateDesktopError;
    if (launch.child.exitCode !== null || launch.child.signalCode !== null)
      throw new Error("私有 Desktop launcher 在 marker 前退出");
    if (launch.privateDesktopMarker !== undefined) {
      if (!(await rememberOwnedProcessTree(launch)))
        throw new Error("私有 Desktop launcher PID 身份未能核实");
      const guiOwner = launch.ownedProcesses.get(launch.privateDesktopMarker.pid);
      if (guiOwner === undefined)
        throw new Error("私有 Desktop GUI PID 不属于本轮 launcher 子树");
      return launch.privateDesktopMarker;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  throw new Error("私有 Desktop launcher 未在期限内发布身份 marker");
}

/** 生产 EXE 使用固定 Tauri identifier；发现同名 app process 时 fail closed，避免 single-instance 唤醒用户窗口。@author kongweiguang */
async function assertNoRunningDesktopInstance(desktopExecutable) {
  const imageName = basename(desktopExecutable).replace(/'/gu, "''");
  const command = `$imageName='${imageName}'; @(Get-CimInstance Win32_Process | Where-Object { $_.Name -ieq $imageName } | Select-Object @{Name='pid';Expression={$_.ProcessId}}, @{Name='creationDate';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')} else {''}}}) | ConvertTo-Json -Compress`;
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", command],
    { windowsHide: true, timeout: 15_000, maxBuffer: 64 * 1024 },
  );
  const parsed = JSON.parse(String(result.stdout ?? "").trim() || "[]");
  const processes = Array.isArray(parsed) ? parsed : [parsed];
  if (processes.length > 0) {
    const identities = processes.map((item) => `${item.pid}@${item.creationDate}`).join(", ");
    throw new Error(
      `检测到同名桌面进程；为避免 single-instance 把验收请求送到现有窗口，未启动安装版：${identities}`,
    );
  }
}

/** 构造独立 PowerShell 语句，避免多行原生声明与后续赋值被解析成同一命令参数。@author kongweiguang */
export function buildHideOwnedDesktopWindowScript(pid, timeoutMs) {
  if (!Number.isSafeInteger(pid) || pid < 1) throw new Error("隐藏窗口需要有效的 runner PID");
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1)
    throw new Error("隐藏窗口需要正数等待期限");
  return [
    'Add-Type -TypeDefinition \'using System; using System.Runtime.InteropServices; public static class JaSideChatWindow { [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr handle, int command); [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr handle); }\';',
    `$targetPid=${pid}; $deadline=[DateTime]::UtcNow.AddMilliseconds(${timeoutMs});`,
    "while ([DateTime]::UtcNow -lt $deadline) {",
    "  $target=Get-Process -Id $targetPid -ErrorAction SilentlyContinue; if ($null -eq $target) { exit 3 };",
    "  $handle=$target.MainWindowHandle; if ($handle -ne 0) { [JaSideChatWindow]::ShowWindow($handle, 0) | Out-Null; if (-not [JaSideChatWindow]::IsWindowVisible($handle)) { exit 0 } };",
    "  Start-Sleep -Milliseconds 50;",
    "}; exit 2",
  ].join("\n");
}

/**
 * 只读取 loopback endpoint listener 的最小进程身份；PowerShell 不返回命令行或凭据，
 * 调用方再以内存中的本轮私有目录参数和冻结 PID/CreationDate 绑定 owner。
 * @author kongweiguang
 */
export function buildRuntimeListenerOwnerQueryScript(port, expectedPaths) {
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535)
    throw new Error("Runtime listener 查询需要有效端口");
  const roles = ["home", "data", "run", "log"];
  for (const role of roles) {
    if (!win32.isAbsolute(expectedPaths?.[role] ?? ""))
      throw new Error(`Runtime listener 查询缺少绝对 ${role} 目录`);
  }
  if (!win32.isAbsolute(expectedPaths?.executablePath ?? ""))
    throw new Error("Runtime listener 查询缺少绝对 executable path");
  /** 仅为 PowerShell 单引号字面量转义，不把路径拼进可执行命令或写入证据。 */
  const quote = (value) => `'${String(value).replace(/'/gu, "''")}'`;
  return [
    "$ErrorActionPreference = 'Stop'",
    `$port = ${port}`,
    "$listeners = @(Get-NetTCPConnection -State Listen -LocalAddress '127.0.0.1' -LocalPort $port -ErrorAction SilentlyContinue)",
    "$ownerPids = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)",
    "if ($ownerPids.Count -ne 1) { throw 'isolated_runtime_listener_not_unique' }",
    "$owner = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [string]$ownerPids[0])",
    "if ($null -eq $owner) { throw 'isolated_runtime_owner_missing' }",
    "if ($null -eq $owner.CreationDate) { throw 'isolated_runtime_owner_creation_date_missing' }",
    "$commandLine = [string]$owner.CommandLine",
    `$expectedHome = ${quote(expectedPaths.home)}`,
    `$expectedData = ${quote(expectedPaths.data)}`,
    `$expectedRun = ${quote(expectedPaths.run)}`,
    `$expectedLog = ${quote(expectedPaths.log)}`,
    `$expectedExecutable = ${quote(expectedPaths.executablePath)}`,
    "function Get-JaNormalizedPath([string]$path) {",
    "  $normalized = [IO.Path]::GetFullPath($path).Replace('/', '\\')",
    "  if ($normalized.StartsWith('\\\\?\\')) { $normalized = $normalized.Substring(4) }",
    "  return $normalized.TrimEnd('\\').ToLowerInvariant()",
    "}",
    "function Test-JaEncodedDirectory([string]$argumentName, [string]$expectedPath, [string]$commandLine) {",
    "  $pattern = '(?:^|\\s)\"?--' + $argumentName + '-dir-base64=([A-Za-z0-9_-]+)\"?(?=\\s|$)'",
    "  $matches = [regex]::Matches($commandLine, $pattern)",
    "  if ($matches.Count -ne 1) { return $false }",
    "  $standard = $matches[0].Groups[1].Value.Replace('-', '+').Replace('_', '/')",
    "  switch ($standard.Length % 4) { 0 { } 2 { $standard += '==' } 3 { $standard += '=' } default { return $false } }",
    "  try { $decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($standard)) } catch { return $false }",
    "  if ([string]::IsNullOrWhiteSpace($decoded)) { return $false }",
    "  return (Get-JaNormalizedPath $decoded) -ceq (Get-JaNormalizedPath $expectedPath)",
    "}",
    "$executableMatches = (Get-JaNormalizedPath ([string]$owner.ExecutablePath)) -ceq (Get-JaNormalizedPath $expectedExecutable)",
    "$homeMatches = Test-JaEncodedDirectory 'home' $expectedHome $commandLine",
    "$dataMatches = Test-JaEncodedDirectory 'data' $expectedData $commandLine",
    "$runMatches = Test-JaEncodedDirectory 'run' $expectedRun $commandLine",
    "$logMatches = Test-JaEncodedDirectory 'log' $expectedLog $commandLine",
    "[pscustomobject]@{ pid = [int]$owner.ProcessId; parentPid = [int]$owner.ParentProcessId; creationDate = $owner.CreationDate.ToUniversalTime().ToString('o'); name = [string]$owner.Name; executableMatches = $executableMatches; homeMatches = $homeMatches; dataMatches = $dataMatches; runMatches = $runMatches; logMatches = $logMatches } | ConvertTo-Json -Compress",
  ].join("\n");
}

/** 目录角色跟随后端真实装配：debug JVM 可覆盖 run，release Native 固定使用 JA_HOME/run。@author kongweiguang */
export function runtimeDirectoriesForBackend(directories, backendMode) {
  if (backendMode !== "jvm" && backendMode !== "native")
    throw new Error(`不支持的 runtime backend mode：${backendMode}`);
  const home = join(directories.settings, ".ja");
  return {
    home,
    data: join(home, "data"),
    run: backendMode === "native" ? join(home, "run") : directories.runtime,
    log: join(home, "logs", "java"),
  };
}

/** endpoint 文件缺失只有在 launcher 已记录的 backend owner 也确已退出时才等价于 stopped。@author kongweiguang */
export function assertRecordedBackendExitedWithoutEndpoint(inventory, launch) {
  const executableName = basename(launch.runtimeExecutable).toLowerCase();
  const recordedOwners = [...launch.ownedProcesses.values()].filter(
    (owner) => typeof owner.name === "string" && owner.name.toLowerCase() === executableName,
  );
  if (recordedOwners.length === 0)
    throw new Error("endpoint 缺失且 launcher 未记录 backend owner 身份");
  const liveOwner = recordedOwners.find((owner) =>
    inventory.some(
      (processInfo) =>
        processInfo.pid === owner.pid && processInfo.creationDate === owner.creationDate,
    ),
  );
  if (liveOwner !== undefined)
    throw new Error("endpoint 缺失但 launcher 已记录的 backend owner 仍在运行");
  return { status: "already_stopped", endpointAbsent: true, backendOwnerExited: true };
}

/** 尽早仅隐藏本 runner 的 GUI HWND；与 single-instance 预检结合，不触碰或激活已有用户窗口。@author kongweiguang */
async function hideOwnedDesktopWindow(launch, deadline = Date.now() + 20_000) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || pid < 1) return false;
  const command = buildHideOwnedDesktopWindowScript(pid, Math.max(1, deadline - Date.now()));
  try {
    await execFileAsync("pwsh.exe", ["-NoProfile", "-NonInteractive", "-Command", command], {
      windowsHide: true,
      timeout: Math.max(2_000, deadline - Date.now() + 2_000),
      maxBuffer: 64 * 1024,
    });
    return true;
  } catch (error) {
    if (launch.child.exitCode !== null || launch.child.signalCode !== null) return false;
    throw new Error(`无法隐藏本轮安装版主窗口：${String(error)}`);
  }
}

/**
 * 查询 Windows 进程的窄身份投影；CreationDate 用稳定 ISO 字符串保存，防止
 * cleanup 时 PID 被系统复用后被误当成本轮 Tauri/Java 后代。命令不返回命令行或路径。
 */
async function readProcessInventory() {
  const command =
    "@(Get-CimInstance Win32_Process | Select-Object @{Name='pid';Expression={$_.ProcessId}}, @{Name='parentPid';Expression={$_.ParentProcessId}}, @{Name='creationDate';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')} else {''}}}, Name) | ConvertTo-Json -Compress";
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", command],
    {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 512 * 1024,
    },
  );
  const parsed = JSON.parse(String(result.stdout ?? "").trim() || "[]");
  const values = Array.isArray(parsed) ? parsed : [parsed];
  return values
    .map((value) => ({
      pid: Number(value?.pid),
      parentPid: Number(value?.parentPid),
      creationDate: typeof value?.creationDate === "string" ? value.creationDate : "",
      name: typeof value?.Name === "string" ? value.Name : "",
    }))
    .filter(
      (value) =>
        Number.isSafeInteger(value.pid) &&
        value.pid > 0 &&
        Number.isSafeInteger(value.parentPid) &&
        value.parentPid >= 0,
    );
}

/** 只把真实 endpoint listener 映射为短暂内存身份；PowerShell 输出不含原始命令行或目录。@author kongweiguang */
export async function readRuntimeListenerOwner(endpoint, expectedPaths) {
  const script = buildRuntimeListenerOwnerQueryScript(endpoint.port, expectedPaths);
  let stdout;
  try {
    ({ stdout } = await execFileAsync(
      "pwsh.exe",
      ["-NoProfile", "-NonInteractive", "-Command", script],
      { windowsHide: true, timeout: 15_000, maxBuffer: 32 * 1024 },
    ));
  } catch {
    throw new Error("无法从本轮 loopback endpoint 确认唯一 Native owner");
  }
  let value;
  try {
    value = JSON.parse(String(stdout ?? "").trim());
  } catch {
    throw new Error("本轮 loopback endpoint owner identity 无效");
  }
  const owner = {
    pid: Number(value?.pid),
    parentPid: Number(value?.parentPid),
    creationDate: typeof value?.creationDate === "string" ? value.creationDate : "",
    name: typeof value?.name === "string" ? value.name : "",
    executableMatches: value?.executableMatches === true,
    homeMatches: value?.homeMatches === true,
    dataMatches: value?.dataMatches === true,
    runMatches: value?.runMatches === true,
    logMatches: value?.logMatches === true,
  };
  if (
    !Number.isSafeInteger(owner.pid) ||
    owner.pid < 1 ||
    !Number.isSafeInteger(owner.parentPid) ||
    owner.parentPid < 0 ||
    owner.creationDate === "" ||
    owner.name === "" ||
    ![
      owner.executableMatches,
      owner.homeMatches,
      owner.dataMatches,
      owner.runMatches,
      owner.logMatches,
    ].every(Boolean)
  ) {
    throw new Error("loopback endpoint listener 不属于本轮隔离 App Server");
  }
  return owner;
}

/**
 * 正常退出后按 endpoint 身份完成非 force 的 JA-RPC runtime/shutdown；只在 listener、启动二进制、
 * 四个隔离目录参数和 PID/CreationDate 都属于本轮时才发送请求，并等待 endpoint 与 owner 自然退出。
 * @author kongweiguang
 */
export async function stopIsolatedRuntimeAfterNormalExit(directories, launch) {
  const runtimeDirectories = runtimeDirectoriesForBackend(directories, launch.backendMode);
  const endpointDirectories = { runtime: runtimeDirectories.run };
  const endpoint = await readRuntimeEndpointMetadata(endpointDirectories);
  if (endpoint === undefined)
    return assertRecordedBackendExitedWithoutEndpoint(await readProcessInventory(), launch);
  const endpointPath = join(runtimeDirectories.run, "app-server.endpoint.json");
  let document;
  try {
    document = JSON.parse(await readFile(endpointPath, "utf8"));
  } catch {
    throw new Error("本轮 endpoint 凭据文档不可读取");
  }
  if (
    document?.host !== endpoint.host ||
    document?.port !== endpoint.port ||
    document?.serverInstanceId !== endpoint.serverInstanceId ||
    document?.runtimeGeneration !== endpoint.runtimeGeneration ||
    document?.protocolMajor !== 2 ||
    document?.protocolMinor !== 0 ||
    !/^[0-9a-f]{64}$/u.test(String(document?.token))
  ) {
    throw new Error("本轮 endpoint 身份与认证凭据不一致");
  }

  const [home, data, run, log, executablePath] = await Promise.all(
    [
      runtimeDirectories.home,
      runtimeDirectories.data,
      runtimeDirectories.run,
      runtimeDirectories.log,
      launch.runtimeExecutable,
    ].map((path) => realpath(path)),
  );
  const expectedPaths = { home, data, run, log, executablePath };
  const unchangedEndpoint = await readRuntimeEndpointMetadata(endpointDirectories);
  if (JSON.stringify(unchangedEndpoint) !== JSON.stringify(endpoint))
    throw new Error("本轮 endpoint 在 shutdown 前发生变化");
  const owner = await readRuntimeListenerOwner(endpoint, expectedPaths);
  const knownOwner = launch.ownedProcesses.get(owner.pid);
  if (
    knownOwner === undefined ||
    knownOwner.creationDate !== owner.creationDate ||
    typeof knownOwner.name !== "string" ||
    knownOwner.name.toLowerCase() !== owner.name.toLowerCase()
  ) {
    throw new Error("本轮 endpoint listener 不匹配 launcher 已冻结的进程身份");
  }

  const shutdown = await requestRunScopedRuntimeShutdown(
    endpoint,
    document.token,
    AbortSignal.timeout(30_000),
  );
  if (shutdown.status !== "acknowledged" || shutdown.socketClose?.status !== "closed")
    throw new Error("本轮 runtime/shutdown 未得到完整 JA-RPC 确认");

  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const [currentEndpoint, inventory] = await Promise.all([
      readRuntimeEndpointMetadata(endpointDirectories),
      readProcessInventory(),
    ]);
    if (
      currentEndpoint !== undefined &&
      JSON.stringify(currentEndpoint) !== JSON.stringify(endpoint)
    ) {
      throw new Error("shutdown 后本轮 runtime endpoint 被替换");
    }
    const ownerRemains = inventory.some(
      (processInfo) =>
        processInfo.pid === owner.pid && processInfo.creationDate === owner.creationDate,
    );
    if (currentEndpoint === undefined && !ownerRemains)
      return { status: "acknowledged", endpointRemoved: true, ownerExited: true };
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("本轮 App Server 已收到 shutdown，但 endpoint 或 owner 未自然退出");
}

/** 读取本轮唯一 WebView2 profile 的窄身份；profile 是额外 owner 边界，不是 executable path。 */
async function readWebView2ProfileInventory(profile) {
  const escaped = profile.replace(/'/gu, "''");
  const command = `$profile='${escaped}'; @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedgewebview2.exe' -and $_.CommandLine -like ('*' + $profile + '*') } | Select-Object @{Name='pid';Expression={$_.ProcessId}}, @{Name='creationDate';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')} else {''}}}) | ConvertTo-Json -Compress`;
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", command],
    {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 128 * 1024,
    },
  );
  const parsed = JSON.parse(String(result.stdout ?? "").trim() || "[]");
  const values = Array.isArray(parsed) ? parsed : [parsed];
  return values
    .map((value) => ({ pid: Number(value?.pid), creationDate: value?.creationDate }))
    .filter(
      (value) =>
        Number.isSafeInteger(value.pid) &&
        value.pid > 0 &&
        typeof value.creationDate === "string" &&
        value.creationDate.length > 0,
    );
}

/** 从一次完整进程快照收集 launcher 自身及全部可见后代，避免依赖 executable path。 */
function processTree(inventory, rootPid) {
  const children = new Map();
  for (const processInfo of inventory) {
    const siblings = children.get(processInfo.parentPid) ?? [];
    siblings.push(processInfo);
    children.set(processInfo.parentPid, siblings);
  }
  const byPid = new Map(inventory.map((processInfo) => [processInfo.pid, processInfo]));
  const result = [];
  const pending = [rootPid];
  const visited = new Set();
  while (pending.length > 0) {
    const pid = pending.shift();
    if (visited.has(pid)) continue;
    visited.add(pid);
    const processInfo = byPid.get(pid);
    if (processInfo !== undefined) result.push(processInfo);
    for (const child of children.get(pid) ?? []) pending.push(child.pid);
  }
  return result;
}

/**
 * 记录真实 PID/CreationDate 与进程名；最终 isolated endpoint cleanup 需把 listener owner
 * 关联到本轮 launcher 快照，PID 单独不足以授权 shutdown。
 * @author kongweiguang
 */
async function rememberOwnedProcessTree(launch, deadline = Date.now() + 15_000) {
  const rootPid = launch?.child?.pid;
  if (!Number.isSafeInteger(rootPid) || rootPid < 1) return false;
  while (Date.now() < deadline) {
    try {
      const inventory = await readProcessInventory();
      const tree = processTree(inventory, rootPid);
      const root = tree.find((processInfo) => processInfo.pid === rootPid);
      if (root?.creationDate) {
        launch.rootIdentity = { pid: root.pid, creationDate: root.creationDate };
        for (const processInfo of tree) {
          if (!processInfo.creationDate) continue;
          launch.ownedProcesses.set(processInfo.pid, {
            pid: processInfo.pid,
            parentPid: processInfo.parentPid,
            creationDate: processInfo.creationDate,
            name: processInfo.name,
          });
        }
        for (const processInfo of await readWebView2ProfileInventory(launch.webviewProfile).catch(
          () => [],
        )) {
          launch.ownedProcesses.set(processInfo.pid, processInfo);
        }
        return true;
      }
    } catch {
      // WMI 启动阶段可能暂时不可读；只在有界期限内重试。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  launch.cleanupWarnings.push(`launcher PID ${rootPid} 没有可靠 CreationDate 身份`);
  return false;
}

/** 预热 loopback runner 的 WebView2 UDF；真实模型 Native 由私有 Desktop 直接启动，不做多余重启。@author kongweiguang */
async function primeWebViewProfile(
  directories,
  frontendPort,
  cdpPort,
  configPath,
  java,
  jar,
  targetDirectory,
  launchOptions,
) {
  const launch = startTauri(
    directories,
    frontendPort,
    cdpPort,
    configPath,
    java,
    jar,
    targetDirectory,
    launchOptions,
  );
  let cleanup;
  let failure;
  try {
    const hidden =
      launch.privateDesktop === true
        ? (await waitForPrivateDesktop(launch), true)
        : launch.backendMode === "native"
          ? await hideOwnedDesktopWindow(launch)
          : true;
    if (!hidden) throw new Error("Native 安装版窗口未能在 profile 预热前隐藏");
    await rememberOwnedProcessTree(launch);
    const deadline = Date.now() + 120_000;
    const preferences = join(directories.webview, "EBWebView", "Default", "Preferences");
    let ready = false;
    while (Date.now() < deadline) {
      if (launch.child.exitCode !== null || launch.child.signalCode !== null) {
        throw new Error(
          `WebView2 profile 预热时 Tauri 退出：${launch.output.slice(-4).join("\n")}`,
        );
      }
      try {
        await access(preferences);
        ready = true;
        break;
      } catch {
        // WebView2 profile 在首轮启动中分阶段创建，期限内继续等待。
      }
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
    }
    if (!ready) {
      const output = launch.output.slice(-4).join("\n");
      throw new Error(`隔离 WebView2 profile 未在期限内完成预热${output ? `：\n${output}` : ""}`);
    }
  } catch (error) {
    failure = error;
  } finally {
    cleanup = await stopTauri(launch);
  }
  if (cleanup.warnings.length > 0)
    throw new Error(`WebView2 profile 预热 cleanup 未闭合：${cleanup.warnings.join("；")}`);
  if (failure !== undefined) throw failure;
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 2_000));
}

/**
 * 构造隔离 Tauri 子进程环境，并清除 Windows 大小写重复的 Path 键，
 * JVM dev 需要 Vite workspace hook；Native 安装版只保留隔离 profile 与 WebView2/CDP 输入，避免泄漏开发钩子。
 * @author kongweiguang
 */
export function buildTauriEnvironment({
  directories,
  frontendPort,
  cdpPort,
  java,
  jar,
  targetDirectory,
  baseEnvironment = process.env,
  backendMode = "jvm",
}) {
  const cleanBaseEnvironment =
    backendMode === "native"
      ? Object.fromEntries(
          Object.entries(baseEnvironment).filter(
            ([name]) => !/^(?:JA_E2E_|JA_DEBUG_|JA_TEST_|VITE_JA_E2E_)/iu.test(name),
          ),
        )
      : baseEnvironment;
  const inheritedPath = cleanBaseEnvironment.PATH ?? cleanBaseEnvironment.Path ?? "";
  const hostUserProfile = cleanBaseEnvironment.USERPROFILE ?? cleanBaseEnvironment.HOME ?? "";
  const env = {
    ...cleanBaseEnvironment,
    APPDATA: directories.appdata,
    LOCALAPPDATA: directories.localappdata,
    USERPROFILE: directories.settings,
    JA_HOME: join(directories.settings, ".ja"),
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    WEBVIEW2_USER_DATA_FOLDER: directories.webview,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `${edgeDebugArguments}${cdpPort}`,
    PATH: inheritedPath,
  };
  if (backendMode === "jvm") {
    Object.assign(env, {
      JA_E2E_EXIT_TRACE_PATH: join(directories.runtime, "ja-exit-trace.log"),
      JA_E2E_DEV_PORT: String(frontendPort),
      CARGO_TARGET_DIR: targetDirectory,
      CARGO_HOME: baseEnvironment.CARGO_HOME ?? join(hostUserProfile, ".cargo"),
      RUSTUP_HOME: baseEnvironment.RUSTUP_HOME ?? join(hostUserProfile, ".rustup"),
      TAURI_DEV_HOST: "127.0.0.1",
      JAVA_HOME: dirname(dirname(java)),
      JA_JAVA25_HOME: dirname(dirname(java)),
      JA_E2E_JAVA_HOME: dirname(dirname(java)),
      JA_TEST_JAVA: java,
      JA_DEBUG_JAVA: java,
      JA_DEBUG_JAR: jar,
      VITE_JA_E2E_PROJECT_PATH: directories.workspace,
      PATH: [dirname(java), inheritedPath].filter(Boolean).join(";"),
    });
  } else if (backendMode === "native") {
    for (const key of [
      "JA_E2E_EXIT_TRACE_PATH",
      "JA_E2E_DEV_PORT",
      "JA_E2E_RUNTIME_ROOT",
      "JA_E2E_JAVA_HOME",
      "JA_TEST_JAVA",
      "JA_DEBUG_JAVA",
      "JA_DEBUG_JAR",
      "JA_JAVA25_HOME",
      "CARGO_TARGET_DIR",
      "TAURI_DEV_HOST",
    ])
      delete env[key];
  } else {
    throw new Error(`不支持的侧聊后端模式：${backendMode}`);
  }
  delete env.Path;
  return env;
}

/** 启动本轮唯一 launcher root；真实模型 Native 由私有 Desktop wrapper 创建，避免碰触用户输入桌面。@author kongweiguang */
function startTauri(
  directories,
  frontendPort,
  cdpPort,
  configPath,
  java,
  jar,
  targetDirectory,
  launchOptions = {},
) {
  const backendMode = launchOptions.backendMode ?? "jvm";
  const env = buildTauriEnvironment({
    directories,
    frontendPort,
    cdpPort,
    java,
    jar,
    targetDirectory,
    backendMode,
  });
  if (backendMode === "native") {
    const privateDesktop = launchOptions.privateDesktop === true;
    const child = spawn(
      privateDesktop ? "pwsh.exe" : launchOptions.desktopExecutable,
      privateDesktop
        ? [
            "-NoProfile",
            "-NonInteractive",
            "-File",
            privateDesktopLauncher,
            "-ExecutablePath",
            launchOptions.desktopExecutable,
            "-WorkingDirectory",
            launchOptions.packageRoot,
          ]
        : [],
      {
      cwd: privateDesktop ? repoRoot : launchOptions.packageRoot,
      env,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
      },
    );
    const output = [];
    const launch = {
      child,
      output,
      webviewProfile: directories.webview,
      rootIdentity: undefined,
      ownedProcesses: new Map(),
      cleanupWarnings: [],
      backendMode,
      runtimeExecutable: launchOptions.nativeExecutable,
      privateDesktop,
    };
    child.once("error", (error) => {
      launch.startError = error.code ?? "SPAWN_FAILED";
    });
    if (privateDesktop) {
      let pending = "";
      child.stdout?.on("data", (chunk) => {
        pending += String(chunk);
        const lines = pending.split(/\r?\n/u);
        pending = lines.pop() ?? "";
        for (const line of lines) {
          if (!line.startsWith("JA_PRIVATE_DESKTOP_STARTED ")) continue;
          try {
            if (launch.privateDesktopMarker !== undefined)
              throw new Error("私有 Desktop launcher 重复发布 marker");
            launch.privateDesktopMarker = parsePrivateDesktopMarker(line);
          } catch {
            launch.privateDesktopError = new Error("私有 Desktop launcher marker 无效");
          }
        }
      });
      child.stderr?.on("data", () => {});
    } else {
      child.stdout?.on("data", (chunk) => output.push(String(chunk).slice(-2_000)));
      child.stderr?.on("data", (chunk) => output.push(String(chunk).slice(-2_000)));
    }
    return launch;
  }
  const command = process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe";
  // cmd.exe 的 /s /c 在内部命令和路径都带引号时需要最外层引号，避免首个路径被剥离。
  const line = `""pnpm.cmd" tauri dev --no-watch --config "${configPath}""`;
  const child = spawn(command, ["/d", "/s", "/c", line], {
    cwd: repoRoot,
    env,
    windowsHide: true,
    windowsVerbatimArguments: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = [];
  child.stdout?.on("data", (chunk) => output.push(String(chunk).slice(-2_000)));
  child.stderr?.on("data", (chunk) => output.push(String(chunk).slice(-2_000)));
  return {
    child,
    output,
    webviewProfile: directories.webview,
    rootIdentity: undefined,
    ownedProcesses: new Map(),
    cleanupWarnings: [],
    backendMode,
    runtimeExecutable: java,
  };
}

/**
 * 按本轮 launcher 的 PID/CreationDate 身份收口进程；正常退出验收只观察残留，
 * 只有阶段失败时才强制终止已核实的本轮进程，避免 cleanup 掩盖原始退出证据。
 */
async function stopTauri(launch, { force = true } = {}) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || pid < 1) {
    const warnings = [...(launch?.cleanupWarnings ?? []), "launcher PID 不可用"];
    return { warnings, residual: [] };
  }
  const wasRunning = launch.child.exitCode === null && launch.child.signalCode === null;
  if (wasRunning && force) await rememberOwnedProcessTree(launch, Date.now() + 3_000);
  for (const processInfo of await readWebView2ProfileInventory(launch.webviewProfile).catch(
    () => [],
  )) {
    launch.ownedProcesses.set(processInfo.pid, processInfo);
  }
  const inventory = await readProcessInventory().catch(() => []);
  const rootMatches =
    launch.rootIdentity !== undefined &&
    inventory.some(
      (processInfo) =>
        processInfo.pid === launch.rootIdentity.pid &&
        processInfo.creationDate === launch.rootIdentity.creationDate,
    );
  // 先递归回收经过 PID+CreationDate 核验的 launcher 子树，避免先杀 root 后子树脱离。
  if (force && wasRunning && rootMatches) {
    await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 64 * 1024,
    }).catch(() => undefined);
    if (launch.child.exitCode === null && launch.child.signalCode === null) launch.child.kill();
  } else if (force && wasRunning) {
    launch.cleanupWarnings.push(`launcher PID ${pid} 未通过 CreationDate 核验，未强杀`);
  }
  if (force) await new Promise((resolvePromise) => setTimeout(resolvePromise, 1_000));
  const remaining = await readProcessInventory().catch(() => []);
  const verifiedRemaining = remaining.filter((processInfo) => {
    const owned = launch.ownedProcesses.get(processInfo.pid);
    return owned !== undefined && owned.creationDate === processInfo.creationDate;
  });
  if (force) {
    for (const processInfo of verifiedRemaining) {
      if (processInfo.pid === pid && rootMatches) continue;
      await execFileAsync("taskkill.exe", ["/PID", String(processInfo.pid), "/F"], {
        windowsHide: true,
        timeout: 15_000,
        maxBuffer: 64 * 1024,
      }).catch(() => undefined);
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 500));
  }
  // taskkill 返回时 Windows 可能仍在回收已终止进程；用有界复核避免把退出尾声误报为泄漏。
  const cleanupDeadline = Date.now() + 8_000;
  let residual;
  do {
    const finalInventory = await readProcessInventory().catch((error) => {
      launch.cleanupWarnings.push(`无法核验退出进程：${String(error)}`);
      return [];
    });
    residual = finalInventory.filter((processInfo) => {
      const owned = launch.ownedProcesses.get(processInfo.pid);
      return owned !== undefined && owned.creationDate === processInfo.creationDate;
    });
    if (residual.length === 0 || Date.now() >= cleanupDeadline) break;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  } while (residual.length > 0 && Date.now() < cleanupDeadline);
  if (residual.length > 0) {
    launch.cleanupWarnings.push(
      force
        ? `本轮仍有 ${residual.length} 个经身份核验的进程残留`
        : `正常退出后仍有 ${residual.length} 个经身份核验的进程残留`,
    );
  }
  return { warnings: [...launch.cleanupWarnings], residual };
}

/** 校验 JDK 25 与可复用 App Server JAR；不让 runner 静默使用默认 JDK 或旧路径。 */
async function resolveJavaAndJar() {
  const javaHome =
    process.env.JA_E2E_SIDE_CHAT_JAVA_HOME?.trim() ||
    process.env.JA_E2E_JAVA_HOME?.trim() ||
    "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
  const java = join(javaHome, "bin", "java.exe");
  const jar = resolve(
    process.env.JA_E2E_SIDE_CHAT_APP_SERVER_JAR?.trim() ||
      process.env.JA_E2E_APP_SERVER_JAR?.trim() ||
      process.env.JA_DEBUG_JAR?.trim() ||
      join(repoRoot, "ja-agent/ja-app-server", "target", "ja-app-server.jar"),
  );
  await access(java);
  await access(jar);
  const version = await execFileAsync(java, ["-version"], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
  if (!`${version.stderr ?? ""}${version.stdout ?? ""}`.includes('version "25')) {
    throw new Error("侧聊 runner 必须使用 JDK 25");
  }
  return { java, jar };
}

/** 启动真实模型或 loopback driver；两条路线共享 CDP，不共享 Provider 夹具。@author kongweiguang */
async function runDriver(directories, cdpPort, phase, scenario, backendMode) {
  const realModel = scenario.startsWith("real-model");
  const driver = realModel ? realModelDriver : sideChatScript;
  if (realModel && phase !== "initial") throw new Error("真实模型桌面场景不支持重启 phase");
  const retainedFile = join(directories.workspace, "side-chat-retained.txt");
  const approvalWriteDirectory = join(directories.workspace, "approval-probes");
  await mkdir(approvalWriteDirectory, { recursive: true });
  const child = spawn(process.execPath, [driver], {
    cwd: repoRoot,
    env: {
      ...process.env,
      JA_E2E_SIDE_CHAT_ISOLATED: "1",
      JA_E2E_SIDE_CHAT_CDP_ENDPOINT: `http://127.0.0.1:${cdpPort}`,
      JA_E2E_SIDE_CHAT_ARTIFACT_DIR: directories.artifacts,
      JA_E2E_SIDE_CHAT_RETAINED_FILE: retainedFile,
      JA_E2E_SIDE_CHAT_PHASE: phase,
      JA_E2E_SIDE_CHAT_SCENARIO: scenario,
      JA_E2E_SIDE_CHAT_BACKEND_MODE: backendMode,
      JA_E2E_SIDE_CHAT_APPROVAL_WRITE_DIR: approvalWriteDirectory,
      JA_E2E_SIDE_CHAT_NORMAL_EXIT: phase === "restart" ? "1" : "0",
      // 会话发现是独立的跨 Workspace API；runner 不允许 workspace scope 降级通过。
      JA_E2E_SIDE_CHAT_THREAD_LIST_SCOPE: "all",
      JA_E2E_SIDE_CHAT_THREAD_LIST_COMMAND:
        process.env.JA_E2E_SIDE_CHAT_THREAD_LIST_COMMAND?.trim() || "ja_thread_discover",
      JA_REAL_MODEL_DESKTOP_CDP_ENDPOINT: `http://127.0.0.1:${cdpPort}`,
      JA_REAL_MODEL_DESKTOP_EVIDENCE_DIR: directories.artifacts,
      JA_REAL_MODEL_DESKTOP_WORKSPACE: directories.workspace,
      JA_REAL_MODEL_DESKTOP_SCENARIO: scenario,
      JA_REAL_MODEL_DESKTOP_WRITE_DIR: approvalWriteDirectory,
    },
    stdio: "inherit",
    windowsHide: true,
  });
  return new Promise((resolvePromise, rejectPromise) => {
    child.once("error", rejectPromise);
    child.once("exit", (code, signal) => resolvePromise(code ?? (signal === null ? 1 : 1)));
  });
}

/** 正常退出阶段必须等待 launcher 自行结束，避免后续 cleanup 的强杀掩盖应用退出结果。 */
async function waitForLauncherExit(launch, deadline = Date.now() + 30_000) {
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null || launch.child.signalCode !== null) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("正常退出请求后 Tauri launcher 未自行结束");
}

/** 校验 initial driver 已留下重启所需身份事实，防止空证据进入 restart 阶段。 */
async function requireInitialEvidence(artifactDirectory) {
  const statePath = join(artifactDirectory, "side-chat-state.json");
  const state = JSON.parse(await readFile(statePath, "utf8"));
  for (const key of [
    "rootThreadId",
    "sideThreadId",
    "liveSideThreadId",
    "btwSideThreadId",
    "btwIdleSideThreadId",
    "btwRequestTargetThreadId",
  ]) {
    if (typeof state[key] !== "string" || state[key].length === 0) {
      throw new Error(`initial 证据缺少 ${key}`);
    }
  }
  if (state.btwRequestTargetThreadId !== state.btwSideThreadId) {
    throw new Error("initial 证据中的 /btw 请求目标不是带正文侧聊");
  }
}

/** JVM debug 窗口须保留完整原生退出轨迹；Native 由 launcher 自退和 SQLite purge 联合证明。@author kongweiguang */
async function requireNormalExitEvidence(runtimeRoot, backendMode) {
  if (backendMode === "native") return;
  const tracePath = join(runtimeRoot, "ja-exit-trace.log");
  const trace = await readFile(tracePath, "utf8");
  const expected = [
    "stage=exit_requested_enter",
    "stage=exit_requested_return",
    "stage=exit_enter",
    "stage=exit_return",
  ];
  let offset = -1;
  for (const line of expected) {
    const next = trace.indexOf(line, offset + 1);
    if (next < 0) throw new Error(`正常退出轨迹缺少 ${line}`);
    offset = next;
  }
}

/**
 * 运行单个真窗阶段并按本轮身份清理；最终正常退出先通过认证的 JA-RPC shutdown 收口 idle daemon，
 * 因为关闭 Desktop client 本身只会断开共享 runtime 连接。
 * @author kongweiguang
 */
async function runPhase(
  directories,
  frontendPort,
  cdpPort,
  configPath,
  java,
  jar,
  targetDirectory,
  phase,
  launchOptions = {},
) {
  const launch = startTauri(
    directories,
    frontendPort,
    cdpPort,
    configPath,
    java,
    jar,
    targetDirectory,
    launchOptions,
  );
  let cleanup;
  let failure;
  let normalExitCompleted = false;
  try {
    let privateDesktopMarker;
    if (launch.privateDesktop === true)
      privateDesktopMarker = await waitForPrivateDesktop(launch);
    const hiddenPromise =
      launch.privateDesktop === true
        ? Promise.resolve(true)
        : launch.backendMode === "native"
          ? hideOwnedDesktopWindow(launch)
          : Promise.resolve(true);
    const [owned, hidden] = await Promise.all([rememberOwnedProcessTree(launch), hiddenPromise]);
    if (!owned) throw new Error("launcher PID 身份未能在 CDP 验收前核实");
    if (!hidden) throw new Error("Native 安装版窗口未能在 CDP 验收前隐藏");
    await waitForCdp(cdpPort, launch, Date.now() + 120_000);
    await rememberOwnedProcessTree(launch, Date.now() + 5_000);
    if (privateDesktopMarker !== undefined) {
      const guiOwner = launch.ownedProcesses.get(privateDesktopMarker.pid);
      if (guiOwner === undefined)
        throw new Error("私有 Desktop GUI PID 在 CDP 就绪后脱离本轮 launcher 子树");
      await writeFile(
        join(directories.artifacts, "private-desktop-launch.json"),
        `${JSON.stringify({
          ...privateDesktopMarker,
          wrapperPid: launch.child.pid,
          guiProcessCreationDate: guiOwner.creationDate,
          cdpReady: true,
          foregroundOrInputCalls: 0,
        }, null, 2)}\n`,
        "utf8",
      );
    }
    const exitCode = await runDriver(
      directories,
      cdpPort,
      phase,
      launchOptions.scenario ?? "full",
      launchOptions.backendMode ?? "jvm",
    );
    if (exitCode !== 0) throw new Error(`${phase} 侧聊 driver 失败 code=${exitCode}`);
    if (phase === "restart") {
      await waitForLauncherExit(launch);
      normalExitCompleted = true;
    }
  } catch (error) {
    failure = error;
  } finally {
    if (phase === "restart" && normalExitCompleted) {
      try {
        await stopIsolatedRuntimeAfterNormalExit(directories, launch);
      } catch (error) {
        launch.cleanupWarnings.push(
          `本轮 isolated App Server graceful shutdown 未闭合：${error instanceof Error ? error.message : "unknown error"}`,
        );
      }
    }
    cleanup = await stopTauri(launch, { force: phase !== "restart" || !normalExitCompleted });
  }
  if (cleanup.warnings.length > 0)
    throw new Error(`${phase} cleanup 未闭合：${cleanup.warnings.join("；")}`);
  if (failure !== undefined) throw failure;
}

/**
 * 正常退出必须删除目标侧聊及隔离库中的全部临时标记；持久 Thread 可以继续存在。
 * V1 将临时生命周期内联到 Thread，不能借旧独立表制造已清理的假象。
 */
export function assertNormalExitSideChatsPurged(database, sideThreadId) {
  if (typeof sideThreadId !== "string" || sideThreadId.length === 0)
    throw new Error("正常退出缺少存活侧聊身份");
  const remaining = database
    .prepare("SELECT COUNT(*) AS count FROM ja_conversation_thread WHERE thread_id=?")
    .get(sideThreadId);
  const markers = database
    .prepare(
      "SELECT COUNT(*) AS count FROM ja_conversation_thread WHERE temporary_lifecycle_state IS NOT NULL",
    )
    .get();
  if (remaining.count !== 0 || markers.count !== 0) throw new Error("正常退出未清除存活临时侧聊");
}

/** 创建隔离 profile 并收口本轮真窗；Native 生产二进制只在无同名进程时启动且所有持久状态落入私有 JA_HOME。@author kongweiguang */
async function main() {
  if (process.platform !== "win32") throw new Error("侧聊 runner 仅支持 Windows 11");
  const options = parseSideChatRunnerArguments(process.argv.slice(2));
  if (options.backendMode === "native")
    await assertNoRunningDesktopInstance(options.desktopExecutable);
  const { java, jar } =
    options.backendMode === "jvm" ? await resolveJavaAndJar() : { java: "", jar: "" };
  const configuredTarget =
    process.env.JA_E2E_SIDE_CHAT_TARGET_DIR?.trim() ||
    process.env.JA_E2E_SIDE_CHAT_CARGO_TARGET_DIR?.trim();
  const targetDirectory = resolve(configuredTarget || defaultTargetDirectory);
  const realModel = options.scenario.startsWith("real-model");
  const root = realModel
    ? options.runRoot
    : await mkdtemp(join(tmpdir(), "ja-side-chat-"));
  if (realModel) {
    try {
      await lstat(root);
      throw new Error("真实模型 run-root 已存在；为保护既有证据拒绝覆盖");
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
  }
  const directories = {
    root,
    settings: join(root, "profile"),
    appdata: join(root, "appdata"),
    localappdata: join(root, "localappdata"),
    runtime: join(root, "runtime"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
    artifacts: join(root, realModel ? "evidence" : "artifacts"),
    package: join(root, "native-package"),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  const realModelHomeSecurity = realModel
    ? await copyRealModelHome(directories.settings, options.sourceJaHome)
    : undefined;
  if (!realModel) await writeIsolatedHome(directories.settings);
  if (realModelHomeSecurity !== undefined) {
    await writeFile(
      join(directories.artifacts, "real-model-home-security.json"),
      `${JSON.stringify(
        {
          copiedFiles: realModelHomeSecurity.copiedFiles,
          byteIdentityVerified: realModelHomeSecurity.byteIdentityVerified,
          authAcl: realModelHomeSecurity.authAcl,
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
  }
  const frontendPort = options.backendMode === "jvm" ? await reservePort() : undefined;
  const cdpPort = await reservePort(new Set(frontendPort === undefined ? [] : [frontendPort]));
  let launchOptions = {
    backendMode: options.backendMode,
    scenario: options.scenario,
    privateDesktop: realModel,
  };
  if (options.backendMode === "native") {
    const staged = await stageNativeDesktopPackage({
      desktopExecutable: options.desktopExecutable,
      nativeExecutable: options.nativeExecutable,
      packageRoot: directories.package,
    });
    launchOptions = {
      ...launchOptions,
      desktopExecutable: staged.executable,
      packageRoot: staged.packageRoot,
      nativeExecutable: staged.sidecar,
      scenario: options.scenario,
    };
    await writeFile(
      join(directories.artifacts, "side-chat-native-stage.json"),
      `${JSON.stringify(
        {
          backendMode: options.backendMode,
          desktop: staged.desktop,
          native: staged.native,
          isolatedPackageRoot: staged.packageRoot,
          jaHome: join(directories.settings, ".ja"),
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
  }
  const configPath =
    options.backendMode === "jvm"
      ? await writeTauriOverlay(directories.runtime, frontendPort)
      : undefined;
  let exitCode = 1;
  try {
    if (!realModel) {
      await primeWebViewProfile(
        directories,
        frontendPort,
        cdpPort,
        configPath,
        java,
        jar,
        targetDirectory,
        launchOptions,
      );
    }
    await runPhase(
      directories,
      frontendPort,
      cdpPort,
      configPath,
      java,
      jar,
      targetDirectory,
      "initial",
      launchOptions,
    );
    if (realModel) {
      const report = JSON.parse(await readFile(join(directories.artifacts, "real-model-desktop.json"), "utf8"));
      const expectedStatus =
        options.scenario === "real-model-smoke"
          ? "real_model_desktop_smoke_passed"
          : "real_model_desktop_passed";
      if (report.status !== expectedStatus)
        throw new Error("真实模型桌面 driver 未给出完整验收证据");
      exitCode = 0;
    } else if (options.scenario === "approvals") {
      const approvalReport = JSON.parse(
        await readFile(join(directories.artifacts, "side-chat-approvals.json"), "utf8"),
      );
      if (approvalReport.status !== "approvals_passed")
        throw new Error("审批专项缺少真实允许、拒绝、取消、恢复与归属证据");
      exitCode = 0;
    } else if (process.env.JA_E2E_SIDE_CHAT_SUBAGENT_ONLY === "1") {
      const focused = JSON.parse(
        await readFile(join(directories.artifacts, "side-chat-initial.json"), "utf8"),
      );
      if (
        focused.status !== "subagent_passed" ||
        !focused.close.closedThreadIds.includes(focused.childTaskThreadId)
      ) {
        throw new Error("子任务专项缺少真实完成与子树关闭证据");
      }
      exitCode = 0;
    } else {
      await requireInitialEvidence(directories.artifacts);
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 2_000));
      await runPhase(
        directories,
        frontendPort,
        cdpPort,
        configPath,
        java,
        jar,
        targetDirectory,
        "restart",
        launchOptions,
      );
      await requireNormalExitEvidence(directories.runtime, options.backendMode);
      // 退出后只读精确隔离数据库，验证退出前仍存活的侧聊确实由正常生命周期清除。
      const restart = JSON.parse(
        await readFile(join(directories.artifacts, "side-chat-restart.json"), "utf8"),
      );
      const database = new DatabaseSync(join(directories.settings, ".ja", "data", "ja.db"), {
        readOnly: true,
      });
      try {
        assertNormalExitSideChatsPurged(database, restart.normalExitSideThreadId);
      } finally {
        database.close();
      }
      exitCode = 0;
    }
  } catch (error) {
    process.stderr.write(
      `JA_SIDE_CHAT_RUNNER_FAILED ${error instanceof Error ? error.message : String(error)}\n`,
    );
  }
  process.stdout.write(
    `JA_SIDE_CHAT_RUNNER_EXIT code=${exitCode} backend=${options.backendMode} artifacts=${directories.artifacts} target=${targetDirectory} cdp=${cdpPort}\n`,
  );
  if (exitCode !== 0) process.exitCode = exitCode;
}

const invokedPath =
  process.argv[1] === undefined ? undefined : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) await main();
