// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useState, type ComponentProps } from "react";
import { createRoot } from "react-dom/client";
import { ConversationTimelineSurface } from "@/app/composition/ConversationTimelineSurface";
import { NavigationSidebar, type ThreadProjection } from "@/features/navigation";
import {
  Composer,
  useTimelineStore,
  type TimelineEvent,
  type TimelineTurn,
} from "@/features/conversation";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";
import "./streamingStabilityBrowserFixture.css";

const THREAD_ID = "thread_streaming_stability";
const TURN_ID = "turn_streaming_stability";
const WORKSPACE_ID = "workspace_streaming_stability";
const STREAM_EVENT_EPOCH_MS = Date.parse("2026-09-20T00:00:01Z");
type ComposerModel = NonNullable<ComponentProps<typeof Composer>["models"]>[number];
type ComposerPreferences = NonNullable<ComponentProps<typeof Composer>["preferences"]>;
type HistoryMode = "ready" | "background-busy" | "empty-busy";

const MODEL: ComposerModel = {
  value: "fixture:gpt-stream",
  providerId: "fixture",
  providerLabel: "Fixture Provider",
  modelId: "gpt-stream",
  modelIdentifier: "gpt-stream",
  modelLabel: "GPT Stream Fixture",
  contextWindowTokens: 32_000,
  reasoningLevelMap: { low: "low" },
  defaultReasoningLevel: "low",
};

const PREFERENCES: ComposerPreferences = {
  providerId: MODEL.providerId,
  modelId: MODEL.modelId,
  reasoningLevel: "low",
  accessMode: "approval_required",
  collaborationMode: "default",
  titleSource: "manual",
};

const RUNNING_TURN: TimelineTurn = {
  threadId: THREAD_ID,
  turnId: TURN_ID,
  status: "running",
  startedAt: "2026-09-20T00:00:01Z",
  changeSet: null,
};
let eventSequence = 1;
let streamSequence = 0;
let lastDelta: TimelineEvent | undefined;
let updateHistoryMode: ((mode: HistoryMode) => void) | undefined;

declare global {
  interface Window {
    __JA_STREAMING_STABILITY__: {
      appendReasoning(text: string): string;
      appendContextCompaction(): string;
      appendDelta(text: string): string;
      repeatLastDelta(): string;
      complete(finalText: string): string;
      setHistoryMode(mode: HistoryMode): string;
    };
  }
}

/** 只通过公开 reducer seam 建立运行中的 Turn，fixture 不伪造组件私有状态。 */
function prepareTimeline(): void {
  const store = useTimelineStore.getState();
  store.reset();
  store.applyHostEvent({
    kind: "status",
    status: { status: "ready", generation: 1, serverInstanceId: "srv_streaming_stability" },
    eventId: "evt_streaming_stability_ready",
    occurredAt: "2026-09-20T00:00:00Z",
  });
  store.applySnapshot(
    {
      threadId: THREAD_ID,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      liveStream: null,
      nextCursor: null,
    },
    WORKSPACE_ID,
  );
  store.applyTurnAccepted({
    threadId: THREAD_ID,
    turnId: TURN_ID,
    threadRevision: 1,
    submittedText: "请连续输出一段用于稳定性验收的回复。",
    submittedAt: "2026-09-20T00:00:01Z",
  });
  store.applyHostEvent({
    kind: "timeline",
    event: {
      jsonrpc: "2.0",
      method: "turn/state-changed",
      params: {
        serverInstanceId: "srv_streaming_stability",
        eventId: "evt_streaming_stability_running",
        sequence: eventSequence,
        generation: 1,
        workspaceId: WORKSPACE_ID,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: 2,
        occurredAt: "2026-09-20T00:00:01Z",
        from: "queued",
        to: "running",
      },
    },
  });
}

/** fixture 复用生产组件和全局样式，只把 native 端口替换为无副作用的展示回调。 */
export function StreamingStabilityBrowserFixture() {
  const [draft, setDraft] = useState("");
  /** 浏览器稳定性 fixture 不触发原生 Explorer，仅满足隔离视图的可选目录入口。 */
  const openProjectFolderFixture = async (): Promise<void> => undefined;
  const [historyMode, setHistoryMode] = useState<HistoryMode>("ready");
  const turn = useTimelineStore((state) => state.turns[TURN_ID] ?? RUNNING_TURN);
  const thread: ThreadProjection = {
    threadId: THREAD_ID,
    title: "流式回复稳定性",
    status: "active",
    pinned: false,
    latestTurnStatus: turn.status,
    latestTurnSeen: true,
  };
  useEffect(() => {
    // 浏览器验收只通过显式 fixture seam 改变历史投影，避免脚本触碰 React 私有状态。
    updateHistoryMode = setHistoryMode;
    return () => {
      updateHistoryMode = undefined;
    };
  }, []);
  const historyBusy = historyMode !== "ready";
  const threads = historyMode === "empty-busy" ? [] : [thread];
  return (
    <main className="ja-shell">
      <div className="ja-layout">
        <div className="ja-navigation-shell">
          <NavigationSidebar
            projects={[{ workspaceId: WORKSPACE_ID, displayName: "稳定性验收" }]}
            projectCatalogLoading={false}
            currentWorkspaceId={WORKSPACE_ID}
            noProjectSelected={false}
            projectSectionCollapsed={false}
            historySectionCollapsed={false}
            runtimeLabel={turn.status === "running" ? "工作中" : "已连接"}
            runtimeTone={turn.status === "running" ? "busy" : "ready"}
            currentThreadId={THREAD_ID}
            threads={threads}
            historyBusy={historyBusy}
            newConversationDisabled={false}
            projectBusy={false}
            compact={false}
            platform="windows"
            activeAction="workspace"
            conversationSearchOpen={false}
            onNewConversation={() => undefined}
            onSelectConversation={() => undefined}
            onOpenProjectFolder={openProjectFolderFixture}
            onOpenConversationSearch={() => undefined}
            onRenameConversation={async () => undefined}
            onPinConversation={async () => undefined}
            onArchiveConversation={async () => undefined}
            mutatingThreadIds={[]}
            onChooseProject={() => undefined}
            onSelectNoProject={() => undefined}
            onSelectProject={() => undefined}
            onProjectSectionCollapsedChange={() => undefined}
            onHistorySectionCollapsedChange={() => undefined}
            onRetryProjects={() => undefined}
            onOpenSettings={() => undefined}
            onRequestClose={() => undefined}
            onOpenWorkspaceFolder={async () => undefined}
            onOpenLegacySharedFolder={async () => undefined}
          />
        </div>
        <div className="ja-workspace-stage">
          <main className="ja-main">
            <section className="ja-conversation" aria-label="对话">
              <header className="ja-conversation-header">
                <div className="ja-conversation-heading">
                  <strong>流式回复稳定性</strong>
                </div>
              </header>
              <div className="ja-conversation-body">
                <ConversationTimelineSurface
                  threadId={THREAD_ID}
                  answeredRequest={null}
                  answeredAnswers={{}}
                  turns={[turn]}
                />
              </div>
              <div className="ja-conversation-composer-dock ja-conversation-content-rail">
                <Composer
                  preferences={PREFERENCES}
                  models={[MODEL]}
                  text={draft}
                  onTextChange={setDraft}
                  placeholder="继续告诉 Ja 你想做什么…"
                  threadId={THREAD_ID}
                  workspaceId={WORKSPACE_ID}
                  runtimeGeneration={1}
                  activeTurn={turn.status === "running"}
                  onSend={() => undefined}
                />
              </div>
            </section>
          </main>
        </div>
      </div>
    </main>
  );
}

prepareTimeline();
window.__JA_STREAMING_STABILITY__ = {
  /** 切换可观察的历史加载投影，用于证明后台刷新静默且首次短请求不会闪烁。 */
  setHistoryMode(mode: HistoryMode): string {
    if (updateHistoryMode === undefined) return "invalid";
    updateHistoryMode(mode);
    return "applied";
  },
  /** Reasoning 使用独立协议事件建立真实 WorkProcess，不与回复正文共享展示槽。 */
  appendReasoning(text: string): string {
    streamSequence += 1;
    eventSequence += 1;
    const event: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/reasoning-summary-delta",
      params: {
        serverInstanceId: "srv_streaming_stability",
        eventId: `evt_streaming_stability_reasoning_${streamSequence}`,
        sequence: eventSequence,
        generation: 1,
        workspaceId: WORKSPACE_ID,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: 2,
        occurredAt: new Date(STREAM_EVENT_EPOCH_MS + eventSequence * 100).toISOString(),
        streamSeq: streamSequence,
        text,
      },
    };
    lastDelta = event;
    return useTimelineStore.getState().applyHostEvent({ kind: "timeline", event }) ?? "invalid";
  },
  /** 自动压缩沿真实 started/compacted 生命周期投影为过程中的一条 Tool，不借用右侧工作台状态。 */
  appendContextCompaction(): string {
    // 与正文共享 fixture 时钟，才能验证 Renderer 按权威 occurredAt 保持同一阅读顺序。
    eventSequence += 1;
    const currentRevision = useTimelineStore.getState().threadRevisionByThread[THREAD_ID] ?? 0;
    const started: TimelineEvent = {
      jsonrpc: "2.0",
      method: "context/compaction-started",
      params: {
        serverInstanceId: "srv_streaming_stability",
        eventId: "evt_streaming_stability_compaction_started",
        sequence: eventSequence,
        generation: 1,
        workspaceId: WORKSPACE_ID,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: currentRevision,
        occurredAt: new Date(STREAM_EVENT_EPOCH_MS + eventSequence * 100).toISOString(),
        compactionId: "cmp_streaming_context",
        trigger: "automatic",
        sourceRevision: currentRevision,
        inputTokensBefore: 12_000,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v1",
      },
    };
    const startedResult = useTimelineStore
      .getState()
      .applyHostEvent({ kind: "timeline", event: started });
    if (startedResult !== "applied") return startedResult ?? "invalid";

    eventSequence += 1;
    const compacted: TimelineEvent = {
      jsonrpc: "2.0",
      method: "context/compacted",
      params: {
        serverInstanceId: "srv_streaming_stability",
        eventId: "evt_streaming_stability_compacted",
        sequence: eventSequence,
        generation: 1,
        workspaceId: WORKSPACE_ID,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: currentRevision + 1,
        occurredAt: new Date(STREAM_EVENT_EPOCH_MS + eventSequence * 100).toISOString(),
        compactionId: "cmp_streaming_context",
        checkpointId: "checkpoint_streaming_context",
        trigger: "automatic",
        sourceRevision: currentRevision,
        inputTokensBefore: 12_000,
        inputTokensAfter: 4_000,
        strategyVersion: "ja-context-v1",
      },
    };
    return (
      useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: compacted }) ??
      "invalid"
    );
  },
  /** CDP 每次只追加正文；返回 reducer 结果，便于验收脚本拒绝静默失败。 */
  appendDelta(text: string): string {
    streamSequence += 1;
    eventSequence += 1;
    const event: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_streaming_stability",
        eventId: `evt_streaming_stability_delta_${streamSequence}`,
        sequence: eventSequence,
        generation: 1,
        workspaceId: WORKSPACE_ID,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: 2,
        occurredAt: new Date(STREAM_EVENT_EPOCH_MS + eventSequence * 100).toISOString(),
        streamSeq: streamSequence,
        text,
      },
    };
    lastDelta = event;
    return useTimelineStore.getState().applyHostEvent({ kind: "timeline", event }) ?? "invalid";
  },
  /** 重放完全相同的协议事件，验证 reducer 去重不会重复正文或重建过程节点。 */
  repeatLastDelta(): string {
    if (lastDelta === undefined) return "invalid";
    return (
      useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: lastDelta }) ??
      "invalid"
    );
  },
  /** terminal 直接校准流式正文并收口状态，不伪造协议禁止的无 Tool model-step 事件。 */
  complete(finalText: string): string {
    eventSequence += 1;
    const currentRevision = useTimelineStore.getState().threadRevisionByThread[THREAD_ID] ?? 0;
    const terminal: TimelineEvent = {
      jsonrpc: "2.0",
      method: "turn/terminal",
      params: {
        serverInstanceId: "srv_streaming_stability",
        eventId: "evt_streaming_stability_terminal",
        sequence: eventSequence,
        generation: 1,
        workspaceId: WORKSPACE_ID,
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: currentRevision + 1,
        occurredAt: "2026-09-20T00:00:10Z",
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_streaming_stability_final", text: finalText },
        changeSet: {
          state: "complete",
          incompleteReasons: [],
          files: [],
          stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
        },
      },
    };
    return (
      useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: terminal }) ?? "invalid"
    );
  },
};

document.documentElement.dataset["palette"] = "xcode";
document.documentElement.dataset["theme"] = "light";
createRoot(document.getElementById("root")!).render(<StreamingStabilityBrowserFixture />);
