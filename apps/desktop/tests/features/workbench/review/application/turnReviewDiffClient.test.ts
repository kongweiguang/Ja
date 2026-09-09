// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import { createTurnReviewDiffParser } from "@/features/workbench/review/application/turnReviewDiffClient";

afterEach(() => vi.unstubAllGlobals());

describe("Turn diff Worker failure recovery", () => {
  it("活动解析只保留一个最新待发请求，并以 AbortError 结算被替换项", async () => {
    const listeners = new Map<string, (event: MessageEvent) => void>();
    const posted: Array<{ requestId: number; unified: string }> = [];
    vi.stubGlobal(
      "Worker",
      class {
        /** 捕获真实 client 注册的消息回调，由测试精确推进 Worker 完成边界。 */
        addEventListener(name: string, listener: (event: MessageEvent) => void): void {
          listeners.set(name, listener);
        }
        /** 本用例保持 parser 存活，移除监听只需维护可观察注册表。 */
        removeEventListener(name: string): void {
          listeners.delete(name);
        }
        /** 记录真正越过 Worker 边界的正文，queued 项不得提前复制进消息通道。 */
        postMessage(message: { requestId: number; unified: string }): void {
          posted.push(message);
        }
        terminate = vi.fn();
      },
    );
    const parser = createTurnReviewDiffParser();

    const parseA = parser.parse("A");
    const parseB = parser.parse("B");
    const parseC = parser.parse("C");
    await expect(parseB).rejects.toMatchObject({ name: "AbortError" });
    expect(posted).toEqual([{ requestId: 1, unified: "A" }]);

    listeners.get("message")?.(new MessageEvent("message", { data: { requestId: 1, files: [] } }));
    await expect(parseA).resolves.toEqual([]);
    expect(posted).toEqual([
      { requestId: 1, unified: "A" },
      { requestId: 3, unified: "C" },
    ]);
    listeners.get("message")?.(new MessageEvent("message", { data: { requestId: 3, files: [] } }));
    await expect(parseC).resolves.toEqual([]);
    parser.dispose();
  });

  it("显式取消待发解析后不再 post 旧正文，后续新请求仍可正常解析", async () => {
    const listeners = new Map<string, (event: MessageEvent) => void>();
    const posted: Array<{ requestId: number; unified: string }> = [];
    vi.stubGlobal(
      "Worker",
      class {
        /** 捕获 Worker 回调，让 active 完成和下一次调度按测试控制的顺序发生。 */
        addEventListener(name: string, listener: (event: MessageEvent) => void): void {
          listeners.set(name, listener);
        }
        /** dispose 后移除监听，避免测试间残留 Worker 生命周期。 */
        removeEventListener(name: string): void {
          listeners.delete(name);
        }
        /** 消息列表代表真正复制进 Worker 通道的正文，取消项不得出现在这里。 */
        postMessage(message: { requestId: number; unified: string }): void {
          posted.push(message);
        }
        terminate = vi.fn();
      },
    );
    const parser = createTurnReviewDiffParser();

    const parseA = parser.parse("A");
    const parseB = parser.parse("B");
    parser.cancelQueued();
    await expect(parseB).rejects.toMatchObject({ name: "AbortError" });
    listeners.get("message")?.(new MessageEvent("message", { data: { requestId: 1, files: [] } }));
    await expect(parseA).resolves.toEqual([]);
    expect(posted).toEqual([{ requestId: 1, unified: "A" }]);

    const parseC = parser.parse("C");
    expect(posted).toEqual([
      { requestId: 1, unified: "A" },
      { requestId: 3, unified: "C" },
    ]);
    listeners.get("message")?.(new MessageEvent("message", { data: { requestId: 3, files: [] } }));
    await expect(parseC).resolves.toEqual([]);
    parser.dispose();
  });

  it.each(["error", "messageerror"])(
    "settles pending reads on %s and disposes once",
    async (eventName) => {
      const listeners = new Map<string, () => void>();
      const terminate = vi.fn();
      vi.stubGlobal(
        "Worker",
        class {
          /** 用明确事件模拟 Worker 故障，不运行第二套 Diff parser。 */
          addEventListener(name: string, listener: () => void): void {
            listeners.set(name, listener);
          }
          /** 验证 dispose 解除了整个生命周期的监听。 */
          removeEventListener(name: string): void {
            listeners.delete(name);
          }
          postMessage = vi.fn();
          terminate = terminate;
        },
      );
      const parser = createTurnReviewDiffParser();
      const reading = parser.parse("--- a/file.txt");
      const rejected = expect(reading).rejects.toThrow("diff parser unavailable");
      listeners.get(eventName)?.();
      await rejected;
      await expect(parser.parse("retry")).rejects.toThrow("diff parser unavailable");
      parser.dispose();
      parser.dispose();
      expect(terminate).toHaveBeenCalledTimes(1);
      expect(listeners.size).toBe(0);
    },
  );
});
