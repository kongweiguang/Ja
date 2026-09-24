// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { parseArguments, validateContextMenusWebView2Report } from "./context-menus-webview2.mjs";

/** 构造完整的真窗证据合同，保留可选剪贴板未覆盖项以防报告冒充完整能力。 */
function validReport() {
  const readSample = (callId, phase) => ({
    callId,
    command: "ja_workspace_read_file",
    phase,
    workspaceMatchesFixture: true,
    pathMatchesExpected: true,
  });
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    coverage: {
      navigation: {
        projectMenu: { pointerTargetStable: true, escapeRestoresFocus: true },
        conversationMenu: {
          rightClickTargetStable: true,
          pinAndUnpin: true,
          renamePreservesIdentity: true,
        },
      },
      workbenchTabs: {
        tabKinds: ["files", "preview", "terminal"],
        topLevelMenu: {
          rightClickPreservesActive: true,
          keyboardEntry: true,
          escapeRestoresFocus: true,
        },
        previewPageMenu: {
          pageMenu: true,
          copyAddressItem: true,
          closeCleansPageTab: true,
        },
      },
      files: {
        treeMenu: true,
        trashCancelReopen: true,
        treeFileOpen: true,
        treeReadSamples: [readSample(1, "start"), readSample(1, "resolved")],
        fileTabOpen: true,
        fileTabMenu: true,
        openedTabPath: "src/conflict.ts",
        fileOpenNoticeBeforeAction: false,
        fileOpenNoticeAfterAction: false,
        searchResultMenu: true,
        searchResultMatchCount: 3,
        searchResultPath: "src/conflict.ts",
        conflictSearchOpen: true,
        conflictSearchReadSamples: [readSample(2, "start"), readSample(2, "resolved")],
        plainResultMatchCount: 1,
        plainResultPath: "no-head-untracked.txt",
        plainResultFound: true,
        plainTextSearchOpen: true,
        plainTextReadSamples: [readSample(3, "start"), readSample(3, "resolved")],
        searchKeyboardEntry: true,
        composerReferenceMenu: true,
      },
      review: { rowMenu: true, diffTargetPreserved: true, revertUsesConfirmation: true },
      messages: {
        copyItem: true,
        editQuestion: true,
        selectedTextNativeMenu: true,
        fileLinkNativeMenu: true,
        queuedMessage: { menu: true, editCancelPreservesText: true, deleteRemovesTarget: true },
      },
      terminal: {
        tabMenu: true,
        paneMenu: true,
        bodyMenu: true,
        copyAbsentWithoutSelection: true,
        copyAvailableWithSelection: true,
        clipboardFixtureRoundTrip: false,
        clipboardExecution: "not_executed",
      },
      keyboard: { contextMenuKey: true, shiftF10: true, escapeRestoresFocus: true },
      positioning: { narrowViewport: true, edgeCollisionChecked: true },
      themes: ["light", "dark"],
    },
    notCovered: [
      {
        area: "terminal_clipboard_execution",
        reason: "真窗已验证终端粘贴入口；原生剪贴板读取未执行，以免触碰宿主剪贴板。",
      },
      {
        area: "composer_attachment",
        reason: "本轮没有待发送附件 fixture；已覆盖排队消息和工作区引用的对象菜单。",
      },
      {
        area: "clipboard_write_execution",
        reason: "为避免修改宿主剪贴板，只检查消息、Preview 和终端菜单的复制入口，不触发写入。",
      },
    ],
    productFailures: [],
    screenshots: [
      "project-menu-light.png",
      "project-menu-dark.png",
      "message-menu-light.png",
      "review-file-menu-light.png",
      "terminal-body-menu-dark.png",
      "file-search-menu-light.png",
    ],
    pageErrors: [],
  };
}

/** CLI 必须锁定 JDK25 默认值并要求隔离产物和证据输出目标。 */
test("参数固定隔离 JAR、JDK 25、Cargo target 和证据目录", () => {
  const options = parseArguments([
    "--evidence-directory",
    "target/context-evidence",
    "--jar",
    "target/app-server.jar",
  ]);
  assert.equal(options.javaHome, "C:\\Users\\24052\\.jdks\\liberica-25.0.2");
  assert.match(options.cargoTargetDirectory, /codex-context-menus$/u);
  assert.throws(() => parseArguments(["--evidence-directory", "target/evidence"]), /--jar/u);
  assert.throws(() => parseArguments(["--jar", "target/app-server.jar"]), /evidence-directory/u);
});

/** 只有每个范围都带真窗入口、主题和视口证据时报告才可通过。 */
test("真窗报告要求菜单范围、键盘、Escape、边缘、主题与截图齐全", () => {
  const report = validReport();
  assert.equal(validateContextMenusWebView2Report(report), report);

  const missingSearch = validReport();
  missingSearch.coverage.files.searchResultMenu = false;
  assert.throws(() => validateContextMenusWebView2Report(missingSearch));

  const missingQueueAction = validReport();
  missingQueueAction.coverage.messages.queuedMessage.deleteRemovesTarget = false;
  assert.throws(() => validateContextMenusWebView2Report(missingQueueAction));

  const missingTerminalSelection = validReport();
  missingTerminalSelection.coverage.terminal.copyAvailableWithSelection = false;
  assert.throws(() => validateContextMenusWebView2Report(missingTerminalSelection));

  const unidentifiedSearchHit = validReport();
  unidentifiedSearchHit.coverage.files.searchResultPath = "src/unexpected.ts";
  assert.throws(() => validateContextMenusWebView2Report(unidentifiedSearchHit));

  const observedOpenFailure = validReport();
  observedOpenFailure.verdict = "FAIL";
  observedOpenFailure.coverage.files.treeFileOpen = false;
  observedOpenFailure.coverage.files.conflictSearchOpen = false;
  observedOpenFailure.coverage.files.plainTextSearchOpen = false;
  observedOpenFailure.coverage.files.fileTabOpen = false;
  observedOpenFailure.coverage.files.fileTabMenu = false;
  observedOpenFailure.coverage.files.fileOpenNoticeBeforeAction = true;
  observedOpenFailure.coverage.files.fileOpenNoticeAfterAction = true;
  observedOpenFailure.productFailures = [
    {
      area: "file_tree_row_open",
      action: "tree_row_click",
      path: "src/conflict.ts",
      opened: false,
      readSamples: observedOpenFailure.coverage.files.treeReadSamples,
    },
    {
      area: "file_search_open",
      action: "search_result_context_menu_open",
      path: "src/conflict.ts",
      opened: false,
      readSamples: observedOpenFailure.coverage.files.conflictSearchReadSamples,
      readFailureNoticeBeforeAction: true,
      readFailureNoticeAfterAction: true,
    },
    {
      area: "file_search_open",
      action: "search_result_context_menu_open",
      path: "no-head-untracked.txt",
      opened: false,
      readSamples: observedOpenFailure.coverage.files.plainTextReadSamples,
      readFailureNoticeBeforeAction: true,
      readFailureNoticeAfterAction: true,
    },
  ];
  observedOpenFailure.notCovered.splice(1, 0, {
    area: "file_tree_row_open",
    reason: "树行单击未创建文件标签；已记录对应的 workspace read 阶段和脱敏错误码。",
  });
  observedOpenFailure.notCovered.splice(2, 0, {
    area: "file_search_open",
    reason: "搜索结果缺失或打开动作未创建文件标签；逐次 read 样本见 coverage.files。",
  });
  observedOpenFailure.notCovered.splice(3, 0, {
    area: "file_tab_menu",
    reason: "没有文件标签可供右键验收。",
  });
  observedOpenFailure.screenshots.push("file-open-failure-light.png");
  assert.equal(validateContextMenusWebView2Report(observedOpenFailure), observedOpenFailure);

  const missingPlainSearch = validReport();
  missingPlainSearch.verdict = "FAIL";
  missingPlainSearch.coverage.files.plainResultMatchCount = 0;
  missingPlainSearch.coverage.files.plainResultFound = false;
  missingPlainSearch.coverage.files.plainTextSearchOpen = false;
  missingPlainSearch.coverage.files.plainTextReadSamples = [];
  missingPlainSearch.productFailures = [
    {
      area: "file_search_open",
      action: "search_result_missing",
      path: "no-head-untracked.txt",
      opened: false,
      readSamples: [],
      readFailureNoticeBeforeAction: false,
      readFailureNoticeAfterAction: false,
      searchResultFound: false,
    },
  ];
  missingPlainSearch.notCovered.splice(1, 0, {
    area: "file_search_open",
    reason: "搜索结果缺失或打开动作未创建文件标签；逐次 read 样本见 coverage.files。",
  });
  assert.equal(validateContextMenusWebView2Report(missingPlainSearch), missingPlainSearch);

  const stageFailure = validReport();
  stageFailure.verdict = "FAIL";
  stageFailure.coverage.review = {
    rowMenu: false,
    diffTargetPreserved: false,
    revertUsesConfirmation: false,
  };
  stageFailure.productFailures = [
    { area: "review", error: "selection did not commit", screenshot: "review-failure.png" },
  ];
  stageFailure.screenshots.push("review-failure.png");
  assert.equal(validateContextMenusWebView2Report(stageFailure), stageFailure);

  const unsafeReadSample = validReport();
  unsafeReadSample.coverage.files.treeReadSamples[0].args = { raw: true };
  assert.throws(() => validateContextMenusWebView2Report(unsafeReadSample));

  const missingTheme = validReport();
  missingTheme.coverage.themes = ["light"];
  assert.throws(() => validateContextMenusWebView2Report(missingTheme));

  const noFailureBoundary = validReport();
  noFailureBoundary.notCovered = [];
  assert.throws(() => validateContextMenusWebView2Report(noFailureBoundary));
});

/** 真窗报告中的任何 renderer exception 都必须使验收失败。 */
test("真窗报告拒绝页面异常与缺少截图证据", () => {
  const pageError = validReport();
  pageError.pageErrors.push("renderer exception");
  assert.throws(() => validateContextMenusWebView2Report(pageError));

  const noScreenshots = validReport();
  noScreenshots.screenshots = [];
  assert.throws(() => validateContextMenusWebView2Report(noScreenshots));
});
