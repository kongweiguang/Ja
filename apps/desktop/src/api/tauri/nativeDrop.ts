// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import type { RuntimeNativeBridge } from "./runtime";

export const JA_NATIVE_DROP_EVENT = "ja://workspace-native-drop";

const NativeDropEventSchema = z.discriminatedUnion("phase", [
  z
    .object({
      phase: z.enum(["enter", "over", "leave"]),
      x: z.number().finite().min(-1e9).max(1e9),
      y: z.number().finite().min(-1e9).max(1e9),
      count: z.number().int().min(0).max(10_000),
    })
    .strict(),
  z
    .object({
      phase: z.literal("drop"),
      x: z.number().finite().min(-1e9).max(1e9),
      y: z.number().finite().min(-1e9).max(1e9),
      count: z.number().int().min(0).max(10_000),
      dropToken: z.string().uuid(),
    })
    .strict(),
]);

export type NativeDropEvent = z.infer<typeof NativeDropEventSchema>;
export type NativeDropCommit = Extract<NativeDropEvent, { phase: "drop" }>;
type NativeDropListener = (event: NativeDropEvent) => void;
type NativeDropCommitListener = (event: NativeDropCommit) => void;

/** 统一校验 Rust 的脱敏四态事件，畸形 payload 在任何 Feature 观察前丢弃。 */
export function parseNativeDropEvent(payload: unknown): NativeDropEvent {
  return NativeDropEventSchema.parse(payload);
}

/**
 * 每个 native bridge 只保留一个底层 listener；Feature 订阅的是经过校验的多播投影，
 * 既不能订阅任意事件，也不会重复建立原生窗口监听。
 */
export class NativeDropRouter {
  private readonly listeners = new Set<NativeDropListener>();
  private unlisten: (() => void | Promise<void>) | undefined;
  private installing: Promise<void> | undefined;

  constructor(private readonly bridge: Pick<RuntimeNativeBridge, "listen">) {}

  /** 首个消费者到达时注册一次 listener，并用共享 Promise 合并并发订阅。 */
  private async ensureListening(): Promise<void> {
    if (this.unlisten !== undefined) return;
    if (this.installing === undefined) {
      this.installing = this.bridge
        .listen<unknown>(JA_NATIVE_DROP_EVENT, (payload) => {
          let event: NativeDropEvent;
          try {
            event = parseNativeDropEvent(payload);
          } catch {
            return;
          }
          for (const listener of [...this.listeners]) listener(event);
        })
        .then((unlisten) => {
          this.unlisten = unlisten;
        })
        .finally(() => {
          this.installing = undefined;
        });
    }
    await this.installing;
  }

  /** 返回幂等退订函数；最后一个消费者离开时同步释放底层 Tauri listener。 */
  async subscribe(listener: NativeDropListener): Promise<() => Promise<void>> {
    this.listeners.add(listener);
    try {
      await this.ensureListening();
    } catch (error) {
      this.listeners.delete(listener);
      throw error;
    }
    let active = true;
    return async () => {
      if (!active) return;
      active = false;
      this.listeners.delete(listener);
      if (this.listeners.size !== 0 || this.unlisten === undefined) return;
      const unlisten = this.unlisten;
      this.unlisten = undefined;
      await unlisten();
    };
  }

  /** Files/Terminal 只观察可消费 token 的 Drop 终态，不能误用 Enter/Over 做文件操作。 */
  subscribeCommit(listener: NativeDropCommitListener): Promise<() => Promise<void>> {
    return this.subscribe((event) => {
      if (event.phase === "drop") listener(event);
    });
  }
}

const routers = new WeakMap<object, NativeDropRouter>();

/** 按 bridge identity 复用唯一 router，使生产默认 bridge 与测试注入 bridge 都保持隔离。 */
export function nativeDropRouterFor(bridge: Pick<RuntimeNativeBridge, "listen">): NativeDropRouter {
  const identity = bridge as object;
  const existing = routers.get(identity);
  if (existing !== undefined) return existing;
  const created = new NativeDropRouter(bridge);
  routers.set(identity, created);
  return created;
}
