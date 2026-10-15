// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import test from "node:test";
import { pathToFileURL } from "node:url";
import {
  buildAssistantFileReply,
  canonicalFileDocumentPath,
  createPdfFixture,
  startBrowserFileProviderFixture,
  validateBrowserFileLinksReport,
  validateNativePreviewFallbackReport,
  writeBrowserFileFixture,
} from "./browser-file-links-webview2.mjs";

/** @author kongweiguang
 * 构造闭合的 Windows 真窗成功报告；每个反例只破坏一项重要文件浏览语义。
 */
function validReport() {
  return {
    contractVersion: 1,
    status: "passed",
    runtime: { platform: "win32", surface: "tauri_webview2", boundary: "debug_jar" },
    fixture: {
      provider: "deterministic_loopback",
      externalCalls: 0,
      externalTextOutsideWorkspace: true,
    },
    assistantTurn: {
      completed: true,
      referencesNotResolvedBeforeClick: true,
      markdownLinkClicked: true,
      markdownHtmlSourceOpenedInFiles: true,
      markdownHtmlPreviewWasExplicit: true,
      relativeHtmlSourceOpenedInFiles: true,
      relativeHtmlPreviewWasExplicit: true,
      inlineCodePathClicked: true,
      lineColumnPathClicked: true,
      legacyLineColumnPathClicked: true,
      lineOnlyPathClicked: true,
      missingPathFeedback: true,
      missingPathMessageRetained: true,
      missingPathFocusRetained: true,
      missingPathNoNativePageOpened: true,
      missingPathResolutionRejected: true,
      missingPathResolveAttempted: true,
      missingPathCtrlClickReachedNativeExplorerCommand: true,
      missingPathCtrlClickKeptPanelHidden: true,
      missingPathCtrlEnterReachedNativeExplorerCommand: true,
      duplicateTargetsOpenedWithoutChoice: true,
      directoryOrdinaryClickSelectedInFiles: true,
      directoryClickNoBrowserTabCreated: true,
      explorerDirectoryOpened: false,
      absoluteWindowsPathClicked: true,
      absoluteFileUrlClicked: true,
    },
    browser: {
      fileOpenAcknowledgements: [
        {
          command: "ja_preview_open_file",
          pageId: "12345678-1234-1234-1234-1234567890ab",
          tabPageId: "12345678-1234-1234-1234-1234567890ab",
          generation: 1,
          ackLatencyMs: 45,
        },
        {
          command: "ja_preview_open_file",
          pageId: "22345678-1234-1234-1234-1234567890ab",
          tabPageId: "22345678-1234-1234-1234-1234567890ab",
          generation: 2,
          ackLatencyMs: 45,
        },
        {
          command: "ja_preview_open_file",
          pageId: "32345678-1234-1234-1234-1234567890ab",
          tabPageId: "32345678-1234-1234-1234-1234567890ab",
          generation: 2,
          ackLatencyMs: 45,
        },
      ],
      htmlChildContentVerified: true,
      relativePngDecoded: true,
      relativeSvgDecoded: true,
      relativePdfSourceVerified: true,
      imagePageOpened: true,
      svgPageOpened: true,
      svgDocumentOpened: true,
      pdfDocumentOpened: true,
      audioVisiblePreviewHealthy: true,
      audioLocalPreview: {
        contentType: "audio/wav",
        mediaTag: "video",
        mediaElementPresent: true,
        controlsVisible: true,
        readyState: 1,
        metadataReady: true,
        durationSeconds: 1,
        localFileSource: true,
        videoDimensions: { width: 0, height: 0 },
        browserPreviewViewport: { width: 900, height: 650 },
        documentViewport: { width: 900, height: 650 },
        childScreenshotSize: { width: 900, height: 650 },
        metadataTimeout: false,
        width: null,
        height: null,
      },
      audioNativePreviewReadiness: {
        pageId: "22345678-1234-1234-1234-1234567890ab",
        generation: 2,
        status: "open",
        loadStatus: "finished",
        targetUrlMatched: true,
      },
      videoLocalPreview: {
        contentType: "video/mp4",
        mediaTag: "video",
        mediaElementPresent: true,
        controlsVisible: true,
        readyState: 1,
        metadataReady: true,
        durationSeconds: 1,
        localFileSource: true,
        videoDimensions: { width: 160, height: 90 },
        browserPreviewViewport: { width: 900, height: 650 },
        documentViewport: { width: 900, height: 650 },
        childScreenshotSize: { width: 900, height: 650 },
        metadataTimeout: false,
        width: 160,
        height: 90,
      },
      videoNativePreviewReadiness: {
        pageId: "32345678-1234-1234-1234-1234567890ab",
        generation: 2,
        status: "open",
        loadStatus: "finished",
        targetUrlMatched: true,
      },
      videoVisiblePreviewHealthy: true,
      multiplePagesOpen: true,
      pageIdsDistinct: true,
      addressFocused: true,
      viewportResizeApplied: true,
      nativeChildResized: true,
      nativeChildRestoredSize: true,
      backForwardReloadVerified: true,
      crossSchemeBackForwardVerified: true,
      crossSchemeHistory: {
        samePageId: true,
        httpDocumentObserved: true,
        fileDocumentObserved: true,
        backResolved: true,
        forwardResolved: true,
        forwardRestoredLocalDocument: true,
      },
    },
    textViewer: {
      workspaceFileOpened: true,
      duplicateAbsoluteTargetOpened: true,
      sourceEditorFocused: true,
      workspaceFileReadOnly: false,
      externalFileReadOnly: true,
      externalFileEditorFocused: true,
      absoluteWindowsPathOpened: true,
      externalFileOutsideWorkspace: true,
      sourceReveal: { line: 2, column: 4 },
      legacyLineColumnReveal: { line: 2, column: 4 },
      lineOnlyReveal: { line: 1, column: 1 },
    },
    threadScope: {
      isolated: true,
      aPagesRestored: true,
      bClosed: true,
      closedNativeResourcesAcknowledged: true,
      nativeCloseAckCount: 1,
      hiddenLayoutAckCount: 1,
      visibleLayoutAckCount: 1,
    },
    pageErrors: [],
    screenshots: [
      "message.png",
      "html.png",
      "text.png",
      "browser-tabs.png",
      "image.png",
      "image-narrow.png",
      "svg.png",
      "pdf.png",
      "history.png",
      "thread-b.png",
      "close.png",
    ],
  };
}

/** 在独立临时目录中创建和回收本机路径 fixture，避免测试写入仓库或用户文件夹。 */
async function withTemporaryWorkspace(operation) {
  const root = await mkdtemp(join(tmpdir(), "ja-browser-file-links-test-"));
  try {
    await operation(root);
  } finally {
    const temporaryRoot = resolve(tmpdir());
    const relation = relative(temporaryRoot, resolve(root));
    assert.ok(relation.length > 0 && !relation.startsWith(".."));
    await rm(resolve(root), { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
}

/** 语料必须覆盖真实目录及两种绝对目标；盘符的定位后缀独立于含空格的文件名。 */
test("workspace fixture covers Unicode paths, relative assets, and workspace-external read-only text", async () => {
  await withTemporaryWorkspace(async (root) => {
    const workspace = join(root, "workspace");
    const fixture = await writeBrowserFileFixture(workspace);
    const directory = await stat(fixture.directoryPath);
    const html = await readFile(fixture.htmlPath, "utf8");
    const png = await readFile(fixture.imagePath);
    const svg = await readFile(fixture.svgPath, "utf8");
    const pdf = await readFile(fixture.pdfPath);
    const audio = await readFile(fixture.audioPath);
    const video = await readFile(fixture.videoPath);
    const duplicateA = await readFile(fixture.duplicateAPath, "utf8");
    const duplicateB = await readFile(fixture.duplicateBPath, "utf8");
    const reply = buildAssistantFileReply(fixture);

    assert.match(fixture.relativeHtmlPath, /图形 示例[\\/]首页 demo\.html/u);
    assert.equal(directory.isDirectory(), true);
    assert.equal(fixture.relativeDirectoryPath, "web/图形 示例");
    assert.ok(fixture.externalPath.startsWith(root));
    assert.ok(relative(workspace, fixture.externalPath).startsWith(".."));
    assert.match(html, /JA_BROWSER_FILE_HTML_CONTENT/u);
    assert.match(html, /src="\.\/资源\/像素图\.png"/u);
    assert.match(html, /src="\.\/资源\/标记 图\.svg"/u);
    assert.ok(png.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex")));
    assert.match(svg, /JA_BROWSER_FILE_SVG_CONTENT/u);
    assert.ok(pdf.subarray(0, 8).equals(Buffer.from("%PDF-1.4", "ascii")));
    assert.ok(pdf.includes(Buffer.from("JA_BROWSER_FILE_PDF_CONTENT", "ascii")));
    assert.equal(audio.toString("ascii", 0, 4), "RIFF");
    assert.equal(audio.toString("ascii", 8, 12), "WAVE");
    assert.equal(audio.readUInt32LE(24), 8_000);
    assert.equal(audio.readUInt32LE(40), audio.length - 44);
    assert.equal(video.toString("ascii", 4, 8), "ftyp");
    assert.ok(video.includes(Buffer.from("avc1", "ascii")));
    assert.ok(video.includes(Buffer.from("mdat", "ascii")));
    assert.match(reply, /\[打开本地 HTML\]\(file:/u);
    assert.match(reply, /启动 模块\.ts#L2C4/u);
    assert.match(reply, /启动 模块\.ts:2:4/u);
    assert.match(reply, /项目目录：`web\/图形 示例\/`/u);
    assert.match(reply, /音频 demo\.wav/u);
    assert.match(reply, /视频 demo\.mp4/u);
    assert.match(reply, /外部 只读说明\.txt/u);
    assert.match(reply, /重复 名称\.txt/u);
    assert.ok(
      reply.includes(`[重复 名称.txt](<${fixture.duplicateAPath.replaceAll("\\", "/")}:2:3>)`),
    );
    assert.ok(reply.includes(`[重复 名称.txt](${pathToFileURL(fixture.duplicateBPath).href})`));
    assert.match(
      await readFile(fixture.duplicateRootPath, "utf8"),
      /JA_BROWSER_FILE_DUPLICATE_ROOT/u,
    );
    assert.match(duplicateA, /JA_BROWSER_FILE_DUPLICATE_A/u);
    assert.match(duplicateB, /JA_BROWSER_FILE_DUPLICATE_B/u);
  });
});

test("PDF fixture uses valid xref byte offsets and points to its actual objects", () => {
  const pdf = createPdfFixture().toString("latin1");
  const xrefOffset = Number(/startxref\n(\d+)\n/u.exec(pdf)?.[1]);
  assert.equal(pdf.slice(xrefOffset, xrefOffset + 5), "xref\n");
  const xref = pdf.slice(xrefOffset).split(/\r?\n/u);
  const objectCount = Number(/^0 (\d+)$/u.exec(xref[1])?.[1]);
  assert.equal(objectCount, 6);
  for (let index = 1; index < objectCount; index += 1) {
    const entry = xref[index + 2];
    const offset = Number(entry.slice(0, 10));
    assert.ok(pdf.slice(offset).startsWith(`${index} 0 obj\n`));
  }
});

test("Files document paths resolve workspace-relative DOM values against the isolated workspace", () => {
  const workspace = resolve(tmpdir(), "ja-browser-file-links-workspace", "work tree");
  const relativePath = "src/导航/启动 模块.ts";
  const expectedPath = join(workspace, "src", "导航", "启动 模块.ts");

  assert.equal(
    canonicalFileDocumentPath(relativePath, workspace),
    canonicalFileDocumentPath(expectedPath, workspace),
  );
  assert.equal(
    canonicalFileDocumentPath(expectedPath, workspace),
    canonicalFileDocumentPath(relativePath.replaceAll("/", "\\"), workspace),
  );
  assert.equal(
    canonicalFileDocumentPath(expectedPath, workspace),
    canonicalFileDocumentPath(`\\\\?\\${expectedPath}`, workspace),
  );
});

test("provider fixture serves only deterministic completed Responses and records no request text", async () => {
  const fixture = await startBrowserFileProviderFixture();
  try {
    fixture.setReply("fixture response");
    const response = await fetch(`${fixture.baseUrl}/responses`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ input: [{ content: [{ text: "JA_BROWSER_FILE_LINKS_REQUEST" }] }] }),
    });
    assert.equal(response.status, 200);
    const stream = await response.text();
    assert.match(stream, /event: response\.completed/u);
    assert.match(stream, /fixture response/u);
    const historyResponse = await fetch(fixture.historyUrl);
    assert.equal(historyResponse.status, 200);
    assert.match(await historyResponse.text(), /JA_BROWSER_FILE_HTTP_HISTORY_ORIGIN/u);
    assert.deepEqual(fixture.snapshot(), {
      requestCount: 1,
      acceptanceTurnCount: 1,
      historyRequestCount: 1,
    });
  } finally {
    await fixture.close();
  }
});

test("WebView2 result validator rejects partial skeletons and missing native cleanup evidence", () => {
  const report = validReport();
  assert.equal(validateBrowserFileLinksReport(report), report);
  report.threadScope.closedNativeResourcesAcknowledged = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
});

/** 同名文件必须由真实绝对目标区分，不接受恢复旧选择器的报告。 */
test("file targets require exact duplicate paths without a chooser", () => {
  const report = validReport();
  report.assistantTurn.duplicateTargetsOpenedWithoutChoice = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.assistantTurn.duplicateTargetsOpenedWithoutChoice = true;
  report.textViewer.duplicateAbsoluteTargetOpened = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
});

/** @author kongweiguang
 * 普通 HTML 路径必须在 Files 打开源码，浏览器预览必须来自明确菜单动作并显示真实页面。
 */
test("HTML references require Files source opening and an explicit Browser preview action", () => {
  const report = validReport();
  assert.equal(validateBrowserFileLinksReport(report), report);
  report.assistantTurn.markdownHtmlSourceOpenedInFiles = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.assistantTurn.markdownHtmlSourceOpenedInFiles = true;
  report.assistantTurn.relativeHtmlPreviewWasExplicit = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
});

/** @author kongweiguang 音视频真窗验收报告必须包含实际可读的本地媒体元素、metadata 和视频尺寸。 */
test("media Preview result requires readable local audio and video metadata", () => {
  const report = validReport();
  assert.equal(validateBrowserFileLinksReport(report), report);
  report.browser.audioLocalPreview.metadataReady = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioLocalPreview.metadataReady = true;
  report.browser.audioLocalPreview.mediaTag = "canvas";
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioLocalPreview.mediaTag = "video";
  report.browser.audioLocalPreview.videoDimensions = { width: 16, height: 9 };
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioLocalPreview.videoDimensions = { width: 0, height: 0 };
  report.browser.audioVisiblePreviewHealthy = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioVisiblePreviewHealthy = true;
  report.browser.videoVisiblePreviewHealthy = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.videoVisiblePreviewHealthy = true;
  report.browser.videoLocalPreview.width = 0;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.videoLocalPreview.width = 160;
  report.browser.audioLocalPreview.childScreenshotSize.height = 3;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioLocalPreview.childScreenshotSize.height = 650;
  report.browser.videoLocalPreview.browserPreviewViewport.height = 3;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.videoLocalPreview.browserPreviewViewport.height = 650;
  report.browser.audioNativePreviewReadiness.loadStatus = "failed";
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioNativePreviewReadiness.loadStatus = "finished";
  report.browser.audioNativePreviewReadiness.generation = 3;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.browser.audioNativePreviewReadiness.generation = 2;
  report.browser.videoNativePreviewReadiness.targetUrlMatched = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
});

/** @author kongweiguang Explorer 验收同时要求文件选中与目录直接打开的闭环证据。 */
test("Explorer folder verification requires a selected file result", () => {
  const report = validReport();
  report.fixture.explorerFolderVerificationRequested = true;
  assert.throws(() => validateBrowserFileLinksReport(report));
  report.assistantTurn.explorerFolderOpenedAndFileSelected = true;
  report.assistantTurn.explorerDirectoryOpened = true;
  assert.equal(validateBrowserFileLinksReport(report), report);
});

test("WebView2 report requires missing file resolution to stop before native page creation", () => {
  const report = validReport();
  report.assistantTurn.missingPathNoNativePageOpened = false;
  assert.throws(() => validateBrowserFileLinksReport(report));
});

test("Preview open ACK identity and generation must match the active page tab", () => {
  const report = validReport();
  report.browser.fileOpenAcknowledgements[0].tabPageId = "different-page-id";
  assert.throws(() => validateBrowserFileLinksReport(report));
});

/** 创建满足 direct native Preview 范围且显式排除 Conversation UI 验收的报告。 */
function validNativePreviewReport() {
  return {
    contractVersion: 1,
    status: "passed",
    acceptanceScope: "native_preview_layer_only",
    conversationFlowVerified: false,
    runtime: { platform: "win32", surface: "tauri_webview2", boundary: "debug_jar" },
    runtimeBootstrap: {
      observedStatus: "stopped",
      directPreviewInvokeSucceeded: true,
      runtimeReadyRequired: false,
    },
    browser: {
      directFileOpenSucceeded: true,
      htmlChildContentVerified: true,
      relativePngDecoded: true,
      relativeSvgDecoded: true,
      relativePdfSourceVerified: true,
      svgDocumentOpened: true,
      imageDocumentOpened: true,
      pdfDocumentOpened: true,
      multiplePagesOpen: true,
      pageIdsDistinct: true,
      viewportResizeApplied: true,
      viewportRestored: true,
      crossSchemeHistory: {
        sameChildTarget: true,
        httpDocumentObserved: true,
        localDocumentObserved: true,
        backResolved: true,
        reloadResolved: true,
        reloadRequestObserved: true,
        forwardResolved: true,
        forwardRestoredLocalDocument: true,
      },
      childScreenshotBytes: 2048,
    },
    threadScope: {
      closedNativeResourcesAcknowledged: true,
      allChildTargetsClosed: true,
      nativeCloseAckCount: 5,
    },
    browserTabFocusVerified: false,
    fileLinkUiVerified: false,
    textViewerRoutingVerified: false,
    screenshots: Array.from({ length: 6 }, (_, index) => ({
      file: `native-${index}.png`,
      bytes: 2048,
    })),
  };
}

test("native Preview fallback report states its limited scope and requires child/close evidence", () => {
  const report = validNativePreviewReport();
  assert.equal(validateNativePreviewFallbackReport(report), report);
  report.conversationFlowVerified = true;
  assert.throws(() => validateNativePreviewFallbackReport(report));
  report.conversationFlowVerified = false;
  report.browser.crossSchemeHistory.forwardRestoredLocalDocument = false;
  assert.throws(() => validateNativePreviewFallbackReport(report));
});
