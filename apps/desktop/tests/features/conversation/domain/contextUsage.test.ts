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
  requestId: "request_1",
  requestOrdinal: 1,
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
  it("只按请求画像投影 Provider 的真实输入 Token", () => {
    expect(resolve()).toEqual({
      certainty: "known",
      usedTokens: 42_000,
      limitTokens: 128_000,
      percentage: 33,
      ringPercentage: 32.8125,
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

  /** 压缩只在成功且晚于 Provider Usage 时覆盖输入量，旧事件或中间态不能倒退展示事实。 */
  it("只采用更新的压缩后计量", () => {
    expect(
      resolve({
        compaction: {
          phase: "compacted",
          inputTokensAfter: 12_000,
          occurredAt: "2026-08-31T00:00:02Z",
        },
      }),
    ).toMatchObject({
      usedTokens: 12_000,
      percentage: 9,
      source: "compaction",
      measuredAt: "2026-08-31T00:00:02Z",
    });
    expect(
      resolve({
        compaction: {
          phase: "compacted",
          inputTokensAfter: 12_000,
          occurredAt: "2026-08-31T00:00:00Z",
        },
      }),
    ).toMatchObject({ usedTokens: 42_000, source: "provider" });
    expect(
      resolve({
        compaction: {
          phase: "started",
          inputTokensAfter: null,
          occurredAt: "2026-08-31T00:00:02Z",
        },
      }),
    ).toMatchObject({ usedTokens: 42_000, source: "provider" });
  });

  /** 80% 与 95% 是完整窗口上的稳定阈值，环形进度在超限时仍约束在可绘制范围。 */
  it("在 80% 警告并在 95% 进入危险态", () => {
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 79_000,
          totalTokens: 81_000,
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
          totalTokens: 82_000,
        },
      }),
    ).toMatchObject({ percentage: 80, ringPercentage: 80, tone: "warning" });
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 95_000,
          totalTokens: 97_000,
        },
      }),
    ).toMatchObject({ percentage: 95, ringPercentage: 95, tone: "danger" });
    expect(
      resolve({
        usage: {
          ...USAGE,
          profile: { ...PROFILE, contextWindowTokens: 100_000 },
          inputTokens: 120_000,
          totalTokens: 122_000,
        },
      }),
    ).toMatchObject({ percentage: 120, ringPercentage: 100, tone: "danger" });
  });
});
