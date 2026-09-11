// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Bot, ChevronRight, Circle, GitBranch, RefreshCw } from "lucide-react";
import type { CSSProperties, ReactElement } from "react";
import { Button, EmptyState, ErrorState } from "@/shared/ui/primitives";
import type { TaskSummary } from "../domain/taskModel";
import { taskElapsedLabel, taskStateLabel } from "../domain/taskModel";
import {
  buildTaskOverviewSections,
  selectDelegatedTasks,
  type TaskTreeNode,
} from "../domain/taskOverview";
import "./tasks.css";

/** 递归列表使用 treeitem 后紧邻 group 的标准结构，键盘焦点只落在可点击行。 */
function TaskTreeItems({
  nodes,
  level,
  onOpenTask,
}: {
  nodes: readonly TaskTreeNode[];
  level: number;
  onOpenTask: (task: TaskSummary) => void;
}): ReactElement {
  return (
    <>
      {nodes.map((node, index) => {
        const { task } = node;
        const elapsed = taskElapsedLabel(task);
        return (
          <li key={task.taskThreadId} role="none">
            <button
              type="button"
              role="treeitem"
              aria-level={level}
              aria-posinset={index + 1}
              aria-setsize={nodes.length}
              aria-expanded={node.children.length > 0 ? true : undefined}
              className="ja-task-tree-row"
              style={{ "--task-depth": level - 1 } as CSSProperties}
              onClick={() => onOpenTask(task)}
            >
              <span className="ja-task-tree-branch">
                {level > 1 ? <GitBranch aria-hidden="true" /> : <Bot aria-hidden="true" />}
              </span>
              <span className="ja-task-tree-main">
                <span className="ja-task-tree-title">
                  <strong>{task.taskName}</strong>
                  <small>{task.taskKind === "subagent" ? "Subagent" : "侧聊"}</small>
                </span>
                <span className="ja-task-tree-summary">
                  {task.latestSafeSummary ?? "等待首次安全进度…"}
                </span>
                <span className="ja-task-tree-meta">
                  <Circle aria-hidden="true" data-state={task.state} />
                  {taskStateLabel(task.state)}
                  {elapsed === undefined ? null : ` · ${elapsed}`}
                </span>
              </span>
              {task.unreadCount > 0 ? (
                <span className="ja-task-unread" aria-label={`${task.unreadCount} 条未读`}>
                  {Math.min(99, task.unreadCount)}
                </span>
              ) : null}
              <ChevronRight aria-hidden="true" />
            </button>
            {node.children.length === 0 ? null : (
              <ul className="ja-task-tree-group" role="group">
                <TaskTreeItems nodes={node.children} level={level + 1} onOpenTask={onOpenTask} />
              </ul>
            )}
          </li>
        );
      })}
    </>
  );
}

/** 树形总览只投影服务端摘要，绝不为未选中实例读取正文或建立 observe。 */
export function SubagentOverview({
  tasks,
  ownerThreadId,
  loading,
  error,
  onRefresh,
  onOpenTask,
}: {
  tasks: readonly TaskSummary[];
  ownerThreadId: string | undefined;
  loading: boolean;
  error?: string;
  onRefresh: () => Promise<void>;
  onOpenTask: (task: TaskSummary) => void;
}): ReactElement {
  if (error !== undefined && tasks.length === 0)
    return <ErrorState title="子智能体暂不可用" message={error} onRetry={() => void onRefresh()} />;
  const sections = buildTaskOverviewSections(selectDelegatedTasks(tasks, ownerThreadId));
  return (
    <section className="ja-task-overview" aria-label="子智能体总览" aria-busy={loading}>
      <header className="ja-task-overview-header">
        <div>
          <h2>子智能体</h2>
          <p>{tasks.length === 0 ? "当前主任务还没有派生任务" : `共 ${tasks.length} 个后代任务`}</p>
        </div>
        <Button type="button" variant="ghost" size="sm" onClick={() => void onRefresh()}>
          <RefreshCw aria-hidden="true" />
          刷新
        </Button>
      </header>
      {tasks.length === 0 && !loading ? (
        <EmptyState title="暂无子智能体" description="Agent 派发后会自动出现在这里。" />
      ) : (
        <div className="ja-task-sections">
          {sections.map((section) => (
            <section
              key={section.key}
              className="ja-task-section"
              aria-labelledby={`task-${section.key}`}
            >
              <h3 id={`task-${section.key}`}>{section.label}</h3>
              <ul className="ja-task-tree" role="tree" aria-labelledby={`task-${section.key}`}>
                <TaskTreeItems nodes={section.roots} level={1} onOpenTask={onOpenTask} />
              </ul>
            </section>
          ))}
        </div>
      )}
    </section>
  );
}
