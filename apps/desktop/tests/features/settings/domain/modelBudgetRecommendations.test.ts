// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { getModelBudgetRecommendation } from "@/features/settings/domain/modelBudgetRecommendations";

describe("getModelBudgetRecommendation", () => {
  /** 只有精确官方端点与已核验模型 ID 才能给厂商级推荐，避免把代理能力当成事实。 */
  it("recognizes exact official DeepSeek V4 endpoints and IDs", () => {
    expect(
      getModelBudgetRecommendation("deepseek-v4-pro", "https://api.deepseek.com"),
    ).toMatchObject({
      contextWindowTokens: 1_000_000,
      maxOutputTokens: 8_192,
      label: "推荐预算",
    });
    expect(
      getModelBudgetRecommendation(" deepseek-v4-flash ", " https://api.deepseek.com "),
    ).toMatchObject({ contextWindowTokens: 1_000_000 });
  });

  /** 近似域名、端口、凭据、代理路径和旧模型 ID 都降级为通用起始预算。 */
  it.each([
    ["https://api.deepseek.com.example.com", "deepseek-v4-pro"],
    ["https://api.deepseek.com:8443", "deepseek-v4-pro"],
    ["https://user:token@api.deepseek.com", "deepseek-v4-pro"],
    ["https://proxy.example.test/deepseek", "deepseek-v4-pro"],
    ["https://api.deepseek.com", "deepseek-chat"],
    ["https://api.deepseek.com", "deepseek-v4-pro-preview"],
  ])("does not infer vendor limits for %s / %s", (baseUrl, model) => {
    expect(getModelBudgetRecommendation(model, baseUrl)).toMatchObject({
      contextWindowTokens: 128_000,
      maxOutputTokens: 8_192,
      label: "通用起始预算",
    });
  });

  /** 未知模型仍提供可调整的安全起点，推荐值不会等同于用户已经输入的真实能力。 */
  it("keeps unknown models on an explicit adjustable baseline", () => {
    const recommendation = getModelBudgetRecommendation("my-custom-model", "not a valid url");
    expect(recommendation.description).toContain("按供应商实际额度调整");
    expect(recommendation.contextWindowTokens).toBe(128_000);
  });
});
