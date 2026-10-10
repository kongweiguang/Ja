// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 关闭回执必须精确表示一次临时侧聊清理的完整 Thread 子树；独立校验器让真窗脚本
 * 共享同一协议约束，避免 UI 回执和 SQLite 删除证据使用不同集合规则。
 */
export function assertSideChatCloseReceipt(receipt, rootThreadId, expectedThreadIds) {
  const keys = receipt !== null && typeof receipt === "object" ? Object.keys(receipt).sort() : [];
  const closedThreadIds = receipt?.closedThreadIds;
  if (
    JSON.stringify(keys) !== JSON.stringify(["closedThreadIds"]) ||
    !/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(rootThreadId ?? "") ||
    !Array.isArray(closedThreadIds) ||
    closedThreadIds.length === 0 ||
    !Array.isArray(expectedThreadIds) ||
    expectedThreadIds.length === 0
  ) {
    throw new Error("侧聊关闭回执结构无效");
  }

  const isThreadId = (threadId) =>
    typeof threadId === "string" && /^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(threadId);
  const expected = [...expectedThreadIds].sort();
  const actual = [...closedThreadIds];
  if (
    expected.some((threadId) => !isThreadId(threadId)) ||
    actual.some((threadId) => !isThreadId(threadId)) ||
    !expected.includes(rootThreadId) ||
    !actual.includes(rootThreadId) ||
    new Set(expected).size !== expected.length ||
    new Set(actual).size !== actual.length ||
    JSON.stringify(actual) !== JSON.stringify([...actual].sort()) ||
    JSON.stringify(actual) !== JSON.stringify(expected)
  ) {
    throw new Error("侧聊关闭回执与关闭前 Thread 子树不一致");
  }
  return actual;
}
