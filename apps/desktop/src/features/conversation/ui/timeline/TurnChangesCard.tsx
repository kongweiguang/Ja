// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronDown, FileDiff } from "lucide-react";
import { useState, type ReactElement } from "react";
import { Button } from "@/shared/ui/primitives";
import { type TimelineTurn, type TurnChangeSet } from "../../domain/timelineTypes";

export interface TurnChangesCardProps {
  turn: TimelineTurn;
  changeSet: TurnChangeSet;
  onReview?: (requestedPath?: string) => void;
}

const CHANGE_STATUS_LABELS = {
  added: "新增",
  modified: "修改",
  deleted: "删除",
} as const;

const COLLAPSED_FILE_LIMIT = 3;

/**
 * 修改卡消费终态冻结 ChangeSet；partial 明示不完整性，零修改不占用答复后的阅读空间。
 */
export function TurnChangesCard({
  changeSet,
  onReview,
}: TurnChangesCardProps): ReactElement | null {
  const canReview = changeSet.artifactId !== undefined && onReview !== undefined;
  const [expanded, setExpanded] = useState(false);

  if (changeSet.stats.files === 0) return null;

  const hiddenFileCount = Math.max(0, changeSet.files.length - COLLAPSED_FILE_LIMIT);
  const visibleFiles = expanded ? changeSet.files : changeSet.files.slice(0, COLLAPSED_FILE_LIMIT);

  return (
    <section className="ja-turn-changes" aria-label="修改记录">
      <header className="ja-turn-changes__header">
        <FileDiff aria-hidden="true" />
        <strong>
          {changeSet.state === "complete"
            ? `已编辑 ${changeSet.stats.files} 个文件`
            : `已确认 ${changeSet.stats.files} 个文件，可能不完整`}
        </strong>
        <span className="ja-turn-changes__stats">
          <span className="is-addition">+{changeSet.stats.additions}</span>
          <span className="is-deletion">−{changeSet.stats.deletions}</span>
        </span>
        {canReview ? (
          <Button type="button" variant="secondary" size="sm" onClick={() => onReview()}>
            查看修改
          </Button>
        ) : null}
      </header>
      <ul className="ja-turn-changes__files">
        {visibleFiles.map((file) => (
          <li key={file.path}>
            {canReview ? (
              <button
                type="button"
                aria-label={`查看 ${file.path} 的修改`}
                onClick={() => onReview(file.path)}
              >
                <span className={`is-${file.status}`}>{CHANGE_STATUS_LABELS[file.status]}</span>
                <code title={file.path}>{file.path}</code>
                {file.binary ? (
                  <small>二进制</small>
                ) : (
                  <small>
                    +{file.additions} −{file.deletions}
                  </small>
                )}
              </button>
            ) : (
              <div>
                <span className={`is-${file.status}`}>{CHANGE_STATUS_LABELS[file.status]}</span>
                <code title={file.path}>{file.path}</code>
                {file.binary ? (
                  <small>二进制</small>
                ) : (
                  <small>
                    +{file.additions} −{file.deletions}
                  </small>
                )}
              </div>
            )}
          </li>
        ))}
      </ul>
      {hiddenFileCount > 0 ? (
        <button
          type="button"
          className="ja-turn-changes__expand"
          aria-expanded={expanded}
          onClick={() => setExpanded((current) => !current)}
        >
          <span>{expanded ? "收起文件" : `再显示 ${hiddenFileCount} 个文件`}</span>
          <ChevronDown aria-hidden="true" />
        </button>
      ) : null}
      {changeSet.stats.truncated ? <p>文件列表或统计仅显示安全范围内的内容。</p> : null}
    </section>
  );
}
