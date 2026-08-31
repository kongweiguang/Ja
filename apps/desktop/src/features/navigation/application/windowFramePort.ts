// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WindowAction, WindowFrameState } from "../domain/navigationModels";

export interface WindowFrameObserver {
  /** 动作后主动查询权威 frame，覆盖原生 resize 事件合并或晚到。 */
  refresh(): void;
  /** 释放原生 listener；实现必须处理注册尚未完成的卸载竞态。 */
  dispose(): void;
}

/** Application 只依赖观察与封闭动作端口，不接触 Tauri window 或 event 类型。 */
export interface WindowFramePort {
  observe(listener: (state: WindowFrameState) => void): WindowFrameObserver;
  invoke(action: WindowAction): Promise<void>;
}
