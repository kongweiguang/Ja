// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { resolveContextUsage } from "@/features/conversation/domain/contextUsage";
import type { TimelineContextUsage } from "@/features/conversation/domain/timelineTypes";

const PROFILE = {
  providerId: "provider_openai",
  modelId: "model_gpt",
  api: "openai_responses",
  upstreamModel: "gpt-5.6-sol",
  requestedReasoning: "medium",
  effectiveReasoning: "medium",
  accessMode: "approval_required",
  configGeneration: "cfg_1",
  promptRevision: "prompt_1",
  toolCatalogRevision: "tools_1",
  contextWindowTokens: 128_000,
  maxOutputTokens: 16_000,
} as const;

const USAGE: TimelineContextUsage = {
  modelRequestId: "modelreq_1",
  modelRequestOrdinal: 1,
  modelRound: 1,
  purpose: "assistant",
  profile: PROFILE,
  certainty: "known",
  inputTokens: 42_000,
  outputTokens: 2_000,
  totalTokens: 44_000,
  measuredAt: "2026-08-31T00:00:01Z",
};

/** 统一构造已通过请求画像校验的输入，让各断言只改变其关心的上下文事实。 */
function resolve(overrides: Partial<Parameters<typeof resolveContextUsage>[0]> = {}) {
  return resolveContextUsage({
    usage: USAGE,
    ...overrides,
  });
}

describe("context usage presentation", () => {
  /** 上下文窗口只取自该请求画像，当前偏好变化不能改写历史百分比。 */
  it("按最近普通请求的完整总量投影上下文，包含缓存与输出", () => {
    expect(resolve()).toEqual({
      certainty: "known",
      usedTokens: 44_000,
      limitTokens: 128_000,
      percentage: 34.4,
      ringPercentage: 34.375,
      tone: "neutral",
      source: "provider",
      measuredAt: USAGE.measuredAt,
    });
    expect(resolve({ usage: undefined })).toBeUndefined();
  });

  /** 崩溃窗口的 UNKNOWN Usage 只展示不确定事实，不能参与百分比或压缩覆盖计算。 */
  it("把未知 Provider Usage 投影为不可量化状态", () => {
    expect(
      resolve({
        usage: {
          ...USAGE,
          certainty: "unknown",
          inputTokens: null,
          outputTokens: null,
          totalTokens: null,
        },
      }),
    ).toEqual({
      certainty: "unknown",
      source: "provider",
      measuredAt: USAGE.measuredAt,
    });
  });

  /** thread/read 在压缩后重建仅由持久检查点估算提供的上下文，不恢复过期的实测输入。 */
  it("从恢复快照投影压缩检查点的本地估算", () => {
    expect(
      resolve({
        usage: {
          ...USAGE,
          certainty: "unknown",
          inputTokens: null,
          outputTokens: null,
          totalTokens: null,
          contextEstimate: {
            certainty: "estimated",
            source: "local",
            usedTokens: 12_000,
            limitTokens: PROFILE.contextWindowTokens,
            measuredAt: "2026-08-31T00:00:02Z",
          },
        },
      }),
    ).toMatchObject({
      certainty: "estimated",
      usedTokens: 12_000,
      source: "local",
      measuredAt: "2026-08-31T00:00:02Z",
    });
  });

  /** Provider 请求尚未结算时显示 Java 按完整当前请求 envelope 计算的 dispatch 估算。 */
  it("在 Provider 实测到来前显示当前请求本地估算", () => {
    expect(
      resolve({
        usage: {
          ...USAGE,
          certainty: "unknown",
          inputTokens: null,
          outputTokens: null,
          totalTokens: null,
          contextEstimate: {
            certainty: "estimated",
            source: "local",
            usedTokens: 18_500,
            limitTokens: PROFILE.contextWindowTokens,
            measuredAt: "2026-08-31T00:00:02Z",
          },
        },
      }),
    ).toMatchObject({
      certainty: "estimated",
      usedTokens: 18_500,
      percentage: 14.5,
      source: "local",
    });
  });

  /** 摘要成功开始新投影阶段，旧上游计量失效并显示压缩后 envelope 的本地预算。 */
  it("压缩后使用明确标记的压缩后估算，并接受阶段后的计量", () => {
    expect(
      resolve({
        compaction: {
          phase: "compacted",
          inputTokensAfter: 12_000,
          occurredAt: "2026-08-31T00:00:02Z",
        },
      }),
    ).toMatchObject({
      certainty: "estimated",
      usedTokens: 12_000,
      limitTokens: 128_000,
      source: "local",
      measuredAt: "2026-08-31T00:00:02Z",
    });
    expect(
      resolve({
        usage: {
          ...USAGE,
          inputTokens: 18_000,
          outputTokens: 2_000,
          totalTokens: 20_000,
          measuredAt: "2026-08-31T00:00:03Z",
        },
        compaction: {
          phase: "compacted",
          inputTokensAfter: 12_000,
          occurredAt: "2026-08-31T00:00:02Z",
        },
      }),
    ).toMatchObject({
      certainty: "known",
      usedTokens: 20_000,
      source: "provider",
      measuredAt: "2026-08-31T00:00:03Z",
    });
    expect(
      resolve({
        compaction: {
          phase: "failed",
          inputTokensAfter: null,
          occurredAt: "2026-08-31T00:00:03Z",
        },
      }),
    ).toMatchObject({ certainty: "known", usedTokens: 44_000, source: "provider" });
  });

  /** 生命周期投影可在 snapshot 后收敛，但成功摘要的阶段边界必须继续阻断旧 Provider 读数。 */
  it("持久压缩边界在没有 lifecycle 详情时仍使旧 Usage 失效", () => {
    expect(resolve({ invalidatedAt: "2026-08-31T00:00:02Z" })).toEqual({
      certainty: "unknown",
      source: "provider",
      measuredAt: "2026-08-31T00:00:02Z",
    });
    expect(
      resolve({
        invalidatedAt: "2026-08-31T00:00:02Z",
        usage: { ...USAGE, measuredAt: "2026-08-31T00:00:03Z" },
      }),
    ).toMatchObject({ certainty: "known", usedTokens: 44_000, source: "provider" });
  });

  /** 80% 与 95% 是完整窗口上的稳定阈值，环形进度在超限时仍约束在可绘制范围。 */
  it("在 80% 警告并在 95% 进入危险态", () => {
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 79_000,
          outputTokens: 0,
          totalTokens: 79_000,
        },
      }),
    ).toMatchObject({
      percentage: 79,
      tone: "neutral",
    });
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 80_000,
          outputTokens: 0,
          totalTokens: 80_000,
        },
      }),
    ).toMatchObject({ percentage: 80, ringPercentage: 80, tone: "warning" });
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 95_000,
          outputTokens: 0,
          totalTokens: 95_000,
        },
      }),
    ).toMatchObject({ percentage: 95, ringPercentage: 95, tone: "danger" });
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 120_000,
          outputTokens: 0,
          totalTokens: 120_000,
        },
      }),
    ).toMatchObject({ percentage: 120, ringPercentage: 100, tone: "danger" });
  });

  /** 原截图规模：小的未缓存输入不能代替完整的缓存输入与输出上下文。 */
  it("使用最近请求完整用量避免高缓存会话显示接近零", () => {
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 1_000_000 },
          inputTokens: 35_000,
          outputTokens: 47_000,
          totalTokens: 689_000,
        },
      }),
    ).toMatchObject({
      certainty: "known",
      usedTokens: 689_000,
      percentage: 68.9,
    });
  });

  /** 摘要模型的 Token 预算与用户会话当前上下文无关，不能成为圆环的基准。 */
  it("不把摘要请求用量显示为对话上下文", () => {
    expect(resolve({ usage: { ...USAGE, purpose: "summary" } })).toBeUndefined();
  });
});
