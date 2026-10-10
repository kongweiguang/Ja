// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Download, LoaderCircle, MoreHorizontal, Sparkles } from "lucide-react";
import { useState, type ReactElement } from "react";
import type { ConversationCompactionView } from "../../application/useConversationController";
import { IconButton, Menu, MenuContent, MenuItem, MenuTrigger } from "@/shared/ui/primitives";
import { Notification } from "@/shared/ui/notifications";
import "./ThreadOperationsMenu.css";

export interface ThreadOperationsMenuProps {
  scope: string;
  /** 对话框覆盖工作面时隐藏提示但保留 Thread owner，避免退出/重建打断反馈生命周期。 */
  enabled?: boolean;
  showCompactAction: boolean;
  showExportAction?: boolean;
  compaction: ConversationCompactionView;
  onCompact: () => void | Promise<void>;
  onCancelCompaction: () => void | Promise<void>;
  onDismissFeedback: () => void;
  onExportConversation?: () => Promise<"saved" | "cancelled" | "unsupported" | "downloaded">;
}

/**
 * Thread 菜单只保留整段对话级操作；逐条回复的分叉入口归属对应答复，避免用户失去位置上下文。
 * 提示跟随 Thread owner 的可见门禁原位停启，不让后台会话的反馈覆盖当前模态对话框。
 * @author kongweiguang
 */
export function ThreadOperationsMenu({
  scope,
  enabled = true,
  showCompactAction,
  showExportAction = false,
  compaction,
  onCompact,
  onCancelCompaction,
  onDismissFeedback,
  onExportConversation,
}: ThreadOperationsMenuProps): ReactElement | null {
  const [operation, setOperation] = useState<"export">();
  const [feedback, setFeedback] = useState<{ message: string; tone: "success" | "error" }>();
  const pending = compaction.phase === "running";
  const operationPending = operation !== undefined;
  const showMenu = showCompactAction || pending || showExportAction;
  if (!showMenu && compaction.phase === "idle" && feedback === undefined) return null;

  /** 取消保存保持安静；unsupported 与写入失败都显示稳定反馈并允许重试。 */
  const exportConversation = async (): Promise<void> => {
    if (pending || operationPending || onExportConversation === undefined) return;
    setOperation("export");
    setFeedback(undefined);
    try {
      const result = await onExportConversation();
      if (result === "saved") setFeedback({ message: "对话已导出。", tone: "success" });
      if (result === "downloaded")
        setFeedback({ message: "Markdown 文件已开始下载。", tone: "success" });
      if (result === "unsupported")
        setFeedback({ message: "当前 WebView2 不支持原生文件保存。", tone: "error" });
    } catch {
      setFeedback({ message: "导出失败，请重试。", tone: "error" });
    } finally {
      setOperation(undefined);
    }
  };

  return (
    <div className="ja-thread-operations">
      {showMenu ? (
        <Menu>
          <MenuTrigger asChild>
            <IconButton
              className="ja-inline-icon-button ja-thread-operations-trigger"
              label={pending ? "上下文压缩中" : "打开对话操作"}
              disabled={pending || operationPending}
            >
              <MoreHorizontal aria-hidden="true" />
            </IconButton>
          </MenuTrigger>
          <MenuContent
            className="ja-thread-operations-menu"
            align="end"
            sideOffset={8}
            collisionPadding={12}
            aria-label="对话操作"
          >
            <MenuItem
              className="ja-thread-operations-item"
              disabled={pending || operationPending}
              onSelect={() => {
                if (!pending) void onCompact();
              }}
            >
              <Sparkles aria-hidden="true" />
              <span>{pending ? "正在压缩上下文…" : "压缩上下文"}</span>
            </MenuItem>
            {showExportAction ? (
              <MenuItem
                className="ja-thread-operations-item"
                disabled={pending || operationPending}
                onSelect={() => void exportConversation()}
              >
                {operation === "export" ? (
                  <LoaderCircle className="ja-thread-operations-spin" aria-hidden="true" />
                ) : (
                  <Download aria-hidden="true" />
                )}
                <span>{operation === "export" ? "正在导出…" : "导出对话 Markdown"}</span>
              </MenuItem>
            ) : null}
          </MenuContent>
        </Menu>
      ) : null}
      {feedback === undefined ? null : (
        <Notification
          scope={`${scope}:thread-operation`}
          message={feedback.message}
          tone={feedback.tone}
          enabled={enabled}
          dismissible
          dismissLabel="关闭对话操作提示"
          onDismiss={() => setFeedback(undefined)}
        />
      )}
      {compaction.phase === "idle" || compaction.message === undefined ? null : (
        <Notification
          scope={scope}
          message={compaction.message}
          tone={
            compaction.phase === "error"
              ? "error"
              : compaction.phase === "success"
                ? "success"
                : "info"
          }
          dismissible={!pending}
          enabled={enabled}
          dismissLabel="关闭上下文压缩提示"
          onDismiss={onDismissFeedback}
        >
          {pending && compaction.cancellable && compaction.message !== "正在停止上下文压缩…" ? (
            <button type="button" onClick={() => void onCancelCompaction()}>
              停止
            </button>
          ) : null}
          {compaction.retryable && showCompactAction ? (
            <button type="button" onClick={() => void onCompact()}>
              重试
            </button>
          ) : null}
        </Notification>
      )}
    </div>
  );
}
