// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type NativeShortcutCommand = "review" | "files" | "terminal" | "preview" | "side_chat";

export interface NativeShortcutContext {
  readonly projectCapabilitiesEnabled: boolean;
  readonly conversationFocusEnabled: boolean;
}

interface NativeShortcutContextSnapshot extends NativeShortcutContext {
  readonly epoch: string;
  readonly revision: number;
  readonly ready: boolean;
  readonly mainHandlerStatus: "pending" | "ready" | "unavailable" | "unsupported";
}

export interface NativeShortcutSubscription {
  onCommand(command: NativeShortcutCommand): void;
  onStatus?(status: "unavailable"): void;
}

/**
 * NativeShortcutPort 只暴露 lease context 与封闭 command stream；hook 不接触 Tauri event、
 * window DTO 或 adapter error，真实实现由外层 composition 注入。
 */
export interface NativeShortcutPort {
  updateContext(context: NativeShortcutContext): Promise<NativeShortcutContextSnapshot>;
  subscribe(subscription: NativeShortcutSubscription): Promise<() => void>;
}
