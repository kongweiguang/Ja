// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useState } from "react";
import type { TerminalLayoutV1 } from "../domain/terminalLayout";
import type { TerminalLayoutStorage } from "./terminalLayoutStorage";

interface TerminalLayoutSnapshot {
  workspaceId: string;
  storage: TerminalLayoutStorage;
  layout: TerminalLayoutV1 | undefined;
}

/**
 * composition 保留当前 workspace 最新的 dormant layout 投影并转发 controller 提交；该投影只为
 * 同进程 capability 重挂提供输入，跨进程权威 schema 和介质仍由 Terminal adapter 独占。
 * workspace/storage owner 变化时同步换代，避免把旧项目布局短暂交给新 controller。
 */
export function useTerminalLayoutPersistence(
  workspaceId: string,
  storage: TerminalLayoutStorage,
): readonly [TerminalLayoutV1 | undefined, (layout: TerminalLayoutV1) => void] {
  const [snapshot, setSnapshot] = useState<TerminalLayoutSnapshot>(() => ({
    workspaceId,
    storage,
    layout: storage.load(workspaceId),
  }));
  let current = snapshot;
  if (snapshot.workspaceId !== workspaceId || snapshot.storage !== storage) {
    current = { workspaceId, storage, layout: storage.load(workspaceId) };
    setSnapshot(current);
  }

  /**
   * 先提交当前进程投影再写介质：localStorage quota/ACL 失败不能让显式关闭后的同进程重挂
   * 回退旧布局；真正重启仍只接受 storage 严格解析成功的值。
   */
  const save = useCallback(
    (layout: TerminalLayoutV1): void => {
      setSnapshot((existing) =>
        existing.workspaceId === layout.workspaceId && existing.storage === storage
          ? { ...existing, layout }
          : existing,
      );
      try {
        storage.save(layout);
      } catch {
        // localStorage quota/ACL 失败不能让 PTY 交互崩溃，当前会话仍由 Terminal controller 持有。
      }
    },
    [storage],
  );
  return [current.layout, save] as const;
}
