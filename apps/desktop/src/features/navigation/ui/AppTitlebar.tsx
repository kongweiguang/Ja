// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ArrowLeft,
  ArrowRight,
  Copy,
  Minus,
  PanelLeft,
  Square,
  X,
  type LucideIcon,
} from "lucide-react";
import { type MouseEvent, type ReactElement, type SyntheticEvent } from "react";
import { IconButton } from "@/shared/ui/primitives";
import type { DesktopPlatform, WindowAction, WindowFrameState } from "../domain/navigationModels";
import { navigationShortcut } from "../domain/shortcuts";
import "./titlebar.css";

type TitlebarAction = () => void | PromiseLike<void>;

/**
 * 执行应用回调时收口可选异步适配器的拒绝，避免窗口或导航切换期间产生未处理 Promise。
 */
function safelyInvoke(action: TitlebarAction): void {
  try {
    void Promise.resolve(action()).catch(() => undefined);
  } catch {
    // 导航由调用方持有；可选动作失败不能破坏标题栏稳定性，也不能泄漏事件处理器的拒绝 Promise。
  }
}

/**
 * 在显式交互区域阻止事件继续传播，避免 Tauri 将按钮手势解释为拖拽或标题栏双击。
 */
function stopEventPropagation(event: SyntheticEvent<HTMLElement>): void {
  event.stopPropagation();
}

/**
 * 识别显式退出拖拽面的后代节点；该检查补足 Tauri data attribute 对嵌套 SVG/Icon 目标的处理。
 */
function isNonDragTarget(target: EventTarget | null): boolean {
  return (
    typeof Element !== "undefined" &&
    target instanceof Element &&
    target.closest("button, [data-ja-no-window-drag]") !== null
  );
}

interface WindowControlProps {
  action: WindowAction;
  close?: boolean;
  disabled?: boolean;
  icon: LucideIcon;
  label: string;
  tooltip?: string;
  onInvoke: (action: WindowAction) => void;
}

/**
 * 渲染一个 Windows 原生操作入口，并让完整命中区域同时具备稳定无障碍合同和禁止拖拽边界。
 */
function WindowControl({
  action,
  close = false,
  disabled = false,
  icon: Icon,
  label,
  tooltip,
  onInvoke,
}: WindowControlProps): ReactElement {
  return (
    <IconButton
      className={`ja-window-control${close ? " is-close" : ""}`}
      label={label}
      tooltip={tooltip}
      disabled={disabled}
      aria-busy={disabled || undefined}
      data-window-action={action}
      data-ja-no-window-drag
      onClick={() => onInvoke(action)}
      onDoubleClick={stopEventPropagation}
      onPointerDown={stopEventPropagation}
    >
      <Icon aria-hidden="true" focusable="false" />
    </IconButton>
  );
}

export interface AppTitlebarProps {
  /** 已检测的宿主平台族；浏览器宿主必须传入 `unknown`，避免伪装成原生窗口。 */
  platform: DesktopPlatform;
  /** 主侧栏当前是否可见，由外部 Shell 持有这一状态事实。 */
  sidebarOpen: boolean;
  /** 专用页面明确不提供侧栏时隐藏切换入口，避免渲染无效操作。 */
  showSidebarToggle?: boolean;
  /** 切换主侧栏但不让标题栏耦合应用状态 owner。 */
  onToggleSidebar: TitlebarAction;
  /** 应用导航栈是否存在可返回目标，由导航 owner 提供权威事实。 */
  canGoBack: boolean;
  /** 应用导航栈是否存在可前进目标，由导航 owner 提供权威事实。 */
  canGoForward: boolean;
  /** 请求导航到上一个应用视图，标题栏本身不维护历史栈。 */
  onBack: TitlebarAction;
  /** 请求导航到下一个应用视图，标题栏本身不维护历史栈。 */
  onForward: TitlebarAction;
  /** 原生 frame 由 application controller 投影，UI 不自行订阅 Tauri event。 */
  windowFrame: WindowFrameState;
  /** 任一原生动作执行期间锁住窗口控件，避免重复点击形成竞态。 */
  windowActionPending?: WindowAction;
  /** 窗口动作已经由 application 收口失败与刷新策略。 */
  onWindowAction: (action: WindowAction) => void;
}

/**
 * 为桌面与浏览器预览提供同一个平台感知标题栏。macOS 为原生 traffic lights 留出空间，
 * Windows 只拥有三个真实窗口动作，其余宿主使用安全的非原生表面。
 */
export function AppTitlebar({
  platform,
  sidebarOpen,
  showSidebarToggle = true,
  onToggleSidebar,
  canGoBack,
  canGoForward,
  onBack,
  onForward,
  windowFrame,
  windowActionPending,
  onWindowAction,
}: AppTitlebarProps): ReactElement {
  const sidebarShortcut = navigationShortcut("toggle-sidebar", platform);
  const backShortcut = navigationShortcut("go-back", platform);
  const forwardShortcut = navigationShortcut("go-forward", platform);

  /** 把窗口意图交还 application controller，标题栏不接触原生 adapter 或刷新时序。 */
  const handleWindowAction = (action: WindowAction): void => {
    safelyInvoke(() => onWindowAction(action));
  };

  /**
   * 只允许 Windows 的真实拖拽面响应双击最大化；嵌套控件不得冒泡成窗口状态转换。
   */
  const handleTitlebarDoubleClick = (event: MouseEvent<HTMLElement>): void => {
    if (
      platform !== "windows" ||
      windowActionPending !== undefined ||
      isNonDragTarget(event.target)
    ) {
      return;
    }
    handleWindowAction("toggle-maximize");
  };

  return (
    <header
      className={`ja-titlebar is-${platform}${windowFrame.fullscreen ? " is-fullscreen" : ""}`}
      data-platform={platform}
      data-window-maximized={windowFrame.maximized}
      data-window-fullscreen={windowFrame.fullscreen}
      data-tauri-drag-region
      aria-label="应用标题栏"
      onDoubleClick={handleTitlebarDoubleClick}
    >
      <div
        className="ja-titlebar-leading ja-titlebar-interactive"
        data-ja-no-window-drag
        onDoubleClick={stopEventPropagation}
        onPointerDown={stopEventPropagation}
      >
        {showSidebarToggle ? (
          <IconButton
            className="ja-titlebar-button"
            label={sidebarOpen ? "隐藏侧边栏" : "显示侧边栏"}
            aria-expanded={sidebarOpen}
            aria-keyshortcuts={sidebarShortcut.aria}
            data-ja-no-window-drag
            onClick={() => safelyInvoke(onToggleSidebar)}
            onDoubleClick={stopEventPropagation}
            onPointerDown={stopEventPropagation}
          >
            <PanelLeft aria-hidden="true" focusable="false" />
          </IconButton>
        ) : null}
        <IconButton
          className="ja-titlebar-button"
          label="后退"
          aria-keyshortcuts={backShortcut.aria}
          disabled={!canGoBack}
          data-ja-no-window-drag
          onClick={() => safelyInvoke(onBack)}
          onDoubleClick={stopEventPropagation}
          onPointerDown={stopEventPropagation}
        >
          <ArrowLeft aria-hidden="true" focusable="false" />
        </IconButton>
        <IconButton
          className="ja-titlebar-button"
          label="前进"
          aria-keyshortcuts={forwardShortcut.aria}
          disabled={!canGoForward}
          data-ja-no-window-drag
          onClick={() => safelyInvoke(onForward)}
          onDoubleClick={stopEventPropagation}
          onPointerDown={stopEventPropagation}
        >
          <ArrowRight aria-hidden="true" focusable="false" />
        </IconButton>
      </div>

      {platform === "windows" ? (
        <div
          className="ja-window-controls ja-titlebar-interactive"
          data-ja-no-window-drag
          onDoubleClick={stopEventPropagation}
          onPointerDown={stopEventPropagation}
        >
          <WindowControl
            action="minimize"
            icon={Minus}
            label="最小化"
            disabled={windowActionPending !== undefined}
            onInvoke={handleWindowAction}
          />
          <WindowControl
            action="toggle-maximize"
            icon={windowFrame.maximized ? Copy : Square}
            label={windowFrame.maximized ? "还原" : "最大化"}
            disabled={windowActionPending !== undefined}
            onInvoke={handleWindowAction}
          />
          <WindowControl
            action="hide"
            icon={X}
            label="关闭"
            tooltip="隐藏到系统托盘"
            close
            disabled={windowActionPending !== undefined}
            onInvoke={handleWindowAction}
          />
        </div>
      ) : null}
    </header>
  );
}
