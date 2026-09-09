// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { ReviewSourceNavigation } from "@/app/composition/ReviewSourceNavigation";
import type {
  ReviewActions,
  ReviewLayerFilter,
  ReviewSource,
  ReviewViewModel,
  TurnReviewPort,
  TurnReviewTarget,
} from "@/features/workbench/review";
import { ReviewPanelView, TurnReviewPanelView } from "@/features/workbench/review";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";

type ReviewFile = NonNullable<ReviewViewModel["state"]["snapshot"]>["files"][number];
type ReviewFileDiff = NonNullable<ReviewViewModel["state"]["diff"]>;

// README 只选择小型示例；默认入口继续保留完整回归语料与既有测试断言。
const README_DEMO = new URLSearchParams(window.location.search).get("demo") === "readme";
const FILES: readonly ReviewFile[] = README_DEMO
  ? [
      reviewFile("home-page", "unstaged", "src/pages/Home.tsx", "modified", 8, 3),
      reviewFile("home-style", "unstaged", "src/styles/home.css", "modified", 5, 2),
    ]
  : [
      reviewFile("conflict", "unstaged", "src/runtime/merge.ts", "conflicted", 8, 5),
      reviewFile("partial-staged", "staged", "src/editor/partially-staged.ts", "modified", 4, 1),
      reviewFile(
        "partial-unstaged",
        "unstaged",
        "src/editor/partially-staged.ts",
        "modified",
        3,
        2,
      ),
      reviewFile(
        "settings",
        "unstaged",
        "src/features/settings/panels/AppearancePanel.tsx",
        "modified",
        12,
        6,
      ),
      reviewFile(
        "new-test",
        "untracked",
        "apps/desktop/tests/review/created.ts",
        "untracked",
        18,
        0,
      ),
      reviewFile(
        "rename",
        "staged",
        "crates/ja-runtime/src/review/git_snapshot.rs",
        "renamed",
        5,
        2,
        "crates/ja-runtime/src/review/snapshot.rs",
      ),
      reviewFile("deleted", "staged", "docs/legacy-review.md", "deleted", 0, 27),
      reviewFile(
        "binary",
        "untracked",
        "assets/review-preview.png",
        "untracked",
        null,
        null,
        null,
        true,
      ),
    ];

const SOURCE_OPTIONS: ReviewSource[] = [
  { kind: "uncommitted" },
  { kind: "unstaged" },
  { kind: "staged" },
  { kind: "branch", refId: "main" },
  { kind: "commit", commitId: "c0ffee1234567890" },
];

const TURN_TARGET: TurnReviewTarget = {
  kind: "frozen_turn",
  workspaceId: "ws_browser_fixture",
  threadId: "thread_browser_fixture",
  turnId: "turn_browser_fixture",
  threadRevision: 7,
  completedAt: "2026-09-07T00:00:00Z",
  state: "complete",
  incompleteReasons: [],
  files: [
    {
      path: "src/app/composition/ReviewSourceNavigation.tsx",
      status: "modified",
      additions: 1,
      deletions: 1,
      binary: false,
      truncated: false,
    },
    {
      path: "src/features/workbench/review/ui/ReviewShell.tsx",
      status: "modified",
      additions: 1,
      deletions: 1,
      binary: false,
      truncated: false,
    },
  ],
  stats: { files: 2, additions: 2, deletions: 2, binaryFiles: 0, truncated: false },
  artifactId: "artifact_browser_fixture",
};

const TURN_ARTIFACT = [
  "diff --git a/src/app/composition/ReviewSourceNavigation.tsx b/src/app/composition/ReviewSourceNavigation.tsx",
  "--- a/src/app/composition/ReviewSourceNavigation.tsx",
  "+++ b/src/app/composition/ReviewSourceNavigation.tsx",
  "@@ -1,2 +1,2 @@",
  "-const label = 'Review';",
  "+const label = '最后一轮';",
  " export { label };",
  "diff --git a/src/features/workbench/review/ui/ReviewShell.tsx b/src/features/workbench/review/ui/ReviewShell.tsx",
  "--- a/src/features/workbench/review/ui/ReviewShell.tsx",
  "+++ b/src/features/workbench/review/ui/ReviewShell.tsx",
  "@@ -1,2 +1,2 @@",
  "-const treeWidth = 280;",
  "+const treeWidth = 240;",
  " export { treeWidth };",
].join("\n");

const TURN_PORT: TurnReviewPort = {
  readFrozen: async (_target, file) => {
    const content = TURN_ARTIFACT;
    const bytes = new TextEncoder().encode(content);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    return {
      artifactId: "artifact_browser_fixture",
      filePath: file.path,
      byteLength: bytes.byteLength,
      sha256: [...digest].map((value) => value.toString(16).padStart(2, "0")).join(""),
      content,
    };
  },
};

/** Fixture 文件由稳定 id 表达暂存层身份，同路径两层不会被路径去重。 */
function reviewFile(
  fileId: string,
  layer: ReviewFile["layer"],
  path: string,
  status: ReviewFile["status"],
  additions: number | null,
  deletions: number | null,
  oldPath: string | null = null,
  binary = false,
): ReviewFile {
  return {
    fileId,
    layer,
    path,
    oldPath,
    status,
    additions,
    deletions,
    binary,
    truncated: false,
    hunks: binary
      ? []
      : [
          {
            hunkId: `hunk-${fileId}`,
            header: "@@ -1,14 +1,14 @@",
            oldStart: 1,
            oldLines: 14,
            newStart: 1,
            newLines: 14,
          },
        ],
  };
}

/** 与真实 Git DTO 一样只提供有界差异，不用合成全文掩盖双栏能力断链。 */
function diffFor(file: ReviewFile, source: ReviewSource): ReviewFileDiff {
  if (file.binary) {
    return {
      workspaceId: "ws_browser_fixture",
      source,
      revision: "browser-fixture-revision",
      fileId: file.fileId,
      layer: file.layer,
      path: file.path,
      oldPath: file.oldPath,
      status: file.status,
      binary: true,
      truncated: false,
      original: null,
      modified: null,
      unified: null,
      hunks: [],
      lines: [],
    };
  }
  const lines = Array.from({ length: 15 }, (_, index) => {
    const line = index + 1;
    if (line === 5)
      return {
        kind: "deletion" as const,
        oldLine: line,
        newLine: null,
        text: README_DEMO ? "  const ctaLabel = '开始使用';" : "const density = 'comfortable';",
      };
    if (line === 6)
      return {
        kind: "addition" as const,
        oldLine: null,
        newLine: 5,
        text: README_DEMO ? "  const ctaLabel = '查看首页';" : "const density = 'compact';",
      };
    return {
      kind: "context" as const,
      oldLine: line > 6 ? line - 1 : line,
      newLine: line > 6 ? line - 1 : line,
      text: !README_DEMO
        ? `context line ${line}`
        : line === 2
          ? "  <h1>让团队更快完成工作</h1>"
          : line === 3
            ? "  <p>把想法变成清晰的下一步。</p>"
            : line === 4
              ? "  <HeroAction label={ctaLabel} />"
                : `  <section data-block="${line}" />`,
    };
  });
  return {
    workspaceId: "ws_browser_fixture",
    source,
    revision: "browser-fixture-revision",
    fileId: file.fileId,
    layer: file.layer,
    path: file.path,
    oldPath: file.oldPath,
    status: file.status,
    binary: false,
    truncated: false,
    original: null,
    modified: null,
    unified: `--- a/${file.path}\n+++ b/${file.path}\n@@ -1,14 +1,14 @@`,
    hunks: file.hunks,
    lines,
  };
}

/** 将 URL 中的主题请求映射到生产 token 合同；system 模式保留媒体查询所有权。 */
function applyRequestedTheme(): void {
  const requested = new URLSearchParams(globalThis.location.search).get("theme");
  const root = globalThis.document.documentElement;
  root.dataset["palette"] = "xcode";
  root.dataset["themeMode"] = requested ?? "system";
  if (requested === "light" || requested === "dark") root.dataset["theme"] = requested;
  else delete root.dataset["theme"];
}

/** 浏览器 fixture 挂载生产 Review 视图，只用内存状态驱动视觉与可见交互。 */
export function ReviewRedesignBrowserFixture() {
  const [mode, setMode] = useState<"git" | "turn">(() =>
    new URLSearchParams(globalThis.location.search).get("mode") === "turn" ? "turn" : "git",
  );
  const [source, setSource] = useState<ReviewSource>({ kind: "uncommitted" });
  const [layerFilter, setLayerFilter] = useState<ReviewLayerFilter>("all");
  const [selectedFileId, setSelectedFileId] = useState(
    README_DEMO ? "home-page" : "partial-unstaged",
  );
  const [query, setQuery] = useState("");
  const [viewMode, setViewMode] = useState<"unified" | "split">("unified");
  const [notice, setNotice] = useState<string>();

  const visibleFiles = useMemo(
    () => (layerFilter === "all" ? [...FILES] : FILES.filter((file) => file.layer === layerFilter)),
    [layerFilter],
  );
  const selectedFile =
    visibleFiles.find((file) => file.fileId === selectedFileId) ?? visibleFiles[0];
  const stressStats = new URLSearchParams(globalThis.location.search).get("stats") === "stress";
  const stats = {
    files: stressStats ? 999_999 : new Set(visibleFiles.map((file) => file.path)).size,
    additions: stressStats
      ? 128_456
      : visibleFiles.reduce((sum, file) => sum + (file.additions ?? 0), 0),
    deletions: stressStats
      ? 98_765
      : visibleFiles.reduce((sum, file) => sum + (file.deletions ?? 0), 0),
    binaryFiles: visibleFiles.filter((file) => file.binary).length,
    truncated: false,
  };
  const viewModel: ReviewViewModel = {
    state: {
      loading: false,
      catalogLoading: false,
      diffLoading: false,
      source,
      catalog: {
        workspaceId: "ws_browser_fixture",
        repositoryName: "ja",
        currentBranch: "main",
        headCommitId: "c0ffee1234567890",
        baseRefs: [{ refId: "main", label: "main", kind: "base" }],
        commits: [
          {
            commitId: "c0ffee1234567890",
            subject: "Review redesign fixture",
            author: "Fixture",
            authoredAt: "2026-09-07T00:00:00Z",
          },
        ],
      },
      snapshot: {
        workspaceId: "ws_browser_fixture",
        source,
        revision: "browser-fixture-revision",
        files: visibleFiles,
        stats,
        capabilities: { stage: true, unstage: true, revert: true },
      },
      diff: selectedFile === undefined ? undefined : diffFor(selectedFile, source),
      selectedFileId: selectedFile?.fileId,
      filter: "all",
      layerFilter,
      query,
      viewMode,
      drawerOpen: false,
      error: undefined,
      diffError: undefined,
      notice,
      pendingOperationIds: new Set<string>(),
    },
    sourceOptions: SOURCE_OPTIONS,
    visibleFiles,
    selectedFile,
  };

  const actions: ReviewActions = {
    refresh: () => setNotice("已刷新浏览器夹具。"),
    setSource,
    selectFile: setSelectedFileId,
    setFilter: () => undefined,
    setLayerFilter: (next) => {
      setLayerFilter(next);
      const nextFiles = next === "all" ? FILES : FILES.filter((file) => file.layer === next);
      setSelectedFileId((current) =>
        nextFiles.some((file) => file.fileId === current) ? current : (nextFiles[0]?.fileId ?? ""),
      );
    },
    setQuery,
    setViewMode,
    setDrawerOpen: () => undefined,
    applyAction: async () => setNotice("浏览器夹具不会执行 Git 写入。"),
    cancelOperation: async () => undefined,
    clearNotice: () => setNotice(undefined),
  };
  const currentLabel =
    source.kind === "uncommitted"
      ? layerFilter === "all"
        ? "未提交（全部）"
        : `未提交（${layerFilter === "staged" ? "已暂存" : layerFilter === "unstaged" ? "未暂存" : "未跟踪"}）`
      : source.kind === "branch"
        ? "比较 main"
        : source.kind === "commit"
          ? "比较 c0ffee12"
          : source.kind === "staged"
            ? "已暂存"
            : "未暂存";
  const sourceNavigation = (
    <ReviewSourceNavigation
      currentLabel={mode === "turn" ? "最后一轮" : currentLabel}
      turnSelected={mode === "turn"}
      latestTurnAvailable
      viewModel={viewModel}
      actions={actions}
      onShowRetainedTurn={() => setMode("turn")}
      onShowLatestTurn={() => setMode("turn")}
      onShowWorkspaceReview={() => setMode("git")}
    />
  );

  return (
    <main
      aria-label="Review 浏览器视觉夹具"
      data-fixture-runtime="browser_fixture"
      style={{ width: "100vw", height: "100vh", minWidth: 0, minHeight: 0, overflow: "hidden" }}
    >
      {mode === "turn" ? (
        <TurnReviewPanelView
          active
          target={TURN_TARGET}
          port={TURN_PORT}
          onShowWorkspaceReview={() => setMode("git")}
          onCopyText={async () => undefined}
          sourceNavigation={sourceNavigation}
          scopeLabel="最后一轮"
        />
      ) : (
        <ReviewPanelView
          viewModel={viewModel}
          actions={actions}
          onCopyText={async () => undefined}
          sourceNavigation={sourceNavigation}
        />
      )}
    </main>
  );
}

applyRequestedTheme();
createRoot(document.getElementById("root")!).render(<ReviewRedesignBrowserFixture />);
