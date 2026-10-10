// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import test from "node:test";
import {
  captureRendererDiagnostics,
  countProjectPressureFiles,
  createProjectManagementFixture,
  installDirectProjectManagementInvokeProbe,
  installProjectManagementInvokeProbe,
  isExpectedWorkspaceBusySummary,
  parseArguments,
  projectDialogCancellationPassed,
  summarizeProjectBindingInvokes,
  startProjectManagementProviderFixture,
  validateProjectManagementReport,
  validateWorkspaceUpdateDiagnostic,
} from "./project-management-webview2.mjs";
import { createReviewGitFixture } from "./review-redesign-production.mjs";

/** @author kongweiguang 真实 Dialog 返回详情；缺少取消或焦点恢复事实不能靠非空对象通过总报告。 */
test("dialog cancellation projects complete native evidence to a boolean", () => {
  const result = {
    cancelled: true,
    escapeRestoresFocusToProject: true,
    escapeRestoresToVisibleFallbackNarrow: true,
  };
  assert.equal(projectDialogCancellationPassed(result), true);
  assert.equal(projectDialogCancellationPassed({ ...result, cancelled: false }), false);
  assert.equal(
    projectDialogCancellationPassed({ ...result, escapeRestoresFocusToProject: false }),
    false,
  );
  assert.equal(projectDialogCancellationPassed({ cancelled: true }), false);
  assert.equal(projectDialogCancellationPassed(undefined), false);
});

/** @author kongweiguang 摘要应包含真正的会话标题；项目名或通用不可用信息不能通过此验收。 */
test("busy summary checks the actual Thread title instead of the project name", () => {
  const message =
    "暂时无法更改项目目录：占用会话：会话‘历史验收会话’：运行中。请先等待运行任务结束后重试。";
  assert.equal(isExpectedWorkspaceBusySummary(message, "历史验收会话"), true);
  assert.equal(isExpectedWorkspaceBusySummary(message, "项目名称"), false);
  assert.equal(isExpectedWorkspaceBusySummary("运行时暂不可用", "历史验收会话"), false);
  assert.equal(isExpectedWorkspaceBusySummary(message, ""), false);
});

/** @author kongweiguang 即便报告成功落盘，真实布局或繁忙反馈失败也必须让短诊断失败关闭。 */
test("workspace update diagnostic rejects incomplete acceptance", () => {
  assert.throws(
    () => validateWorkspaceUpdateDiagnostic({ status: "diagnostic_complete" }),
    /fixture.workspaceReadProbePositiveControl/u,
  );
});

/** 创建仅在系统 Temp 内的目录供参数、隔离边界和清理断言复用。 */
async function withTemporaryDirectory(operation) {
  const root = await mkdtemp(join(tmpdir(), "ja-project-management-test-"));
  try {
    await operation(root);
  } finally {
    const relation = relative(resolve(tmpdir()), resolve(root));
    assert.ok(relation.length > 0 && !relation.startsWith(".."));
    await rm(root, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
}

/** @author kongweiguang 构造完整报告基准；失败关闭校验禁止只启动真窗就声称项目恢复通过。 */
function validReport() {
  return {
    contractVersion: 1,
    status: "passed",
    runtime: { platform: "win32", surface: "tauri_webview2", boundary: "debug_jar" },
    coverage: {
      workspaceIdFormat: true,
      projectMenuFourItems: true,
      lightDarkThemesVerified: true,
      keyboardContextMenu: true,
      escapeRestoresFocus: true,
      cancelDiscardedDraft: true,
      narrowDialogFitsViewport: true,
      longPathDialogLayoutVerified: true,
      directoryNoticeHiddenDuringEdit: true,
      projectPathCopiedToNativeClipboard: true,
      missingDirectoryRecheckPreservedHistory: true,
      missingDirectoryBindingStable: true,
      initialHistoryVisible: true,
      busyTurnBlockedDirectoryChange: true,
      busyErrorVisible: true,
      busyUpdateReturnedExpectedSummary: true,
      missingDirectorySelectable: true,
      missingDirectoryHistoryVisible: true,
      missingDirectoryErrorVisible: true,
      renameWithMissingDirectory: true,
      directoryRebound: true,
      workspaceIdPreservedAfterRestart: true,
      threadIdPreservedAfterRestart: true,
      historyVisibleAfterRestart: true,
      serverRestartVerified: true,
      workspaceReadProbePositiveControl: true,
      sameWorkspaceThreadSwitchVerified: true,
      dirtyDocumentSaveCancelDiscardVerified: true,
      pressureFixtureCountsVerified: true,
      hiddenReviewSnapshotStable: true,
      hiddenFilesIoZero: true,
      menuItemCount: 4,
      menuItemLabels: ["新建对话", "编辑项目…", "在资源管理器中打开", "复制项目路径"],
    },
    pageErrors: [],
    screenshots: ["menu-light.png", "menu-dark.png", "history.png", "restart.png"],
  };
}

/** CLI 参数拒绝复用用户目录并要求共享 target，避免测试静默启动另一套 Tauri build。 */
test("project management runner requires the shared built target and Temp evidence directory", () => {
  const evidenceDirectory = join(tmpdir(), "ja-project-management-evidence");
  const cargoTargetDirectory = join(tmpdir(), "ja-shared-built-target");
  const parsed = parseArguments([
    "--diagnose-binding",
    "--evidence-directory",
    evidenceDirectory,
    "--jar",
    join(tmpdir(), "ja-app-server.jar"),
    "--cargo-target-directory",
    cargoTargetDirectory,
  ]);

  assert.equal(parsed.evidenceDirectory, resolve(evidenceDirectory));
  assert.equal(parsed.cargoTargetDirectory, resolve(cargoTargetDirectory));
  assert.equal(parsed.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.equal(parsed.diagnoseBinding, true);
  assert.equal(parsed.diagnoseWorkspaceUpdate, false);
  const workspaceUpdateDiagnostic = parseArguments([
    "--diagnose-workspace-update",
    "--evidence-directory",
    evidenceDirectory,
    "--jar",
    join(tmpdir(), "ja-app-server.jar"),
    "--cargo-target-directory",
    cargoTargetDirectory,
  ]);
  assert.equal(workspaceUpdateDiagnostic.diagnoseWorkspaceUpdate, true);
  assert.throws(
    () => parseArguments(["--evidence-directory", evidenceDirectory, "--jar", "app.jar"]),
    /--cargo-target-directory is required/u,
  );
  assert.throws(
    () =>
      parseArguments([
        "--evidence-directory",
        resolve(tmpdir(), "..", "outside-temp"),
        "--jar",
        "app.jar",
        "--cargo-target-directory",
        cargoTargetDirectory,
      ]),
    /inside the operating system Temp/u,
  );
  assert.throws(
    () =>
      parseArguments([
        "--diagnose-binding",
        "--diagnose-workspace-update",
        "--evidence-directory",
        evidenceDirectory,
        "--jar",
        join(tmpdir(), "ja-app-server.jar"),
        "--cargo-target-directory",
        cargoTargetDirectory,
      ]),
    /cannot be combined/u,
  );
});

/** 原生调用探针只保留命令、错误码、短项目 ID 和耗时，避免诊断泄露 cwd/配置输入。 */
test("direct native invoke probe records safe results and redacts arguments and error details", async () => {
  const storage = {
    value: "[]",
    getItem() {
      return this.value;
    },
    setItem(_key, value) {
      this.value = value;
    },
  };
  const internals = {
    async invoke(command) {
      if (command === "ja_runtime_workspace_open")
        return { workspaceId: "ws_1234567890abcdef1234567890abcdef" };
      throw new Error("invalid args for private path C:\\Users\\fixture\\secret");
    },
  };
  const targetWindow = { __TAURI_INTERNALS__: internals, sessionStorage: storage };
  assert.deepEqual(installDirectProjectManagementInvokeProbe({ window: targetWindow, storage }), {
    installed: true,
    reused: false,
  });
  const workspace = await internals.invoke("ja_runtime_workspace_open", {
    input: { cwd: "C:\\Users\\fixture\\private-workspace" },
  });
  assert.equal(workspace.workspaceId, "ws_1234567890abcdef1234567890abcdef");
  await assert.rejects(
    internals.invoke("ja_runtime_workspace_read", {
      input: { workspaceId: "ws_1234567890abcdef1234567890abcdef" },
    }),
    /private path/u,
  );
  const records = JSON.parse(storage.value);
  assert.deepEqual(records, [
    {
      command: "ja_runtime_workspace_open",
      startedAt: records[0].startedAt,
      phase: "fulfilled",
      durationMs: records[0].durationMs,
      resultWorkspaceId: "ws_123456789",
    },
    {
      command: "ja_runtime_workspace_read",
      workspaceId: "ws_123456789",
      startedAt: records[1].startedAt,
      phase: "rejected",
      durationMs: records[1].durationMs,
      errorCode: "ARGUMENT_DESERIALIZATION",
    },
  ]);
  assert.doesNotMatch(storage.value, /private-workspace|private path|C:\\\\Users/u);
  assert.deepEqual(summarizeProjectBindingInvokes(records), {
    counts: {
      ja_runtime_workspace_open: { pending: 0, fulfilled: 1, rejected: 0 },
      ja_runtime_workspace_read: { pending: 0, fulfilled: 0, rejected: 1 },
    },
    rejected: [
      {
        command: "ja_runtime_workspace_read",
        workspaceId: "ws_123456789",
        errorCode: "ARGUMENT_DESERIALIZATION",
        durationMs: records[1].durationMs,
      },
    ],
    pending: [],
  });
});

/** addInitScript 序列化后的 probe 必须能运行且记录 positive control，不依赖 Node 模块闭包。 */
test("project invoke probe records real workspace reads using only safe command identity", async () => {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const previousRecords = globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__;
  try {
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = undefined;
    installProjectManagementInvokeProbe();
    const result = await globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__(
      {
        command: "ja_runtime_workspace_read",
        args: { input: { workspaceId: "ws_1234567890abcdef1234567890abcdef" } },
      },
      async () => ({ displayName: "private project name", root: "C:\\private" }),
    );
    assert.equal(result.displayName, "private project name");
    assert.deepEqual(
      globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__.map(({ command, phase, workspaceId }) => ({
        command,
        phase,
        workspaceId,
      })),
      [
        {
          command: "ja_runtime_workspace_read",
          phase: "start",
          workspaceId: "ws_123456789",
        },
        {
          command: "ja_runtime_workspace_read",
          phase: "resolved",
          workspaceId: "ws_123456789",
        },
      ],
    );
    assert.doesNotMatch(JSON.stringify(globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__), /private/u);
  } finally {
    if (previous === undefined) delete globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    else globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous;
    if (previousRecords === undefined) delete globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__;
    else globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__ = previousRecords;
  }
});

/** @author kongweiguang 验证目录 Busy 原始元组进入 E2E 探针且拒绝文案、路径与标题不会落入证据。 */
test("project invoke probe retains only the safe workspace busy rejection tuple", async () => {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const previousRecords = globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__;
  try {
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = undefined;
    installProjectManagementInvokeProbe();
    await assert.rejects(
      globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__(
        {
          command: "ja_runtime_workspace_update",
          args: {
            input: {
              workspaceId: "ws_1234567890abcdef1234567890abcdef",
              cwd: "C:\\private\\directory",
            },
          },
        },
        async () => {
          throw Object.assign(
            new Error("暂时无法更改项目目录：会话‘private title’仍运行。C:\\private\\directory"),
            { code: "WORKSPACE_BUSY", retryable: true },
          );
        },
      ),
      /private title/u,
    );
    const records = globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__;
    assert.deepEqual(
      records.map(({ command, phase, workspaceId, failure }) => ({
        command,
        phase,
        workspaceId,
        failure,
      })),
      [
        {
          command: "ja_runtime_workspace_update",
          phase: "start",
          workspaceId: "ws_123456789",
          failure: undefined,
        },
        {
          command: "ja_runtime_workspace_update",
          phase: "rejected",
          workspaceId: "ws_123456789",
          failure: {
            errorCode: "WORKSPACE_BUSY",
            runnerPhaseClassification: "busy_summary_shape",
            retryable: true,
          },
        },
      ],
    );
    assert.doesNotMatch(JSON.stringify(records), /private title|private\\\\directory/u);
  } finally {
    if (previous === undefined) delete globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    else globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous;
    if (previousRecords === undefined) delete globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__;
    else globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__ = previousRecords;
  }
});

/** 项目搬迁仅触及本用例创建的 Unicode Temp 目录，拒绝依赖仓库或用户项目路径。 */
test("project fixture creates only temporary Unicode paths for original, moved and comparison projects", async () => {
  await withTemporaryDirectory(async (root) => {
    const fixture = await createProjectManagementFixture(root);
    for (const directory of [fixture.originalRoot, fixture.movedRoot, fixture.otherRoot]) {
      const relation = relative(resolve(root), resolve(directory));
      assert.ok(relation.length > 0 && !relation.startsWith(".."));
      assert.equal((await stat(directory)).isDirectory(), true);
    }
    assert.match(fixture.originalRoot, /原始 项目 工作区/u);
    assert.match(fixture.movedRoot, /搬迁后 项目 工作区/u);
  });
});

/** 通过真正的 Git NUL-delimited 输出计数，固定 ignored/untracked 压力夹具核验而非只信参数。 */
test("pressure fixture counts actual ignored and untracked files beneath the selected project root", async () => {
  await withTemporaryDirectory(async (root) => {
    const workspace = join(root, "中文 带空格 项目");
    const expected = { ignoredFiles: 3, untrackedFiles: 2 };
    await createReviewGitFixture(workspace, {
      fixture: "full",
      ...expected,
    });
    assert.deepEqual(await countProjectPressureFiles(workspace), expected);
  });
});

/** 在途请求只记录闭集计数，确保忙碌门禁真实而报告不含用户正文或路径。 */
test("project management provider holds busy Turns and exposes only closed count evidence", async () => {
  const fixture = await startProjectManagementProviderFixture();
  try {
    const pendingResponse = fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        input: [{ content: [{ text: "JA_PROJECT_MANAGEMENT_BUSY_TURN" }] }],
      }),
    });
    assert.equal(await fixture.waitForBusyTurn(2_000), true);
    fixture.releaseBusyTurn();
    const response = await pendingResponse;
    assert.equal(response.status, 200);
    assert.match(await response.text(), /JA_PROJECT_MANAGEMENT_BUSY_TURN_COMPLETED/u);
    assert.deepEqual(fixture.snapshot(), {
      requestCount: 1,
      acceptanceTurnCount: 0,
      busyTurnCount: 1,
    });
  } finally {
    fixture.releaseBusyTurn();
    await fixture.close();
  }
});

/** 成功报告要求身份、交互与重启证据齐全，防止半程 UI 启动被算作通过。 */
test("project management report rejects missing closed-loop restart or interaction evidence", () => {
  const report = validReport();
  assert.equal(validateProjectManagementReport(report), report);
  report.coverage.workspaceIdPreservedAfterRestart = false;
  assert.throws(() => validateProjectManagementReport(report));
  const flickering = validReport();
  flickering.coverage.missingDirectoryBindingStable = false;
  assert.throws(
    () => validateProjectManagementReport(flickering),
    /missingDirectoryBindingStable/u,
  );
});

/** 验证 React boundary 的 console Error.stack、界面 DOM 和失败截图在真窗关闭前一起落入 Temp。 */
test("renderer failure diagnostics preserve React console stack and visible DOM evidence", async () => {
  await withTemporaryDirectory(async (root) => {
    let screenshotPath;
    const page = {
      async evaluate() {
        return {
          url: "http://tauri.localhost/",
          projectRows: [],
          shell: [{ ready: "false", text: "React fallback" }],
          bodyText: "React fallback",
        };
      },
      async screenshot(options) {
        screenshotPath = options.path;
        await writeFile(screenshotPath, "isolated screenshot placeholder", "utf8");
      },
    };
    const errors = [
      {
        type: "console.error",
        text: "The above error occurred in the <App> component.",
        location: { url: "http://tauri.localhost/src/App.tsx", lineNumber: 1, columnNumber: 2 },
        handles: [
          {
            async evaluate() {
              return {
                name: "TypeError",
                message: "cannot render project metadata",
                stack: "TypeError: cannot render project metadata\\n at ProjectSidebar",
              };
            },
          },
        ],
      },
    ];

    const result = await captureRendererDiagnostics(page, errors, root, "boundary");
    const consoleEvidence = await readFile(join(root, "boundary-console-errors.json"), "utf8");
    const domEvidence = await readFile(join(root, "boundary-dom.json"), "utf8");
    assert.match(consoleEvidence, /ProjectSidebar/u);
    assert.match(domEvidence, /React fallback/u);
    assert.equal(result.consoleErrors[0].arguments[0].name, "TypeError");
    assert.ok(screenshotPath?.startsWith(root));
    assert.match(await readFile(screenshotPath, "utf8"), /placeholder/u);
  });
});
