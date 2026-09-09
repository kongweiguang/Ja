// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { StrictMode, useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ReviewPanelView,
  useReviewController,
  type ReviewPort,
  type ReviewViewModel,
} from "@/features/workbench/review";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";

type ReviewFile = NonNullable<ReviewViewModel["state"]["snapshot"]>["files"][number];
type ReviewSnapshot = NonNullable<ReviewViewModel["state"]["snapshot"]>;
type ReviewFileDiff = NonNullable<ReviewViewModel["state"]["diff"]>;

interface InvocationSample {
  readonly command: "catalog" | "snapshot" | "fileDiff";
  readonly fileId?: string;
  readonly durationMs: number;
}

interface FixtureTelemetry {
  readonly startedAt: number;
  readonly samples: InvocationSample[];
  readonly counts: {
    catalog: number;
    snapshot: number;
    fileDiff: number;
    subscribe: number;
    unsubscribe: number;
  };
}

interface ReviewPerformanceFixtureApi {
  readonly telemetry: FixtureTelemetry;
  setActive(active: boolean): void;
  switchWorkspace(): void;
}

declare global {
  // 浏览器 runner 只读取聚合计数与计时，不暴露 fixture 路径或响应正文。
  var __JA_REVIEW_PERFORMANCE_FIXTURE__: ReviewPerformanceFixtureApi | undefined;
}

const parameters = new URLSearchParams(globalThis.location.search);
const fileCount = Math.min(10_000, Math.max(4, Number(parameters.get("files") ?? 2_500)));
const adapterDelayMs = Math.min(100, Math.max(0, Number(parameters.get("delayMs") ?? 5)));
const telemetry: FixtureTelemetry = {
  startedAt: performance.now(),
  samples: [],
  counts: { catalog: 0, snapshot: 0, fileDiff: 0, subscribe: 0, unsubscribe: 0 },
};

/** 让 adapter 延迟可控且有界，使报告能区分 React 开销与已知 I/O 等待。 */
async function delay(durationMs: number): Promise<void> {
  if (durationMs <= 0) return;
  await new Promise<void>((resolvePromise) => globalThis.setTimeout(resolvePromise, durationMs));
}

/** 生成稳定分组路径；规模只压力测试生产树投影和虚拟列表，不伪装成 native Git 数据。 */
function buildFiles(count: number): ReviewFile[] {
  return Array.from({ length: count }, (_, index) => {
    const fileId = `file-${String(index).padStart(5, "0")}`;
    const group = String(index % 50).padStart(2, "0");
    const feature = String(Math.floor(index / 50) % 20).padStart(2, "0");
    const path =
      index < 4 ? `src/00-hot/${fileId}.ts` : `src/group-${group}/feature-${feature}/${fileId}.ts`;
    return {
      fileId,
      layer: index % 7 === 0 ? "staged" : index % 11 === 0 ? "untracked" : "unstaged",
      path,
      oldPath: null,
      status: index % 11 === 0 ? "untracked" : "modified",
      additions: 2,
      deletions: index % 11 === 0 ? 0 : 1,
      binary: false,
      truncated: false,
      hunks: [
        {
          hunkId: `hunk-${fileId}`,
          header: "@@ -1,2 +1,2 @@",
          oldStart: 1,
          oldLines: 2,
          newStart: 1,
          newLines: 2,
        },
      ],
    } satisfies ReviewFile;
  });
}

/** 单文件响应保持很小，避免把正文解析成本混入文件树/controller 调度指标。 */
function buildDiff(file: ReviewFile, snapshot: ReviewSnapshot): ReviewFileDiff {
  return {
    workspaceId: snapshot.workspaceId,
    source: snapshot.source,
    revision: snapshot.revision,
    fileId: file.fileId,
    layer: file.layer,
    path: file.path,
    oldPath: null,
    status: file.status,
    binary: false,
    truncated: false,
    original: "export const value = 1;\n",
    modified: "export const value = 2;\n",
    unified: `--- a/${file.path}\n+++ b/${file.path}\n@@ -1 +1 @@\n-export const value = 1;\n+export const value = 2;`,
    hunks: file.hunks,
    lines: [
      { kind: "deletion", oldLine: 1, newLine: null, text: "export const value = 1;" },
      { kind: "addition", oldLine: null, newLine: 1, text: "export const value = 2;" },
    ],
  };
}

/** 记录单次 adapter 调用耗时，正文和 workspace identity 均不进入全局报告。 */
async function measured<T>(
  command: InvocationSample["command"],
  operation: () => Promise<T>,
  fileId?: string,
): Promise<T> {
  telemetry.counts[command] += 1;
  const startedAt = performance.now();
  try {
    return await operation();
  } finally {
    telemetry.samples.push({ command, fileId, durationMs: performance.now() - startedAt });
  }
}

/**
 * 只替换 production controller 的 ReviewPort：调用次数、竞态、新鲜度与 UI 更新仍走真实 Hook，
 * fixture 不声称验证 Tauri IPC 或原生 Git。
 */
function createMeasuredAdapter(files: ReviewFile[]): ReviewPort {
  let activeSnapshot: ReviewSnapshot | undefined;
  return {
    catalog: async ({ workspaceId }) =>
      measured("catalog", async () => {
        await delay(adapterDelayMs);
        return {
          workspaceId,
          repositoryName: "review-performance-fixture",
          currentBranch: "main",
          headCommitId: "fixture-head",
          baseRefs: [],
          commits: [],
        };
      }),
    snapshot: async ({ workspaceId, source }) =>
      measured("snapshot", async () => {
        await delay(adapterDelayMs);
        activeSnapshot = {
          workspaceId,
          source,
          revision: "fixture-revision",
          files,
          stats: {
            files: files.length,
            additions: files.length * 2,
            deletions: files.filter((file) => file.status !== "untracked").length,
            binaryFiles: 0,
            truncated: false,
          },
          capabilities: { stage: true, unstage: true, revert: true },
        };
        return activeSnapshot;
      }),
    fileDiff: async ({ fileId }) =>
      measured(
        "fileDiff",
        async () => {
          await delay(fileId === "file-00002" ? adapterDelayMs + 60 : adapterDelayMs);
          const snapshot = activeSnapshot;
          const file = files.find((candidate) => candidate.fileId === fileId);
          if (snapshot === undefined || file === undefined)
            throw { code: "RUNTIME_PROTOCOL_ERROR" };
          return buildDiff(file, snapshot);
        },
        fileId,
      ),
    apply: async () => {
      throw { code: "REVIEW_READ_ONLY" };
    },
    cancel: async ({ workspaceId, operationId }) => ({
      workspaceId,
      operationId,
      cancelled: false,
    }),
    subscribeInvalidated: async () => {
      telemetry.counts.subscribe += 1;
      return () => {
        telemetry.counts.unsubscribe += 1;
      };
    },
  };
}

/** 挂载真实 Review controller/view，并只向 runner 暴露隐藏和 workspace 切换两项测试动作。 */
export function ReviewPerformanceBrowserFixture() {
  const [active, setActive] = useState(true);
  const [workspaceIndex, setWorkspaceIndex] = useState(1);
  const files = useMemo(() => buildFiles(fileCount), []);
  const adapter = useMemo(() => createMeasuredAdapter(files), [files]);
  const controller = useReviewController({
    workspaceId: `ws_review_perf_${workspaceIndex}`,
    generation: workspaceIndex,
    selectionScopeId: "review-performance-browser",
    snapshotEnabled: active,
    adapter,
  });

  /** runner 隐藏 Review 后只改变生产 Hook 的激活输入，不卸载组件或替换状态。 */
  const updateActive = useCallback((next: boolean) => setActive(next), []);

  /** 隐藏态切换 workspace/generation，用于证明没有延迟或隐式 snapshot。 */
  const switchWorkspace = useCallback(() => setWorkspaceIndex((current) => current + 1), []);

  /** effect 与组件生命周期绑定，StrictMode 重放也不会留下指向已卸载 state 的控制函数。 */
  useEffect(() => {
    const api = { telemetry, setActive: updateActive, switchWorkspace };
    globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__ = api;
    return () => {
      if (globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__ === api)
        globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__ = undefined;
    };
  }, [switchWorkspace, updateActive]);

  return (
    <main
      aria-label="Review 性能浏览器夹具"
      data-fixture-runtime="production_controller_browser_fixture"
      data-fixture-active={active ? "true" : "false"}
      style={{ width: "100vw", height: "100vh", minWidth: 0, minHeight: 0, overflow: "hidden" }}
    >
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ReviewPerformanceBrowserFixture />
  </StrictMode>,
);
