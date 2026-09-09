// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { projectTimelineChronology } from "@/features/conversation/domain/timelineChronology";

/** 以最小候选构造 chronology fixture，避免测试把 UI 字段误当成排序输入。 */
function candidate(identity: string, occurredAt: string | undefined, value = identity) {
  return { identity, occurredAt, value };
}

describe("projectTimelineChronology", () => {
  it("按服务端发生时间合并实时与快照事实，不依赖输入到达顺序", () => {
    const projected = projectTimelineChronology([
      candidate("task:completed", "2026-09-04T08:00:03Z"),
      candidate("conversation:second", "2026-09-04T08:00:02Z"),
      candidate("task:dispatched", "2026-09-04T08:00:01Z"),
      candidate("conversation:first", "2026-09-04T08:00:00Z"),
    ]);

    expect(projected.map((entry) => entry.identity)).toEqual([
      "conversation:first",
      "task:dispatched",
      "conversation:second",
      "task:completed",
    ]);
  });

  it("相同时间戳按稳定 identity 排序，并将重复投递收敛为最后一个权威投影", () => {
    const timestamp = "2026-09-04T08:00:00.000Z";
    const projected = projectTimelineChronology([
      candidate("task:b", timestamp, "旧 B"),
      candidate("task:a", timestamp, "A"),
      candidate("task:b", timestamp, "新 B"),
    ]);

    expect(projected.map((entry) => [entry.identity, entry.value])).toEqual([
      ["task:a", "A"],
      ["task:b", "新 B"],
    ]);
  });

  it("未提交或非法时间不伪造发生顺序，并在流式重投影时保持 identity 全序", () => {
    const projected = projectTimelineChronology([
      candidate("stream:z", undefined),
      candidate("committed", "2026-09-04T08:00:00Z"),
      candidate("stream:a", "not-a-timestamp"),
    ]);

    expect(projected.map((entry) => entry.identity)).toEqual(["committed", "stream:a", "stream:z"]);
  });
});
