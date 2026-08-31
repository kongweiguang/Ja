// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { X } from "lucide-react";
import type { FormEvent, ReactElement } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
  IconButton,
} from "@/shared/ui/primitives";
import type { SaveAsDialogProps } from "./types";

/**
 * 只收集一个工作区相对目标路径。对话框刻意不暴露 native 绝对路径选择器，因为
 * Rust 持有的当前工作区是唯一有权解析目标路径的 owner。
 */
export function SaveAsDialog({
  open,
  sourcePath,
  value,
  error,
  pending,
  onValueChange,
  onCancel,
  onSubmit,
}: SaveAsDialogProps): ReactElement {
  /** 在显式用户事件中提交，同时保留原生 form 的键盘行为，避免另建一套快捷键分支。 */
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!pending) void onSubmit();
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !pending) onCancel();
      }}
    >
      <DialogContent
        className="ja-files-save-as-dialog"
        overlayClassName="ja-files-save-as-overlay"
        aria-describedby="ja-files-save-as-description"
        onEscapeKeyDown={(event) => {
          if (pending) event.preventDefault();
        }}
        onInteractOutside={(event) => event.preventDefault()}
      >
        <div className="ja-files-save-as-heading">
          <div>
            <DialogTitle>另存为</DialogTitle>
            <DialogDescription id="ja-files-save-as-description">
              保存 {sourcePath} 的本地草稿；目标必须是工作区内的新相对路径。
            </DialogDescription>
          </div>
          <IconButton label="关闭另存为" disabled={pending} onClick={onCancel}>
            <X aria-hidden="true" />
          </IconButton>
        </div>
        <form onSubmit={submit} noValidate>
          <label htmlFor="ja-files-save-as-path">工作区相对路径</label>
          <input
            id="ja-files-save-as-path"
            autoFocus
            autoComplete="off"
            spellCheck="false"
            value={value}
            aria-invalid={error !== undefined}
            aria-describedby={
              error === undefined ? "ja-files-save-as-hint" : "ja-files-save-as-error"
            }
            onChange={(event) => onValueChange(event.target.value)}
          />
          <p id="ja-files-save-as-hint" className="ja-files-save-as-hint">
            例如 src/main.copy.ts；已有文件不会被覆盖。
          </p>
          {error === undefined ? null : (
            <p id="ja-files-save-as-error" className="ja-files-save-as-error" role="alert">
              {error}
            </p>
          )}
          <div className="ja-files-save-as-actions">
            <button type="button" disabled={pending} onClick={onCancel}>
              取消
            </button>
            <button type="submit" disabled={pending}>
              {pending ? "正在保存…" : "保存副本"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
