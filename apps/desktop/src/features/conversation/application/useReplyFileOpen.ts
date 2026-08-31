// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { WorkspaceOpenTarget, WorkspaceOpenTargetInfo } from "../domain/openTarget";
import { latestReplyFilePaths } from "../domain/replyFiles";
import type { TimelineItemAdapter } from "../domain/timelineTypes";
import type { WorkspaceOpenPort } from "./ports";

const EDITOR_TARGETS = new Set<WorkspaceOpenTarget>([
  "vscode",
  "visual_studio",
  "zed",
  "pycharm",
  "webstorm",
]);

export interface ReplyFileOpenProjection {
  files: readonly string[];
  targets: readonly WorkspaceOpenTargetInfo[];
  discovering: boolean;
  opening: boolean;
  error?: string;
  onRetryDiscovery?: () => void;
  onOpen: (relativePath: string, target: WorkspaceOpenTarget) => Promise<void>;
}

/**
 * 为选中 Project 发现已安装 Editor，并且只打开权威 Reply Metadata 中已有的 File Path。
 * Executable Path 绝不进入 Renderer 状态，原生 Closed Target Enum 始终作为路由边界。
 */
export function useReplyFileOpen(
  workspaceId: string | undefined,
  items: readonly TimelineItemAdapter[],
  adapter: WorkspaceOpenPort,
): ReplyFileOpenProjection {
  const files = useMemo(() => latestReplyFilePaths(items), [items]);
  const fileSignature = files.join("\u0000");
  const [targets, setTargets] = useState<WorkspaceOpenTargetInfo[]>([]);
  const [discovering, setDiscovering] = useState(false);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string>();
  const [discoveryFailed, setDiscoveryFailed] = useState(false);
  const discoveryGeneration = useRef(0);
  const discoveryInFlight = useRef(false);

  /** 启动一次受 Generation Fence 保护的 Discovery，且不扩大原生 Target Enum。 */
  const discoverTargets = useCallback((): void => {
    const generation = ++discoveryGeneration.current;
    setError(undefined);
    setDiscoveryFailed(false);
    if (workspaceId === undefined || fileSignature.length === 0) {
      setTargets([]);
      setDiscovering(false);
      discoveryInFlight.current = false;
      return;
    }

    discoveryInFlight.current = true;
    setDiscovering(true);
    void adapter
      .openTargets({ workspaceId })
      .then((result) => {
        if (discoveryGeneration.current !== generation) return;
        setTargets(
          result.targets.filter((target) => target.available && EDITOR_TARGETS.has(target.target)),
        );
      })
      .catch(() => {
        if (discoveryGeneration.current !== generation) return;
        setTargets([]);
        setDiscoveryFailed(true);
        setError("无法读取本机编辑器，请稍后重试。");
      })
      .finally(() => {
        if (discoveryGeneration.current === generation) {
          discoveryInFlight.current = false;
          setDiscovering(false);
        }
      });
  }, [adapter, fileSignature, workspaceId]);

  useEffect(() => {
    discoverTargets();
    return () => {
      discoveryGeneration.current += 1;
      discoveryInFlight.current = false;
    };
  }, [discoverTargets]);

  /** 只重试失败的 Discovery；新 Generation 会使任何迟到旧结果失效。 */
  const onRetryDiscovery = useCallback((): void => {
    if (!discoveryFailed || discoveryInFlight.current) return;
    discoverTargets();
  }, [discoverTargets, discoveryFailed]);

  /** Reply 所有的文件只能通过原生 Discovery 确认可用的 Editor 打开。 */
  const onOpen = useCallback(
    async (relativePath: string, target: WorkspaceOpenTarget): Promise<void> => {
      if (
        workspaceId === undefined ||
        !files.includes(relativePath) ||
        !targets.some((candidate) => candidate.target === target)
      ) {
        setError("这个文件或编辑器当前不可用。");
        return;
      }
      setOpening(true);
      setError(undefined);
      try {
        await adapter.open({ workspaceId, target, relativePath });
      } catch {
        setError("文件打开失败，请确认编辑器仍可用。");
      } finally {
        setOpening(false);
      }
    },
    [adapter, files, targets, workspaceId],
  );

  return {
    files,
    targets,
    discovering,
    opening,
    error,
    ...(discoveryFailed ? { onRetryDiscovery } : {}),
    onOpen,
  };
}
