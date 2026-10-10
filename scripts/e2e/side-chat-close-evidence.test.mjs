// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { assertSideChatCloseReceipt } from "./side-chat-close-evidence.mjs";

const rootThreadId = "thr_side_root";
const expectedThreadIds = ["thr_side_child", rootThreadId];

/** 只接受服务端按字典序返回、覆盖根与完整持久子树的单字段回执。 */
test("accepts the exact canonical side-chat close receipt", () => {
  assert.deepEqual(
    assertSideChatCloseReceipt(
      { closedThreadIds: ["thr_side_child", rootThreadId] },
      rootThreadId,
      expectedThreadIds,
    ),
    ["thr_side_child", rootThreadId],
  );
});

/** 旧布尔标记、非法身份、遗漏或重复身份、乱序和额外字段都不能冒充完整清理。 */
test("rejects incomplete or legacy side-chat close receipts", () => {
  for (const receipt of [
    undefined,
    { closedThreadIds: [] },
    { closedThreadIds: [rootThreadId] },
    { closedThreadIds: [rootThreadId, "thr_side_child", "thr_unexpected"] },
    { closedThreadIds: [rootThreadId, rootThreadId] },
    { closedThreadIds: [rootThreadId, "thr_side_child"] },
    { closedThreadIds: [rootThreadId, "not_a_thread"] },
    { closed: true, closedThreadIds: [rootThreadId, "thr_side_child"] },
  ]) {
    assert.throws(
      () => assertSideChatCloseReceipt(receipt, rootThreadId, expectedThreadIds),
      /侧聊关闭回执/u,
    );
  }
});

/** expected lineage 本身若漏根或含重复 ID，验收基准无效时也必须失败关闭。 */
test("rejects an invalid frozen lineage baseline", () => {
  assert.throws(
    () => assertSideChatCloseReceipt(
      { closedThreadIds: [rootThreadId, "thr_side_child"] },
      rootThreadId,
      [rootThreadId, rootThreadId],
    ),
    /侧聊关闭回执/u,
  );
  assert.throws(
    () => assertSideChatCloseReceipt(
      { closedThreadIds: [rootThreadId] },
      rootThreadId,
      ["thr_side_child"],
    ),
    /侧聊关闭回执/u,
  );
});
