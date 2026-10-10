// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TimelineContextUsage } from "./timelineTypes";

type ContextUsageTone = "neutral" | "warning" | "danger";

/** Composer 只消费已经完成身份校验和阈值计算的展示模型，不接触事件或配置原始对象。 */
export type ContextUsagePresentation =
  | KnownContextUsagePresentation
  | EstimatedContextUsagePresentation
  | UnknownContextUsagePresentation;

/** Provider 完整响应的输入与输出计量可渲染实测占用。 */
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

/** 完整 Provider envelope 或成功压缩检查点产生的本地估算，必须与上游实测区分。 */
interface EstimatedContextUsagePresentation {
  certainty: "estimated";
  usedTokens: number;
  limitTokens: number;
  percentage: number;
  ringPercentage: number;
  tone: ContextUsageTone;
  source: "local";
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

/** Provider 的 totalTokens 包含完整请求输入与输出；压缩时报告本地包络估算。 */
function measuredPresentation(
  usedTokens: number,
  limitTokens: number,
  measuredAt: string,
  certainty: "known" | "estimated",
  source: "provider" | "local",
): KnownContextUsagePresentation | EstimatedContextUsagePresentation | undefined {
  if (!Number.isSafeInteger(usedTokens) || usedTokens < 0 || !Number.isFinite(usedTokens))
    return undefined;
  const rawPercentage = (usedTokens / limitTokens) * 100;
  if (!Number.isFinite(rawPercentage)) return undefined;
  const values = {
    usedTokens,
    limitTokens,
    percentage: Math.round(rawPercentage * 10) / 10,
    ringPercentage: Math.min(100, Math.max(0, rawPercentage)),
    tone: usageTone(rawPercentage),
    measuredAt,
  };
  if (certainty === "known" && source === "provider") {
    return { certainty, source, ...values };
  }
  if (certainty === "estimated" && source === "local") {
    return { certainty, source, ...values };
  }
  return undefined;
}

/**
 * 以完整 Provider totalTokens 表示最近请求的上下文用量，缓存属于输入的一部分，输出也进入对话上下文。
 * 成功压缩后改用同一完整 envelope 的本地预算并明确标为 estimated；非法窗口和缺失预算保持未知。
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
      (!Number.isSafeInteger(usage.totalTokens) || usage.totalTokens < 0))
  )
    return undefined;

  const providerTimestamp = timestamp(usage.measuredAt);
  if (providerTimestamp === undefined) return undefined;
  if (usage.purpose !== "assistant") return undefined;
  const snapshotEstimate = usage.contextEstimate;
  if (snapshotEstimate != null) {
    const estimateTimestamp = timestamp(snapshotEstimate.measuredAt);
    if (
      snapshotEstimate.certainty !== "estimated" ||
      snapshotEstimate.source !== "local" ||
      snapshotEstimate.limitTokens !== contextWindowTokens ||
      estimateTimestamp === undefined ||
      !Number.isSafeInteger(snapshotEstimate.usedTokens) ||
      snapshotEstimate.usedTokens < 0
    ) {
      return undefined;
    }
    return measuredPresentation(
      snapshotEstimate.usedTokens,
      contextWindowTokens,
      snapshotEstimate.measuredAt,
      "estimated",
      "local",
    );
  }
  const compaction = input.compaction;
  const invalidatedAt =
    input.invalidatedAt ?? (compaction?.phase === "compacted" ? compaction.occurredAt : undefined);
  const compactionTimestamp = invalidatedAt === undefined ? undefined : timestamp(invalidatedAt);
  const compactionInvalidatesUsage =
    invalidatedAt !== undefined &&
    compactionTimestamp !== undefined &&
    compactionTimestamp >= providerTimestamp;
  if (compactionInvalidatesUsage) {
    if (
      compaction?.phase === "compacted" &&
      compaction.inputTokensAfter !== null &&
      timestamp(compaction.occurredAt) !== undefined
    ) {
      return (
        measuredPresentation(
          compaction.inputTokensAfter,
          contextWindowTokens,
          compaction.occurredAt,
          "estimated",
          "local",
        ) ?? {
          certainty: "unknown",
          source: "provider",
          measuredAt: compaction.occurredAt,
        }
      );
    }
    return {
      certainty: "unknown",
      source: "provider",
      measuredAt: invalidatedAt,
    };
  }
  if (usage.certainty === "unknown") {
    return { certainty: "unknown", source: "provider", measuredAt: usage.measuredAt };
  }
  return measuredPresentation(
    usage.totalTokens,
    contextWindowTokens,
    usage.measuredAt,
    "known",
    "provider",
  );
}
