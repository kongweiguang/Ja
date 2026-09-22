// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TimelineContextUsage } from "./timelineTypes";

type ContextUsageTone = "neutral" | "warning" | "danger";

/** Composer 只消费已经完成身份校验和阈值计算的展示模型，不接触事件或配置原始对象。 */
export type ContextUsagePresentation =
  | KnownContextUsagePresentation
  | UnknownContextUsagePresentation;

/** 只有真实 Provider 响应的输入计量可渲染百分比环。 */
interface KnownContextUsagePresentation {
  certainty: "known";
  usedTokens: number;
  limitTokens: number;
  percentage: number;
  ringPercentage: number;
  tone: ContextUsageTone;
  source: "provider";
  measuredAt: string;
}

/** 没有当前投影阶段的可信计量时才声明未知；摘要提交后的旧 KNOWN 不能继续冒充当前输入。 */
interface UnknownContextUsagePresentation {
  certainty: "unknown";
  source: "provider";
  measuredAt: string;
}

/** 压缩成功会切换历史投影阶段，旧 Provider 输入计量随即失效；失败不会改写原阶段。 */
interface ContextUsageCompactionFact {
  phase: "started" | "compacted" | "failed";
  inputTokensAfter: number | null;
  occurredAt: string;
}

export interface ResolveContextUsageInput {
  usage?: TimelineContextUsage;
  compaction?: ContextUsageCompactionFact;
  /** 成功摘要的持久阶段边界；它独立于瞬态 compaction lifecycle，防止快照后旧用量复活。 */
  invalidatedAt?: string;
}

/** 将时间文本折算为可比较事实；不合法时间必须使候选失效，不能隐式回退为零。 */
function timestamp(value: string): number | undefined {
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/** 根据完整窗口占比选择克制的三段状态色，80% 前保持中性，95% 后才进入危险态。 */
function usageTone(percentage: number): ContextUsageTone {
  if (percentage >= 95) return "danger";
  if (percentage >= 80) return "warning";
  return "neutral";
}

/**
 * 只按已提交的 Provider 画像计算当前投影阶段的上下文占用；非法窗口保持不可展示，绝不借当前
 * 偏好补造。成功摘要会改变送给模型的历史前缀，因此早于或同刻的 Provider 用量必须失效为未知，
 * 直到新的真实响应重新测量；失败摘要不改变已发送上下文，保留上一笔 KNOWN。
 */
export function resolveContextUsage(
  input: ResolveContextUsageInput,
): ContextUsagePresentation | undefined {
  const { usage } = input;
  const contextWindowTokens = usage?.profile.contextWindowTokens;
  if (
    usage === undefined ||
    !Number.isSafeInteger(contextWindowTokens) ||
    contextWindowTokens === undefined ||
    contextWindowTokens <= 0 ||
    (usage.certainty === "known" &&
      (!Number.isSafeInteger(usage.inputTokens) || usage.inputTokens < 0))
  )
    return undefined;

  const providerTimestamp = timestamp(usage.measuredAt);
  if (providerTimestamp === undefined) return undefined;
  if (usage.certainty === "unknown") {
    return { certainty: "unknown", source: "provider", measuredAt: usage.measuredAt };
  }
  const compaction = input.compaction;
  const invalidatedAt =
    input.invalidatedAt ?? (compaction?.phase === "compacted" ? compaction.occurredAt : undefined);
  const compactionTimestamp = invalidatedAt === undefined ? undefined : timestamp(invalidatedAt);
  if (
    invalidatedAt !== undefined &&
    compactionTimestamp !== undefined &&
    compactionTimestamp >= providerTimestamp
  ) {
    return {
      certainty: "unknown",
      source: "provider",
      measuredAt: invalidatedAt,
    };
  }
  const usedTokens = usage.inputTokens;
  const measuredAt = usage.measuredAt;

  const rawPercentage = (usedTokens / contextWindowTokens) * 100;
  if (!Number.isFinite(rawPercentage)) return undefined;
  return {
    certainty: "known",
    usedTokens,
    limitTokens: contextWindowTokens,
    percentage: Math.round(rawPercentage * 10) / 10,
    ringPercentage: Math.min(100, Math.max(0, rawPercentage)),
    tone: usageTone(rawPercentage),
    source: "provider",
    measuredAt,
  };
}
