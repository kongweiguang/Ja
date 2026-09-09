// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 描述一个可进入统一 Timeline 的低频事实。identity 必须来自持久化身份，不能使用数组位置，
 * 否则 reload 与实时事件以不同顺序抵达时会造成卡片跳动或重复。
 */
export interface TimelineChronologyCandidate<T> {
  readonly identity: string;
  readonly occurredAt?: string;
  readonly value: T;
}

/** identity 使用代码点全序，避免运行环境 locale 差异改变相同时间戳下的稳定顺序。 */
function compareIdentity(left: string, right: string): number {
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

/** 非法或尚未提交的时间不伪造为 epoch；它们由稳定 identity 排在已提交事实之后。 */
function occurrenceMillis(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * 将 Conversation 与外部持久活动投影到同一确定性总序。重复 identity 采用最后一个权威投影，
 * 已提交事实按服务端时间排序；同毫秒与未提交事实再按 identity 排序，使 live、reload 和重放一致。
 */
export function projectTimelineChronology<T>(
  candidates: readonly TimelineChronologyCandidate<T>[],
): readonly TimelineChronologyCandidate<T>[] {
  const byIdentity = new Map<string, TimelineChronologyCandidate<T>>();
  for (const candidate of candidates) byIdentity.set(candidate.identity, candidate);
  return [...byIdentity.values()].sort((left, right) => {
    const leftTime = occurrenceMillis(left.occurredAt);
    const rightTime = occurrenceMillis(right.occurredAt);
    if (leftTime !== undefined && rightTime !== undefined && leftTime !== rightTime) {
      return leftTime - rightTime;
    }
    if (leftTime !== undefined && rightTime === undefined) return -1;
    if (leftTime === undefined && rightTime !== undefined) return 1;
    return compareIdentity(left.identity, right.identity);
  });
}
