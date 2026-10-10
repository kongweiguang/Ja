// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { assertForkChildTurnBoundary, findInheritedFinalAnswer } from "./fork-reply-webview2.mjs";

const directory = dirname(fileURLToPath(import.meta.url));
const driverPath = join(directory, "fork-reply-webview2.mjs");
const fixturePath = join(directory, "fixtures", "fork-reply-provider.mjs");

/** 验证每个目标 Thread 用自身重映射后的 itemId 定位继承回复，来源身份只用于请求归属。 @author kongweiguang */
test("maps an inherited fork answer inside the destination Thread identity", () => {
  const snapshot = {
    items: [
      {
        itemId: "item_child_first",
        kind: "final_answer",
        origin: "inherited",
        text: "JA_SOURCE_ANSWER_1",
      },
      {
        itemId: "item_child_selected",
        kind: "final_answer",
        origin: "inherited",
        text: "JA_SOURCE_ANSWER_2",
      },
      {
        itemId: "item_child_native",
        kind: "final_answer",
        origin: "native",
        text: "JA_CHILD_ANSWER",
      },
    ],
  };

  const selected = findInheritedFinalAnswer(snapshot, "JA_SOURCE_ANSWER_2");
  assert.equal(selected.itemId, "item_child_selected");
  assert.notEqual(selected.itemId, "item_source_selected");
  assert.equal(findInheritedFinalAnswer(snapshot).itemId, "item_child_selected");
});

/** 把来源活动状态与锚点之后的时间/错误固定为快照契约，避免 Java 投影回归只靠 DOM 才暴露。 @author kongweiguang */
test("rejects active or post-boundary inherited Turn metadata", () => {
  const boundary = "2026-10-10T14:00:03.000Z";
  const settledTurn = {
    turnId: "turn_child_prefix",
    origin: "inherited",
    status: "completed",
    requestedAt: "2026-10-10T14:00:01.000Z",
    updatedAt: boundary,
    completedAt: boundary,
    errorCode: null,
  };
  const snapshot = {
    items: [{ origin: "inherited" }],
    turns: [settledTurn],
    liveStream: null,
  };

  assert.doesNotThrow(() => assertForkChildTurnBoundary(snapshot, boundary));
  assert.throws(
    () =>
      assertForkChildTurnBoundary(
        {
          ...snapshot,
          turns: [{ ...settledTurn, status: "running", completedAt: null }],
        },
        boundary,
      ),
    /active Turn identities/u,
  );
  assert.throws(
    () =>
      assertForkChildTurnBoundary(
        {
          ...snapshot,
          turns: [{ ...settledTurn, updatedAt: "2026-10-10T14:00:04.000Z" }],
        },
        boundary,
      ),
    /past the selected reply boundary/u,
  );
  assert.throws(
    () =>
      assertForkChildTurnBoundary(
        {
          ...snapshot,
          turns: [{ ...settledTurn, errorCode: "SOURCE_FAILURE" }],
        },
        boundary,
      ),
    /cannot inherit a later error/u,
  );
});

/** 只验证 driver 把稳定边界接到生产 adapter/native probe，并保持跨 reload 的报告状态作用域有效。 @author kongweiguang */
test("fork reply WebView2 driver covers delegated faults and inherited long-body paging", async () => {
  const [driver, fixture] = await Promise.all([
    readFile(driverPath, "utf8"),
    readFile(fixturePath, "utf8"),
  ]);

  assert.ok(/mode: "fail_first_created_thread_read"/u.test(driver));
  assert.ok(/mode: "delay_fork_receipt_until_selection"/u.test(driver));
  assert.ok(/mode: "drop_fork_and_operation_read_receipts"/u.test(driver));
  assert.ok(/const invokeReal = \(\) =>/u.test(driver));
  assert.ok(/recordResult\(await invokeReal\(\)\)/u.test(driver));
  assert.ok(/record\.targetThreadId = result\?\.thread\?\.threadId/u.test(driver));
  assert.ok(/faultPlan\.targetThreadId = record\.targetThreadId/u.test(driver));
  assert.equal((driver.match(/await clickForkReplyOnce\(page,/gu) ?? []).length, 6);
  assert.ok(/entry\.sourceThreadId === expectedSourceThreadId/u.test(driver));
  assert.ok(/recoveredReceipt\?\.resultTargetThreadId, targetThreadId/u.test(driver));

  assert.ok(/history\.itemContentRead\(/u.test(driver));
  assert.ok(/capturePublicWorkFailureEvidence/u.test(driver));
  assert.ok(/fork-public-work-marker-timeout\.png/u.test(driver));
  assert.ok(/fork-target-location-missing\.json/u.test(driver));
  assert.ok(/captureForkFailureEvidence/u.test(driver));
  assert.ok(/fork-failure-diagnostics\.json/u.test(driver));
  assert.ok(/providerRequests: fixture\.snapshot\(\)/u.test(driver));
  assert.ok(/turns: page\.turns\.map\([\s\S]*?errorCode: turn\.errorCode/u.test(driver));
  assert.ok(/activeStreamArticles/u.test(driver));
  assert.ok(/activeTurnRows/u.test(driver));
  assert.ok(/section\.ja-work-process \.ja-work-process__content/u.test(driver));
  assert.ok(
    /const activeRow = page[\s\S]{0,80}\.locator\("\.ja-chat-timeline__row"\)/u.test(driver),
  );
  assert.ok(/activeTurnId/u.test(driver));
  assert.ok(
    /fixture\.releaseActive\(\);[\s\S]*?selectThread\(page, created\.workspaceName, branchB, deadline\);[\s\S]*?readReplyAnchor\(page, forkReplyMarkers\.activeAnswer/u.test(
      driver,
    ),
  );
  assert.ok(/fork-flow-failure\.png/u.test(driver));
  assert.ok(/revealPublicWorkForReply/u.test(driver));
  assert.ok(/ja-work-process__trigger/u.test(driver));
  assert.ok(/alreadyExpanded \? \/收起工作过程\/u : \/查看工作过程\/u/u.test(driver));
  assert.ok(/offsetCharacters,/u.test(driver));
  assert.ok(/limitCharacters: 65_536/u.test(driver));
  assert.ok(/Buffer\.byteLength\(forkReplyLongAnswerText, "utf8"\)/u.test(driver));
  assert.ok(/nestedLongHistory/u.test(driver));
  assert.ok(/fork-open-failure-retry\.png/u.test(driver));
  assert.ok(/fork-delayed-receipt-selection\.png/u.test(driver));
  assert.ok(/fork-lost-receipt-recovered\.png/u.test(driver));
  assert.ok(/clickForkReplyTwice/u.test(driver));
  assert.ok(/elementFromPoint/u.test(driver));
  assert.ok(/page\.mouse\.move\(articlePoint\.x, articlePoint\.y\)/u.test(driver));
  assert.ok(/article\?\.matches\(":hover"\)/u.test(driver));
  assert.ok(/Emulation\.setFocusEmulationEnabled/u.test(driver));
  assert.ok(/page\.__forkFocusEmulation = focusEmulation/u.test(driver));
  assert.ok(/focusEmulation: page\.__forkFocusEmulation/u.test(driver));
  assert.ok(/hiddenIdleReadWindowMs/u.test(driver));
  assert.ok(/readsForHiddenThreads: hiddenIdleReads/u.test(driver));
  assert.ok(/return target === button \|\| button\.contains\(target\)/u.test(driver));
  assert.ok(/event\.type === "click" &&[\s\S]*?event\.isTrusted/u.test(driver));
  assert.ok(
    /await page\.setViewportSize\(desktopViewportSize\);[\s\S]*?sourceAfterBranches/u.test(driver),
  );
  assert.ok(/actionBar\?\.pointerEvents === "auto"/u.test(driver));
  assert.ok(/reloadedBReply\.article\.hover\(\)/u.test(driver));
  assert.ok(/findInheritedFinalAnswer\(snapshot\)/u.test(driver));
  assert.ok(/targetItem\.turnId,[\s\S]*?sourceTurnId/u.test(driver));
  assert.ok(/expectedTargetItemId: targetItem\.itemId/u.test(driver));

  assert.ok(/8 \* 1024 \* 1024/u.test(fixture));
  assert.ok(/forkReplyLongAnswerText/u.test(fixture));
  assert.ok(/longAnswerOccurrences/u.test(fixture));
});

/** 保证触屏分叉前先恢复可导航视口并检查真实项目/历史入口，避免把响应式收起误判为分叉失败。 @author kongweiguang */
test("restores the desktop viewport and exposes real navigation before touch fork", async () => {
  const driver = await readFile(driverPath, "utf8");
  const touchSection = driver.indexOf(
    'path: join(evidenceDirectory, "fork-reply-keyboard-focus.png")',
  );
  const viewportRestore = driver.indexOf(
    "await page.setViewportSize(desktopViewportSize);",
    touchSection,
  );
  const sidebarCheck = driver.indexOf(
    "ensureNavigationSidebarVisible(page, created.workspaceName, branchB, deadline);",
    viewportRestore,
  );
  const touchFork = driver.indexOf("const touchBranch = await forkReply(", sidebarCheck);

  assert.ok(touchSection >= 0);
  assert.ok(viewportRestore > touchSection);
  assert.ok(sidebarCheck > viewportRestore);
  assert.ok(touchFork > sidebarCheck);
  assert.match(driver, /button\[data-scope-kind="project"\]/u);
  assert.ok(driver.includes('`button[data-thread-id="${threadId}"]`'));
  assert.match(driver, /getByRole\("button", \{ name: "显示侧边栏", exact: true \}\)/u);
  assert.match(driver, /getByRole\("button", \{ name: "展开项目", exact: true \}\)/u);
  assert.match(driver, /getByRole\("button", \{ name: "展开最近对话", exact: true \}\)/u);
});
