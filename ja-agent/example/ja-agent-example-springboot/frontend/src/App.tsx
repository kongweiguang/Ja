// @author kongweiguang
import { lazy, Suspense, useEffect, useRef, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import {
  ArrowDownToLine,
  Check,
  LoaderCircle,
  MessageSquare,
  PanelLeft,
  PanelRight,
  Pause,
  Play,
  RefreshCw,
  X,
} from "lucide-react";
import { ConversationPane } from "./ConversationPane";
import { ThreadSidebar } from "./ThreadSidebar";
import { getSession, isTerminal } from "./state";
import type { ThreadView, WorkbenchTab } from "./types";
import { useWorkbench } from "./useWorkbench";

/** 能力编辑器属于次要界面，仅在用户明确打开后加载代码与数据。 */
const WorkbenchPanel = lazy(async () => {
  const module = await import("./WorkbenchPanel");
  return { default: module.WorkbenchPanel };
});
const loadingWorkbench = (
  <aside className="workbench-panel">
    <div className="workbench-loading" role="status">
      <LoaderCircle className="spin" />
      正在读取能力
    </div>
  </aside>
);

/** 保留网页宿主的真实能力，并沿用桌面端简洁的导航与对话层级。 */
export function App() {
  const workbench = useWorkbench();
  const { state, commit } = workbench;
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [renameTarget, setRenameTarget] = useState<ThreadView | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [renameBusy, setRenameBusy] = useState(false);
  const [renameError, setRenameError] = useState("");
  const [createBusy, setCreateBusy] = useState(false);
  const [busyThreadId, setBusyThreadId] = useState<string>();
  // 目录筛选不得卸载已打开的对话或清除其草稿。
  const selectedMetadata = useRef<ThreadView | undefined>(undefined);
  const listedThread = state.threads.find((thread) => thread.threadId === state.selectedThreadId);
  if (listedThread) selectedMetadata.current = listedThread;
  const selected =
    listedThread ??
    (selectedMetadata.current?.threadId === state.selectedThreadId
      ? selectedMetadata.current
      : undefined);
  const session = selected ? getSession(state, selected.threadId) : undefined;
  const activeTurn = session?.turns.at(-1);

  useEffect(() => {
    if (!state.sidebarOpen) return;
    /** Escape 仅关闭导航抽屉，服务端执行不受这一临时界面的开关影响。 */
    const closeDrawer = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      commit({ type: "patch", value: { sidebarOpen: false } });
      document.querySelector<HTMLButtonElement>(".topbar .mobile-only")?.focus();
    };
    window.addEventListener("keydown", closeDrawer);
    return () => window.removeEventListener("keydown", closeDrawer);
  }, [state.sidebarOpen, commit]);

  /** 创建操作异步执行，期间禁用可见入口，并由控制器保留稳定的操作标识。 */
  const createThread = async () => {
    if (createBusy) return;
    setCreateBusy(true);
    try {
      await workbench.createThread();
    } finally {
      setCreateBusy(false);
    }
  };

  /** 仅在明确打开能力界面时读取高成本数据，关闭界面不触发读取。 */
  const openWorkbench = (tab: WorkbenchTab = state.workbenchTab) => {
    workbench.commit({ type: "patch", value: { workbenchOpen: true, sidebarOpen: false } });
    void workbench.loadWorkbench(tab);
  };

  /** 归档遵守版本 CAS，并选中真实存在的剩余会话，不在本地伪造历史记录。 */
  const archiveThread = async (thread: ThreadView) => {
    setBusyThreadId(thread.threadId);
    try {
      await workbench.updateThread(thread, { archived: true });
      if (thread.threadId === state.selectedThreadId) {
        const next = state.threads.find(
          (item) => item.threadId !== thread.threadId && !item.archived,
        );
        if (next) workbench.selectThread(next.threadId);
        else await createThread();
      }
    } catch (error) {
      workbench.setNotice(`归档失败：${message(error)}`, "error");
    } finally {
      setBusyThreadId(undefined);
    }
  };

  /** 恢复操作仅修改元数据，保留服务端管理的原始标识和对话记录。 */
  const restoreThread = async (thread: ThreadView) => {
    setBusyThreadId(thread.threadId);
    try {
      await workbench.updateThread(thread, { archived: false });
      workbench.commit({ type: "patch", value: { archivedView: false } });
      workbench.selectThread(thread.threadId);
    } catch (error) {
      workbench.setNotice(`恢复会话失败：${message(error)}`, "error");
    } finally {
      setBusyThreadId(undefined);
    }
  };

  /** 失败时保留用户编辑；明确重试时使用刷新后的元数据，不绕过 CAS。 */
  const saveRename = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!renameTarget || !renameValue.trim() || renameBusy) return;
    setRenameBusy(true);
    setRenameError("");
    try {
      const observed =
        state.threads.find((thread) => thread.threadId === renameTarget.threadId) ?? renameTarget;
      await workbench.updateThread(observed, { title: renameValue.trim() });
      setRenameTarget(null);
    } catch (error) {
      setRenameError(`重命名失败：${message(error)}`);
    } finally {
      setRenameBusy(false);
    }
  };

  return (
    <div
      className={`app-shell ${state.workbenchOpen ? "with-workbench" : ""} ${sidebarCollapsed ? "sidebar-collapsed" : ""}`}
    >
      {state.sidebarOpen && (
        <button
          className="scrim sidebar-scrim"
          aria-label="关闭会话栏"
          onClick={() => workbench.commit({ type: "patch", value: { sidebarOpen: false } })}
        />
      )}
      <ThreadSidebar
        state={state}
        collapsed={sidebarCollapsed}
        createBusy={createBusy || state.loading}
        busyThreadId={busyThreadId}
        onCreate={() => void createThread()}
        onSelect={workbench.selectThread}
        onPatch={(value) => workbench.commit({ type: "patch", value })}
        onRename={(thread) => {
          setRenameTarget(thread);
          setRenameValue(thread.title);
          setRenameError("");
        }}
        onArchive={(thread) => void archiveThread(thread)}
        onRestore={(thread) => void restoreThread(thread)}
        onLoadMore={() => void workbench.loadMoreThreads()}
      />

      <main className="conversation-shell">
        <header className="topbar">
          <button
            className="icon-button desktop-only"
            title={sidebarCollapsed ? "打开会话栏" : "收起会话栏"}
            aria-label={sidebarCollapsed ? "打开会话栏" : "收起会话栏"}
            aria-expanded={!sidebarCollapsed}
            onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
          >
            <PanelLeft />
          </button>
          <button
            className="icon-button mobile-only"
            title="打开会话栏"
            aria-label="打开会话栏"
            aria-expanded={state.sidebarOpen}
            onClick={() => workbench.commit({ type: "patch", value: { sidebarOpen: true } })}
          >
            <PanelLeft />
          </button>
          <div className="topbar-heading">
            <MessageSquare aria-hidden="true" />
            <h1>{selected?.title || "新会话"}</h1>
            <span
              className="status-line visually-hidden"
              role="status"
              data-state={activeTurn?.state ?? "READY"}
            >
              {state.loading
                ? "正在连接 Ja"
                : activeTurn
                  ? activeTurn.state === "SUSPENDED" && session?.observing
                    ? "正在确认继续请求"
                    : statusLabel(activeTurn.state)
                  : "已就绪"}
            </span>
          </div>
          <div className="topbar-actions">
            {activeTurn && isTerminal(activeTurn.state) && (
              <button
                className="icon-button"
                title="压缩上下文"
                aria-label="压缩上下文"
                onClick={() =>
                  void workbench
                    .compact(selected!.threadId, activeTurn)
                    .catch((error) => workbench.setNotice(message(error), "error"))
                }
              >
                <ArrowDownToLine />
              </button>
            )}
            {activeTurn &&
              !activeTurn.resumeRequired &&
              ["QUEUED", "RUNNING"].includes(activeTurn.state) && (
                <button
                  className="icon-button"
                  title="暂停执行"
                  aria-label="暂停执行"
                  onClick={() =>
                    void workbench.controlTurn(selected!.threadId, activeTurn, "pause")
                  }
                >
                  <Pause />
                </button>
              )}
            {activeTurn?.state === "SUSPENDED" &&
              !session?.observing &&
              !activeTurn.recoveries.length &&
              !activeTurn.recoveryError && (
                <button
                  className="icon-button"
                  title="继续执行"
                  aria-label="继续执行"
                  onClick={() =>
                    void workbench
                      .resumeTurn(selected!.threadId, activeTurn)
                      .catch((error) =>
                        workbench.setNotice(`继续执行失败：${message(error)}`, "error"),
                      )
                  }
                >
                  <Play />
                </button>
              )}
            {activeTurn?.state === "UNKNOWN" && (
              <button
                className="icon-button"
                title="重新检查执行状态"
                aria-label="重新检查执行状态"
                onClick={() => void workbench.loadHistory(selected!.threadId)}
              >
                <RefreshCw />
              </button>
            )}
            {state.experienceEnabled && (
              <button
                className={`icon-button ${state.workbenchOpen ? "is-selected" : ""}`}
                title={state.workbenchOpen ? "关闭能力面板" : "打开能力面板"}
                aria-label={state.workbenchOpen ? "关闭能力面板" : "打开能力面板"}
                aria-expanded={state.workbenchOpen}
                onClick={() =>
                  state.workbenchOpen
                    ? workbench.commit({ type: "patch", value: { workbenchOpen: false } })
                    : openWorkbench()
                }
              >
                <PanelRight />
              </button>
            )}
          </div>
        </header>

        {state.notice && (
          <div
            className={`toast toast-${state.notice.tone}`}
            role={state.notice.tone === "error" ? "alert" : "status"}
          >
            <span>{state.notice.text}</span>
            <button
              className="icon-button"
              title="关闭通知"
              aria-label="关闭通知"
              onClick={() => workbench.setNotice("")}
            >
              <X />
            </button>
          </div>
        )}
        {state.loadingError && (
          <div className="connection-error" role="alert">
            <span>{state.loadingError}</span>
            <button className="text-button" onClick={() => void workbench.refreshThreads()}>
              重试
            </button>
          </div>
        )}
        {selected && session ? (
          <ConversationPane
            key={selected.threadId}
            threadId={selected.threadId}
            session={session}
            model={state.model}
            agentName={
              state.agents.find((agent) => agent.id === selected.agentId)?.name ?? selected.agentId
            }
            experienceEnabled={state.experienceEnabled}
            attachmentCapabilities={state.attachmentCapabilities}
            pendingRecoveries={state.pendingRecoveries}
            onRefreshRecoveries={workbench.refreshRecoveries}
            readOnly={selected.archived}
            pending={state.pending[selected.threadId]}
            onLoadMoreHistory={workbench.loadMoreHistory}
            onSubmit={workbench.submit}
            onUploadFiles={workbench.addAttachments}
            onCancelAttachment={workbench.cancelAttachmentUpload}
            onRetryAttachment={workbench.retryAttachment}
            onRemoveAttachment={workbench.removeAttachment}
            onSessionChange={workbench.updateSession}
            onAnswer={workbench.answerAndResume}
            onResume={workbench.resumeTurn}
            onError={(error) => workbench.setNotice(`继续执行失败：${message(error)}`, "error")}
            onRetry={workbench.retrySubmission}
            onControl={workbench.controlTurn}
            onWorkbench={() => openWorkbench("skills")}
            onResolveRecovery={workbench.resolveRecovery}
          />
        ) : (
          <div className="conversation-empty">
            {state.loading ? (
              <LoaderCircle className="spin" aria-label="正在连接 Ja" />
            ) : (
              <>
                <h2>Ja</h2>
                <button
                  className="text-button"
                  disabled={createBusy}
                  onClick={() => void createThread()}
                >
                  新会话
                </button>
              </>
            )}
          </div>
        )}
      </main>
      {state.workbenchOpen && (
        <Suspense fallback={loadingWorkbench}>
          <WorkbenchPanel
            state={state}
            onClose={() => workbench.commit({ type: "patch", value: { workbenchOpen: false } })}
            onTab={(tab) => void workbench.loadWorkbench(tab)}
            onReload={() => void workbench.loadWorkbench(state.workbenchTab)}
            onLoadMoreHistory={workbench.loadMoreHistory}
            onNotice={workbench.setNotice}
            selectedSkills={selected ? (session?.selectedSkills ?? []) : []}
            onSkillsChange={(selectedSkills) =>
              selected && workbench.updateSession(selected.threadId, { selectedSkills })
            }
          />
        </Suspense>
      )}
      <Dialog.Root
        open={Boolean(renameTarget)}
        onOpenChange={(open) => {
          if (!open && !renameBusy) setRenameTarget(null);
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="modal-backdrop" />
          <Dialog.Content className="rename-dialog" aria-describedby={undefined}>
            <form onSubmit={saveRename}>
              <div className="dialog-heading">
                <Dialog.Title>重命名会话</Dialog.Title>
                <Dialog.Close
                  className="icon-button"
                  title="关闭"
                  aria-label="关闭"
                  disabled={renameBusy}
                >
                  <X />
                </Dialog.Close>
              </div>
              <label className="field-label" htmlFor="rename-input">
                会话名称
              </label>
              <input
                id="rename-input"
                maxLength={120}
                value={renameValue}
                onChange={(event) => setRenameValue(event.target.value)}
                required
                disabled={renameBusy}
              />
              {renameError && (
                <p className="turn-error rename-error" role="alert">
                  {renameError}
                </p>
              )}
              <div className="dialog-actions">
                <Dialog.Close className="text-button" type="button" disabled={renameBusy}>
                  取消
                </Dialog.Close>
                <button
                  className="primary-button"
                  type="submit"
                  disabled={renameBusy || !renameValue.trim()}
                >
                  {renameBusy ? (
                    <>
                      <LoaderCircle className="spin" />
                      保存中
                    </>
                  ) : (
                    <>
                      <Check />
                      保存
                    </>
                  )}
                </button>
              </div>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}

/** 只展示传输层的简短错误说明，不把任意响应对象当作界面文案。 */
function message(error: unknown): string {
  return error instanceof Error ? error.message : "操作失败。";
}

/** 为屏幕阅读器保留执行状态反馈，避免重复展示时间线已有的工作指示。 */
function statusLabel(state: string): string {
  switch (state) {
    case "QUEUED":
    case "RUNNING":
      return "正在工作";
    case "WAITING_APPROVAL":
      return "等待确认";
    case "WAITING_INPUT":
      return "等待你的回答";
    case "SUSPENDED":
      return "已暂停";
    case "COMPLETED":
      return "已完成";
    case "FAILED":
      return "执行失败";
    case "CANCELLED":
      return "已取消";
    case "HISTORY":
      return "历史记录";
    default:
      return "状态未确认";
  }
}
