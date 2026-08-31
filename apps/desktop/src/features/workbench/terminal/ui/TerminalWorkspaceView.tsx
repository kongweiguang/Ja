// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Columns2, Plus, RotateCcw, Rows2, X } from "lucide-react";
import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactElement,
  type RefObject,
} from "react";
import { TerminalPanel } from "./TerminalPanel";
import type {
  TerminalDropFailure,
  TerminalPaneRuntime,
  TerminalWorkspaceController,
  TerminalWorkspaceViewController,
} from "../application";
import {
  MAX_SPLIT_RATIO,
  MAX_TERMINAL_RELATIVE_CWD_LENGTH,
  MIN_SPLIT_RATIO,
  clampSplitRatio,
  isValidTerminalRelativeCwd,
  type TerminalLayoutNode,
  type TerminalPaneLayout,
  type TerminalProfile,
  type TerminalSplitLayout,
  type TerminalTabCreateOptions,
  type TerminalTabLayout,
} from "../domain";
import { IconButton, Select } from "@/shared/ui/primitives";
import "./TerminalWorkspace.css";

const TERMINAL_CREATOR_ID = "ja-terminal-tab-creator";
const TERMINAL_CREATOR_CWD_HINT_ID = "ja-terminal-tab-creator-cwd-hint";
const TERMINAL_CREATOR_CWD_ERROR_ID = "ja-terminal-tab-creator-cwd-error";
const TERMINAL_PROFILE_LABELS: Readonly<Record<TerminalProfile, string>> = {
  default: "系统默认",
  power_shell: "PowerShell",
  cmd: "命令提示符",
  bash: "Bash",
  zsh: "Zsh",
  fish: "Fish",
};
export interface TerminalWorkspaceViewProps {
  className?: string;
  active: boolean;
  controller: TerminalWorkspaceViewController;
  rootRef: RefObject<HTMLElement | null>;
}

/**
 * 纯 UI 只渲染 controller 投影与发出用户动作；native adapter、订阅和 token
 * 生命周期由 application controller 负责，短暂创建器和拖拽预览仍留在视图层。
 */
export function TerminalWorkspaceView({
  className,
  active,
  controller,
  rootRef,
}: TerminalWorkspaceViewProps): ReactElement {
  const [creatorOpen, setCreatorOpen] = useState(false);
  const activeTabId = controller.layout.activeTabId;
  const creatorTriggerRef = useRef<HTMLButtonElement | null>(null);
  const addTabLabel = controller.closeAllPending
    ? "终端工作区正在关闭"
    : controller.profilesStatus === "loading"
      ? "正在检测可用终端环境"
      : controller.profilesStatus === "failed" || controller.profiles.length === 0
        ? "没有可用的终端环境"
        : controller.canAddTab
          ? "新建终端标签页"
          : "已达到 8 个终端窗格上限";

  /** 打开可编辑创建器前重查实时窗格预算，避免旧按钮越界。 */
  const openTerminalCreator = (event: ReactMouseEvent<HTMLButtonElement>): void => {
    if (!controller.canAddTab) return;
    creatorTriggerRef.current = event.currentTarget;
    setCreatorOpen(true);
  };

  /** 只丢弃 renderer 本地草稿，并把键盘焦点恢复到仍存在的终端控件。 */
  const closeTerminalCreator = (): void => {
    setCreatorOpen(false);
    queueMicrotask(() => {
      const trigger = creatorTriggerRef.current;
      if (trigger !== null && trigger.isConnected && !trigger.disabled) {
        trigger.focus();
        return;
      }
      rootRef.current
        ?.querySelector<HTMLButtonElement>('[role="tab"][aria-selected="true"]')
        ?.focus();
    });
  };

  const rootClass =
    className === undefined ? "ja-terminal-workspace" : `ja-terminal-workspace ${className}`;
  return (
    <section className={rootClass} aria-label="终端工作区" ref={rootRef}>
      <header className="ja-terminal-tab-strip">
        <div className="ja-terminal-tabs" role="tablist" aria-label="终端标签页">
          {controller.layout.tabs.map((tab) => (
            <TerminalTab
              key={tab.tabId}
              tab={tab}
              active={tab.tabId === activeTabId}
              controller={controller}
            />
          ))}
        </div>
        <IconButton
          className="ja-terminal-add-tab"
          label={addTabLabel}
          aria-haspopup="dialog"
          aria-expanded={creatorOpen && controller.canAddTab}
          aria-controls={TERMINAL_CREATOR_ID}
          disabled={!controller.canAddTab}
          onClick={openTerminalCreator}
        >
          <Plus aria-hidden="true" size={15} />
        </IconButton>
      </header>
      {creatorOpen && controller.canAddTab ? (
        <TerminalTabCreator
          supportedProfiles={controller.profiles}
          onCreate={controller.addTab}
          onDismiss={closeTerminalCreator}
        />
      ) : null}
      {activeTabId === null ? (
        <div className="ja-terminal-empty">
          <p>没有打开的终端标签页。</p>
          <button
            type="button"
            aria-haspopup="dialog"
            aria-expanded={creatorOpen && controller.canAddTab}
            aria-controls={TERMINAL_CREATOR_ID}
            disabled={!controller.canAddTab}
            onClick={openTerminalCreator}
          >
            <Plus aria-hidden="true" size={15} />
            新建终端
          </button>
        </div>
      ) : (
        <div className="ja-terminal-tab-content">
          {controller.layout.tabs.map((tab) => (
            <div
              key={tab.tabId}
              id={`ja-terminal-panel-${tab.tabId}`}
              role="tabpanel"
              aria-label={tab.title}
              hidden={tab.tabId !== activeTabId}
              className="ja-terminal-tab-panel"
            >
              <TerminalNodeView
                node={tab.root}
                tab={tab}
                active={active && tab.tabId === activeTabId}
                controller={controller}
                runtimes={controller.runtimes}
                dropFailure={controller.dropFailure}
                onDismissDropFailure={controller.dismissDropFailure}
              />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/** 创建器只枚举 Rust 返回项；取消或 Escape 仍只丢弃本地草稿。 */
function TerminalTabCreator({
  supportedProfiles,
  onCreate,
  onDismiss,
}: {
  supportedProfiles: readonly TerminalProfile[];
  onCreate: (options?: TerminalTabCreateOptions) => boolean;
  onDismiss: () => void;
}): ReactElement {
  const [profile, setProfile] = useState<TerminalProfile>(() =>
    supportedProfiles.includes("default") ? "default" : supportedProfiles[0]!,
  );
  const [relativeCwd, setRelativeCwd] = useState("");
  const [error, setError] = useState<string>();

  /** 提交前校验输入，防止非法 cwd 被静默解释为工作区根目录。 */
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!isValidTerminalRelativeCwd(relativeCwd)) {
      setError(
        `请输入工作区内的相对目录，不能使用盘符、绝对路径或 ..，且不能超过 ${MAX_TERMINAL_RELATIVE_CWD_LENGTH} 个字符。`,
      );
      return;
    }
    const created = onCreate({
      profile,
      ...(relativeCwd.trim().length === 0 ? {} : { relativeCwd }),
    });
    if (!created) {
      setError("终端未创建：输入无效或已达到 8 个终端窗格上限。");
      return;
    }
    onDismiss();
  };

  /** 为键盘用户提供零副作用 Escape 路径，且不提交外围表单。 */
  const dismissOnEscape = (event: KeyboardEvent<HTMLFormElement>): void => {
    if (event.key !== "Escape") return;
    event.preventDefault();
    event.stopPropagation();
    onDismiss();
  };

  return (
    <form
      id={TERMINAL_CREATOR_ID}
      className="ja-terminal-creator"
      role="dialog"
      aria-modal="false"
      aria-labelledby={`${TERMINAL_CREATOR_ID}-title`}
      onSubmit={submit}
      onKeyDown={dismissOnEscape}
    >
      <div className="ja-terminal-creator-heading">
        <strong id={`${TERMINAL_CREATOR_ID}-title`}>新建终端</strong>
        <span>选择启动环境，默认从工作区根目录开始。</span>
      </div>
      <div className="ja-terminal-creator-field">
        <span>Shell profile</span>
        <Select
          autoFocus
          ariaLabel="Shell profile"
          className="ja-terminal-profile-select"
          value={profile}
          options={supportedProfiles.map((value) => ({
            value,
            label: TERMINAL_PROFILE_LABELS[value],
          }))}
          onValueChange={(value) => {
            setProfile(value as TerminalProfile);
            setError(undefined);
          }}
        />
      </div>
      <label className="ja-terminal-creator-field">
        <span>工作目录</span>
        <input
          value={relativeCwd}
          maxLength={MAX_TERMINAL_RELATIVE_CWD_LENGTH + 1}
          aria-invalid={error === undefined ? undefined : true}
          aria-describedby={
            error === undefined
              ? TERMINAL_CREATOR_CWD_HINT_ID
              : `${TERMINAL_CREATOR_CWD_HINT_ID} ${TERMINAL_CREATOR_CWD_ERROR_ID}`
          }
          autoComplete="off"
          spellCheck={false}
          placeholder="例如 packages/desktop"
          onChange={(event) => {
            setRelativeCwd(event.currentTarget.value);
            setError(undefined);
          }}
        />
      </label>
      <span id={TERMINAL_CREATOR_CWD_HINT_ID} className="ja-terminal-creator-hint">
        仅接受工作区相对路径；留空表示工作区根目录。
      </span>
      {error === undefined ? null : (
        <span id={TERMINAL_CREATOR_CWD_ERROR_ID} className="ja-terminal-creator-error" role="alert">
          {error}
        </span>
      )}
      <div className="ja-terminal-creator-actions">
        <button type="button" onClick={onDismiss}>
          取消
        </button>
        <button type="submit" className="is-primary">
          创建终端
        </button>
      </div>
    </form>
  );
}

/** 分离标签标题与关闭命中区，使键盘焦点行为保持可预测。 */
function TerminalTab({
  tab,
  active,
  controller,
}: {
  tab: TerminalTabLayout;
  active: boolean;
  controller: TerminalWorkspaceController;
}): ReactElement {
  const closing =
    controller.closeAllPending ||
    terminalNodeHasLifecycle(tab.root, controller.runtimes, "closing");
  return (
    <div className={`ja-terminal-tab${active ? " is-active" : ""}`} role="presentation">
      <button
        type="button"
        role="tab"
        aria-selected={active}
        aria-controls={`ja-terminal-panel-${tab.tabId}`}
        disabled={controller.closeAllPending}
        onClick={() => controller.activateTab(tab.tabId)}
      >
        <span className="ja-terminal-tab-title">{tab.title}</span>
        <small title={`${terminalProfileLabel(tab.profile)} · ${tab.relativeCwd ?? "."}`}>
          {terminalProfileLabel(tab.profile)}
        </small>
      </button>
      <IconButton
        className="ja-terminal-tab-close"
        label={closing ? `${tab.title}正在关闭` : `关闭${tab.title}`}
        tooltip={closing ? "正在关闭终端标签页" : `关闭${tab.title}`}
        disabled={closing}
        onClick={() => runTerminalAction(() => controller.closeTab(tab.tabId))}
      >
        <X aria-hidden="true" size={13} />
      </IconButton>
    </div>
  );
}

/**
 * 渲染窗格叶子或递归展开分屏树，并把 pane-scoped native-drop 失败传到目标叶子；
 * 短暂反馈不进入持久布局状态。
 */
function TerminalNodeView({
  node,
  tab,
  active,
  controller,
  runtimes,
  dropFailure,
  onDismissDropFailure,
}: {
  node: TerminalLayoutNode;
  tab: TerminalTabLayout;
  active: boolean;
  controller: TerminalWorkspaceController;
  runtimes: Readonly<Record<string, TerminalPaneRuntime>>;
  dropFailure?: TerminalDropFailure;
  onDismissDropFailure: (paneId: string) => void;
}): ReactElement {
  if (node.kind === "pane")
    return (
      <TerminalPaneView
        pane={node}
        tab={tab}
        active={active}
        controller={controller}
        runtime={runtimes[node.paneId]}
        dropFailure={dropFailure}
        onDismissDropFailure={onDismissDropFailure}
      />
    );
  return (
    <TerminalSplitView
      split={node}
      tab={tab}
      active={active}
      controller={controller}
      runtimes={runtimes}
      dropFailure={dropFailure}
      onDismissDropFailure={onDismissDropFailure}
    />
  );
}

/**
 * 保持嵌套分屏几何独立，使每个手柄只更新自己的 splitId；短暂拖放失败只传给
 * 实际拥有失败原生操作的窗格。
 */
function TerminalSplitView({
  split,
  tab,
  active,
  controller,
  runtimes,
  dropFailure,
  onDismissDropFailure,
}: {
  split: TerminalSplitLayout;
  tab: TerminalTabLayout;
  active: boolean;
  controller: TerminalWorkspaceController;
  runtimes: Readonly<Record<string, TerminalPaneRuntime>>;
  dropFailure?: TerminalDropFailure;
  onDismissDropFailure: (paneId: string) => void;
}): ReactElement {
  const [preview, setPreview] = useState<{ splitId: string; ratio: number }>();
  const visibleRatio = preview?.splitId === split.splitId ? preview.ratio : split.ratio;
  const firstBasis = `${visibleRatio * 100}%`;

  /** 只更新 renderer 本地几何，避免高频 pointer 事件写入 localStorage。 */
  const previewRatio = (ratio: number | undefined): void => {
    setPreview(ratio === undefined ? undefined : { splitId: split.splitId, ratio });
  };

  /** pointer 或键盘事务结束时只提交一次最终有界比例。 */
  const commitRatio = (ratio: number): void => {
    controller.setSplitRatio(tab.tabId, split.splitId, ratio);
    setPreview(undefined);
  };

  return (
    <div className={`ja-terminal-split is-${split.orientation}`}>
      <div
        className="ja-terminal-split-child"
        style={
          split.orientation === "horizontal" ? { flexBasis: firstBasis } : { flexBasis: firstBasis }
        }
      >
        <TerminalNodeView
          node={split.first}
          tab={tab}
          active={active}
          controller={controller}
          runtimes={runtimes}
          dropFailure={dropFailure}
          onDismissDropFailure={onDismissDropFailure}
        />
      </div>
      <SplitHandle
        split={split}
        ratio={visibleRatio}
        onPreview={previewRatio}
        onCommit={commitRatio}
      />
      <div className="ja-terminal-split-child is-rest">
        <TerminalNodeView
          node={split.second}
          tab={tab}
          active={active}
          controller={controller}
          runtimes={runtimes}
          dropFailure={dropFailure}
          onDismissDropFailure={onDismissDropFailure}
        />
      </div>
    </div>
  );
}

/**
 * 高频 pointer 几何只留在本地，在 release、capture loss、cancel 或 window blur 时
 * 提交一次持久比例；键盘变化是离散操作，因此立即提交。
 */
function SplitHandle({
  split,
  ratio,
  onPreview,
  onCommit,
}: {
  split: TerminalSplitLayout;
  ratio: number;
  onPreview: (ratio: number | undefined) => void;
  onCommit: (ratio: number) => void;
}): ReactElement {
  const finishDragRef = useRef<(() => void) | undefined>(undefined);
  const disposeDragRef = useRef<(() => void) | undefined>(undefined);

  /** React 在拖拽中卸载分屏时只移除全局 listener，不持久化已失效几何。 */
  useEffect(() => () => disposeDragRef.current?.(), []);

  /** 启动一个有界拖拽事务，并安装条带外终止 listener。 */
  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>): void => {
    if (
      (typeof event.button === "number" && event.button !== 0) ||
      event.isPrimary === false ||
      finishDragRef.current !== undefined
    )
      return;
    const container = event.currentTarget.parentElement;
    if (container === null) return;
    const rect = container.getBoundingClientRect();
    const extent = split.orientation === "horizontal" ? rect.width : rect.height;
    if (!Number.isFinite(extent) || extent <= 0) return;
    event.preventDefault();
    const pointerId = event.pointerId;
    const target = event.currentTarget;
    let latestRatio = clampSplitRatio(ratio);
    let finished = false;

    /** 把 pointer 坐标投影到 0.2 至 0.8 的 renderer 预览范围。 */
    const update = (client: number): void => {
      const offset = split.orientation === "horizontal" ? client - rect.left : client - rect.top;
      latestRatio = clampSplitRatio(offset / extent);
      onPreview(latestRatio);
    };

    /** 在终止事件可能触发重复提交前先移除全部 listener。 */
    const cleanupListeners = (): void => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onCancel);
      window.removeEventListener("blur", finish);
    };

    /** 只为拥有当前事务的 pointer 应用 renderer 预览。 */
    const onMove = (move: PointerEvent): void => {
      if (move.pointerId === pointerId)
        update(split.orientation === "horizontal" ? move.clientX : move.clientY);
    };

    /** 以 release 坐标结束，避免合并的最后一次 move 丢失。 */
    const onUp = (up: PointerEvent): void => {
      if (up.pointerId !== pointerId) return;
      update(split.orientation === "horizontal" ? up.clientX : up.clientY);
      finish();
    };

    /** 将原生 cancel 视为结束边界，并保留最后可见比例。 */
    const onCancel = (cancel: PointerEvent): void => {
      if (cancel.pointerId === pointerId) finish();
    };

    /** 栅住 drag ref 后只提交一次，再释放 pointer capture。 */
    const finish = (): void => {
      if (finished) return;
      finished = true;
      finishDragRef.current = undefined;
      disposeDragRef.current = undefined;
      cleanupListeners();
      try {
        if (target.hasPointerCapture(pointerId)) target.releasePointerCapture(pointerId);
      } catch {
        // WebView teardown 可能在 lost-capture 事件到达前撤销 capture。
      }
      onPreview(undefined);
      onCommit(latestRatio);
    };

    /** unmount cleanup 刻意不持久化已删除分屏的几何。 */
    const dispose = (): void => {
      if (finished) return;
      finished = true;
      finishDragRef.current = undefined;
      disposeDragRef.current = undefined;
      cleanupListeners();
    };

    finishDragRef.current = finish;
    disposeDragRef.current = dispose;
    try {
      target.setPointerCapture(pointerId);
    } catch {
      // jsdom 和已销毁 WebView 可能没有存活的 pointer-capture target。
    }
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onCancel);
    window.addEventListener("blur", finish);
  };

  /** 把 separator 键盘意图映射为一次立即且有界的持久化提交。 */
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>): void => {
    let nextRatio: number | undefined;
    if (event.key === "Home") nextRatio = MIN_SPLIT_RATIO;
    else if (event.key === "End") nextRatio = MAX_SPLIT_RATIO;
    else if (
      (split.orientation === "horizontal" && event.key === "ArrowLeft") ||
      (split.orientation === "vertical" && event.key === "ArrowUp")
    )
      nextRatio = ratio - 0.05;
    else if (
      (split.orientation === "horizontal" && event.key === "ArrowRight") ||
      (split.orientation === "vertical" && event.key === "ArrowDown")
    )
      nextRatio = ratio + 0.05;
    if (nextRatio === undefined) return;
    event.preventDefault();
    onCommit(clampSplitRatio(nextRatio));
  };

  return (
    <button
      type="button"
      className={`ja-terminal-split-handle is-${split.orientation}`}
      role="separator"
      aria-orientation={split.orientation === "horizontal" ? "vertical" : "horizontal"}
      aria-label="调整终端分屏比例"
      aria-valuemin={Math.round(MIN_SPLIT_RATIO * 100)}
      aria-valuemax={Math.round(MAX_SPLIT_RATIO * 100)}
      aria-valuenow={Math.round(ratio * 100)}
      onPointerDown={onPointerDown}
      onPointerCancel={() => finishDragRef.current?.()}
      onLostPointerCapture={() => finishDragRef.current?.()}
      onKeyDown={onKeyDown}
    >
      <span />
    </button>
  );
}

/** 每个窗格只持有一个 xterm 实例，并在同一终端表面投影生命周期动作与可关闭的局部拖放失败。 */
function TerminalPaneView({
  pane,
  tab,
  active,
  controller,
  runtime,
  dropFailure,
  onDismissDropFailure,
}: {
  pane: TerminalPaneLayout;
  tab: TerminalTabLayout;
  active: boolean;
  controller: TerminalWorkspaceController;
  runtime: TerminalPaneRuntime | undefined;
  dropFailure?: TerminalDropFailure;
  onDismissDropFailure: (paneId: string) => void;
}): ReactElement {
  const ensurePaneOpen = controller.ensurePaneOpen;
  useEffect(() => {
    if (!active) return;
    void ensurePaneOpen(pane.paneId);
  }, [active, ensurePaneOpen, pane.paneId]);
  const inputProps = controller.terminalInputProps(pane.paneId);
  const lifecycle = runtime?.lifecycle ?? "dormant";
  const visibleLifecycle = controller.closeAllPending ? "closing" : lifecycle;
  const splitAvailable = controller.canSplitPane(tab.tabId, pane.paneId);
  const closing = visibleLifecycle === "closing";
  const droppedBytes = runtime?.droppedBytes ?? 0;
  const outputWarningTitleId = `ja-terminal-output-warning-${pane.paneId}`;
  const outputWarningHintId = `ja-terminal-output-warning-hint-${pane.paneId}`;
  const splitBlockedReason = closing
    ? "终端正在关闭"
    : splitAvailable
      ? undefined
      : "已达到每标签 4 个或工作区 8 个终端窗格上限";
  const splitDisabled = splitBlockedReason !== undefined;
  return (
    <div
      className={`ja-terminal-pane is-${visibleLifecycle}`}
      data-pane-id={pane.paneId}
      data-terminal-pane-id={pane.paneId}
      data-terminal-session-id={runtime?.session?.sessionId}
      data-terminal-session-generation={runtime?.session?.generation}
      aria-busy={closing || undefined}
      onPointerDown={() => controller.activatePane(tab.tabId, pane.paneId)}
    >
      <header className="ja-terminal-pane-toolbar">
        <span className="ja-terminal-pane-label">{terminalProfileLabel(tab.profile)}</span>
        <span className="ja-terminal-pane-cwd" title={tab.relativeCwd ?? "工作区根目录"}>
          {tab.relativeCwd ?? "."}
        </span>
        <span className="ja-terminal-pane-state" role="status">
          {lifecycleLabel(visibleLifecycle)}
        </span>
        <div className="ja-terminal-pane-actions">
          <IconButton
            label={splitDisabled ? `横向分屏不可用：${splitBlockedReason}` : "横向分屏"}
            tooltip={splitBlockedReason ?? "横向分屏"}
            disabled={splitDisabled}
            onClick={(event) => {
              event.stopPropagation();
              controller.splitPane(tab.tabId, pane.paneId, "horizontal");
            }}
          >
            <Rows2 aria-hidden="true" size={14} />
          </IconButton>
          <IconButton
            label={splitDisabled ? `纵向分屏不可用：${splitBlockedReason}` : "纵向分屏"}
            tooltip={splitBlockedReason ?? "纵向分屏"}
            disabled={splitDisabled}
            onClick={(event) => {
              event.stopPropagation();
              controller.splitPane(tab.tabId, pane.paneId, "vertical");
            }}
          >
            <Columns2 aria-hidden="true" size={14} />
          </IconButton>
          {!closing && (lifecycle === "exited" || lifecycle === "failed") ? (
            <IconButton
              label="重启终端"
              onClick={(event) => {
                event.stopPropagation();
                runTerminalAction(() => controller.restartPane(pane.paneId));
              }}
            >
              <RotateCcw aria-hidden="true" size={14} />
            </IconButton>
          ) : null}
          <IconButton
            label={closing ? "终端窗格正在关闭" : "关闭终端窗格"}
            tooltip={closing ? "正在关闭终端窗格" : "关闭终端窗格"}
            disabled={closing}
            onClick={(event) => {
              event.stopPropagation();
              runTerminalAction(() => controller.removePane(tab.tabId, pane.paneId));
            }}
          >
            <X aria-hidden="true" size={14} />
          </IconButton>
        </div>
      </header>
      {droppedBytes > 0 ? (
        <div
          className="ja-terminal-output-warning"
          role="status"
          aria-live="polite"
          aria-atomic="true"
          aria-labelledby={outputWarningTitleId}
          aria-describedby={outputWarningHintId}
        >
          <strong id={outputWarningTitleId}>输出已截断（丢弃 {droppedBytes} 字节）</strong>
          <span id={outputWarningHintId}>
            后续输出仍会继续显示；如需完整输出，请重启终端后重新执行命令。
          </span>
        </div>
      ) : null}
      <div className="ja-terminal-pane-body">
        <TerminalPanel
          key={`${pane.paneId}:${runtime?.session?.generation ?? lifecycle}`}
          {...inputProps}
          ariaLabel={`终端窗格 ${tab.title}`}
        />
        {dropFailure?.paneId === pane.paneId ? (
          <div className="ja-terminal-pane-overlay is-error" role="alert">
            <span>{dropFailure.message}</span>
            <button
              type="button"
              onClick={() => onDismissDropFailure(pane.paneId)}
              title="关闭提示后重新从文件管理器拖入"
            >
              重新拖入
            </button>
          </div>
        ) : null}
        {lifecycle === "opening" ? (
          <div className="ja-terminal-pane-overlay" role="status">
            正在启动终端…
          </div>
        ) : null}
        {lifecycle === "failed" ? (
          <div className="ja-terminal-pane-overlay is-error" role="alert">
            <span>{runtime?.error ?? "终端无法启动"}</span>
            <button
              type="button"
              onClick={() => runTerminalAction(() => controller.restartPane(pane.paneId))}
            >
              <RotateCcw aria-hidden="true" size={14} />
              重试
            </button>
          </div>
        ) : null}
        {lifecycle === "exited" ? (
          <div className="ja-terminal-pane-overlay" role="status">
            <span>终端进程已退出</span>
            <button
              type="button"
              onClick={() => runTerminalAction(() => controller.restartPane(pane.paneId))}
            >
              <RotateCcw aria-hidden="true" size={14} />
              重启
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** 检查标签内的待完成原生生命周期，用于禁用重复关闭和分屏控件。 */
function terminalNodeHasLifecycle(
  node: TerminalLayoutNode,
  runtimes: Readonly<Record<string, TerminalPaneRuntime>>,
  lifecycle: TerminalPaneRuntime["lifecycle"],
): boolean {
  if (node.kind === "pane") return runtimes[node.paneId]?.lifecycle === lifecycle;
  return (
    terminalNodeHasLifecycle(node.first, runtimes, lifecycle) ||
    terminalNodeHasLifecycle(node.second, runtimes, lifecycle)
  );
}

/** controller 已投影可重试错误，UI 边界只消费 rejection，避免产生 unhandled Promise。 */
function runTerminalAction(operation: () => Promise<void>): void {
  void operation().catch(() => {
    // 窗格 runtime 会保留原生 session 并展示重试提示。
  });
}

/** 为屏幕阅读器和紧凑工具栏显式映射生命周期标签。 */
function lifecycleLabel(lifecycle: TerminalPaneRuntime["lifecycle"]): string {
  switch (lifecycle) {
    case "dormant":
      return "休眠";
    case "opening":
      return "启动中";
    case "running":
      return "运行中";
    case "exited":
      return "已退出";
    case "failed":
      return "失败";
    case "closing":
      return "关闭中";
    case "closed":
      return "已关闭";
  }
}

/** 将持久化 profile 闭集映射为简短展示标签，绝不暴露可执行文件路径。 */
function terminalProfileLabel(profile: TerminalProfile): string {
  return TERMINAL_PROFILE_LABELS[profile];
}
