// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { createRoot } from "react-dom/client";
import { ReviewSourceNavigation } from "@/app/composition/ReviewSourceNavigation";
import { createReviewAdapter } from "@/api/tauri/review";
import {
  ChatTimeline,
  TimelineDisclosureCache,
  TurnChangesCard,
  type TimelineItemAdapter,
  type TimelineTurn,
  type TurnChangeSet,
} from "@/features/conversation";
import {
  TurnReviewPanelView,
  useReviewController,
  type TurnReviewPort,
  type TurnReviewTarget,
} from "@/features/workbench/review";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";

const THREAD_ID = "thread_turn_changes_fixture";
const CARD_TURN_ID = "turn_card_fixture";
const TIMELINE_TARGET_TURN_ID = "turn_timeline_target";
const TIMELINE_TARGET_INDEX = 14;
const TIMELINE_ROW_COUNT = 32;
const PALETTES = ["ja", "xcode", "jetbrains", "obsidian", "claude"] as const;
type Palette = (typeof PALETTES)[number];
type FixtureState = "complete" | "partial" | "truncated";

interface FixtureScenario {
  readonly scene: "card" | "timeline";
  readonly width: number;
  readonly fileCount: number;
  readonly state: FixtureState;
  readonly longPath: boolean;
  readonly palette: Palette;
  readonly theme: "light" | "dark";
  readonly zoom: number;
}

interface ReviewRequest {
  readonly target: TurnReviewTarget;
  readonly path?: string;
  readonly revision: number;
}

interface ReadObservation {
  readonly threadId: string;
  readonly turnId: string;
  readonly artifactId: string;
  readonly path: string;
}

declare global {
  interface Window {
    __JA_TURN_CHANGES__?: {
      readCount(): number;
      reads(): ReadObservation[];
      lastRequest(): { threadId: string; turnId: string; path?: string } | undefined;
    };
  }
}

/** 查询参数只驱动可视语料，不改变生产组件行为；非法输入归一到小型安全默认。 */
function readScenario(): FixtureScenario {
  const query = new URLSearchParams(globalThis.location.search);
  const number = (key: string, fallback: number, minimum: number, maximum: number): number => {
    const value = Number(query.get(key));
    return Number.isSafeInteger(value) && value >= minimum && value <= maximum ? value : fallback;
  };
  const palette = query.get("palette");
  const state = query.get("state");
  return {
    scene: query.get("scene") === "timeline" ? "timeline" : "card",
    width: number("width", 480, 320, 760),
    fileCount: number("files", 23, 1, 23),
    state: state === "partial" || state === "truncated" ? state : "complete",
    longPath: query.get("longPath") === "1",
    palette: PALETTES.includes(palette as Palette) ? (palette as Palette) : "ja",
    theme: query.get("theme") === "dark" ? "dark" : "light",
    zoom: number("zoom", 100, 100, 200),
  };
}

/** 状态摘要来自同一冻结 ChangeSet，文件名语料覆盖路径、状态和安全截断边界。 */
function createChangeSet(fileCount: number, state: FixtureState, longPath: boolean): TurnChangeSet {
  const files = Array.from({ length: fileCount }, (_, index) => {
    const number = String(index + 1).padStart(2, "0");
    const path =
      longPath && index === Math.min(5, fileCount - 1)
        ? "packages/desktop/src/features/conversation/ui/timeline/components/turn-changes/reviewed-file-with-a-deliberately-long-readable-name.tsx"
        : `src/features/conversation/turn-changes/reviewed-file-${number}.tsx`;
    const status =
      index === 1 ? ("added" as const) : index === 2 ? ("deleted" as const) : ("modified" as const);
    return {
      path,
      status,
      additions: index + 2,
      deletions: index + 1,
      binary: false,
      truncated: state === "truncated" && index === fileCount - 1,
    };
  });
  const incomplete = state !== "complete";
  return {
    state: state === "partial" ? "partial" : "complete",
    incompleteReasons: incomplete ? ["capture_failed"] : [],
    files,
    stats: {
      files: files.length,
      additions: files.reduce((total, file) => total + file.additions, 0),
      deletions: files.reduce((total, file) => total + file.deletions, 0),
      binaryFiles: 0,
      truncated: state === "truncated",
    },
    artifactId: "artifact_turn_changes_fixture",
  };
}

/** Review 目标只承接卡片所选 Turn 的冻结文件与摘要，禁止夹具回读真实工作树。 */
function createReviewTarget(turn: TimelineTurn, changeSet: TurnChangeSet): TurnReviewTarget {
  if (changeSet.artifactId === undefined) throw new Error("fixture requires a frozen artifact id");
  return {
    kind: "frozen_turn",
    workspaceId: "workspace_turn_changes_fixture",
    threadId: turn.threadId,
    turnId: turn.turnId,
    threadRevision: turn.threadRevision ?? 1,
    completedAt: turn.completedAt,
    state: changeSet.state,
    incompleteReasons: changeSet.incompleteReasons,
    files: changeSet.files.map((file) => ({ ...file })),
    stats: { ...changeSet.stats },
    artifactId: changeSet.artifactId,
  };
}

/** 十六个滚动行以前后自然正文建立真实虚拟列表，目标 Turn 位于可离开 viewport 的中段。 */
function createTimelineFixtures(changeSet: TurnChangeSet): {
  readonly items: readonly TimelineItemAdapter[];
  readonly turns: readonly TimelineTurn[];
} {
  const turns = Array.from({ length: TIMELINE_ROW_COUNT }, (_, index): TimelineTurn => {
    const turnId =
      index === TIMELINE_TARGET_INDEX ? TIMELINE_TARGET_TURN_ID : `turn_history_${index}`;
    const timestamp = new Date(Date.UTC(2026, 8, 1, 0, index)).toISOString();
    return {
      origin: "native" as const,
      threadId: THREAD_ID,
      turnId,
      threadRevision: index + 1,
      status: "completed",
      startedAt: timestamp,
      completedAt: new Date(Date.parse(timestamp) + 30_000).toISOString(),
      changeSet: index === TIMELINE_TARGET_INDEX ? changeSet : null,
    };
  });
  const items = turns.flatMap((turn, index): TimelineItemAdapter[] => {
    const timestamp = new Date(Date.UTC(2026, 8, 1, 0, index)).toISOString();
    return [
      {
        origin: "native" as const,
        itemId: `item_user_${turn.turnId}`,
        threadId: THREAD_ID,
        turnId: turn.turnId,
        kind: "user_message",
        status: "completed",
        text: `第 ${index + 1} 次修改任务`,
        createdAt: timestamp,
      },
      {
        origin: "native" as const,
        itemId: `item_final_${turn.turnId}`,
        threadId: THREAD_ID,
        turnId: turn.turnId,
        kind: "agent_message",
        status: "completed",
        final: true,
        text: `第 ${index + 1} 次处理已经完成。${"这段较长的已提交回复用于形成真实可滚动的会话历史，展开修改列表时保留用户当前阅读位置。".repeat(3)}`,
        createdAt: new Date(Date.parse(timestamp) + 30_000).toISOString(),
      },
    ];
  });
  return { items, turns };
}

/** 样式夹具只切换生产语义 token，Palette 和明暗模式的颜色仍由 tokens.css 所有。 */
function applyFixtureTheme(scenario: FixtureScenario): void {
  const root = globalThis.document.documentElement;
  root.dataset["palette"] = scenario.palette;
  root.dataset["theme"] = scenario.theme;
  root.dataset["themeMode"] = scenario.theme;
  root.style.zoom = String(scenario.zoom / 100);
}

/** 容器固定卡片逻辑宽度；放大矩阵由浏览器视口同比例扩展，避免截图剪掉目标表面。 */
function cardColumnStyle(width: number): CSSProperties {
  return { width, maxWidth: "100%", minWidth: 0 };
}

/** 真实 Card、ChatTimeline 与 Turn Review 共用同一个可计数的冻结读取端口。 */
export function TurnChangesCardBrowserFixture() {
  const [scenario] = useState(readScenario);
  const changeSet = useMemo(
    () =>
      createChangeSet(
        scenario.scene === "timeline" ? 23 : scenario.fileCount,
        scenario.scene === "timeline" ? "truncated" : scenario.state,
        scenario.longPath || scenario.scene === "timeline",
      ),
    [scenario],
  );
  const cardTurn: TimelineTurn = useMemo(
    () => ({
      origin: "native" as const,
      threadId: THREAD_ID,
      turnId: CARD_TURN_ID,
      threadRevision: 41,
      status: "completed",
      startedAt: "2026-10-01T00:00:00.000Z",
      completedAt: "2026-10-01T00:00:30.000Z",
      changeSet,
    }),
    [changeSet],
  );
  const { items, turns } = useMemo(() => createTimelineFixtures(changeSet), [changeSet]);
  const disclosureCache = useMemo(() => new TimelineDisclosureCache(), []);
  const [panelVisible, setPanelVisible] = useState(false);
  const [reviewRequest, setReviewRequest] = useState<ReviewRequest>();
  const [lastRequest, setLastRequest] = useState<
    { threadId: string; turnId: string; path?: string } | undefined
  >();
  const requestRevision = useRef(0);
  const failNextRead = useRef(true);
  const readLog = useRef<ReadObservation[]>([]);
  const port = useMemo<TurnReviewPort>(
    () => ({
      /** 首次读取确定性失败一次；重试按冻结目标和完整相对路径返回可解析 Diff。 */
      async readFrozen(target, file, signal) {
        if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
        readLog.current.push({
          threadId: target.threadId,
          turnId: target.turnId,
          artifactId: target.artifactId,
          path: file.path,
        });
        if (failNextRead.current) {
          failNextRead.current = false;
          throw new Error("deterministic fixture read failure");
        }
        const content = [
          `diff --git a/${file.path} b/${file.path}`,
          `--- a/${file.path}`,
          `+++ b/${file.path}`,
          "@@ -1,1 +1,1 @@",
          "-const before = 1;",
          "+const after = 1;",
        ].join("\n");
        const digest = new Uint8Array(
          await crypto.subtle.digest("SHA-256", new TextEncoder().encode(content)),
        );
        return {
          artifactId: target.artifactId,
          filePath: file.path,
          byteLength: new TextEncoder().encode(content).byteLength,
          sha256: [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join(""),
          content,
        };
      },
    }),
    [],
  );
  const reviewTarget = reviewRequest?.target ?? createReviewTarget(cardTurn, changeSet);
  const timelineScene = scenario.scene === "timeline";
  // 使用生产范围导航，但冻结轮次夹具不激活 Git controller，避免额外扫描改变读取计数。
  const reviewAdapter = useMemo(() => createReviewAdapter(), []);
  const workspaceReview = useReviewController({
    workspaceId: undefined,
    snapshotEnabled: false,
    catalogEnabled: false,
    adapter: reviewAdapter,
  });
  /** 卡片只传入它拥有的冻结 Turn 与路径；面板显示才会启动单文件读取。 */
  function requestReview(
    turn: TimelineTurn,
    selectedChangeSet: TurnChangeSet,
    path?: string,
  ): void {
    const target = createReviewTarget(turn, selectedChangeSet);
    requestRevision.current += 1;
    setReviewRequest({ target, path, revision: requestRevision.current });
    setLastRequest({ threadId: turn.threadId, turnId: turn.turnId, path });
    setPanelVisible(true);
  }

  useEffect(() => {
    applyFixtureTheme(scenario);
    window.__JA_TURN_CHANGES__ = {
      readCount: () => readLog.current.length,
      reads: () => [...readLog.current],
      lastRequest: () => lastRequest,
    };
    return () => {
      delete window.__JA_TURN_CHANGES__;
    };
  }, [lastRequest, scenario]);

  const reviewPane = (
    <aside
      aria-label="本轮修改查看面板"
      data-testid="review-pane"
      hidden={!panelVisible}
      style={{ minWidth: 0, minHeight: 0, overflow: "hidden", flex: "1 1 0" }}
    >
      <TurnReviewPanelView
        active={panelVisible}
        target={reviewTarget}
        port={port}
        requestedPath={reviewRequest?.path}
        requestedPathRevision={reviewRequest?.revision ?? 0}
        scopeLabel="最后一轮"
        sourceNavigation={
          <ReviewSourceNavigation
            currentLabel="最后一轮"
            turnSelected
            retainedTurnLabel="最后一轮"
            latestTurnAvailable
            viewModel={workspaceReview.viewModel}
            actions={workspaceReview.actions}
            onShowRetainedTurn={() => undefined}
            onShowLatestTurn={() => undefined}
            onShowWorkspaceReview={() => undefined}
          />
        }
        onShowWorkspaceReview={() => undefined}
        onCopyText={async () => undefined}
      />
    </aside>
  );

  if (!timelineScene) {
    return (
      <main
        data-fixture-scene="card"
        style={{
          display: "flex",
          width: "100vw",
          height: "100vh",
          minWidth: 0,
          minHeight: 0,
          overflow: "hidden",
        }}
      >
        <section
          data-testid="card-column"
          aria-label="轮次修改列表"
          style={{ ...cardColumnStyle(scenario.width), flex: "0 0 auto", overflow: "auto" }}
        >
          <TurnChangesCard
            turn={cardTurn}
            changeSet={changeSet}
            disclosureCache={disclosureCache}
            onReview={(path) => requestReview(cardTurn, changeSet, path)}
          />
        </section>
        {reviewPane}
      </main>
    );
  }

  return (
    <main
      data-fixture-scene="timeline"
      style={{
        display: "flex",
        width: "100vw",
        height: "100vh",
        minWidth: 0,
        minHeight: 0,
        flexDirection: "column",
        overflow: "hidden",
      }}
    >
      <header
        style={{
          display: "flex",
          minHeight: 40,
          flex: "0 0 auto",
          alignItems: "center",
          justifyContent: "flex-end",
          padding: "0 12px",
          borderBottom: "1px solid var(--ja-border)",
        }}
      >
        <button
          type="button"
          data-testid="review-visibility-toggle"
          onClick={() => setPanelVisible((visible) => !visible)}
        >
          {panelVisible ? "隐藏 Review" : "显示 Review"}
        </button>
      </header>
      <div
        data-testid="timeline-workspace"
        style={{ display: "flex", flex: "1 1 0", minWidth: 0, minHeight: 0, overflow: "hidden" }}
      >
        <div style={{ display: "flex", minWidth: 0, minHeight: 0, flex: "1 1 0" }}>
          <ChatTimeline
            threadId={THREAD_ID}
            items={items}
            turns={turns}
            disclosureCache={disclosureCache}
            onReviewTurn={(turn, selectedChangeSet, path) =>
              requestReview(turn, selectedChangeSet, path)
            }
          />
        </div>
        {reviewPane}
      </div>
    </main>
  );
}

applyFixtureTheme(readScenario());
createRoot(document.getElementById("root")!).render(<TurnChangesCardBrowserFixture />);
