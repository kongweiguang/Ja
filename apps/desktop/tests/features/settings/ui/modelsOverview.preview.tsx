// @author kongweiguang
import { createRoot } from "react-dom/client";
import { ModelsSection } from "@/features/settings/ui/models";
import type { SettingsPorts } from "@/features/settings/application/ports";
import type { ProviderProjection } from "@/features/settings/domain/types";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/features/settings/ui/settings.css";

const provider: ProviderProjection = {
  providerId: "preview",
  name: "local-cockpit",
  api: "openai_responses",
  baseUrl: "https://example.test/v1",
  credentialId: "fixture",
  credentialConfigured: true,
  networkTimeouts: { connectTimeoutMs: 10000, requestTimeoutMs: 120000 },
  agentDefaults: {
    context: { autoCompact: true },
    turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3600000 },
  },
  models: ["gpt-5.6-sol", "gpt-5.6-mini"].map((model) => ({
    modelId: model,
    name: model,
    model,
    capabilities: { contextWindowTokens: 256000, maxOutputTokens: 8192 },
    reasoningLevelMap: {},
    defaultReasoningLevel: null,
  })),
};
/** 浏览器预览只模拟外部端口，始终渲染生产组件与全局样式，不发起网络写入。 */
const ports = new Proxy({}, { get: () => async () => undefined }) as SettingsPorts;
createRoot(document.getElementById("root")!).render(
  <main style={{ maxWidth: 960, margin: "0 auto", padding: "56px 32px" }}>
    <ModelsSection
      providers={[provider, { ...provider, providerId: "deepseek", name: "DeepSeek" }]}
      defaultSelection={{ providerId: "preview", modelId: "gpt-5.6-sol", reasoningLevel: null }}
      snapshotRevision={1}
      ports={ports}
    />
  </main>,
);
