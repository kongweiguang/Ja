// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { StrictMode, useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  TurnReviewPanelView,
  type TurnReviewPort,
  type TurnReviewTarget,
} from "@/features/workbench/review";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";

interface ReadSample {
  readonly path: string;
  readonly startedAt: number;
  completedAt?: number;
  abortedAt?: number;
}

interface WorkerCounts {
  created: number;
  terminated: number;
  highlightCreated: number;
  highlightTerminated: number;
  highlightRequests: number;
  highlightReplies: number;
}

interface SyntaxSample {
  readonly path: string;
  readonly state: string;
  readonly at: number;
}

interface SmoothReviewTelemetry {
  readonly startedAt: number;
  readonly reads: ReadSample[];
  readonly workers: WorkerCounts;
  readonly syntax: SyntaxSample[];
  readonly longTasks: number[];
  readonly longTaskSupported: boolean;
  fileCount: number;
  activeReads: number;
  maxActiveReads: number;
}

interface SmoothReviewFixtureApi {
  readonly telemetry: SmoothReviewTelemetry;
  setActive(active: boolean): void;
  requestPath(path: string): void;
}

type FrozenTurnReviewTarget = Extract<TurnReviewTarget, { kind: "frozen_turn" }>;
type TurnReviewFile = FrozenTurnReviewTarget["files"][number];

declare global {
  // Runner 只读取路径身份、计数与耗时；fake artifact 正文不会越过 fixture 边界。
  var __JA_SMOOTH_REVIEW_FIXTURE__: SmoothReviewFixtureApi | undefined;
}

const parameters = new URLSearchParams(globalThis.location.search);
const fileCount = Math.min(500, Math.max(100, Number(parameters.get("files") ?? 100)));
const highlightDelayMs = Math.min(
  1_000,
  Math.max(0, Number(parameters.get("highlightDelayMs") ?? 160)),
);

const FIXTURE_PATHS = {
  tail: "src/zzz-tail/ReviewTail.ts",
  a: "src/00-hot/A-large.ts",
  b: "src/00-hot/B-large.ts",
  c: "src/00-hot/C-large.ts",
  hidden: "src/00-hot/D-hidden.ts",
  hiddenNext: "src/00-hot/E-hidden-next.ts",
} as const;

const telemetry: SmoothReviewTelemetry = {
  startedAt: performance.now(),
  reads: [],
  workers: {
    created: 0,
    terminated: 0,
    highlightCreated: 0,
    highlightTerminated: 0,
    highlightRequests: 0,
    highlightReplies: 0,
  },
  syntax: [],
  longTasks: [],
  longTaskSupported:
    typeof PerformanceObserver !== "undefined" &&
    PerformanceObserver.supportedEntryTypes.includes("longtask"),
  fileCount,
  activeReads: 0,
  maxActiveReads: 0,
};

/** 主题只使用生产 token 的 data contract，截图不会另写一套 fixture 颜色。 */
function applyRequestedTheme(): void {
  const requested = parameters.get("theme") ?? "system";
  const root = globalThis.document.documentElement;
  root.dataset["palette"] = "xcode";
  root.dataset["themeMode"] = requested;
  if (requested === "light" || requested === "dark") root.dataset["theme"] = requested;
  else delete root.dataset["theme"];
}

/** 包装真实 Worker 只做计数和确定性延迟，所有消息仍交给生产 module Worker 处理。 */
function installWorkerTelemetry(): void {
  const NativeWorker = globalThis.Worker;
  if (NativeWorker === undefined) return;
  const wrapped = new Proxy(NativeWorker, {
    construct(target, argumentsList) {
      const worker = Reflect.construct(target, argumentsList) as Worker;
      const url = String(argumentsList[0] ?? "");
      const highlight = url.includes("reviewSyntaxHighlightWorker");
      telemetry.workers.created += 1;
      if (highlight) telemetry.workers.highlightCreated += 1;
      const nativePostMessage = worker.postMessage.bind(worker);
      const nativeTerminate = worker.terminate.bind(worker);
      let terminated = false;
      worker.postMessage = ((message: unknown, transferOrOptions?: unknown) => {
        if (highlight) telemetry.workers.highlightRequests += 1;
        const forward = (): void => {
          if (terminated) return;
          Reflect.apply(
            nativePostMessage,
            worker,
            transferOrOptions === undefined ? [message] : [message, transferOrOptions],
          );
        };
        if (highlight && highlightDelayMs > 0) globalThis.setTimeout(forward, highlightDelayMs);
        else forward();
      }) as Worker["postMessage"];
      worker.addEventListener("message", () => {
        if (highlight) telemetry.workers.highlightReplies += 1;
      });
      worker.terminate = () => {
        if (!terminated) {
          terminated = true;
          telemetry.workers.terminated += 1;
          if (highlight) telemetry.workers.highlightTerminated += 1;
        }
        nativeTerminate();
      };
      return worker;
    },
  });
  Object.defineProperty(globalThis, "Worker", {
    configurable: true,
    writable: true,
    value: wrapped,
  });
}

/** MutationObserver 记录用户实际看见的 plain/loading/ready 顺序，不依赖生产测试开关。 */
function observeSyntaxStates(): () => void {
  const last = new Map<string, string>();
  const capture = (): void => {
    for (const root of document.querySelectorAll<HTMLElement>("[data-review-syntax]")) {
      const path = root.dataset["reviewDiffPath"];
      const state = root.dataset["reviewSyntax"];
      if (path === undefined || state === undefined || last.get(path) === state) continue;
      last.set(path, state);
      telemetry.syntax.push({ path, state, at: performance.now() });
    }
  };
  const observer = new MutationObserver(capture);
  observer.observe(document.documentElement, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ["data-review-syntax"],
  });
  capture();
  return () => observer.disconnect();
}

/** Long Task API 不可用时保留空样本，runner 会明确报告该浏览器能力边界。 */
function observeLongTasks(): () => void {
  if (typeof PerformanceObserver === "undefined") return () => undefined;
  try {
    const observer = new PerformanceObserver((entries) => {
      telemetry.longTasks.push(...entries.getEntries().map((entry) => entry.duration));
    });
    observer.observe({ type: "longtask", buffered: true });
    return () => observer.disconnect();
  } catch {
    return () => undefined;
  }
}

/**
 * 构造精确字节数的合法 Unified Diff；填充平均分摊到上下文行，避免单个超长行把渲染
 * 压力误测成文件读取压力。logicalLines 表示新旧两侧各自的逻辑行数。
 */
function buildSizedUnified(path: string, targetBytes: number, logicalLines: number): string {
  const lines = [
    `diff --git a/${path} b/${path}`,
    `--- a/${path}`,
    `+++ b/${path}`,
    `@@ -1,${logicalLines} +1,${logicalLines} @@`,
    '-export const changed = "before";',
    '+export const changed = "after";',
    ...Array.from({ length: logicalLines - 1 }, (_, index) => ` line_${index}`),
  ];
  const encoder = new TextEncoder();
  const baseline = encoder.encode(lines.join("\n")).byteLength;
  const deficit = targetBytes - baseline;
  if (deficit < 0) throw new Error("sized review fixture baseline exceeds target bytes");
  const contextStart = 6;
  const contextLines = lines.length - contextStart;
  const fillPerLine = Math.floor(deficit / contextLines);
  const extraLines = deficit % contextLines;
  for (let offset = 0; offset < contextLines; offset += 1) {
    const index = contextStart + offset;
    lines[index] = `${lines[index]}${"x".repeat(fillPerLine + (offset < extraLines ? 1 : 0))}`;
  }
  const unified = lines.join("\n");
  if (encoder.encode(unified).byteLength !== targetBytes)
    throw new Error("sized review fixture did not reach target bytes");
  return unified;
}

/** 小文件用于证明 100 文件摘要不会导致首次点击读取整份聚合正文。 */
function buildSmallUnified(path: string): string {
  return [
    `diff --git a/${path} b/${path}`,
    `--- a/${path}`,
    `+++ b/${path}`,
    "@@ -1,2 +1,2 @@",
    '-export const reviewMode = "blocking";',
    '+export const reviewMode = "smooth";',
    " export const ready = true;",
  ].join("\n");
}

/** 文件摘要刻意包含 100 项，但正文只按所选文件构造并返回。 */
function buildFiles(count: number): readonly TurnReviewFile[] {
  const fixed = [
    FIXTURE_PATHS.a,
    FIXTURE_PATHS.b,
    FIXTURE_PATHS.c,
    FIXTURE_PATHS.hidden,
    FIXTURE_PATHS.hiddenNext,
  ];
  const generated = Array.from(
    { length: Math.max(0, count - fixed.length - 1) },
    (_, index) =>
      `src/generated/group-${String(index % 12).padStart(2, "0")}/file-${String(index).padStart(3, "0")}.ts`,
  );
  return [...fixed, ...generated, FIXTURE_PATHS.tail].map((path) => ({
    path,
    status: "modified" as const,
    additions: 1,
    deletions: 1,
    binary: false,
    truncated: false,
  }));
}

const SIZED_DIFFS = new Map<string, string>([
  [FIXTURE_PATHS.a, buildSizedUnified(FIXTURE_PATHS.a, 65_536, 512)],
  [FIXTURE_PATHS.b, buildSizedUnified(FIXTURE_PATHS.b, 1_048_576, 10_000)],
  [FIXTURE_PATHS.c, buildSizedUnified(FIXTURE_PATHS.c, 65_536, 512)],
]);
let deferFirstAForRace = true;

/** 仅首次 A 读取故意延迟且忽略取消，用于证明迟到正文无法覆盖 C，不污染性能样本。 */
async function delayedRead(path: string, signal?: AbortSignal): Promise<string> {
  const sample: ReadSample = { path, startedAt: performance.now() };
  telemetry.reads.push(sample);
  telemetry.activeReads += 1;
  telemetry.maxActiveReads = Math.max(telemetry.maxActiveReads, telemetry.activeReads);
  const delayedRace = path === FIXTURE_PATHS.a && deferFirstAForRace;
  if (delayedRace) deferFirstAForRace = false;
  const duration =
    delayedRace
      ? 220
      : path === FIXTURE_PATHS.hidden
        ? 600
        : path === FIXTURE_PATHS.c
          ? 20
          : 8;
  const honorAbort = !delayedRace;
  try {
    await new Promise<void>((resolvePromise, rejectPromise) => {
      const timer = globalThis.setTimeout(resolvePromise, duration);
      if (!honorAbort || signal === undefined) return;
      const abort = (): void => {
        globalThis.clearTimeout(timer);
        sample.abortedAt = performance.now();
        rejectPromise(new DOMException("aborted", "AbortError"));
      };
      if (signal.aborted) abort();
      else signal.addEventListener("abort", abort, { once: true });
    });
    sample.completedAt = performance.now();
    return SIZED_DIFFS.get(path) ?? buildSmallUnified(path);
  } finally {
    telemetry.activeReads -= 1;
  }
}

/** Fake port 只替换 IO 边界；必须收到目标文件，缺失时失败而不回退聚合 artifact。 */
function createPort(): TurnReviewPort {
  return {
    readFrozen: async (_target, ...argumentsList: readonly unknown[]) => {
      const file = argumentsList[0] as TurnReviewFile | undefined;
      const signal = argumentsList[1] as AbortSignal | undefined;
      if (file === undefined || typeof file.path !== "string")
        throw new Error("frozen review must request one file");
      const content = await delayedRead(file.path, signal);
      const bytes = new TextEncoder().encode(content);
      const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
      return {
        artifactId: "artifact_smooth_review_fixture",
        filePath: file.path,
        byteLength: bytes.byteLength,
        sha256: [...digest].map((value) => value.toString(16).padStart(2, "0")).join(""),
        content,
      };
    },
  };
}

/** 挂载生产 Turn Review，并只暴露可见性与显式路径导航两个场景动作。 */
export function SmoothReviewBrowserFixture() {
  const files = useMemo(() => buildFiles(fileCount), []);
  const port = useMemo(() => createPort(), []);
  const [active, setActive] = useState(true);
  const [requested, setRequested] = useState<{ path: string; revision: number }>({
    path: FIXTURE_PATHS.tail,
    revision: 1,
  });
  const target = useMemo<FrozenTurnReviewTarget>(
    () => ({
      kind: "frozen_turn",
      workspaceId: "ws_smooth_review_fixture",
      threadId: "thread_smooth_review_fixture",
      turnId: "turn_smooth_review_fixture",
      threadRevision: 1,
      completedAt: "2026-09-08T00:00:00Z",
      state: "complete",
      incompleteReasons: [],
      files,
      stats: {
        files: files.length,
        additions: files.length,
        deletions: files.length,
        binaryFiles: 0,
        truncated: false,
      },
      artifactId: "artifact_smooth_review_fixture",
    }),
    [files],
  );
  const updateActive = useCallback((next: boolean) => setActive(next), []);
  const requestPath = useCallback(
    (path: string) => setRequested((current) => ({ path, revision: current.revision + 1 })),
    [],
  );

  /** API 与组件生命周期成对绑定，reload/StrictMode 后不会残留旧 setter。 */
  useEffect(() => {
    const api = { telemetry, setActive: updateActive, requestPath };
    globalThis.__JA_SMOOTH_REVIEW_FIXTURE__ = api;
    return () => {
      if (globalThis.__JA_SMOOTH_REVIEW_FIXTURE__ === api)
        globalThis.__JA_SMOOTH_REVIEW_FIXTURE__ = undefined;
    };
  }, [requestPath, updateActive]);

  return (
    <main
      aria-label="丝滑 Review 浏览器夹具"
      data-fixture-runtime="production_turn_review_browser_fixture"
      data-fixture-active={active ? "true" : "false"}
      style={{ width: "100vw", height: "100vh", minWidth: 0, minHeight: 0, overflow: "hidden" }}
    >
      <TurnReviewPanelView
        active={active}
        target={target}
        port={port}
        requestedPath={requested.path}
        requestedPathRevision={requested.revision}
        onShowWorkspaceReview={() => undefined}
        onCopyText={async () => undefined}
        scopeLabel="最后一轮"
      />
    </main>
  );
}

applyRequestedTheme();
installWorkerTelemetry();
const stopSyntaxObserver = observeSyntaxStates();
const stopLongTaskObserver = observeLongTasks();
globalThis.addEventListener(
  "pagehide",
  () => {
    stopSyntaxObserver();
    stopLongTaskObserver();
  },
  { once: true },
);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <SmoothReviewBrowserFixture />
  </StrictMode>,
);
