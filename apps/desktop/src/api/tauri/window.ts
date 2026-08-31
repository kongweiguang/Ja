// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen as tauriListen, type UnlistenFn } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { z } from "zod";
import { invokeNativeCommand } from "./nativeInvoke";

export const JA_NATIVE_SHORTCUT_COMMANDS = {
  leaseQuery: "ja_native_shortcut_lease_query",
  contextUpdate: "ja_native_shortcut_context_update",
  contextActivate: "ja_native_shortcut_context_activate",
} as const;

export const JA_NATIVE_SHORTCUT_EVENTS = {
  command: "ja://native-shortcut",
  status: "ja://native-shortcut-status",
} as const;

export const JA_APP_EXIT_COMMANDS = {
  listenerReady: "ja_app_exit_listener_ready",
  listenerUnready: "ja_app_exit_listener_unready",
  commit: "ja_app_exit_commit",
  cancel: "ja_app_exit_cancel",
} as const;

export const JA_APP_EXIT_EVENTS = {
  requested: "ja://app-exit-requested",
} as const;

const NativeShortcutEpochSchema = z.string().uuid();
const NativeShortcutLeaseRevisionSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const NativeShortcutRevisionSchema = NativeShortcutLeaseRevisionSchema.refine(
  (revision) => revision > 0,
);
const NativeShortcutContextSchema = z
  .object({
    projectCapabilitiesEnabled: z.boolean(),
    conversationFocusEnabled: z.boolean(),
  })
  .strict();
const NativeShortcutHandlerStatusSchema = z.enum([
  "pending",
  "ready",
  "unavailable",
  "unsupported",
]);
const NativeShortcutLeaseSchema = z
  .object({
    epoch: NativeShortcutEpochSchema,
    revision: NativeShortcutLeaseRevisionSchema,
    ready: z.boolean(),
    mainHandlerStatus: NativeShortcutHandlerStatusSchema,
  })
  .strict();
const NativeShortcutContextSnapshotSchema = NativeShortcutLeaseSchema.extend({
  revision: NativeShortcutRevisionSchema,
  projectCapabilitiesEnabled: z.boolean(),
  conversationFocusEnabled: z.boolean(),
}).strict();
const NativeShortcutCommandSchema = z.enum(["review", "files", "terminal", "preview", "side_chat"]);
const NativeShortcutEventSchema = z
  .object({
    epoch: NativeShortcutEpochSchema,
    command: NativeShortcutCommandSchema,
    revision: NativeShortcutRevisionSchema,
  })
  .strict();
const NativeShortcutStatusSchema = z.literal("unavailable");

type NativeShortcutCommand = z.infer<typeof NativeShortcutCommandSchema>;
type NativeShortcutStatus = z.infer<typeof NativeShortcutStatusSchema>;
export type NativeShortcutContext = z.infer<typeof NativeShortcutContextSchema>;
export type NativeShortcutLease = z.infer<typeof NativeShortcutLeaseSchema>;
export type NativeShortcutContextSnapshot = z.infer<typeof NativeShortcutContextSnapshotSchema>;
export type NativeShortcutUnsubscribe = () => void;

export interface NativeShortcutSubscription {
  onCommand: (command: NativeShortcutCommand) => void;
  onStatus?: (status: NativeShortcutStatus) => void;
}

/** React 只依赖上下文和固定事件，不接触 Tauri command/event 名称或 wire DTO。 */
export interface NativeShortcutPort {
  /** 以 prepare/activate 两阶段发布 renderer 能力，并返回 Rust 权威激活 ACK。 */
  updateContext: (context: NativeShortcutContext) => Promise<NativeShortcutContextSnapshot>;
  /** 订阅固定事件并取得当前 Rust renderer lease，返回幂等释放函数。 */
  subscribe: (subscription: NativeShortcutSubscription) => Promise<NativeShortcutUnsubscribe>;
}

type NativeShortcutCommandName =
  (typeof JA_NATIVE_SHORTCUT_COMMANDS)[keyof typeof JA_NATIVE_SHORTCUT_COMMANDS];
type NativeShortcutEventName =
  (typeof JA_NATIVE_SHORTCUT_EVENTS)[keyof typeof JA_NATIVE_SHORTCUT_EVENTS];

/** 可注入 bridge 让 wrapper 测试覆盖 wire，而组件测试只替换窄 port。 */
export interface NativeShortcutNativeBridge {
  invoke: (command: NativeShortcutCommandName, args: Record<string, unknown>) => Promise<unknown>;
  listen: (
    event: NativeShortcutEventName,
    handler: (payload: unknown) => void,
  ) => Promise<UnlistenFn>;
}

const defaultNativeShortcutBridge: NativeShortcutNativeBridge = {
  invoke: (command, args) =>
    invokeNativeCommand(command, args, () => tauriInvoke<unknown>(command, args)),
  listen: async (event, handler) =>
    tauriListen<unknown>(event, (payload) => handler(payload.payload)),
};

type NativeShortcutAdapterErrorCode =
  | "invalid_input"
  | "invalid_response"
  | "command_failed"
  | "subscription_failed";

/** 固定错误文本阻止 invoke、COM 或窗口诊断进入 renderer 状态。 */
class NativeShortcutAdapterError extends Error {
  /** 只根据封闭错误码生成静态 renderer 文本。 */
  constructor(readonly code: NativeShortcutAdapterErrorCode) {
    super(
      code === "invalid_input"
        ? "原生快捷键上下文无效"
        : code === "invalid_response"
          ? "原生快捷键返回无效"
          : code === "subscription_failed"
            ? "原生快捷键监听失败"
            : "原生快捷键更新失败",
    );
    this.name = "NativeShortcutAdapterError";
  }
}

/** 严格解析 renderer 上下文并丢弃 Zod 可能携带的字段诊断。 */
function parseNativeShortcutInput(value: unknown): NativeShortcutContext {
  const parsed = NativeShortcutContextSchema.safeParse(value);
  if (!parsed.success) throw new NativeShortcutAdapterError("invalid_input");
  return parsed.data;
}

/** 严格解析 Rust ACK，未知字段或越界 revision 都不能成为事件身份。 */
function parseNativeShortcutSnapshot(value: unknown): NativeShortcutContextSnapshot {
  const parsed = NativeShortcutContextSnapshotSchema.safeParse(value);
  if (!parsed.success) throw new NativeShortcutAdapterError("invalid_response");
  return parsed.data;
}

/** 严格解析 Rust 签发的 renderer epoch；renderer 不生成或猜测进程期 revision。 */
function parseNativeShortcutLease(value: unknown): NativeShortcutLease {
  const parsed = NativeShortcutLeaseSchema.safeParse(value);
  if (!parsed.success) throw new NativeShortcutAdapterError("invalid_response");
  return parsed.data;
}

/** listener 全部就绪后才查询 Rust renderer lease，确保 emit gate 不把注册调用当 ACK。 */
async function invokeNativeShortcutLease(bridge: NativeShortcutNativeBridge): Promise<unknown> {
  try {
    return await bridge.invoke(JA_NATIVE_SHORTCUT_COMMANDS.leaseQuery, {});
  } catch {
    throw new NativeShortcutAdapterError("command_failed");
  }
}

/** 只调用固定 context command，并把所有 native rejection 收敛为静态错误。 */
async function invokeNativeShortcutContext(
  bridge: NativeShortcutNativeBridge,
  input: NativeShortcutContext & { epoch: string; revision: number },
): Promise<unknown> {
  try {
    return await bridge.invoke(JA_NATIVE_SHORTCUT_COMMANDS.contextUpdate, { input });
  } catch {
    throw new NativeShortcutAdapterError("command_failed");
  }
}

/** prepare ACK 后只提交 Rust 刚签发的 exact epoch/revision，不允许 renderer 选择其它能力。 */
async function invokeNativeShortcutActivation(
  bridge: NativeShortcutNativeBridge,
  input: { epoch: string; revision: number },
): Promise<unknown> {
  try {
    return await bridge.invoke(JA_NATIVE_SHORTCUT_COMMANDS.contextActivate, { input });
  } catch {
    throw new NativeShortcutAdapterError("command_failed");
  }
}

/** 复核 Rust context 权限，防止伪造或竞态事件绕过当前页面能力开关。 */
function nativeShortcutEnabled(
  snapshot: NativeShortcutContextSnapshot,
  command: NativeShortcutCommand,
): boolean {
  return command === "side_chat"
    ? snapshot.conversationFocusEnabled
    : snapshot.projectCapabilitiesEnabled;
}

/** 只比较 capability policy，使排队更新能够提交最新 renderer 状态。 */
function sameNativeShortcutContext(
  left: NativeShortcutContext,
  right: NativeShortcutContext,
): boolean {
  return (
    left.projectCapabilitiesEnabled === right.projectCapabilitiesEnabled &&
    left.conversationFocusEnabled === right.conversationFocusEnabled
  );
}

/** 只有 Pending/Ready handler 可被 activate；Pending 会等安装 ACK 后才真正产生事件。 */
function nativeShortcutHandlerCanActivate(
  status: NativeShortcutContextSnapshot["mainHandlerStatus"],
): boolean {
  return status === "pending" || status === "ready";
}

/** 单个 Tauri unlisten 失败不得阻止同组其它 listener 释放。 */
function releaseNativeShortcutListener(unlisten: UnlistenFn | undefined): void {
  try {
    unlisten?.();
  } catch {
    // 窗口正在销毁时继续回收其它 listener，且不向 renderer 透出 native 诊断。
  }
}

/**
 * 持有 Rust 签发的 renderer epoch/revision 与 ACK 身份；旧 identity 保留到
 * query-and-suspend ACK；新 identity 只在 prepare ACK 后预置，并由 exact activate 开门。
 */
export class TauriNativeShortcutAdapter implements NativeShortcutPort {
  private lease: NativeShortcutLease | undefined;
  private activeContext: NativeShortcutContextSnapshot | undefined;
  private latestContext: NativeShortcutContext | undefined;
  private bindingSequence = 0;
  private subscribedBinding: number | undefined;
  private unavailableBinding: number | undefined;
  private closingBinding: number | undefined;
  private controlQueue: Promise<void> = Promise.resolve();
  private releaseBarrier: Promise<void> = Promise.resolve();

  /** 固定 typed bridge；生产默认只指向本文件封闭的 command/event 名称。 */
  constructor(private readonly bridge: NativeShortcutNativeBridge = defaultNativeShortcutBridge) {}

  /** 串行提交最新 context；开始排队时仍保留旧 active，直到 Rust query 已确认 suspend。 */
  async updateContext(context: NativeShortcutContext): Promise<NativeShortcutContextSnapshot> {
    const parsed = parseNativeShortcutInput(context);
    const binding = this.bindingSequence;
    if (this.lease === undefined || !this.bindingIsUsable(binding)) {
      throw new NativeShortcutAdapterError("command_failed");
    }
    this.latestContext = parsed;
    return this.enqueueControl(() => this.commitLatestContext(binding));
  }

  /** 把 control 操作线性化；失败只影响自身结果，不毒化后续 suspend/recovery。 */
  private enqueueControl<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.controlQueue.then(operation);
    this.controlQueue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  /** 判断 subscription 仍可提交 control；closing/unavailable 只停止更新，不提前停 command listener。 */
  private bindingIsUsable(binding: number): boolean {
    return (
      binding === this.bindingSequence &&
      this.subscribedBinding === binding &&
      this.unavailableBinding !== binding &&
      this.closingBinding !== binding
    );
  }

  /** query 是 Rust 的 query-and-suspend ACK；ready=true 不能作为安全的 rebind 基线。 */
  private async queryAndSuspend(binding: number): Promise<NativeShortcutLease> {
    if (!this.bindingIsUsable(binding)) throw new NativeShortcutAdapterError("command_failed");
    const lease = parseNativeShortcutLease(await invokeNativeShortcutLease(this.bridge));
    if (lease.ready) throw new NativeShortcutAdapterError("invalid_response");
    if (!this.bindingIsUsable(binding)) throw new NativeShortcutAdapterError("command_failed");
    this.lease = lease;
    this.activeContext = undefined;
    return lease;
  }

  /** 最多两次确认 query-and-suspend；没有 ACK 时保留 listener/identity，避免 native 仍 ready 却吞键。 */
  private async suspendAfterAcknowledgement(binding: number): Promise<boolean> {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      if (binding !== this.bindingSequence) return false;
      try {
        const lease = parseNativeShortcutLease(await invokeNativeShortcutLease(this.bridge));
        if (lease.ready) throw new NativeShortcutAdapterError("invalid_response");
        if (binding !== this.bindingSequence) return false;
        this.lease = lease;
        this.activeContext = undefined;
        return true;
      } catch {
        // ACK 丢失时再次 query 可确认第一次调用是否已在 Rust 关闭 ready gate。
      }
    }
    return false;
  }

  /**
   * 每次尝试先 query/suspend，再 prepare context；prepare ACK 后预置 exact identity，最后
   * activate。activate ACK 丢失时 listener 已可消费事件，下一次 query 再安全 rebase。
   */
  private async commitLatestContext(binding: number): Promise<NativeShortcutContextSnapshot> {
    let lastError: unknown = new NativeShortcutAdapterError("command_failed");
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const lease = await this.queryAndSuspend(binding);
        const context = this.latestContext;
        if (context === undefined || !this.bindingIsUsable(binding)) {
          throw new NativeShortcutAdapterError("command_failed");
        }
        if (lease.revision >= Number.MAX_SAFE_INTEGER)
          throw new NativeShortcutAdapterError("invalid_input");
        const revision = lease.revision + 1;
        const prepared = parseNativeShortcutSnapshot(
          await invokeNativeShortcutContext(this.bridge, {
            epoch: lease.epoch,
            revision,
            ...context,
          }),
        );
        if (
          prepared.ready ||
          prepared.epoch !== lease.epoch ||
          prepared.revision !== revision ||
          !sameNativeShortcutContext(prepared, context)
        ) {
          throw new NativeShortcutAdapterError("invalid_response");
        }
        if (!this.bindingIsUsable(binding)) throw new NativeShortcutAdapterError("command_failed");
        this.lease = {
          epoch: prepared.epoch,
          revision: prepared.revision,
          ready: false,
          mainHandlerStatus: prepared.mainHandlerStatus,
        };
        this.activeContext = nativeShortcutHandlerCanActivate(prepared.mainHandlerStatus)
          ? { ...prepared, ready: true }
          : undefined;
        const snapshot = parseNativeShortcutSnapshot(
          await invokeNativeShortcutActivation(this.bridge, {
            epoch: prepared.epoch,
            revision: prepared.revision,
          }),
        );
        if (
          snapshot.epoch !== prepared.epoch ||
          snapshot.revision !== prepared.revision ||
          !sameNativeShortcutContext(snapshot, context) ||
          snapshot.ready !== nativeShortcutHandlerCanActivate(snapshot.mainHandlerStatus)
        ) {
          throw new NativeShortcutAdapterError("invalid_response");
        }
        if (!this.bindingIsUsable(binding)) throw new NativeShortcutAdapterError("command_failed");
        this.lease = {
          epoch: snapshot.epoch,
          revision: snapshot.revision,
          ready: snapshot.ready,
          mainHandlerStatus: snapshot.mainHandlerStatus,
        };
        this.activeContext =
          snapshot.ready &&
          snapshot.mainHandlerStatus !== "unavailable" &&
          snapshot.mainHandlerStatus !== "unsupported"
            ? snapshot
            : undefined;
        return snapshot;
      } catch (error) {
        lastError = error;
        if (!this.bindingIsUsable(binding)) throw error;
      }
    }
    await this.suspendAfterAcknowledgement(binding);
    throw lastError;
  }

  /**
   * 同时订阅固定 command/status 事件，成功后才 query Rust lease；若上一 binding 正在
   * 释放，必须先等 suspend ACK，避免快速 unsubscribe→subscribe 取消旧 revocation。
   */
  async subscribe(subscription: NativeShortcutSubscription): Promise<NativeShortcutUnsubscribe> {
    if (this.closingBinding !== undefined) {
      await this.releaseBarrier;
      if (this.closingBinding !== undefined) {
        throw new NativeShortcutAdapterError("subscription_failed");
      }
    }
    const binding = this.bindingSequence + 1;
    this.bindingSequence = binding;
    this.subscribedBinding = binding;
    this.lease = undefined;
    this.activeContext = undefined;
    this.latestContext = undefined;
    this.unavailableBinding = undefined;
    this.closingBinding = undefined;
    let disposed = false;
    let commandUnlisten: UnlistenFn | undefined;
    let statusUnlisten: UnlistenFn | undefined;
    try {
      commandUnlisten = await this.bridge.listen(JA_NATIVE_SHORTCUT_EVENTS.command, (payload) => {
        if (disposed || binding !== this.bindingSequence) return;
        const parsed = NativeShortcutEventSchema.safeParse(payload);
        const active = this.activeContext;
        if (
          !parsed.success ||
          active === undefined ||
          parsed.data.epoch !== active.epoch ||
          parsed.data.revision !== active.revision ||
          !nativeShortcutEnabled(active, parsed.data.command)
        )
          return;
        subscription.onCommand(parsed.data.command);
      });
      statusUnlisten = await this.bridge.listen(JA_NATIVE_SHORTCUT_EVENTS.status, (payload) => {
        if (disposed || binding !== this.bindingSequence) return;
        const parsed = NativeShortcutStatusSchema.safeParse(payload);
        if (!parsed.success) return;
        if (this.unavailableBinding === binding) return;
        this.unavailableBinding = binding;
        subscription.onStatus?.(parsed.data);
        void this.enqueueControl(async () => {
          const suspended = await this.suspendAfterAcknowledgement(binding);
          if (
            suspended &&
            binding === this.bindingSequence &&
            this.unavailableBinding === binding
          ) {
            this.lease = undefined;
            this.latestContext = undefined;
          }
        });
      });
      await this.enqueueControl(() => this.queryAndSuspend(binding));
    } catch {
      disposed = true;
      if (binding === this.bindingSequence) {
        await this.enqueueControl(() => this.suspendAfterAcknowledgement(binding));
        if (binding === this.bindingSequence) {
          this.bindingSequence += 1;
          this.subscribedBinding = undefined;
          this.lease = undefined;
          this.activeContext = undefined;
          this.latestContext = undefined;
          this.closingBinding = undefined;
        }
      }
      releaseNativeShortcutListener(commandUnlisten);
      releaseNativeShortcutListener(statusUnlisten);
      throw new NativeShortcutAdapterError("subscription_failed");
    }
    let unsubscribed = false;
    return () => {
      if (unsubscribed) return;
      unsubscribed = true;
      if (binding === this.bindingSequence) {
        this.closingBinding = binding;
        const release = this.enqueueControl(async () => {
          const suspended = await this.suspendAfterAcknowledgement(binding);
          if (!suspended || binding !== this.bindingSequence) return;
          disposed = true;
          this.bindingSequence += 1;
          this.subscribedBinding = undefined;
          this.lease = undefined;
          this.activeContext = undefined;
          this.latestContext = undefined;
          this.unavailableBinding = undefined;
          this.closingBinding = undefined;
          releaseNativeShortcutListener(commandUnlisten);
          releaseNativeShortcutListener(statusUnlisten);
        });
        this.releaseBarrier = release.then(
          () => undefined,
          () => undefined,
        );
        return;
      }
      disposed = true;
      releaseNativeShortcutListener(commandUnlisten);
      releaseNativeShortcutListener(statusUnlisten);
    };
  }
}

export type WindowAction = "minimize" | "toggle-maximize" | "hide";

export interface WindowFrameState {
  /** 表示原生窗口当前是否占满其 maximized work area。 */
  maximized: boolean;
  /** 表示 macOS 或其他桌面 host 当前是否拥有 fullscreen space。 */
  fullscreen: boolean;
}

export interface WindowFrameObserver {
  /** 操作后重新读取原生状态，因为 resize 事件可能晚到。 */
  refresh: () => void;
  /** 释放原生 resize listener，也覆盖晚完成的异步注册。 */
  dispose: () => void;
}

export interface WindowFocusObserver {
  /** 释放原生 focus/restore listener，也覆盖晚完成的异步注册。 */
  dispose: () => void;
}

export interface AppExitRequest {
  /** renderer 完成 Files/Preview 清理后提交唯一托盘退出请求。 */
  commit: () => Promise<void>;
  /** renderer 无法安全清理时取消请求，使托盘退出可以重试。 */
  cancel: () => Promise<void>;
}

export interface AppExitObserver {
  /** 释放托盘退出 listener，并撤销 renderer-ready ACK。 */
  dispose: () => void;
}

type AppExitCommandName = (typeof JA_APP_EXIT_COMMANDS)[keyof typeof JA_APP_EXIT_COMMANDS];
type AppExitEventName = (typeof JA_APP_EXIT_EVENTS)[keyof typeof JA_APP_EXIT_EVENTS];

/** 可注入 bridge 让测试覆盖 listener-ready、commit、cancel 与卸载完整握手。 */
export interface AppExitNativeBridge {
  invoke: (command: AppExitCommandName, args: Record<string, unknown>) => Promise<unknown>;
  listen: (event: AppExitEventName, handler: () => void) => Promise<UnlistenFn>;
}

const defaultAppExitBridge: AppExitNativeBridge = {
  invoke: (command, args) =>
    invokeNativeCommand(command, args, () => tauriInvoke<unknown>(command, args)),
  listen: async (event, handler) => tauriListen(event, handler),
};

/**
 * 监听唯一托盘退出事件，并在 listener 真正建立后 ACK Rust；普通窗口关闭由原生层
 * 直接隐藏，不进入 Files/Preview 清理，避免“收起”和“退出”混用生命周期。
 */
export function observeAppExitRequested(
  onRequest: (request: AppExitRequest) => void | Promise<void>,
  bridge: AppExitNativeBridge = defaultAppExitBridge,
): AppExitObserver {
  let disposed = false;
  let unlisten: (() => void) | undefined;
  let readyAcknowledged = false;

  /** 固定空参数 command，避免退出握手携带工作区、文件或错误详情。 */
  const invokeExitCommand = async (command: AppExitCommandName): Promise<void> => {
    try {
      await bridge.invoke(command, {});
    } catch {
      throw new Error("native app exit handshake failed");
    }
  };

  void bridge
    .listen(JA_APP_EXIT_EVENTS.requested, () => {
      if (disposed) return;
      const request: AppExitRequest = {
        commit: () => invokeExitCommand(JA_APP_EXIT_COMMANDS.commit),
        cancel: () => invokeExitCommand(JA_APP_EXIT_COMMANDS.cancel),
      };
      try {
        void Promise.resolve(onRequest(request)).catch(() => undefined);
      } catch {
        // Application controller 负责取消请求和用户反馈；event dispatcher 不接收业务异常。
      }
    })
    .then(async (stopListening) => {
      if (disposed) {
        stopListening();
        return;
      }
      unlisten = stopListening;
      try {
        await invokeExitCommand(JA_APP_EXIT_COMMANDS.listenerReady);
        readyAcknowledged = true;
      } catch {
        unlisten?.();
        unlisten = undefined;
      }
    })
    .catch(() => undefined);

  return {
    /** 先失效 callback，再释放 listener；已 ACK 时 best-effort 撤销 Rust 可投递事实。 */
    dispose: () => {
      if (disposed) return;
      disposed = true;
      unlisten?.();
      unlisten = undefined;
      if (readyAcknowledged) {
        readyAcknowledged = false;
        try {
          void invokeExitCommand(JA_APP_EXIT_COMMANDS.listenerUnready).catch(() => undefined);
        } catch {
          // 页面卸载期间 native bridge 可能已经失效；下一次 PageLoad Started 仍会原生撤销 ACK。
        }
      }
    },
  };
}

/** 将未知或浏览器 host 视为已聚焦，使通知策略失败关闭；无法读取原生 focus 时不得产生未请求 alert。 */
export async function isCurrentWindowFocused(): Promise<boolean> {
  try {
    return await getCurrentWindow().isFocused();
  } catch {
    return true;
  }
}

/**
 * 观察原生 focus 与 minimized-to-restored 转换，不依赖 WebView DOM focus；
 * Windows 在程序化 restore 时可能不产生后者。
 */
export function observeWindowFocus(onChange: (focused: boolean) => void): WindowFocusObserver {
  let disposed = false;
  let minimized: boolean | undefined;
  let readRevision = 0;
  const unlisteners: Array<() => void> = [];
  try {
    const appWindow = getCurrentWindow();
    /** 只在 observer 活动时保留 listener；晚到 handle 必须立即释放。 */
    const retain = (registration: Promise<() => void>): void => {
      void registration
        .then((unlisten) => {
          if (disposed) unlisten();
          else unlisteners.push(unlisten);
        })
        .catch(() => undefined);
    };
    /** 只检测真实 minimized-to-restored 边沿，并在发布前确认原生 focus。 */
    const refreshMinimized = (publishRestore: boolean): void => {
      const revision = ++readRevision;
      void appWindow
        .isMinimized()
        .then((current) => {
          if (disposed || revision !== readRevision) return;
          const restored = minimized === true && current === false;
          minimized = current;
          if (!publishRestore || !restored) return;
          void appWindow
            .isFocused()
            .then((focused) => {
              if (!disposed && revision === readRevision && focused) onChange(true);
            })
            .catch(() => undefined);
        })
        .catch(() => undefined);
    };
    refreshMinimized(false);
    retain(
      appWindow.onFocusChanged((event) => {
        if (!disposed) onChange(event.payload);
      }),
    );
    retain(appWindow.onResized(() => refreshMinimized(true)));
  } catch {
    // 浏览器预览保留由 feature 拥有的 DOM focus fallback。
  }
  return {
    /** 释放最终原生 handle 前先使 callback 失效，关闭异步注册竞态。 */
    dispose: () => {
      disposed = true;
      readRevision += 1;
      for (const unlisten of unlisteners.splice(0)) unlisten();
    },
  };
}

/**
 * 同时读取 maximized 与 fullscreen 状态，防止标题栏混用不同原生 frame 的
 * maximize 图标与 macOS 间距。
 */
async function readWindowFrameState(): Promise<WindowFrameState> {
  const appWindow = getCurrentWindow();
  const [maximized, fullscreen] = await Promise.all([
    appWindow.isMaximized(),
    appWindow.isFullscreen(),
  ]);
  return { maximized, fullscreen };
}

/**
 * 通过 resize 事件观察当前原生 frame；React 可能在 Tauri 尚未返回异步 listener
 * handle 时 unmount，因此注册与清理必须保持竞态安全。
 */
export function observeWindowFrameState(
  onChange: (state: WindowFrameState) => void,
): WindowFrameObserver {
  let disposed = false;
  let unlisten: (() => void) | undefined;
  let readRevision = 0;

  /** 只保留最新异步原生读取，使 resize burst 不能恢复陈旧状态。 */
  const refresh = (): void => {
    const revision = ++readRevision;
    void readWindowFrameState()
      .then((state) => {
        if (!disposed && revision === readRevision) {
          onChange(state);
        }
      })
      .catch(() => undefined);
  };

  try {
    const appWindow = getCurrentWindow();
    refresh();
    void appWindow
      .onResized(refresh)
      .then((stopListening) => {
        if (disposed) {
          stopListening();
          return;
        }
        unlisten = stopListening;
        // 关闭首轮读取后、listener 激活前可能发生原生 resize 的短暂注册窗口。
        refresh();
      })
      .catch(() => undefined);
  } catch {
    // 浏览器预览没有原生窗口元数据，因此刻意保留调用方的保守初始状态。
  }

  return {
    refresh,
    dispose: () => {
      disposed = true;
      readRevision += 1;
      unlisten?.();
      unlisten = undefined;
    },
  };
}

/**
 * 原生窗口所有权保留在 Tauri adapter 边界；动作失败只抛出固定脱敏错误，
 * 由 application controller 统一恢复 busy 状态并给出用户反馈。
 */
export async function invokeWindowAction(action: WindowAction): Promise<void> {
  try {
    const appWindow = getCurrentWindow();
    const operation =
      action === "minimize"
        ? appWindow.minimize()
        : action === "toggle-maximize"
          ? appWindow.toggleMaximize()
          : appWindow.hide();
    await operation;
  } catch {
    throw new Error("native window action failed");
  }
}
