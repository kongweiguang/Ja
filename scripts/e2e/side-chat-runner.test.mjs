// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import test from "node:test";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { DatabaseSync } from "node:sqlite";
import { JSDOM } from "jsdom";
import {
  assertRecordedBackendExitedWithoutEndpoint,
  assertNormalExitSideChatsPurged,
  buildHideOwnedDesktopWindowScript,
  buildRealModelAuthAclScript,
  buildRuntimeListenerOwnerQueryScript,
  buildTauriEnvironment,
  copyRealModelHome,
  parseSideChatRunnerArguments,
  parsePrivateDesktopMarker,
  runtimeDirectoriesForBackend,
  sha256File,
  stageNativeDesktopPackage,
  writeTauriOverlay,
} from "./side-chat-runner.mjs";

const execFileAsync = promisify(execFile);

/** 在私有临时目录用无敏感 fixture 核验真实凭据 ACL 要求的单 SID、protected、无继承 ACE。@author kongweiguang */
test("real model auth copy verifies a protected single-current-user FullControl ACL", async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ja-real-model-home-acl-"));
  const sourceJaHome = join(temporaryRoot, "source-ja-home");
  const profileRoot = join(temporaryRoot, "profile");
  const config = "[app]\nmode = \"fixture\"\n";
  const auth = '{"credential_fixture":"non-secret-test-value"}\n';

  try {
    await mkdir(sourceJaHome, { recursive: true });
    await writeFile(join(sourceJaHome, "config.toml"), config, "utf8");
    await writeFile(join(sourceJaHome, "auth.json"), auth, "utf8");
    const copied = await copyRealModelHome(profileRoot, sourceJaHome);

    assert.deepEqual(copied.copiedFiles, ["config.toml", "auth.json"]);
    assert.equal(copied.byteIdentityVerified, true);
    assert.deepEqual(copied.authAcl, {
      ownerIsCurrentSid: true,
      protected: true,
      accessRuleCount: 1,
      onlyFullControl: true,
    });
    assert.equal(await readFile(join(copied.destination, "config.toml"), "utf8"), config);
    assert.equal(await readFile(join(copied.destination, "auth.json"), "utf8"), auth);
    assert.match(buildRealModelAuthAclScript(join(copied.destination, "auth.json")), /SetAccessRuleProtection\(\$true, \$false\)/u);
    assert.throws(() => buildRealModelAuthAclScript("relative\\auth.json"), /绝对文件路径/u);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

/** 使用现行 V1 原始 DDL，确保退出验收会拒绝 OPEN/CLOSING 残留而接受持久 Thread。 */
test("normal side-chat exit requires target and all inline lifecycle markers to be removed", async () => {
  const database = new DatabaseSync(":memory:");
  try {
    const schema = await readFile(
      new URL(
        "../../ja-agent/ja-app-server/src/main/resources/db/migration/V1__kernel.sql",
        import.meta.url,
      ),
      "utf8",
    );
    database.exec(schema);
    database
      .prepare(
        "INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms) " +
          "VALUES('workspace_fixture','C:/fixture','PROJECT','Fixture','TRUSTED',1,1)",
      )
      .run();
    const insert = database.prepare(
      "INSERT INTO ja_conversation_thread(thread_id,owner_user_id,temporary_lifecycle_state,workspace_id,title," +
        "created_at_ms,updated_at_ms,provider_id,model_id,access_mode,title_source) " +
        "VALUES(?,'user_local',?,'workspace_fixture','Fixture',1,1,'provider_fixture','model_fixture','FULL_ACCESS','MANUAL')",
    );
    insert.run("thread_persistent", null);
    assert.doesNotThrow(() => assertNormalExitSideChatsPurged(database, "thread_removed"));
    for (const state of ["OPEN", "CLOSING"]) {
      insert.run("thread_side", state);
      assert.throws(() => assertNormalExitSideChatsPurged(database, "thread_side"), /未清除/u);
      assert.throws(() => assertNormalExitSideChatsPurged(database, "thread_removed"), /未清除/u);
      database.prepare("DELETE FROM ja_conversation_thread WHERE thread_id='thread_side'").run();
    }
    assert.throws(() => assertNormalExitSideChatsPurged(database, "thread_persistent"), /未清除/u);
    assert.throws(() => assertNormalExitSideChatsPurged(database, ""), /缺少/u);
  } finally {
    database.close();
  }
});

/** 临时配置位置不同于仓库配置；Vite hook 必须仍从仓库根启动。 */
test("side-chat overlay anchors its Vite hook to the repository root", async () => {
  const runtimeRoot = await mkdtemp(join(tmpdir(), "ja-side-chat-overlay-"));
  try {
    const configPath = await writeTauriOverlay(runtimeRoot, 41_000);
    const config = JSON.parse(await readFile(configPath, "utf8"));
    const expectedRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

    assert.equal(config.build.devUrl, "http://127.0.0.1:41000");
    assert.deepEqual(config.build.beforeDevCommand, {
      script: "pnpm dev",
      cwd: expectedRoot,
    });
  } finally {
    await rm(runtimeRoot, { recursive: true, force: true });
  }
});

/** 校验 Windows 大小写不敏感的环境块只保留一个 PATH，并继续继承宿主工具搜索目录。 */
test("side-chat Tauri environment replaces the case-duplicate Windows Path key", () => {
  const java = String.raw`C:\jdks\liberica-25\bin\java.exe`;
  const inherited = {
    Path: String.raw`C:\tools\pnpm;C:\tools\cargo`,
    USERPROFILE: String.raw`C:\Users\host`,
    PSModuleAnalysisCachePath: String.raw`C:\Users\host\analysis.cache`,
  };
  const directories = {
    appdata: String.raw`C:\Temp\side-chat\appdata`,
    localappdata: String.raw`C:\Temp\side-chat\localappdata`,
    settings: String.raw`C:\Temp\side-chat\profile`,
    runtime: String.raw`C:\Temp\side-chat\runtime`,
    workspace: String.raw`C:\Temp\side-chat\workspace`,
    webview: String.raw`C:\Temp\side-chat\webview`,
  };

  const environment = buildTauriEnvironment({
    directories,
    frontendPort: 41_000,
    cdpPort: 41_001,
    java,
    jar: String.raw`C:\Temp\ja-app-server.jar`,
    targetDirectory: String.raw`C:\Temp\side-chat\cargo`,
    baseEnvironment: inherited,
  });

  assert.equal(environment.PATH, `${dirname(java)};${inherited.Path}`);
  assert.equal("Path" in environment, false);
  assert.equal(environment.TAURI_DEV_HOST, "127.0.0.1");
  assert.deepEqual(
    Object.keys(environment).filter((name) => name.toLowerCase() === "path"),
    ["PATH"],
  );
  assert.equal(environment.PSModuleAnalysisCachePath, inherited.PSModuleAnalysisCachePath);
  assert.equal(environment.WEBVIEW2_USER_DATA_FOLDER, directories.webview);
});

/** Native 安装版只接受明确 EXE 对，避免无意把 JVM overlay 当生产二进制验收。@author kongweiguang */
test("native side-chat runner arguments require both absolute production executables", () => {
  assert.deepEqual(parseSideChatRunnerArguments([]), { backendMode: "jvm", scenario: "full" });
  assert.throws(() => parseSideChatRunnerArguments(["--backend-mode", "native"]), /必须同时提供/u);
  assert.throws(() => parseSideChatRunnerArguments(["--unknown", "value"]), /未知侧聊验收参数/u);
  assert.throws(
    () =>
      parseSideChatRunnerArguments([
        "--backend-mode",
        "native",
        "--desktop-executable",
        String.raw`C:\install\Ja.exe`,
      ]),
    /必须同时提供/u,
  );
  assert.throws(
    () =>
      parseSideChatRunnerArguments([
        "--backend-mode",
        "native",
        "--desktop-executable",
        "Ja.exe",
        "--native-executable",
        String.raw`C:\native\ja-app-server.exe`,
      ]),
    /绝对路径/u,
  );
  assert.deepEqual(
    parseSideChatRunnerArguments([
      "--backend-mode",
      "native",
      "--desktop-executable",
      String.raw`C:\install\Ja.exe`,
      "--native-executable",
      String.raw`C:\native\ja-app-server.exe`,
    ]),
    {
      backendMode: "native",
      scenario: "full",
      desktopExecutable: resolve(String.raw`C:\install\Ja.exe`),
      nativeExecutable: resolve(String.raw`C:\native\ja-app-server.exe`),
    },
  );
  assert.deepEqual(parseSideChatRunnerArguments(["--scenario", "approvals"]), {
    backendMode: "jvm",
    scenario: "approvals",
  });
  assert.deepEqual(
    parseSideChatRunnerArguments([
      "--backend-mode",
      "native",
      "--desktop-executable",
      String.raw`C:\install\Ja.exe`,
      "--native-executable",
      String.raw`C:\install\sidecars\ja-app-server-x86_64-pc-windows-msvc.exe`,
      "--scenario",
      "approvals",
    ]),
    {
      backendMode: "native",
      scenario: "approvals",
      desktopExecutable: resolve(String.raw`C:\install\Ja.exe`),
      nativeExecutable: resolve(
        String.raw`C:\install\sidecars\ja-app-server-x86_64-pc-windows-msvc.exe`,
      ),
    },
  );
  assert.deepEqual(
    parseSideChatRunnerArguments([
      "--backend-mode",
      "native",
      "--desktop-executable",
      String.raw`C:\install\Ja.exe`,
      "--native-executable",
      String.raw`C:\install\sidecars\ja-app-server-x86_64-pc-windows-msvc.exe`,
      "--scenario",
      "real-model-smoke",
      "--source-ja-home",
      String.raw`C:\Users\tester\.ja`,
      "--run-root",
      String.raw`C:\Temp\ja-real-model\desktop`,
    ]),
    {
      backendMode: "native",
      scenario: "real-model-smoke",
      desktopExecutable: resolve(String.raw`C:\install\Ja.exe`),
      nativeExecutable: resolve(
        String.raw`C:\install\sidecars\ja-app-server-x86_64-pc-windows-msvc.exe`,
      ),
      sourceJaHome: resolve(String.raw`C:\Users\tester\.ja`),
      runRoot: resolve(String.raw`C:\Temp\ja-real-model\desktop`),
    },
  );
  assert.throws(() => parseSideChatRunnerArguments(["--scenario", "real-model"]), /真实模型验收需要/u);
  assert.throws(
    () =>
      parseSideChatRunnerArguments([
        "--backend-mode",
        "native",
        "--desktop-executable",
        String.raw`C:\install\Ja.exe`,
        "--native-executable",
        String.raw`C:\install\ja-app-server.exe`,
        "--scenario",
        "real-model",
        "--source-ja-home",
        String.raw`C:\Users\tester\.ja`,
        "--run-root",
        String.raw`relative\path`,
      ]),
    /绝对路径/u,
  );
  assert.throws(() => parseSideChatRunnerArguments(["--scenario", "unknown"]), /不支持的侧聊场景/u);
});

/** 私有 Desktop marker 只接受不同于 input desktop 的 launcher 闭集证据。@author kongweiguang */
test("private desktop marker is closed and projects only safe launch identity", () => {
  const marker = parsePrivateDesktopMarker(
    'JA_PRIVATE_DESKTOP_STARTED {"pid":125128,"desktop":"JaE2E_0123456789abcdef0123456789abcdef","privateDesktop":true,"inputDesktopDistinct":true,"switchDesktopUsed":false}',
  );
  assert.deepEqual(marker, {
    pid: 125_128,
    desktop: "JaE2E_0123456789abcdef0123456789abcdef",
    privateDesktop: true,
    inputDesktopDistinct: true,
    switchDesktopUsed: false,
  });
  assert.equal(parsePrivateDesktopMarker("unrelated stdout"), undefined);
  assert.throws(
    () =>
      parsePrivateDesktopMarker(
        'JA_PRIVATE_DESKTOP_STARTED {"pid":125128,"desktop":"Default","privateDesktop":true,"inputDesktopDistinct":true,"switchDesktopUsed":false}',
      ),
    /隔离桌面/u,
  );
});

/** Native 包 staging 应逐字节复用生产桌面包与传入 sidecar，并且只向临时根写入。@author kongweiguang */
test("native side-chat staging preserves source package and verifies copied executable hashes", async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ja-side-chat-native-stage-"));
  const productRoot = join(temporaryRoot, "product");
  const packageRoot = join(temporaryRoot, "isolated-package");
  const sidecars = join(productRoot, "sidecars");
  const tools = join(sidecars, "tools");
  const desktopExecutable = join(productRoot, "Ja.exe");
  const nativeExecutable = join(temporaryRoot, "ja-app-server.exe");
  const originalDesktop = "signed installer desktop bytes";
  const originalNative = "native sidecar bytes";

  try {
    await mkdir(tools, { recursive: true });
    await writeFile(desktopExecutable, originalDesktop);
    await writeFile(nativeExecutable, originalNative);
    await writeFile(join(sidecars, "existing-resource.txt"), "keep resource");
    await writeFile(join(tools, "rg.exe"), "tool bytes");

    const staged = await stageNativeDesktopPackage({
      desktopExecutable,
      nativeExecutable,
      packageRoot,
    });

    assert.equal(await readFile(staged.executable, "utf8"), originalDesktop);
    assert.equal(await readFile(staged.sidecar, "utf8"), originalNative);
    assert.equal(
      await readFile(join(packageRoot, "sidecars", "existing-resource.txt"), "utf8"),
      "keep resource",
    );
    assert.equal(
      await readFile(join(packageRoot, "sidecars", "tools", "rg.exe"), "utf8"),
      "tool bytes",
    );
    assert.equal(staged.sidecarResourcePath, "sidecars");
    assert.equal(staged.desktop.sha256, await sha256File(desktopExecutable));
    assert.equal(staged.native.sha256, await sha256File(nativeExecutable));
    assert.equal(await readFile(desktopExecutable, "utf8"), originalDesktop);
    assert.equal(await readFile(nativeExecutable, "utf8"), originalNative);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

/** 资源目录必须匹配 Rust resolver 的固定 `resource_dir/sidecars` 约定，不接受误放在 resources 子目录的包。@author kongweiguang */
test("native side-chat staging rejects sidecars outside the Tauri resource root", async () => {
  const temporaryRoot = await mkdtemp(join(tmpdir(), "ja-side-chat-native-resource-layout-"));
  const productRoot = join(temporaryRoot, "product");
  const nestedSidecars = join(productRoot, "resources", "sidecars");
  const desktopExecutable = join(productRoot, "Ja.exe");
  const nativeExecutable = join(temporaryRoot, "ja-app-server.exe");

  try {
    await mkdir(nestedSidecars, { recursive: true });
    await writeFile(desktopExecutable, "desktop bytes");
    await writeFile(nativeExecutable, "native bytes");
    await assert.rejects(
      stageNativeDesktopPackage({
        desktopExecutable,
        nativeExecutable,
        packageRoot: join(temporaryRoot, "isolated-package"),
      }),
      { code: "ENOENT" },
    );
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

/** Native 真窗不得继承宿主 JA_HOME 或 debug seam，否则会越过隔离 profile 读写用户数据。@author kongweiguang */
test("native side-chat environment isolates JA_HOME and strips development hooks", () => {
  const directories = {
    appdata: String.raw`C:\Temp\side-chat\appdata`,
    localappdata: String.raw`C:\Temp\side-chat\localappdata`,
    settings: String.raw`C:\Temp\side-chat\profile`,
    runtime: String.raw`C:\Temp\side-chat\runtime`,
    workspace: String.raw`C:\Temp\side-chat\workspace`,
    webview: String.raw`C:\Temp\side-chat\webview`,
  };
  const environment = buildTauriEnvironment({
    directories,
    frontendPort: undefined,
    cdpPort: 41_001,
    java: "",
    jar: "",
    targetDirectory: "",
    backendMode: "native",
    baseEnvironment: {
      PATH: String.raw`C:\tools`,
      USERPROFILE: String.raw`C:\Users\host`,
      JA_HOME: String.raw`C:\Users\host\.ja`,
      JA_E2E_RUNTIME_ROOT: String.raw`C:\Users\host\runtime`,
      JA_DEBUG_JAVA: String.raw`C:\Users\host\java.exe`,
      VITE_JA_E2E_PROJECT_PATH: String.raw`C:\Users\host\project`,
    },
  });

  assert.equal(environment.JA_HOME, join(directories.settings, ".ja"));
  assert.equal(environment.USERPROFILE, directories.settings);
  assert.equal(environment.PATH, String.raw`C:\tools`);
  assert.equal("JA_E2E_RUNTIME_ROOT" in environment, false);
  assert.equal("JA_DEBUG_JAVA" in environment, false);
  assert.equal("VITE_JA_E2E_PROJECT_PATH" in environment, false);
});

/** 每个 PowerShell 语句需可由目标 pwsh parser 编译；只解析脚本，不连接或操作任何真实窗口。@author kongweiguang */
test("native window hide script parses as separate PowerShell statements", async () => {
  const script = buildHideOwnedDesktopWindowScript(12_345, 20_000);
  const encodedScript = Buffer.from(script, "utf8").toString("base64");
  const parserCommand = [
    `$source=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedScript}'));`,
    "$tokens=$null; $errors=$null;",
    "[System.Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors) | Out-Null;",
    "if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Error $_.Message }; exit 1 };",
    "Write-Output 'POWERSHELL_AST_OK'",
  ].join(" ");
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", parserCommand],
    { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024 },
  );
  assert.match(String(result.stdout), /POWERSHELL_AST_OK/u);
  assert.throws(() => buildHideOwnedDesktopWindowScript(0, 20_000), /有效/u);
  assert.throws(() => buildHideOwnedDesktopWindowScript(12_345, 0), /正数/u);
});

/** Endpoint cleanup 查询只投影隔离 listener 身份且脚本可解析，不执行 shutdown 或读取凭据。@author kongweiguang */
test("isolated runtime owner query parses without exposing command line or token", async () => {
  const expectedPaths = {
    home: String.raw`C:\Temp\O'Brien\profile\.ja`,
    data: String.raw`C:\Temp\O'Brien\profile\.ja\data`,
    run: String.raw`C:\Temp\O'Brien\runtime`,
    log: String.raw`C:\Temp\O'Brien\profile\.ja\logs\java`,
    executablePath: String.raw`C:\Program Files\Ja\sidecars\ja-app-server.exe`,
  };
  const script = buildRuntimeListenerOwnerQueryScript(43_210, expectedPaths);
  const encodedScript = Buffer.from(script, "utf8").toString("base64");
  const parserCommand = [
    `$source=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedScript}'));`,
    "$tokens=$null; $errors=$null;",
    "[System.Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors) | Out-Null;",
    "if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Error $_.Message }; exit 1 };",
    "Write-Output 'POWERSHELL_AST_OK'",
  ].join(" ");
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", parserCommand],
    { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024 },
  );
  assert.match(String(result.stdout), /POWERSHELL_AST_OK/u);
  assert.match(script, /Get-NetTCPConnection -State Listen/u);
  assert.match(script, /homeMatches/u);
  assert.doesNotMatch(script, /O'Brien/u);
  assert.doesNotMatch(script, /token/u);
  assert.throws(
    () =>
      buildRuntimeListenerOwnerQueryScript(0, {
        home: String.raw`C:\Temp\profile\.ja`,
        data: String.raw`C:\Temp\profile\.ja\data`,
        run: String.raw`C:\Temp\runtime`,
        log: String.raw`C:\Temp\profile\.ja\logs\java`,
        executablePath: String.raw`C:\Program Files\Ja\ja-app-server.exe`,
      }),
    /有效端口/u,
  );

  const commandLine = ["home", "data", "run", "log"]
    .map(
      (role) =>
        `--${role}-dir-base64=${Buffer.from(expectedPaths[role], "utf8").toString("base64url")}`,
    )
    .join(" ");
  const ownerCommandLine = commandLine.replace(/'/gu, "''");
  const ownerExecutablePath = expectedPaths.executablePath.replace(/'/gu, "''");
  const mockedSource = [
    `$ownerCommandLine='${ownerCommandLine}'`,
    `$ownerExecutablePath='${ownerExecutablePath}'`,
    "function Get-NetTCPConnection { [pscustomobject]@{ OwningProcess = 43210 } }",
    "function Get-CimInstance([string]$ClassName, [string]$Filter) { [pscustomobject]@{ ProcessId = 43210; ParentProcessId = 32100; CreationDate = [DateTime]::UtcNow; Name = 'ja-app-server.exe'; ExecutablePath = $ownerExecutablePath; CommandLine = $ownerCommandLine } }",
    script,
  ].join("\n");
  const mockedCommand = [
    `$source=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${Buffer.from(mockedSource, "utf8").toString("base64")}'));`,
    "Invoke-Expression $source",
  ].join(" ");
  const mockedResult = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", mockedCommand],
    { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024 },
  );
  const identity = JSON.parse(String(mockedResult.stdout).trim());
  assert.equal(identity.pid, 43_210);
  assert.equal(identity.homeMatches, true);
  assert.equal(identity.dataMatches, true);
  assert.equal(identity.runMatches, true);
  assert.equal(identity.logMatches, true);
  assert.equal(identity.executableMatches, true);
  assert.equal("commandLine" in identity, false);
});

/** 锁定 release Native HomeLayout 与 debug JVM runtime override 的实际路径差异，防止漏清理后台 daemon。@author kongweiguang */
test("runtime cleanup routes Native and JVM endpoint directories by backend contract", () => {
  const directories = {
    settings: String.raw`C:\Temp\ja-side-chat-fixture\profile`,
    runtime: String.raw`C:\Temp\ja-side-chat-fixture\runtime`,
  };
  assert.deepEqual(runtimeDirectoriesForBackend(directories, "native"), {
    home: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja`,
    data: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja\data`,
    run: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja\run`,
    log: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja\logs\java`,
  });
  assert.deepEqual(runtimeDirectoriesForBackend(directories, "jvm"), {
    home: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja`,
    data: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja\data`,
    run: String.raw`C:\Temp\ja-side-chat-fixture\runtime`,
    log: String.raw`C:\Temp\ja-side-chat-fixture\profile\.ja\logs\java`,
  });
  assert.throws(() => runtimeDirectoriesForBackend(directories, "other"), /backend mode/u);
});

/** endpoint 缺失不能掩盖仍存活且已由本轮 launcher 记录的后台 backend process。@author kongweiguang */
test("missing endpoint only means stopped after the recorded backend owner exits", () => {
  const owner = {
    pid: 126_160,
    creationDate: "2026-10-15T08:01:41.000Z",
    name: "ja-app-server-x86_64-pc-windows-msvc.exe",
  };
  const launch = {
    runtimeExecutable:
      String.raw`C:\Temp\ja-side-chat-fixture\native-package\sidecars\ja-app-server-x86_64-pc-windows-msvc.exe`,
    ownedProcesses: new Map([[owner.pid, owner]]),
  };
  assert.throws(
    () =>
      assertRecordedBackendExitedWithoutEndpoint(
        [{ pid: owner.pid, creationDate: owner.creationDate, name: owner.name }],
        launch,
      ),
    /仍在运行/u,
  );
  assert.deepEqual(assertRecordedBackendExitedWithoutEndpoint([], launch), {
    status: "already_stopped",
    endpointAbsent: true,
    backendOwnerExited: true,
  });
  assert.throws(
    () => assertRecordedBackendExitedWithoutEndpoint([], { ...launch, ownedProcesses: new Map() }),
    /未记录 backend owner/u,
  );
});

/** 真窗 busy probe 的 locator 本身可能就是 form；回归直接以 jsdom 覆盖 self 与 wrapper 两种入口。@author kongweiguang */
test("side-chat Composer probe resolves both the form itself and a wrapper", async () => {
  const source = await readFile(new URL("./side-chat-webview2.mjs", import.meta.url), "utf8");
  assert.match(
    source,
    /element\.matches\("form\.ja-composer"\)\s*\?\s*element\s*:\s*element\.querySelector\("form\.ja-composer"\)/u,
  );
  const dom = new JSDOM('<form class="ja-composer"><textarea></textarea></form>');
  try {
    const form = dom.window.document.querySelector("form.ja-composer");
    const wrapper = dom.window.document.createElement("div");
    wrapper.append(form.cloneNode(true));
    const resolve = (element) =>
      element.matches("form.ja-composer") ? element : element.querySelector("form.ja-composer");
    assert.equal(resolve(form), form);
    assert.equal(resolve(wrapper), wrapper.firstElementChild);
  } finally {
    dom.window.close();
  }
});
