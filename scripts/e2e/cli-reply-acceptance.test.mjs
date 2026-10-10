// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  buildReplyBody,
  assertReplyTextMetricsMatch,
  createReplyStreamProbe,
  isToolStepsListScreen,
  readCompleteToolArtifact,
  rpcResult,
  summarizeReplyText,
  validateObserverResponseFrame,
} from "./cli-reply-acceptance.mjs";
import {
  compileConPtyHarness,
  startConPtyCli,
  VtScreen,
  waitForRawText,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

/** 固定正文必须覆盖终端回复改造约定的主要 Markdown 语义，防止 fixture 被无意简化。 @author kongweiguang */
test("keeps the shared Chinese Markdown fixture comprehensive", async () => {
  const fixture = await readFile(join(repoRoot, "apps", "cli", "tests", "support", "reply-markdown.md"), "utf8");

  for (const marker of [
    "# 流式回复渲染验收",
    "## 有序步骤",
    "第二段内容",
    "3. 第一项",
    "   1. 嵌套条目",
    "4. 第二项",
    "- [x] 已完成事项",
    "> 引用块",
    "| 类型 | 预期 | 示例 |",
    "```powershell",
    "[https://example.com](https://example.com)",
    "Emoji 🙂✅",
    "é",
  ]) {
    assert.ok(fixture.includes(marker), `fixture is missing ${marker}`);
  }
});

/** 长流 fixture 同时超过活动区 64 KiB 边界并保留 900 个顺序唯一标识。 @author kongweiguang */
test("builds a unique 900-line Chinese stream beyond 64 KiB", async () => {
  const markdown = await readFile(join(repoRoot, "apps", "cli", "tests", "support", "reply-markdown.md"), "utf8");
  const reply = buildReplyBody(markdown);
  const rows = reply.text.split("\n").filter((line) => /^JA_REPLY_LONG_\d{4}/u.test(line));

  assert.ok(reply.byteLength > 64 * 1024);
  assert.equal(reply.longLines.length, 900);
  assert.equal(rows.length, 900);
  assert.equal(new Set(rows).size, 900);
  assert.match(rows[0], /^JA_REPLY_LONG_0001/u);
  assert.match(rows.at(-1), /^JA_REPLY_LONG_0900/u);
});

/** 多个 Provider 分片可合并成较少 RPC 通知，验收以 code point、UTF-8、哈希和行标记对账。 @author kongweiguang */
test("matches merged observer notifications to source text independently of frame counts", () => {
  const sourceText = `${Array.from({ length: 600 }, (_, index) => {
    const ordinal = String(index + 1).padStart(4, "0");
    return `JA_REPLY_LONG_${ordinal} · 中文 🙂 é`;
  }).join("\n")}\n`;
  const sourcePoints = Array.from(sourceText);
  const sourceDeltas = [];
  for (let offset = 0; offset < sourcePoints.length; offset += 11) {
    sourceDeltas.push(sourcePoints.slice(offset, offset + 11).join(""));
  }
  const splitAt = Math.floor(sourceDeltas.length / 2);
  const sourceMetrics = {
    ...summarizeReplyText(sourceText),
    sourceSseDeltaFrames: sourceDeltas.length,
  };
  const probe = createReplyStreamProbe("JA_REPLY_LONG_0600");

  assert.equal(probe.append({ turnId: "turn_other", streamSeq: 1, text: "ignored" }, "turn_target"), false);
  assert.equal(probe.append({ turnId: "turn_target", streamSeq: 8, text: sourceDeltas.slice(0, splitAt).join("") }, "turn_target"), false);
  assert.equal(probe.append({ turnId: "turn_target", streamSeq: 9, text: sourceDeltas.slice(splitAt).join("") }, "turn_target"), true);
  const snapshot = probe.snapshot();

  assert.equal(sourceMetrics.markerCount, 600);
  assert.equal(sourceMetrics.markersInOrder, true);
  assert.ok(sourceMetrics.sourceSseDeltaFrames > snapshot.rpcNotificationFrames);
  assert.equal(snapshot.rpcNotificationFrames, 2);
  assertReplyTextMetricsMatch(sourceMetrics, snapshot, "batched text stream");
  assert.equal(snapshot.hasCheckpoint, true);
  assert.equal("text" in snapshot, false);

  const corrupted = createReplyStreamProbe("JA_REPLY_LONG_0600");
  corrupted.append({ turnId: "turn_target", streamSeq: 10, text: `${sourceText}JA_REPLY_LONG_0600` }, "turn_target");
  assert.throws(() => assertReplyTextMetricsMatch(sourceMetrics, corrupted.snapshot(), "duplicated stream"), /mismatch/u);
});

/** 新场景必须复用现有 ConPTY harness 的显式导出入口，避免私有协议或 VT 屏幕实现出现双份。 @author kongweiguang */
test("exports the existing ConPTY acceptance helpers for scenario reuse", () => {
  assert.equal(typeof compileConPtyHarness, "function");
  assert.equal(typeof startConPtyCli, "function");
  assert.equal(startConPtyCli.length, 7, "the optional command must preserve the existing default invocation arguments");
  assert.equal(typeof writeScreenEvidence, "function");
  assert.equal(typeof waitForRawText, "function");
  assert.equal(typeof VtScreen, "function");
});

/** 模拟 observer 的 WebSocket JSON-RPC response，固定完整 frame 返回与错误 frame 的校验边界。 @author kongweiguang */
test("validates observer WebSocket response frames without unwrapping the request contract", () => {
  const responseFrame = {
    jsonrpc: "2.0",
    id: "c:42",
    result: { threadId: "thr_fixture", revision: 7 },
  };

  assert.equal(validateObserverResponseFrame(responseFrame, "c:42"), responseFrame);
  assert.deepEqual(rpcResult(responseFrame, "thread/read"), { threadId: "thr_fixture", revision: 7 });
  assert.throws(() => validateObserverResponseFrame(responseFrame, "c:43"), /invalid response frame/u);
  assert.throws(() => validateObserverResponseFrame({ ...responseFrame, unexpected: true }), /invalid response frame/u);
  assert.throws(
    () => validateObserverResponseFrame({ jsonrpc: "2.0", id: "c:42", result: {}, error: {} }),
    /invalid response frame/u,
  );
  assert.throws(
    () => rpcResult({ jsonrpc: "2.0", id: "c:43", error: { code: -32000, message: "private details", data: { errorCode: "THREAD_NOT_FOUND" } } }, "thread/read"),
    /^Error: thread\/read failed: THREAD_NOT_FOUND$/u,
  );
});

/** 使用跨页 Emoji 固定 Tool artifact 的 code point 游标，防止 UTF-16 长度造成漏字或重复。 @author kongweiguang */
test("reassembles complete Tool artifacts by code point offset", async () => {
  const artifactId = "artifact_fixture";
  const text = "甲🙂乙é丙丁";
  const points = Array.from(text);
  const requests = [];
  let requestId = 0;
  const session = {
    async request(method, params) {
      assert.equal(method, "tool/artifact/read");
      requests.push(params);
      const start = params.offsetCharacters;
      const content = points.slice(start, start + params.limitCharacters).join("");
      const nextOffsetCharacters = start + Array.from(content).length;
      const next = nextOffsetCharacters < points.length ? nextOffsetCharacters : null;
      return {
        jsonrpc: "2.0",
        id: `c:${++requestId}`,
        result: {
          artifactId,
          offsetCharacters: start,
          nextOffsetCharacters: next,
          totalCharacters: points.length,
          truncated: next !== null,
          content,
        },
      };
    },
  };

  const artifact = await readCompleteToolArtifact(session, {
    threadId: "thr_fixture",
    turnId: "turn_fixture",
    callId: "call_fixture",
    artifactId,
  }, 3);

  assert.equal(artifact.content, text);
  assert.equal(artifact.totalCharacters, points.length);
  assert.equal(artifact.pageCount, 3);
  assert.equal(requests[0].offsetCharacters, 0);
  assert.equal(requests[1].offsetCharacters, 3);
  assert.equal(requests[2].offsetCharacters, 6);
  assert.equal(artifact.byteLength, Buffer.byteLength(text, "utf8"));
});

/** 列表检测只承认真实工具面板标题与两条编号项目，排除版本号、上下文百分比和历史消息中的路径。 @author kongweiguang */
test("recognizes the real two-item Tool steps panel and rejects transcript lookalikes", () => {
  assert.equal(
    isToolStepsListScreen("需要审批\n› JA_REPLY_RENDER_ACCEPTANCE\ngpt-6-luna · Ja v0.2.0\n上下文 1.2%"),
    false,
  );
  assert.equal(
    isToolStepsListScreen("工具步骤\n  Enter 查看详情 · Esc 返回回复\n  1. 读取 reply-source.txt\n› 2. 运行 [Console]::OutputEncoding=..."),
    true,
  );
});
