// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useState, type ReactElement } from "react";
import { FolderOpen, RefreshCw } from "lucide-react";
import { Button } from "@/shared/ui/primitives";
import {
  canRetryStorageRecovery,
  storageRecoveryMessage,
  type StorageRecovery,
} from "@/shared/runtime/storageRecovery";
import { SectionHeader } from "./shared";

export interface DataStorageProps {
  info?: {
    dataPath: string;
    logPath: string | null;
    storageRecovery?: StorageRecovery;
  };
  recovery?: StorageRecovery;
  error?: string;
  onReload?: () => Promise<void>;
  onOpenBackup?: (id: string) => Promise<void>;
  onRetry?: () => Promise<void>;
}

/** 运行时恢复裁决独立于设置与目录查询，阻断时仍保留人工动作和原始文件事实。 */
export function DataStorageSection({
  info,
  recovery,
  error,
  onReload,
  onOpenBackup,
  onRetry,
}: DataStorageProps): ReactElement {
  const [pending, setPending] = useState<"reload" | "backup" | "retry">();
  const [feedback, setFeedback] = useState<string>();
  const currentRecovery = recovery ?? info?.storageRecovery;
  /** 一次只执行一个动作，防止重复点击并保留权威结果。 */
  const run = async (action: "reload" | "backup" | "retry"): Promise<void> => {
    if (pending !== undefined) return;
    setPending(action);
    setFeedback(undefined);
    try {
      if (action === "reload") await onReload?.();
      else if (action === "retry") await onRetry?.();
      else if (currentRecovery !== undefined) await onOpenBackup?.(currentRecovery.id);
      setFeedback(
        action === "backup" ? "已打开备份目录" : action === "retry" ? "已恢复使用" : "已重新读取",
      );
    } catch {
      setFeedback(
        action === "backup"
          ? "未能打开备份目录，请重试"
          : action === "retry"
            ? "仍未恢复，请检查上述原因后重试"
            : "读取失败，请重试",
      );
    } finally {
      setPending(undefined);
    }
  };
  return (
    <section
      className="ja-settings-section"
      data-setting-id="storage-data"
      data-setting-search="数据 存储 恢复 历史 备份"
    >
      <SectionHeader title="数据与存储" />
      <div className="ja-storage-details">
        <dl>
          {info === undefined ? null : (
            <>
              <dt>数据目录</dt>
              <dd>{info.dataPath}</dd>
              {info.logPath === null ? null : (
                <>
                  <dt>日志目录</dt>
                  <dd>{info.logPath}</dd>
                </>
              )}
            </>
          )}
          <dt>最近恢复</dt>
          <dd>
            {currentRecovery !== undefined
              ? storageRecoveryMessage(currentRecovery)
              : (error ?? (info === undefined ? "正在读取…" : "未发生数据恢复"))}
          </dd>
          {currentRecovery?.phase === "ready" ? (
            <>
              <dt>历史记录</dt>
              <dd>{currentRecovery.historyAvailable ? "可用" : "暂不可用"}</dd>
            </>
          ) : null}
        </dl>
        {error !== undefined && currentRecovery !== undefined ? (
          <p className="ja-inline-error" role="alert">
            {error}
          </p>
        ) : null}
        <div className="ja-storage-actions">
          {currentRecovery !== undefined &&
          canRetryStorageRecovery(currentRecovery) &&
          onRetry !== undefined ? (
            <Button
              variant="secondary"
              disabled={pending !== undefined}
              onClick={() => void run("retry")}
            >
              <RefreshCw size={16} aria-hidden="true" />
              {pending === "retry" ? "正在恢复…" : "重试"}
            </Button>
          ) : null}
          {currentRecovery?.backupAvailable && onOpenBackup !== undefined ? (
            <Button
              variant="secondary"
              disabled={pending !== undefined}
              onClick={() => void run("backup")}
            >
              <FolderOpen size={16} aria-hidden="true" />
              {pending === "backup" ? "正在打开…" : "打开备份"}
            </Button>
          ) : null}
          {onReload === undefined ? null : (
            <Button
              variant="secondary"
              disabled={pending !== undefined}
              onClick={() => void run("reload")}
            >
              <RefreshCw size={16} aria-hidden="true" />
              {pending === "reload" ? "正在读取…" : "重新读取"}
            </Button>
          )}
        </div>
        {feedback === undefined ? null : <p role="status">{feedback}</p>}
      </div>
    </section>
  );
}
