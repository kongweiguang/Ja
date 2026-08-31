// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { MoreHorizontal, Sparkles, X } from "lucide-react";
import type { ReactElement } from "react";
import type { ConversationCompactionView } from "../../application/useConversationController";
import { IconButton, Menu, MenuContent, MenuItem, MenuTrigger } from "@/shared/ui/primitives";
import "./ThreadOperationsMenu.css";

export interface ThreadOperationsMenuProps {
  showCompactAction: boolean;
  compaction: ConversationCompactionView;
  onCompact: () => void | Promise<void>;
  onDismissFeedback: () => void;
}

/**
 * Thread 菜单只发出压缩意图并展示服务端投影；活动 Turn 时完全移除动作，避免向用户提供
 * 已知会被 THREAD_BUSY 拒绝的入口。
 */
export function ThreadOperationsMenu({
  showCompactAction,
  compaction,
  onCompact,
  onDismissFeedback,
}: ThreadOperationsMenuProps): ReactElement | null {
  const pending = compaction.phase === "running";
  const showMenu = showCompactAction || pending;
  if (!showMenu && compaction.phase === "idle") return null;
  return (
    <div className="ja-thread-operations">
      {showMenu ? (
        <Menu>
          <MenuTrigger asChild>
            <IconButton
              className="ja-inline-icon-button ja-thread-operations-trigger"
              label={pending ? "上下文压缩中" : "打开对话操作"}
              disabled={pending}
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
              disabled={pending}
              onSelect={() => {
                if (!pending) void onCompact();
              }}
            >
              <Sparkles aria-hidden="true" />
              <span>{pending ? "正在压缩上下文…" : "压缩上下文"}</span>
            </MenuItem>
          </MenuContent>
        </Menu>
      ) : null}
      {compaction.phase === "idle" || compaction.message === undefined ? null : (
        <div
          className={`ja-thread-compaction-feedback is-${compaction.phase}`}
          role={compaction.phase === "error" ? "alert" : "status"}
          aria-live="polite"
        >
          <span>{compaction.message}</span>
          {compaction.retryable && showCompactAction ? (
            <button type="button" onClick={() => void onCompact()}>
              重试
            </button>
          ) : null}
          {!pending ? (
            <IconButton label="关闭上下文压缩提示" onClick={onDismissFeedback}>
              <X aria-hidden="true" />
            </IconButton>
          ) : null}
        </div>
      )}
    </div>
  );
}
