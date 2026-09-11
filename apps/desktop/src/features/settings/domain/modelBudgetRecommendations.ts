// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 预算建议只帮助填写草稿，不声明输入能力，也不改变 App Server 的配置所有权。 */
export interface ModelBudgetRecommendation {
  contextWindowTokens: number;
  maxOutputTokens: number;
  label: string;
  description: string;
}

const deepSeekV4Models = new Set([
  "deepseek-v4-flash",
  "deepseek-v4-pro",
  "deepseek-v4-flash-vision-exp",
]);

/**
 * 仅对已核验的官方端点与精确模型 ID 提示厂商窗口，避免同名代理或模糊前缀冒充已知能力。
 * DeepSeek 来源：https://api-docs.deepseek.com/quick_start/pricing，核验于 2026-09-09。
 * 输出建议沿用 Ja 的 8,192 起始预算而非厂商最大输出；未知模型不猜规格，且建议须显式应用。
 */
export function getModelBudgetRecommendation(
  model: string,
  baseUrl: string,
): ModelBudgetRecommendation {
  let officialDeepSeek = false;
  try {
    const endpoint = new URL(baseUrl.trim());
    officialDeepSeek =
      endpoint.protocol === "https:" &&
      endpoint.hostname === "api.deepseek.com" &&
      endpoint.port === "" &&
      endpoint.username === "" &&
      endpoint.password === "";
  } catch {
    // 尚未填写有效地址时保持通用建议，URL 合法性由供应商表单统一反馈。
  }
  if (officialDeepSeek && deepSeekV4Models.has(model.trim())) {
    return {
      contextWindowTokens: 1_000_000,
      maxOutputTokens: 8_192,
      label: "推荐预算",
      description: "官方上下文 1M，输出采用 8,192 起始预算；可按需调整。",
    };
  }
  return {
    contextWindowTokens: 128_000,
    maxOutputTokens: 8_192,
    label: "通用起始预算",
    description: "尚无此模型的已核验推荐，请按供应商实际额度调整。",
  };
}
