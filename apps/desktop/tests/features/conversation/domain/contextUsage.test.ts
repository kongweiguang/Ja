// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { resolveContextUsage } from "@/features/conversation/domain/contextUsage";
import type {
  TimelineContextUsage,
  TimelineTurnRuntimeSnapshot,
} from "@/features/conversation/domain/timelineTypes";

const RUNTIME: TimelineTurnRuntimeSnapshot = {
  providerId: "provider_openai",
  modelId: "model_gpt",
  provider: "openai",
  api: "openai_responses",
  upstreamModel: "gpt-5.6-sol",
  reasoningLevel: "medium",
  accessMode: "approval_required",
  configGeneration: "cfg_1",
};

const USAGE: TimelineContextUsage = {
  turnId: "turn_1",
  modelRound: 1,
  inputTokens: 42_000,
  outputTokens: 2_000,
  totalTokens: 44_000,
  measuredAt: "2026-08-31T00:00:01Z",
};

/** 统一构造已通过身份冻结的输入，让各断言只改变其关心的上下文事实。 */
function resolve(overrides: Partial<Parameters<typeof resolveContextUsage>[0]> = {}) {
  return resolveContextUsage({
    usage: USAGE,
    runtime: RUNTIME,
    providerId: RUNTIME.providerId,
    modelId: RUNTIME.modelId,
    contextWindowTokens: 128_000,
    ...overrides,
  });
}

describe("context usage presentation", () => {
  /** 只有 Usage 所属 Turn 的冻结 Provider/Model 与当前选择一致时，才允许显示真实占用。 */
  it("只向当前模型投影 Provider 的真实输入 Token", () => {
    expect(resolve()).toEqual({
      usedTokens: 42_000,
      limitTokens: 128_000,
      percentage: 33,
      ringPercentage: 32.8125,
      tone: "neutral",
      source: "provider",
      measuredAt: USAGE.measuredAt,
    });
    expect(resolve({ providerId: "provider_anthropic" })).toBeUndefined();
    expect(resolve({ modelId: "model_other" })).toBeUndefined();
    expect(resolve({ usage: undefined })).toBeUndefined();
    expect(resolve({ runtime: undefined })).toBeUndefined();
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
        usage: { ...USAGE, inputTokens: 79_000, totalTokens: 81_000 },
        contextWindowTokens: 100_000,
      }),
    ).toMatchObject({
      percentage: 79,
      tone: "neutral",
    });
    expect(
      resolve({
        usage: { ...USAGE, inputTokens: 80_000, totalTokens: 82_000 },
        contextWindowTokens: 100_000,
      }),
    ).toMatchObject({ percentage: 80, ringPercentage: 80, tone: "warning" });
    expect(
      resolve({
        usage: { ...USAGE, inputTokens: 95_000, totalTokens: 97_000 },
        contextWindowTokens: 100_000,
      }),
    ).toMatchObject({ percentage: 95, ringPercentage: 95, tone: "danger" });
    expect(
      resolve({
        usage: { ...USAGE, inputTokens: 120_000, totalTokens: 122_000 },
        contextWindowTokens: 100_000,
      }),
    ).toMatchObject({ percentage: 120, ringPercentage: 100, tone: "danger" });
  });
});
