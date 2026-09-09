// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  createTurnReviewReadClient,
  SupersededTurnReviewRead,
} from "@/features/workbench/review/application/turnReviewReadClient";
import type { TurnReviewDiffParser } from "@/features/workbench/review/application/turnReviewDiffClient";

/** 提供可控 Promise，避免用 sleep 猜测调度与隐藏后的真实 permit 释放顺序。 */
function deferred<T>(): {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => {
    resolve = nextResolve;
  });
  return { promise, resolve };
}

/** 测试只验证读取调度，parser 用最小结构化输出隔离 parse-diff 实现。 */
function parser(): TurnReviewDiffParser {
  return {
    parse: vi.fn(async (unified: string) => [
      { path: unified, oldPath: null, additions: 0, deletions: 0, hunks: [], lines: [] },
    ]),
    cancelQueued: vi.fn(),
    dispose: vi.fn(),
  };
}

/** 推进到底层 load 已进入在途；用户点击之间天然跨事件轮次，测试需显式还原该边界。 */
async function startScheduledRead(): Promise<void> {
  await Promise.resolve();
}

describe("turn review read client", () => {
  it("最多启动两个底层读取，并把第三个位置压缩为最新选择", async () => {
    const aGate = deferred<string>();
    const bGate = deferred<string>();
    const starts: string[] = [];
    const client = createTurnReviewReadClient(parser());
    const read = (key: string) =>
      client.read({
        key,
        load: () => {
          starts.push(key);
          if (key === "A") return aGate.promise;
          if (key === "B") return bGate.promise;
          return Promise.resolve(key);
        },
      });

    const a = read("A");
    await startScheduledRead();
    const b = read("B");
    await startScheduledRead();
    const c = read("C");
    const d = read("D");
    await expect(c).rejects.toBeInstanceOf(SupersededTurnReviewRead);
    expect(starts).toEqual(["A", "B"]);

    aGate.resolve("A");
    await expect(a).rejects.toMatchObject({ name: "AbortError" });
    await expect(d).resolves.toEqual([expect.objectContaining({ path: "D" })]);
    bGate.resolve("B");
    await expect(b).rejects.toMatchObject({ name: "AbortError" });
    client.dispose();
  });

  it("A-B-A 为最后一次 A 发起新读取，不复用已 supersede 的 A", async () => {
    const oldA = deferred<string>();
    const bGate = deferred<string>();
    const starts: string[] = [];
    const client = createTurnReviewReadClient(parser());
    const read = (key: string) =>
      client.read({
        key,
        load: () => {
          starts.push(key);
          if (starts.length === 1) return oldA.promise;
          if (key === "B") return bGate.promise;
          return Promise.resolve(key);
        },
      });

    const firstA = read("A");
    await startScheduledRead();
    const b = read("B");
    await startScheduledRead();
    const finalA = read("A");
    expect(starts).toEqual(["A", "B"]);
    oldA.resolve("old-A");
    await expect(firstA).rejects.toMatchObject({ name: "AbortError" });
    await expect(finalA).resolves.toEqual([expect.objectContaining({ path: "A" })]);
    expect(starts).toEqual(["A", "B", "A"]);
    bGate.resolve("B");
    await expect(b).rejects.toMatchObject({ name: "AbortError" });
    client.dispose();
  });

  it("只合并当前同键的在途 effect 重放，完成后同键仍重新读取", async () => {
    const gate = deferred<string>();
    const load = vi.fn(() => gate.promise);
    const client = createTurnReviewReadClient(parser());
    const first = client.read({ key: "A", load });
    await startScheduledRead();
    const replay = client.read({ key: "A", load });
    expect(replay).toBe(first);
    expect(load).toHaveBeenCalledTimes(1);
    gate.resolve("A");
    await first;

    await client.read({ key: "A", load: vi.fn(async () => "A-again") });
    expect(load).toHaveBeenCalledTimes(1);
    client.dispose();
  });

  it("隐藏后新面板等待旧底层收口，窗口总在途不会超过两个", async () => {
    const oldA = deferred<string>();
    const oldB = deferred<string>();
    const starts: string[] = [];
    const first = createTurnReviewReadClient(parser());
    const readOld = (key: string, gate: ReturnType<typeof deferred<string>>) =>
      first.read({
        key,
        load: () => {
          starts.push(key);
          return gate.promise;
        },
      });
    const a = readOld("A", oldA);
    await startScheduledRead();
    const b = readOld("B", oldB);
    await startScheduledRead();
    first.dispose();

    const reopened = createTurnReviewReadClient(parser());
    const latest = reopened.read({
      key: "C",
      load: async () => {
        starts.push("C");
        return "C";
      },
    });
    expect(starts).toEqual(["A", "B"]);
    oldA.resolve("A");
    await expect(a).rejects.toMatchObject({ name: "AbortError" });
    await expect(latest).resolves.toEqual([expect.objectContaining({ path: "C" })]);
    oldB.resolve("B");
    await expect(b).rejects.toMatchObject({ name: "AbortError" });
    reopened.dispose();
  });

  it("真正改选与 metadata-only 取消都会立即撤销待解析正文", async () => {
    const parse = parser();
    const client = createTurnReviewReadClient(parse);
    await client.read({ key: "A", load: vi.fn(async () => "A") });
    vi.mocked(parse.cancelQueued).mockClear();
    const pending = deferred<string>();

    const readingB = client.read({ key: "B", load: vi.fn(() => pending.promise) });
    expect(parse.cancelQueued).toHaveBeenCalledTimes(1);
    await startScheduledRead();
    client.cancel();
    expect(parse.cancelQueued).toHaveBeenCalledTimes(2);
    pending.resolve("B");
    await expect(readingB).rejects.toMatchObject({ name: "AbortError" });
    client.dispose();
  });

  it("dispose 释放 parser，并拒绝后续读取", async () => {
    const parse = parser();
    const client = createTurnReviewReadClient(parse);
    client.dispose();
    expect(parse.dispose).toHaveBeenCalledTimes(1);
    await expect(client.read({ key: "late", load: vi.fn(async () => "late") })).rejects.toThrow(
      "disposed",
    );
  });
});
