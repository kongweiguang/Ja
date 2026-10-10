// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronDown, FileDiff } from "lucide-react";
import { useId, useState, type ReactElement } from "react";
import { Button } from "@/shared/ui/primitives";
import {
  type TimelineTurn,
  type TurnChangeFile,
  type TurnChangeSet,
} from "../../domain/timelineTypes";
import type { TimelineDisclosureCache } from "./timelineDisclosure";

export interface TurnChangesCardProps {
  turn: TimelineTurn;
  changeSet: TurnChangeSet;
  onReview?: (requestedPath?: string) => void;
  /** 只保存用户展开意图，不为卡片建立另一份持久化修改事实。 */
  disclosureCache?: TimelineDisclosureCache;
}

const CHANGE_STATUS_LABELS = {
  added: "新增",
  modified: "修改",
  deleted: "删除",
} as const;

const COLLAPSED_FILE_LIMIT = 3;

/** 数字共用一种排版和增删语义，摘要始终取服务端冻结统计而非当前可见文件之和。 */
function ChangeStats({
  additions,
  deletions,
}: {
  additions: number;
  deletions: number;
}): ReactElement {
  return (
    <span className="ja-turn-changes__stats">
      <span className="is-addition">+{additions}</span>
      <span className="is-deletion">−{deletions}</span>
    </span>
  );
}

/** 路径优先保留文件名；按钮和静态行共享正文，缺少可审核身份时不会产生空操作入口。 */
function FileChangeRow({
  file,
  onReview,
}: {
  file: TurnChangeFile;
  onReview?: (requestedPath?: string) => void;
}): ReactElement {
  const separator = file.path.lastIndexOf("/") + 1;
  const status = CHANGE_STATUS_LABELS[file.status];
  const title = `${status}：${file.path}${file.truncated ? "（记录不完整）" : ""}`;
  const content = (
    <>
      <span className="ja-turn-changes__path">
        <span className="ja-visually-hidden">{status}：</span>
        {separator > 0 ? (
          <span className="ja-turn-changes__directory">{file.path.slice(0, separator)}</span>
        ) : null}
        <span className="ja-turn-changes__filename">{file.path.slice(separator)}</span>
      </span>
      {file.binary ? (
        <span className="ja-turn-changes__binary">二进制</span>
      ) : (
        <ChangeStats additions={file.additions} deletions={file.deletions} />
      )}
    </>
  );
  return onReview === undefined ? (
    <div className="ja-turn-changes__file" title={title}>
      {content}
    </div>
  ) : (
    <button
      type="button"
      className="ja-turn-changes__file"
      title={title}
      aria-label={`查看 ${file.path} 的修改，${status}`}
      onClick={() => onReview(file.path)}
    >
      {content}
    </button>
  );
}

/**
 * 只投影终态冻结 ChangeSet；展开意图按 Thread/Turn 隔离并复用既有缓存，虚拟卸载不丢失选择。
 * 查看入口与目标构建所需的 revision 一致，partial 与截断合并说明，可靠零修改不占阅读空间。
 */
export function TurnChangesCard({
  turn,
  changeSet,
  onReview,
  disclosureCache,
}: TurnChangesCardProps): ReactElement | null {
  const canReview =
    changeSet.artifactId !== undefined &&
    turn.threadRevision !== undefined &&
    onReview !== undefined;
  const identity = `${turn.threadId}:${turn.turnId}`;
  const filesId = useId();
  const [disclosure, setDisclosure] = useState<{ identity: string; expanded: boolean }>();
  const expanded =
    disclosureCache?.get(turn.threadId, "changes", turn.turnId) ??
    (disclosure?.identity === identity && disclosure.expanded);

  /** 只在用户动作中提交缓存与重绘意图，避免历史回读、切换或普通渲染自动改写展开选择。 */
  function toggleFiles(): void {
    const nextExpanded = !expanded;
    disclosureCache?.set(turn.threadId, "changes", turn.turnId, nextExpanded);
    setDisclosure({ identity, expanded: nextExpanded });
  }

  if (changeSet.stats.files === 0) return null;

  const hiddenFileCount = Math.max(0, changeSet.files.length - COLLAPSED_FILE_LIMIT);
  const visibleFiles = expanded ? changeSet.files : changeSet.files.slice(0, COLLAPSED_FILE_LIMIT);
  const incomplete = changeSet.state === "partial" || changeSet.stats.truncated;

  return (
    <section className="ja-turn-changes" aria-label="修改记录">
      <header className="ja-turn-changes__header">
        <span className="ja-turn-changes__icon" aria-hidden="true">
          <FileDiff />
        </span>
        <div className="ja-turn-changes__summary">
          <strong>
            {changeSet.state === "complete"
              ? `已编辑 ${changeSet.stats.files} 个文件`
              : `已确认 ${changeSet.stats.files} 个文件`}
          </strong>
          <ChangeStats
            additions={changeSet.stats.additions}
            deletions={changeSet.stats.deletions}
          />
        </div>
        {canReview ? (
          <Button type="button" variant="secondary" size="sm" onClick={() => onReview()}>
            查看修改
          </Button>
        ) : null}
      </header>
      <ul className="ja-turn-changes__files" id={filesId}>
        {visibleFiles.map((file) => (
          <li key={file.path}>
            <FileChangeRow file={file} onReview={canReview ? onReview : undefined} />
          </li>
        ))}
      </ul>
      {hiddenFileCount > 0 ? (
        <button
          type="button"
          className="ja-turn-changes__expand"
          aria-expanded={expanded}
          aria-controls={filesId}
          onClick={toggleFiles}
        >
          <span>{expanded ? "收起文件" : `再显示 ${hiddenFileCount} 个文件`}</span>
          <ChevronDown aria-hidden="true" />
        </button>
      ) : null}
      {incomplete ? <p>仅展示已确认的修改，文件列表或统计可能不完整。</p> : null}
    </section>
  );
}
