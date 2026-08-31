// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { LoaderCircle, Trash2, X } from "lucide-react";
import { useRef, type ReactElement } from "react";
import { IconButton } from "@/shared/ui/primitives";
import type { TrashConfirmDialogProps } from "./types";

/**
 * 始终展示精确字节总数，并为较大的选择补充紧凑二进制单位；native prepare 结果
 * 仍是唯一事实来源，视图不重新估算大小。
 */
function formatByteCount(totalBytes: number): string {
  const exact = `${totalBytes.toLocaleString("zh-CN")} 字节`;
  if (totalBytes < 1_024) return exact;
  const units = ["KB", "MB", "GB", "TB"] as const;
  let value = totalBytes;
  let unitIndex = -1;
  do {
    value /= 1_024;
    unitIndex += 1;
  } while (value >= 1_024 && unitIndex < units.length - 1);
  const compact = new Intl.NumberFormat("zh-CN", {
    maximumFractionDigits: value >= 10 ? 1 : 2,
  }).format(value);
  return `${compact} ${units[unitIndex]}（${exact}）`;
}

/**
 * 展示两阶段 Trash 边界，但永不接收 opaque operation token。取消按钮持有初始焦点，
 * 避免从 Context Menu 延续的意外 Enter 触发破坏性操作。
 */
export function TrashConfirmDialog({
  open,
  relativePath,
  phase,
  fileCount,
  totalBytes,
  error,
  onCancel,
  onConfirm,
  onRestoreFocus,
}: TrashConfirmDialogProps): ReactElement {
  const cancelButtonRef = useRef<HTMLButtonElement>(null);
  const committing = phase === "committing";
  const prepared = fileCount !== undefined && totalBytes !== undefined;

  return (
    <AlertDialog.Root
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !committing) onCancel();
      }}
    >
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="ja-files-trash-overlay" />
        <AlertDialog.Content
          className="ja-files-trash-dialog"
          aria-busy={phase === "preparing" || committing}
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            cancelButtonRef.current?.focus();
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            onRestoreFocus();
          }}
          onEscapeKeyDown={(event) => {
            if (committing) event.preventDefault();
          }}
        >
          <div className="ja-files-trash-heading">
            <div className="ja-files-trash-title-icon" aria-hidden="true">
              <Trash2 />
            </div>
            <div className="ja-files-trash-title-copy">
              <AlertDialog.Title>移入回收站</AlertDialog.Title>
              <AlertDialog.Description>
                该项目会进入 Windows 系统回收站，之后仍可从系统中恢复。
              </AlertDialog.Description>
            </div>
            <AlertDialog.Cancel asChild>
              <IconButton
                className="ja-files-trash-close"
                label="关闭回收站确认"
                disabled={committing}
              >
                <X aria-hidden="true" />
              </IconButton>
            </AlertDialog.Cancel>
          </div>

          <div className="ja-files-trash-body">
            <code className="ja-files-trash-path" title={relativePath}>
              {relativePath}
            </code>
            {phase === "preparing" ? (
              <div className="ja-files-trash-progress" role="status">
                <LoaderCircle aria-hidden="true" className="ja-spin" />
                正在核对项目数量和大小…
              </div>
            ) : null}
            {prepared && fileCount !== undefined && totalBytes !== undefined ? (
              <dl className="ja-files-trash-summary" aria-label="回收站操作摘要">
                <div>
                  <dt>项目数量</dt>
                  <dd>{fileCount.toLocaleString("zh-CN")} 个文件</dd>
                </div>
                <div>
                  <dt>总大小</dt>
                  <dd>{formatByteCount(totalBytes)}</dd>
                </div>
              </dl>
            ) : null}
            {error === undefined ? null : (
              <p className="ja-files-trash-error" role="alert">
                {error}
              </p>
            )}
          </div>

          <div className="ja-files-trash-actions">
            <AlertDialog.Cancel asChild>
              <button ref={cancelButtonRef} type="button" disabled={committing}>
                取消
              </button>
            </AlertDialog.Cancel>
            <button
              type="button"
              className="is-danger"
              disabled={phase !== "ready"}
              onClick={() => void onConfirm()}
            >
              {committing ? "正在移入…" : "移入回收站"}
            </button>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}
