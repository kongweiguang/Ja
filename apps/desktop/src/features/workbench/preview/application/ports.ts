// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export interface PreviewViewport {
  x: number;
  y: number;
  width: number;
  height: number;
  visible: boolean;
}

type PreviewLoadStatus = "loading" | "finished" | "failed";

/** application 只依赖稳定的 Preview 快照形状，不依赖 Tauri adapter 的实现类型。 */
export interface PreviewSessionSnapshot {
  id: string;
  generation: number;
  status: "open" | "closed";
  load_status: PreviewLoadStatus;
  url: string;
  title: string;
  window: { label: string; url: string };
  dropped_events: number;
}

interface PreviewOpenResult {
  snapshot: PreviewSessionSnapshot;
  window: { label: string; url: string };
}

interface PreviewRecoveryReport {
  observed: number;
  recovered: number;
  failed: number;
  pending: number;
}

export type PreviewEvent = {
  session_id: string;
  generation: number;
  sequence: number;
  kind:
    | { type: "opened"; url: string }
    | { type: "navigation_committed"; source: "user" | "redirect"; url: string }
    | { type: "title_changed"; title: string }
    | { type: "load_failed"; message: string }
    | { type: "load_finished"; url: string }
    | { type: "closed" };
};

export type PreviewUnsubscribe = () => void | Promise<void>;

/**
 * native port 收口一个 Preview session 的完整生命周期；application 依赖此端口，
 * 因而无需知道 invoke、event 名称或 child WebView 句柄。
 */
export interface NativePreviewPort {
  recoverPending(): Promise<PreviewRecoveryReport>;
  open(url: string, viewport: PreviewViewport): Promise<PreviewOpenResult>;
  navigate(
    sessionId: string,
    generation: number,
    url: string,
    source: "user" | "redirect",
  ): Promise<PreviewSessionSnapshot>;
  layout(sessionId: string, viewport: PreviewViewport): Promise<PreviewSessionSnapshot>;
  close(sessionId: string): Promise<PreviewSessionSnapshot>;
  events(sessionId: string, maxEvents: number): Promise<PreviewEvent[]>;
  state(sessionId: string): Promise<PreviewSessionSnapshot>;
  subscribe(listener: (event: PreviewEvent) => void): Promise<PreviewUnsubscribe>;
}

/** Preview port 只表达用户意图与 DOM 几何，不暴露 Tauri command 或 WebView 句柄。 */
export interface PreviewPort {
  navigate?: (url: string) => void;
  reload?: () => void;
  retryRecovery?: () => void;
  changeViewport?: (viewport: PreviewViewport) => void;
}
