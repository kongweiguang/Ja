// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  hasSuspendedChildCandidate,
  summarizeSyntheticToolResult,
} from "./subagent-cli-approval-conpty.mjs";

/** 固定 /resume 候选自身的短状态文案，不要求父 Activity 才有的“待恢复”重复出现在候选卡片。 @author kongweiguang */
test("suspended child selector accepts its own status and still matches the requested child", () => {
  const selectorLines = [
    "恢复会话",
    "当前项目 · 搜索：cli-approval-expiry-child · PgDn 加载更多",
    "› 1. cli-approval-expiry-child  子任务 · 已暂停",
    "↑↓ 选择 · Enter 恢复 · Esc 返回",
  ];
  assert.equal(hasSuspendedChildCandidate(selectorLines, "cli-approval-expiry-child"), true);
  assert.equal(hasSuspendedChildCandidate(selectorLines, "different-child"), false);
  assert.equal(hasSuspendedChildCandidate([
    "› 1. cli-approval-expiry-child  子任务 · 正在工作",
    "父任务 · 已暂停 · 待恢复",
  ], "cli-approval-expiry-child"), false);
});

/** 固定失败诊断只保留本地 fixture ToolResult 前 500 字符并清除测试密钥占位值。 @author kongweiguang */
test("synthetic ToolResult diagnostics are bounded and redact the fixture credential", () => {
  const summary = summarizeSyntheticToolResult(`prefix ja-subagent-cli-approval-loopback-placeholder ${"x".repeat(800)}`);
  assert.equal(summary.valueType, "string");
  assert.ok(summary.preview.length <= 500);
  assert.ok(!summary.preview.includes("ja-subagent-cli-approval-loopback-placeholder"));
  assert.ok(summary.preview.includes("<redacted>"));
});
