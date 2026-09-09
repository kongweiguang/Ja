// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useState, type ComponentProps } from "react";
import { createRoot } from "react-dom/client";
import { AppTitlebar, NavigationSidebar } from "@/features/navigation";
import type { ThreadProjection, WindowFrameState } from "@/features/navigation";
import {
  Composer,
  ChatTimeline,
  type TimelineItemAdapter,
  type TimelineTurn,
} from "@/features/conversation";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";
import "./readmeBrowserFixture.css";

const THREAD_ID = "thread_readme_demo";
const TURN_ID = "turn_readme_demo";
type ConversationModelOption = NonNullable<ComponentProps<typeof Composer>["models"]>[number];
type ConversationThreadPreferences = NonNullable<ComponentProps<typeof Composer>["preferences"]>;

const MODEL: ConversationModelOption = {
  value: "demo:gpt-5.3-codex",
  providerId: "demo",
  providerLabel: "示例 Provider",
  modelId: "gpt-5.3-codex",
  modelIdentifier: "gpt-5.3-codex",
  modelLabel: "GPT-5.3 Codex",
  contextWindowTokens: 200_000,
  reasoningLevelMap: { low: "low" },
  defaultReasoningLevel: "low",
};

const PREFERENCES: ConversationThreadPreferences = {
  providerId: MODEL.providerId,
  modelId: MODEL.modelId,
  reasoningLevel: "low",
  accessMode: "approval_required",
  collaborationMode: "default",
  titleSource: "manual",
};

/** README 截图使用可审阅的微型真实投影，保持 ChatTimeline/WorkProcess 的生产分组逻辑。 */
const ITEMS: readonly TimelineItemAdapter[] = [
  {
    itemId: "item_user",
    threadId: THREAD_ID,
    turnId: TURN_ID,
    kind: "user_message",
    status: "completed",
    text: "帮我看看这个网站首页，给出一份简洁的改进建议。",
    createdAt: "2026-09-09T10:00:00.000Z",
  },
  {
    itemId: "item_commentary",
    threadId: THREAD_ID,
    turnId: TURN_ID,
    kind: "commentary",
    status: "completed",
    text: "我先读取首页入口和样式，再整理成一份可以直接执行的建议。",
    createdAt: "2026-09-09T10:00:01.000Z",
  },
  {
    itemId: "item_read",
    threadId: THREAD_ID,
    turnId: TURN_ID,
    kind: "tool_call",
    status: "completed",
    title: "读取首页文件",
    metadata: {
      callId: "call_read_home",
      presentation: {
        kind: "read",
        title: "读取首页文件",
        status: "success",
        relativePaths: ["src/pages/Home.tsx", "src/styles/home.css"],
        outputPreview: "已读取 2 个文件",
        durationMs: 420,
        truncated: false,
      },
    },
    createdAt: "2026-09-09T10:00:02.000Z",
  },
  {
    itemId: "item_command",
    threadId: THREAD_ID,
    turnId: TURN_ID,
    kind: "command",
    status: "completed",
    title: "检查页面测试",
    metadata: {
      callId: "call_test_home",
      presentation: {
        kind: "shell",
        title: "检查页面测试",
        status: "success",
        relativePaths: [],
        command: "pnpm test --filter home",
        stdout: "✓ 12 tests passed\n✓ 页面入口可正常加载",
        exitCode: 0,
        durationMs: 1_800,
        truncated: false,
      },
    },
    createdAt: "2026-09-09T10:00:03.000Z",
  },
  {
    itemId: "item_final",
    threadId: THREAD_ID,
    turnId: TURN_ID,
    kind: "agent_message",
    status: "completed",
    final: true,
    text: "首页基础已经清楚，建议先做三件事：\n\n1. 把首屏主按钮改成一个明确动作。\n2. 把核心卖点收成三条，方便快速浏览。\n3. 在移动端保留标题、主按钮和下一段内容的连续阅读。\n\n整体不需要重做，先从信息层级和首屏节奏开始。",
    createdAt: "2026-09-09T10:00:05.000Z",
  },
];

const TURN: TimelineTurn = {
  turnId: TURN_ID,
  threadId: THREAD_ID,
  status: "completed",
  startedAt: "2026-09-09T10:00:00.000Z",
  completedAt: "2026-09-09T10:00:05.000Z",
  changeSet: null,
};

/** 截图 fixture 只提供展示用 typed port，禁止触发网络、Tauri 或真实 Provider。 */
export function ReadmeBrowserFixture() {
  const [draft, setDraft] = useState("");
  const windowFrame: WindowFrameState = { maximized: false, fullscreen: false };
  const thread: ThreadProjection = {
    threadId: THREAD_ID,
    title: "首页改进建议",
    status: "active",
    pinned: false,
    latestTurnStatus: "completed",
    latestTurnSeen: true,
  };
  return (
    <main className="ja-shell">
      <AppTitlebar
        platform="unknown"
        sidebarOpen
        onToggleSidebar={() => undefined}
        canGoBack={false}
        canGoForward={false}
        onBack={() => undefined}
        onForward={() => undefined}
        windowFrame={windowFrame}
        onWindowAction={() => undefined}
      />
      <div className="ja-layout">
        <div className="ja-navigation-shell">
          <NavigationSidebar
            projects={[{ workspaceId: "workspace_demo", displayName: "网站改版" }]}
            projectCatalogLoading={false}
            currentWorkspaceId="workspace_demo"
            generalWorkspaceSelected={false}
            projectSectionCollapsed={false}
            historySectionCollapsed={false}
            runtimeLabel="已连接"
            runtimeTone="ready"
            currentThreadId={THREAD_ID}
            threads={[thread]}
            historyBusy={false}
            newConversationDisabled={false}
            projectBusy={false}
            compact={false}
            platform="unknown"
            activeAction="workspace"
            conversationSearchOpen={false}
            onNewConversation={() => undefined}
            onSelectConversation={() => undefined}
            onOpenConversationSearch={() => undefined}
            onRenameConversation={async () => undefined}
            onPinConversation={async () => undefined}
            onArchiveConversation={async () => undefined}
            mutatingThreadIds={[]}
            onChooseProject={() => undefined}
            onSelectGeneral={() => undefined}
            onSelectProject={() => undefined}
            onProjectSectionCollapsedChange={() => undefined}
            onHistorySectionCollapsedChange={() => undefined}
            onRetryProjects={() => undefined}
            onOpenSettings={() => undefined}
            onRequestClose={() => undefined}
          />
        </div>
        <div className="ja-workspace-stage">
          <main className="ja-main">
            <section className="ja-conversation" aria-label="对话">
              <header className="ja-conversation-header">
                <div className="ja-conversation-heading">
                  <strong>首页改进建议</strong>
                </div>
                <span className="readme-fixture-caption">网站改版 · 示例工作区</span>
              </header>
              <ChatTimeline items={ITEMS} turns={[TURN]} onCopyText={async () => undefined} />
              <div className="ja-conversation-composer-dock ja-conversation-content-rail">
                <Composer
                  preferences={PREFERENCES}
                  models={[MODEL]}
                  text={draft}
                  onTextChange={setDraft}
                  placeholder="继续告诉 Ja 你想做什么…"
                  threadId={THREAD_ID}
                  workspaceId="workspace_demo"
                  runtimeGeneration={1}
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

document.documentElement.dataset["palette"] = "xcode";
document.documentElement.dataset["theme"] = "light";
createRoot(document.getElementById("root")!).render(<ReadmeBrowserFixture />);
