// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { FileDiff, LoaderCircle, X } from "lucide-react";
import { useState, type ReactElement } from "react";
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  IconButton,
} from "@/shared/ui/primitives";
import { type TimelineTurn, type TurnChangeSet } from "../../domain/timelineTypes";

type AvailableTurnChangeSet = Extract<TurnChangeSet, { state: "available" }>;

export interface TurnChangesCardProps {
  turn: TimelineTurn;
  changeSet: AvailableTurnChangeSet;
  onReadDiff?: (input: { threadId: string; turnId: string; artifactId: string }) => Promise<string>;
}

const CHANGE_STATUS_LABELS = {
  added: "新增",
  modified: "修改",
  deleted: "删除",
  renamed: "重命名",
} as const;

/**
 * 修改卡只消费已可靠归因且非零的冻结 TurnChangeSet；零修改和不可用状态不占用答复后的阅读空间。
 */
export function TurnChangesCard({
  turn,
  changeSet,
  onReadDiff,
}: TurnChangesCardProps): ReactElement | null {
  const [open, setOpen] = useState(false);
  const [diff, setDiff] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const canReview =
    changeSet.state === "available" &&
    changeSet.artifactId !== undefined &&
    onReadDiff !== undefined;

  /** 点击后才读取已持久化 unified diff，避免 Timeline 首屏加载大文本或读取漂移工作树。 */
  const openReview = async (): Promise<void> => {
    if (!canReview || changeSet.artifactId === undefined || onReadDiff === undefined) return;
    setOpen(true);
    if (diff !== undefined || loading) return;
    setLoading(true);
    setError(undefined);
    try {
      setDiff(
        await onReadDiff({
          threadId: turn.threadId,
          turnId: turn.turnId,
          artifactId: changeSet.artifactId,
        }),
      );
    } catch {
      setError("无法读取本轮冻结差异，请稍后重试。");
    } finally {
      setLoading(false);
    }
  };

  if (changeSet.stats.files === 0) return null;

  return (
    <section className="ja-turn-changes" aria-label="修改记录">
      <header className="ja-turn-changes__header">
        <FileDiff aria-hidden="true" />
        <strong>{changeSet.stats.files} 个文件发生修改</strong>
        <span className="ja-turn-changes__stats">
          <span className="is-addition">+{changeSet.stats.additions}</span>
          <span className="is-deletion">−{changeSet.stats.deletions}</span>
        </span>
        {canReview ? (
          <Button type="button" variant="ghost" size="sm" onClick={() => void openReview()}>
            审查修改
          </Button>
        ) : null}
      </header>
      <ul className="ja-turn-changes__files">
        {changeSet.files.map((file) => (
          <li key={`${file.oldPath ?? ""}:${file.path}`}>
            <span className={`is-${file.status}`}>{CHANGE_STATUS_LABELS[file.status]}</span>
            <code title={file.path}>
              {file.oldPath === undefined ? file.path : `${file.oldPath} → ${file.path}`}
            </code>
            {file.binary ? (
              <small>二进制</small>
            ) : (
              <small>
                {file.additions === undefined ? "" : `+${file.additions}`}
                {file.deletions === undefined ? "" : ` −${file.deletions}`}
              </small>
            )}
          </li>
        ))}
      </ul>
      {changeSet.stats.truncated ? <p>文件列表或统计仅显示安全范围内的内容。</p> : null}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="ja-turn-diff-dialog">
          <div className="ja-turn-diff-dialog__header">
            <div>
              <DialogTitle>本轮修改</DialogTitle>
              <DialogDescription>这是 Turn 完成时持久化的冻结差异。</DialogDescription>
            </div>
            <DialogClose asChild>
              <IconButton label="关闭修改审查">
                <X aria-hidden="true" />
              </IconButton>
            </DialogClose>
          </div>
          {loading ? (
            <p className="ja-turn-diff-dialog__status" role="status">
              <LoaderCircle aria-hidden="true" /> 正在读取差异…
            </p>
          ) : error !== undefined ? (
            <p className="ja-turn-diff-dialog__error" role="alert">
              {error}
            </p>
          ) : (
            <pre aria-label="Unified diff">{diff || "本轮没有文本差异。"}</pre>
          )}
        </DialogContent>
      </Dialog>
    </section>
  );
}
