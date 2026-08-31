// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useMemo } from "react";
import type { TerminalLayoutV1 } from "../domain/terminalLayout";
import type { TerminalLayoutStorage } from "./terminalLayoutStorage";

/**
 * composition 只派生当前 workspace 的启动布局并转发活跃 controller 提交；权威 schema 和介质
 * 写入均由 Terminal adapter 独占，通用 UI preference store 不再认识 Terminal 字段。
 */
export function useTerminalLayoutPersistence(
  workspaceId: string,
  storage: TerminalLayoutStorage,
): readonly [TerminalLayoutV1 | undefined, (layout: TerminalLayoutV1) => void] {
  // 只在 workspace 或 storage adapter 变化时同步读取一次，避免 render 期间用 ref 维护平行状态。
  const initialLayout = useMemo(() => storage.load(workspaceId), [storage, workspaceId]);
  /** 写失败不破坏当前 controller 的内存布局；下次真实启动会重新从严格介质读取。 */
  const save = useCallback(
    (layout: TerminalLayoutV1): void => {
      try {
        storage.save(layout);
      } catch {
        // localStorage quota/ACL 失败不能让 PTY 交互崩溃，当前会话仍由 Terminal controller 持有。
      }
    },
    [storage],
  );
  return [initialLayout, save] as const;
}
