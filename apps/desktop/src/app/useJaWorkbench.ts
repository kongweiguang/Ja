// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback } from "react";
import type { WorkbenchTab } from "@/features/workbench";
import {
  usePreviewLifecycleController,
  type NativePreviewPort,
  type PreviewLifecycleProjection,
  type PreviewWorkspaceLifecycle,
} from "@/features/workbench/preview";
import type { TerminalWorkspaceAdapter } from "@/features/workbench/terminal";
import type { ReviewPort } from "@/features/workbench/review";
import type { WorkspaceProjection } from "@/features/workspace";
import type { WorkspaceFilesHostPort } from "./workbenchFilesAdapter";

export type { PreviewWorkspaceLifecycle } from "@/features/workbench/preview";

export interface JaWorkbenchAdapters {
  workspace: WorkspaceFilesHostPort;
  review: ReviewPort;
  terminal: TerminalWorkspaceAdapter;
  preview: NativePreviewPort;
}

export interface JaWorkbenchProjection {
  onTabChange: (tab: WorkbenchTab) => void;
  preview: PreviewLifecycleProjection;
  closePreview: () => Promise<void>;
  previewWorkspaceLifecycle: PreviewWorkspaceLifecycle | undefined;
}

/**
 * App composition hook 只注入 adapter 并组合 Preview lifecycle controller；Tab selection 由 shell
 * 单独拥有，这里不再保存镜像状态，也不复制 Files、Review 或 Terminal 的事实。
 */
export function useJaWorkbench(
  project: WorkspaceProjection | undefined,
  adapters: JaWorkbenchAdapters,
  onSelectedTabChange?: (tab: WorkbenchTab) => void,
): JaWorkbenchProjection {
  const preview = usePreviewLifecycleController(project?.workspaceId, adapters.preview);

  /** selection 只转发给 shell owner，能力组合层不保留第二份可漂移状态。 */
  const onTabChange = useCallback(
    (tab: WorkbenchTab): void => {
      onSelectedTabChange?.(tab);
    },
    [onSelectedTabChange],
  );

  return {
    onTabChange,
    closePreview: preview.closePreview,
    previewWorkspaceLifecycle: preview.workspaceLifecycle,
    preview: preview.preview,
  };
}
