// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

interface WorkbenchMedia {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/**
 * 会话只改变显示偏好的介质命名空间，不伪造传给 native 的 Workspace identity。
 * 不读取原项目级键，避免首次打开某会话时继承另一会话的布局或网页 session。
 */
export function threadWorkbenchStorage(media: WorkbenchMedia, scope: string): WorkbenchMedia {
  const prefix = `ja-thread-workbench:${encodeURIComponent(scope)}:`;
  return {
    /** 读取只定位当前会话的严格 schema，解析仍由各 feature owner 负责。 */
    getItem: (key) => media.getItem(`${prefix}${key}`),
    /** 不复制或重写 Workspace 字段，仅隔离持久化键。 */
    setItem: (key, value) => media.setItem(`${prefix}${key}`, value),
    /** 精确删除当前会话 hint，不触碰其它会话的原生 session 线索。 */
    removeItem: (key) => media.removeItem(`${prefix}${key}`),
  };
}
