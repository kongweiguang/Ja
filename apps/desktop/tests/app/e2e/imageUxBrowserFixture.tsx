// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useState, type ComponentProps } from "react";
import { createRoot } from "react-dom/client";
import {
  ChatTimeline,
  Composer,
  type HistoryAttachmentThumbnailPort,
  type TimelineItemAdapter,
} from "@/features/conversation";
import type { TimelineTurn } from "@/features/conversation";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";
import "./imageUxBrowserFixture.css";

const THREAD_ID = "thread_image_ux_fixture";
const MULTI_TURN_ID = "turn_image_ux_multi";
const PURE_TURN_ID = "turn_image_ux_pure";
type ComposerModel = NonNullable<ComponentProps<typeof Composer>["models"]>[number];
type ComposerPreferences = NonNullable<ComponentProps<typeof Composer>["preferences"]>;

/** 生成带尺寸元数据的本地 SVG data URL，让 headless browser 验收真实 img 解码而不依赖网络或磁盘路径。 */
function fixtureImageData(label: string, background: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="420" viewBox="0 0 640 420"><rect width="640" height="420" rx="28" fill="${background}"/><path d="M54 302 196 166l92 82 72-66 226 188H54Z" fill="#ffffff" fill-opacity=".72"/><circle cx="476" cy="124" r="44" fill="#ffffff" fill-opacity=".78"/><text x="54" y="78" fill="#ffffff" font-family="Segoe UI, sans-serif" font-size="28" font-weight="600">${label}</text></svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

const IMAGE_ONE = fixtureImageData("第一张", "#4778b8");
const IMAGE_TWO = fixtureImageData("第二张", "#b26b4c");

const MODEL: ComposerModel = {
  value: "fixture:image-ux",
  providerId: "fixture",
  providerLabel: "Fixture Provider",
  modelId: "image-ux",
  modelIdentifier: "image-ux",
  modelLabel: "Image UX Fixture",
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

const IMAGE_ATTACHMENTS = [
  {
    attachmentId: "att_image_ux_one",
    displayName: "第一张参考图.svg",
    sizeBytes: 18_432,
    mediaKind: "image" as const,
    mediaType: "image/svg+xml",
  },
  {
    attachmentId: "att_image_ux_two",
    displayName: "第二张参考图.svg",
    sizeBytes: 21_504,
    mediaKind: "image" as const,
    mediaType: "image/svg+xml",
  },
];

const ITEMS: readonly TimelineItemAdapter[] = [
  {
    itemId: "item_multi_image",
    threadId: THREAD_ID,
    turnId: MULTI_TURN_ID,
    kind: "user_message",
    status: "completed",
    text: "请比较这两张图的布局差异，并给出调整建议。",
    attachments: IMAGE_ATTACHMENTS,
    createdAt: "2026-09-23T09:00:00.000Z",
  },
  {
    itemId: "item_pure_image",
    threadId: THREAD_ID,
    turnId: PURE_TURN_ID,
    kind: "user_message",
    status: "completed",
    text: "",
    attachments: [IMAGE_ATTACHMENTS[0]!],
    createdAt: "2026-09-23T09:00:03.000Z",
  },
];

const TURNS: readonly TimelineTurn[] = [
  {
    turnId: MULTI_TURN_ID,
    threadId: THREAD_ID,
    status: "completed",
    startedAt: "2026-09-23T09:00:00.000Z",
    completedAt: "2026-09-23T09:00:02.000Z",
    changeSet: null,
  },
  {
    turnId: PURE_TURN_ID,
    threadId: THREAD_ID,
    status: "completed",
    startedAt: "2026-09-23T09:00:03.000Z",
    completedAt: "2026-09-23T09:00:04.000Z",
    changeSet: null,
  },
];

/** 通过真实缩略图端口返回受控图片资源，覆盖历史消息授权后的图片渲染路径。 */
function createThumbnailPort(): HistoryAttachmentThumbnailPort {
  return {
    open: async ({ attachmentId, authorization }) => ({
      previewSessionId: `preview_${authorization.kind}_${attachmentId}`,
      attachmentId,
      mediaKind: "image",
      thumbnailUrl: attachmentId.endsWith("two") ? IMAGE_TWO : IMAGE_ONE,
    }),
    close: async () => undefined,
  };
}

/** 用生产 Composer 与 ChatTimeline 组合出可重复的图片布局验收场景，不复制任何生产卡片结构。 */
export function ImageUxBrowserFixture() {
  const [draft, setDraft] = useState("");
  return (
    <main className="ja-shell">
      <div className="ja-layout is-sidebar-hidden">
        <div className="ja-workspace-stage">
          <main className="ja-main">
            <section className="ja-conversation" aria-label="对话">
              <header className="ja-conversation-header">
                <div className="ja-conversation-heading">
                  <strong>图片消息布局</strong>
                </div>
                <span className="image-ux-fixture-heading">多图 · 图片-only · 草稿附件</span>
              </header>
              <div className="ja-conversation-body">
                <ChatTimeline
                  items={ITEMS}
                  turns={TURNS}
                  threadId={THREAD_ID}
                  attachmentThumbnailPort={createThumbnailPort()}
                  onOpenAttachmentPreview={() => undefined}
                  onCopyText={async () => undefined}
                />
              </div>
              <div className="ja-conversation-composer-dock ja-conversation-content-rail">
                <Composer
                  preferences={PREFERENCES}
                  models={[MODEL]}
                  text={draft}
                  onTextChange={setDraft}
                  placeholder="随心输入"
                  threadId={THREAD_ID}
                  workspaceId="workspace_image_ux_fixture"
                  runtimeGeneration={1}
                  attachmentDraftItems={IMAGE_ATTACHMENTS.map((attachment, index) => ({
                    state: "ready" as const,
                    itemId: `draft_${attachment.attachmentId}`,
                    fileName: attachment.displayName,
                    ...attachment,
                    thumbnailUrl: index === 0 ? IMAGE_ONE : IMAGE_TWO,
                  }))}
                  onOpenAttachmentPreview={() => undefined}
                  onRemoveAttachment={() => undefined}
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
createRoot(document.getElementById("root")!).render(<ImageUxBrowserFixture />);
