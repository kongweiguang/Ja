// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useRef, useState, type FormEvent, type KeyboardEvent, type ReactElement } from "react";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/shared/ui/primitives";
import type { ThreadProjection } from "../domain/navigationModels";
import "./conversationDialogs.css";

export interface ConversationRenameDialogProps {
  thread: ThreadProjection | undefined;
  open: boolean;
  onOpenChange(open: boolean): void;
  onRename(threadId: string, title: string): Promise<void>;
}

/**
 * Dialog 关闭时卸载编辑 session，确保每次打开都从最新 Thread 投影初始化，而不通过
 * Effect 复制 props 到 state。
 */
export function ConversationRenameDialog({
  thread,
  open,
  onOpenChange,
  onRename,
}: ConversationRenameDialogProps): ReactElement {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open && thread !== undefined ? (
        <ConversationRenameSession
          key={thread.threadId}
          thread={thread}
          onOpenChange={onOpenChange}
          onRename={onRename}
        />
      ) : null}
    </Dialog>
  );
}

interface ConversationRenameSessionProps {
  thread: ThreadProjection;
  onOpenChange(open: boolean): void;
  onRename(threadId: string, title: string): Promise<void>;
}

/**
 * 人工重命名在服务端 CAS 成功前不关闭 Dialog；失败保留输入供用户重试，避免乐观标题与
 * 迟到自动标题产生不可解释的覆盖。
 */
function ConversationRenameSession({
  thread,
  onOpenChange,
  onRename,
}: ConversationRenameSessionProps): ReactElement {
  const [title, setTitle] = useState(thread.title);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const composingRef = useRef(false);

  /** 表单提交冻结当前 identity 与标题，服务端拒绝时不泄漏内部错误。 */
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const normalized = title.trim();
    if (normalized === "" || pending) return;
    setPending(true);
    setError(false);
    void onRename(thread.threadId, normalized)
      .then(() => onOpenChange(false))
      .catch(() => setError(true))
      .finally(() => setPending(false));
  };

  /** IME 组合期间 Enter 只确认候选，不能冒泡触发表单提交。 */
  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === "Enter" && (composingRef.current || event.nativeEvent.isComposing)) {
      event.preventDefault();
    }
  };

  return (
    <DialogContent className="ja-conversation-rename-dialog">
      <DialogTitle>重命名对话</DialogTitle>
      <DialogDescription className="ja-visually-hidden">
        输入当前工作区会话的新标题
      </DialogDescription>
      <form onSubmit={submit}>
        <input
          autoFocus
          aria-label="会话标题"
          value={title}
          maxLength={512}
          disabled={pending}
          onChange={(event) => {
            setTitle(event.currentTarget.value);
            setError(false);
          }}
          onCompositionStart={() => {
            composingRef.current = true;
          }}
          onCompositionEnd={() => {
            composingRef.current = false;
          }}
          onKeyDown={handleKeyDown}
        />
        {error ? <p role="alert">重命名失败，请刷新会话后重试。</p> : null}
        <div className="ja-conversation-rename-actions">
          <DialogClose asChild>
            <Button type="button" variant="ghost" disabled={pending}>
              取消
            </Button>
          </DialogClose>
          <Button type="submit" disabled={title.trim() === "" || pending}>
            {pending ? "保存中…" : "保存"}
          </Button>
        </div>
      </form>
    </DialogContent>
  );
}
