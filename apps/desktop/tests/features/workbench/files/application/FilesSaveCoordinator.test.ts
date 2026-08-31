// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  FilesSaveCoordinator,
  type SaveTimerPort,
} from "@/features/workbench/files/application/FilesSaveCoordinator";

/** 构造可手动触发的时钟，保证测试不依赖真实延迟或浏览器事件循环。 */
function fakeTimer(): SaveTimerPort & { fireAll: () => void } {
  const callbacks = new Map<object, () => void>();
  return {
    set: (_delayMillis, callback) => {
      const handle = {};
      callbacks.set(handle, callback);
      return handle;
    },
    clear: (handle) => {
      callbacks.delete(handle as object);
    },
    fireAll: () => {
      const current = [...callbacks.entries()];
      callbacks.clear();
      for (const [, callback] of current) callback();
    },
  };
}

/** 创建外部可完成的 Promise，用于精确验证保存中的补充 flush。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("FilesSaveCoordinator", () => {
  it("debounce 只触发最后一次调度", async () => {
    const timer = fakeTimer();
    const saveOnce = vi.fn(async () => true);
    const coordinator = new FilesSaveCoordinator({ timer, saveOnce, shouldContinue: () => false });
    coordinator.schedule("a.txt", 500);
    coordinator.schedule("a.txt", 500);
    timer.fireAll();
    await Promise.resolve();
    expect(saveOnce).toHaveBeenCalledTimes(1);
  });

  it("保存期间的多个 flush 合并为一次 ACK 后续保存", async () => {
    const timer = fakeTimer();
    const first = deferred<boolean>();
    const saveOnce = vi
      .fn()
      .mockImplementationOnce(() => first.promise)
      .mockResolvedValueOnce(true);
    const coordinator = new FilesSaveCoordinator({ timer, saveOnce, shouldContinue: () => true });
    const initial = coordinator.flush("a.txt");
    const second = coordinator.flush("a.txt");
    const third = coordinator.flush("a.txt");
    first.resolve(true);
    await Promise.all([initial, second, third]);
    expect(saveOnce).toHaveBeenCalledTimes(2);
  });

  it("失败后终止队列且 reset 清理待执行 timer", async () => {
    const timer = fakeTimer();
    const saveOnce = vi.fn(async () => false);
    const coordinator = new FilesSaveCoordinator({ timer, saveOnce, shouldContinue: () => true });
    await coordinator.flush("a.txt");
    coordinator.schedule("b.txt", 500);
    coordinator.reset();
    timer.fireAll();
    await Promise.resolve();
    expect(saveOnce).toHaveBeenCalledTimes(1);
    expect(coordinator.pendingTasks()).toEqual([]);
  });
});
