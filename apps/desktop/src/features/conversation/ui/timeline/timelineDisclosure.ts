// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type TimelineDisclosureKind = "process" | "tool";

const MAX_TIMELINE_DISCLOSURES = 512;

/**
 * 将 Thread、可折叠对象类型与服务端稳定 identity 合成为单一键，避免不同会话中重复的 Turn/Item ID
 * 共享用户选择；该键只存在于 Renderer 生命周期，不进入持久化或协议。
 */
function disclosureKey(threadId: string, kind: TimelineDisclosureKind, identity: string): string {
  return `${threadId}:${kind}:${identity}`;
}

/**
 * 保存用户明确作出的展开选择，使虚拟列表卸载、历史回读和流式转终态不会重置界面；缓存固定上限
 * 且由 Workspace 生命周期持有，既不成为第二份会话状态，也不会无界积累历史 DOM identity。
 */
export class TimelineDisclosureCache {
  private readonly entries = new Map<string, boolean>();

  /** 只读取明确的用户选择；缺失值交由当前权威状态决定默认展开策略。 */
  get(threadId: string, kind: TimelineDisclosureKind, identity: string): boolean | undefined {
    if (threadId.trim() === "" || identity.trim() === "") return undefined;
    return this.entries.get(disclosureKey(threadId, kind, identity));
  }

  /** 写入最近选择并执行固定上限淘汰，避免切换大量 Thread 后长期保留低价值交互状态。 */
  set(threadId: string, kind: TimelineDisclosureKind, identity: string, open: boolean): void {
    if (threadId.trim() === "" || identity.trim() === "") return;
    const key = disclosureKey(threadId, kind, identity);
    this.entries.delete(key);
    this.entries.set(key, open);
    while (this.entries.size > MAX_TIMELINE_DISCLOSURES) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) return;
      this.entries.delete(oldest);
    }
  }

  /** Workspace 卸载时释放全部瞬态选择，防止后续项目复用旧会话的 disclosure identity。 */
  clear(): void {
    this.entries.clear();
  }
}
