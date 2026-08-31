// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { lazy, Suspense, useCallback, useEffect, useMemo, type ReactElement } from "react";
import { toast } from "sonner";
import { Workbench, type WorkbenchTab } from "@/features/workbench";
import {
  FilesWorkspace,
  useFilesController,
  type FilesWorkspaceLifecycle,
} from "@/features/workbench/files";
import {
  PreviewPanelView,
  usePreviewController,
  type PreviewPort,
} from "@/features/workbench/preview";
import { ReviewPanelView, useReviewController } from "@/features/workbench/review";
import {
  LocalTerminalLayoutStorage,
  useTerminalLayoutPersistence,
} from "@/features/workbench/terminal";
import { LoadingState } from "@/shared/ui/primitives";
import type { WorkspaceProjection } from "@/features/workspace";
import { createNotifyingFilesOperations } from "../application/createNotifyingFilesOperations";
import { usePreviewWorkspaceLifecycle } from "../application/usePreviewWorkspaceLifecycle";
import { useTerminalWorkspaceLifecycle } from "../application/useTerminalWorkspaceLifecycle";
import type { TerminalWorkspaceLifecycle } from "../application/workbenchLifecyclePorts";
import {
  useJaWorkbench,
  type JaWorkbenchAdapters,
  type PreviewWorkspaceLifecycle,
} from "../useJaWorkbench";
import { filesBrowserControllerPorts } from "./filesBrowserControllerPorts";

/** Terminal controller 与 xterm renderer 只在能力首次激活后加载。 */
const LazyTerminalWorkbenchSlot = lazy(async () => {
  const terminal = await import("./TerminalWorkbenchSlot");
  return { default: terminal.TerminalWorkbenchSlot };
});

const terminalLayoutStorage = new LocalTerminalLayoutStorage(() => globalThis.localStorage);

export interface WorkbenchHostProps {
  readonly workspace: WorkspaceProjection;
  readonly generation: number | undefined;
  readonly adapters: JaWorkbenchAdapters;
  readonly active: boolean;
  readonly selectedTab: WorkbenchTab;
  readonly onTabChange: (tab: WorkbenchTab) => void;
  readonly openTabs: readonly WorkbenchTab[];
  readonly onOpenTabsChange: (tabs: readonly WorkbenchTab[]) => void;
  readonly capabilityShortcuts: Partial<Record<WorkbenchTab, string>>;
  readonly onCopyText: (text: string) => Promise<void>;
  readonly onOpenExternalUrl: (url: string) => Promise<void>;
  readonly onClose: () => void;
  readonly onRegisterFilesLifecycle: (lifecycle: FilesWorkspaceLifecycle | undefined) => void;
  readonly onRegisterTerminalLifecycle: (lifecycle: TerminalWorkspaceLifecycle | undefined) => void;
  readonly onRegisterPreviewLifecycle: (lifecycle: PreviewWorkspaceLifecycle | undefined) => void;
  readonly onCloseFilesCapability: (workspaceId: string) => Promise<void>;
  readonly onGitBranchChange: (workspaceId: string, branch: string | undefined) => void;
}

/**
 * WorkbenchHost 持续挂载四个 feature controller，使 Tab 切换不丢编辑器、PTY 或 WebView；
 * 它只组合 view model 与 lifecycle port，不复制 Files、Terminal、Preview 的领域状态；不注入
 * “侧边聊天”开闭动作，确保抽屉布局只由壳层的专用按钮改变。
 */
export function WorkbenchHost({
  workspace,
  generation,
  adapters,
  active,
  selectedTab,
  onTabChange,
  openTabs,
  onOpenTabsChange,
  capabilityShortcuts,
  onCopyText,
  onOpenExternalUrl,
  onClose,
  onRegisterFilesLifecycle,
  onRegisterTerminalLifecycle,
  onRegisterPreviewLifecycle,
  onCloseFilesCapability,
  onGitBranchChange,
}: WorkbenchHostProps): ReactElement {
  const projection = useJaWorkbench(workspace, adapters, onTabChange);
  const terminal = useTerminalWorkspaceLifecycle(
    workspace.workspaceId,
    adapters.terminal,
    openTabs.includes("terminal"),
    active && selectedTab === "terminal",
    onRegisterTerminalLifecycle,
  );
  usePreviewWorkspaceLifecycle(projection.previewWorkspaceLifecycle, onRegisterPreviewLifecycle);
  const previewPort = useMemo<PreviewPort>(
    () => ({
      navigate: projection.preview.onNavigate,
      reload: projection.preview.onReload,
      retryRecovery: projection.preview.onRetryRecovery,
      changeViewport: projection.preview.onViewportChange,
    }),
    [
      projection.preview.onNavigate,
      projection.preview.onReload,
      projection.preview.onRetryRecovery,
      projection.preview.onViewportChange,
    ],
  );
  const previewController = usePreviewController({
    url: projection.preview.url ?? "",
    loading: projection.preview.loading ?? false,
    recovering: projection.preview.recovering ?? false,
    error: projection.preview.error,
    active: active && selectedTab === "preview",
    port: previewPort,
  });
  const review = useReviewController({
    workspaceId: workspace.workspaceId,
    generation,
    adapter: adapters.review,
  });
  const currentGitBranch = review.viewModel.state.catalog?.currentBranch?.trim() || undefined;

  /**
   * Review Catalog 是当前 workspace Git 上下文的只读 owner；向壳层发布同一分支投影，避免
   * Composer 重复查询。general workspace 非仓库时自然保持缺失，不伪造项目能力。
   */
  useEffect(() => {
    onGitBranchChange(workspace.workspaceId, currentGitBranch);
  }, [currentGitBranch, onGitBranchChange, workspace.workspaceId]);

  /** 卸载能力时只清除同一 workspace 的分支投影，防止旧 Workbench 污染新范围。 */
  useEffect(
    () => () => onGitBranchChange(workspace.workspaceId, undefined),
    [onGitBranchChange, workspace.workspaceId],
  );

  const [terminalLayout, persistTerminalLayout] = useTerminalLayoutPersistence(
    workspace.workspaceId,
    terminalLayoutStorage,
  );

  /** Files controller 的脱敏失败按文案去重，避免 Watcher 重试形成 toast 洪泛。 */
  const showFilesNotice = useCallback((message: string): void => {
    toast.error(message, { id: `ja-files:${message}` });
  }, []);
  const filesOperations = useMemo(
    () => createNotifyingFilesOperations(adapters.workspace, showFilesNotice),
    [adapters.workspace, showFilesNotice],
  );
  const files = useFilesController({
    workspaceId: workspace.workspaceId,
    operations: filesOperations,
    onNotice: showFilesNotice,
    onRegisterLifecycle: onRegisterFilesLifecycle,
    ...filesBrowserControllerPorts,
  });

  /** 只有显式 capability 关闭拥有 teardown；隐藏或切换 Tab 必须保留 feature 状态。 */
  const closeCapabilityTab = useCallback(
    (tab: WorkbenchTab): void | Promise<void> => {
      if (tab === "files") return onCloseFilesCapability(workspace.workspaceId);
      if (tab === "preview") return projection.closePreview();
      if (tab === "terminal") return terminal.closeCapability();
    },
    [onCloseFilesCapability, projection, terminal, workspace.workspaceId],
  );

  const terminalSlot = terminal.activated ? (
    <Suspense fallback={<LoadingState label="正在打开终端…" />}>
      <LazyTerminalWorkbenchSlot
        key={`${workspace.workspaceId}:${terminal.controllerGeneration}`}
        workspaceId={workspace.workspaceId}
        adapter={adapters.terminal}
        active={terminal.active}
        initialLayout={terminalLayout}
        onLayoutChange={persistTerminalLayout}
        onOpenExternalUrl={onOpenExternalUrl}
        onCopy={onCopyText}
        onRegisterCloseAll={terminal.registerCloseAll}
      />
    </Suspense>
  ) : (
    <span className="ja-visually-hidden" aria-hidden="true" />
  );

  return (
    <aside
      className="ja-inspector"
      aria-label="工作区面板"
      aria-hidden={active ? undefined : true}
      data-visible={active || undefined}
    >
      <Workbench
        selectedTab={selectedTab}
        onTabChange={projection.onTabChange}
        openTabs={openTabs}
        onOpenTabsChange={onOpenTabsChange}
        onTabClose={closeCapabilityTab}
        views={{
          review: (
            <ReviewPanelView
              viewModel={review.viewModel}
              actions={review.actions}
              onCopyText={onCopyText}
            />
          ),
          files: <FilesWorkspace viewModel={files.viewModel} actions={files.actions} />,
          terminal: terminalSlot,
          preview: (
            <PreviewPanelView
              viewModel={previewController.viewModel}
              actions={previewController.actions}
            />
          ),
        }}
        capabilityShortcuts={capabilityShortcuts}
        onClose={onClose}
      />
    </aside>
  );
}
