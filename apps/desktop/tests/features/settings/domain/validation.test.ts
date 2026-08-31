// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  mcpSchema,
  providerModelSchema,
  providerSchema,
} from "@/features/settings/domain/validation";

const provider = {
  providerId: "provider_openai",
  name: "OpenAI",
  provider: "openai",
  api: "openai_responses",
  baseUrl: "https://api.openai.com/v1",
  credentialId: "cred_openai",
  networkTimeouts: { connectTimeoutMs: 10_000, requestTimeoutMs: 120_000 },
  agentDefaults: {
    context: { autoCompact: true },
    turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3_600_000 },
  },
} as const;
const model = {
  modelId: "model_gpt",
  name: "GPT",
  model: "gpt-5.6-sol",
  capabilities: {
    contextWindowTokens: 256_000,
    maxOutputTokens: 32_000,
  },
  reasoningLevelMap: { low: "low", high: "high" },
  defaultReasoningLevel: "high",
} as const;

describe("settings v4 validation", () => {
  it("accepts a native Provider/API pair", () => {
    expect(providerSchema.safeParse(provider).success).toBe(true);
  });

  it("rejects Provider/API mismatch and unsafe URL", () => {
    expect(providerSchema.safeParse({ ...provider, api: "anthropic_messages" }).success).toBe(
      false,
    );
    expect(
      providerSchema.safeParse({ ...provider, baseUrl: "http://example.com/v1" }).success,
    ).toBe(false);
  });

  it("requires model default reasoning to belong to the supported set", () => {
    expect(providerModelSchema.safeParse(model).success).toBe(true);
    expect(
      providerModelSchema.safeParse({ ...model, defaultReasoningLevel: "medium" }).success,
    ).toBe(false);
  });

  it("rejects renderer-owned modalities and output limits beyond context", () => {
    expect(
      providerModelSchema.safeParse({
        ...model,
        capabilities: { ...model.capabilities, inputModalities: ["text", "text"] },
      }).success,
    ).toBe(false);
    expect(
      providerModelSchema.safeParse({
        ...model,
        capabilities: { ...model.capabilities, maxOutputTokens: 256_000 },
      }).success,
    ).toBe(false);
  });

  it("accepts transport-specific MCP fields and rejects literal credentials", () => {
    const http = {
      name: "Docs",
      transport: "streamable_http",
      endpoint: "https://mcp.example.com/rpc",
      argsText: "",
      envText: "",
      headersText: "X-Tenant=ja",
      authKind: "header",
      authName: "X-API-Key",
      credentialRef: "cred_mcp_docs",
      enabled: true,
    } as const;
    expect(mcpSchema.safeParse(http).success).toBe(true);
    expect(
      mcpSchema.safeParse({ ...http, headersText: "Authorization=Bearer secret" }).success,
    ).toBe(false);
    expect(mcpSchema.safeParse({ ...http, argsText: "--stdio" }).success).toBe(false);
  });
});
