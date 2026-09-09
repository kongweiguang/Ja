// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TimelineContextUsage } from "./timelineTypes";

type ContextUsageTone = "neutral" | "warning" | "danger";
type ContextUsageSource = "provider" | "compaction";

/** Composer 只消费已经完成身份校验和阈值计算的展示模型，不接触事件或配置原始对象。 */
export type ContextUsagePresentation =
  | KnownContextUsagePresentation
  | UnknownContextUsagePresentation;

/** 精确 Provider/压缩计量可渲染百分比环。 */
interface KnownContextUsagePresentation {
  certainty: "known";
  usedTokens: number;
  limitTokens: number;
  percentage: number;
  ringPercentage: number;
  tone: ContextUsageTone;
  source: ContextUsageSource;
  measuredAt: string;
}

/** 崩溃窗口只声明计量未知，不伪造百分比或 Token 数。 */
interface UnknownContextUsagePresentation {
  certainty: "unknown";
  source: "provider";
  measuredAt: string;
}

/** 压缩投影只贡献比 Provider Usage 更新的输入计量，不能单独创造模型身份。 */
interface ContextUsageCompactionFact {
  phase: "started" | "compacted" | "failed";
  inputTokensAfter: number | null;
  occurredAt: string;
}

export interface ResolveContextUsageInput {
  usage?: TimelineContextUsage;
  compaction?: ContextUsageCompactionFact;
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
 * 只按该请求已提交的画像计算上下文占用；非法窗口保持不可展示，绝不借当前偏好补造。
 * 压缩计量仅在更晚且完整时覆盖基准 inputTokens。
 */
export function resolveContextUsage(
  input: ResolveContextUsageInput,
): ContextUsagePresentation | undefined {
  const { usage, compaction } = input;
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
  let usedTokens = usage.inputTokens;
  let measuredAt = usage.measuredAt;
  let source: ContextUsageSource = "provider";
  if (
    compaction?.phase === "compacted" &&
    compaction.inputTokensAfter !== null &&
    Number.isSafeInteger(compaction.inputTokensAfter) &&
    compaction.inputTokensAfter >= 0
  ) {
    const compactionTimestamp = timestamp(compaction.occurredAt);
    if (compactionTimestamp !== undefined && compactionTimestamp > providerTimestamp) {
      usedTokens = compaction.inputTokensAfter;
      measuredAt = compaction.occurredAt;
      source = "compaction";
    }
  }

  const rawPercentage = (usedTokens / contextWindowTokens) * 100;
  if (!Number.isFinite(rawPercentage)) return undefined;
  return {
    certainty: "known",
    usedTokens,
    limitTokens: contextWindowTokens,
    percentage: Math.round(rawPercentage),
    ringPercentage: Math.min(100, Math.max(0, rawPercentage)),
    tone: usageTone(rawPercentage),
    source,
    measuredAt,
  };
}
