// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  inspectProtocolRequest,
  latestScenario,
  multiProviderConfiguration as mediaSchemaConfiguration,
  parseLaunchArguments,
  startCapabilityProviderFixture,
} from "./media-schema-loopback.mjs";
import { multiProviderConfiguration as processSessionConfiguration } from "./process-session-full-access.mjs";

const imageBytes = Buffer.from("89504e470d0a1a0a", "hex");
const encodedImage = imageBytes.toString("base64");
const schema = {
  type: "object",
  properties: { answer: { type: "string", enum: ["JA_SCHEMA_OK"] } },
  required: ["answer"],
  additionalProperties: false,
};

/** 固定 Native 产物是显式覆盖，默认空参数仍选择经 JDK 25 校验的 JAR 路径。 */
test("media/schema launch arguments accept one explicit Native executable", () => {
  assert.deepEqual(parseLaunchArguments([]), { executable: undefined });
  assert.deepEqual(parseLaunchArguments(["--executable", "target/ja-app-server.exe"]), {
    executable: "target/ja-app-server.exe",
  });
  assert.deepEqual(parseLaunchArguments(["--executable=target/ja-app-server.exe"]), {
    executable: "target/ja-app-server.exe",
  });
  assert.throws(() => parseLaunchArguments(["--executable"]), /requires a path/u);
  assert.throws(
    () => parseLaunchArguments(["--executable", "a.exe", "--executable", "b.exe"]),
    /only once/u,
  );
  assert.throws(() => parseLaunchArguments(["--unknown"]), /unsupported/u);
});

/** 历史 marker 与前缀冲突均按实际场景顺序和最具体 marker 识别。 */
test("latest scenario marker follows conversation order and resolves prefix collisions", () => {
  const markers = new Map([
    ["valid:responses", "JA_CAP_RESPONSES_SCHEMA_VALID"],
    ["invalid:responses", "JA_CAP_RESPONSES_SCHEMA_INVALID"],
    ["invalid_schema:responses", "JA_CAP_RESPONSES_SCHEMA_REQUEST_INVALID"],
    ["media:responses", "JA_CAP_RESPONSES_MEDIA"],
    ["mcp:responses", "JA_CAP_RESPONSES_MCP_IMAGE"],
    ["mcp_invalid:responses", "JA_CAP_RESPONSES_MCP_IMAGE_INVALID"],
  ]);
  assert.equal(
    latestScenario(
      {
        input: [
          { type: "message", content: "Marker: JA_CAP_RESPONSES_SCHEMA_VALID" },
          { type: "message", content: "Marker: JA_CAP_RESPONSES_SCHEMA_REQUEST_INVALID" },
          { type: "message", content: "Marker: JA_CAP_RESPONSES_SCHEMA_INVALID" },
        ],
      },
      markers,
    ),
    "invalid:responses",
  );
  assert.equal(
    latestScenario(
      {
        input: [{ type: "message", content: "Marker: JA_CAP_RESPONSES_MCP_IMAGE_INVALID" }],
      },
      markers,
    ),
    "mcp_invalid:responses",
  );
});

/** 独立凭据 owner 避开首写限制；单模型能力降级用于验证 Java 的 Schema 前置拒绝。 */
test("multi-provider acceptance configuration isolates credentials and Schema capability", () => {
  const endpoint = "http://localhost:60842/fixture";
  const documents = [mediaSchemaConfiguration(endpoint), processSessionConfiguration(endpoint)];

  for (const document of documents) {
    const credentialIds = document.providers.map((provider) => provider.credential_id);
    assert.equal(new Set(credentialIds).size, document.providers.length);
    assert.ok(credentialIds.every((credentialId) => credentialId.startsWith("cred_")));
  }

  assert.ok(
    documents[0].providers.every(
      (provider) => provider.models[0].capabilities.supports_output_schema === true,
    ),
  );
  assert.ok(
    documents[0].providers.every(
      (provider) => provider.agent_defaults.context.auto_compact === false,
    ),
  );
  assert.ok(
    documents[0].providers.every(
      (provider) => provider.models[0].capabilities.context_window_tokens === 1_000_000,
    ),
  );
  assert.deepEqual(
    documents[0].providers.map((provider) => provider.models[0].model),
    ["gpt-4o-mini", "gpt-4o-mini", "claude-3-5-sonnet-20241022"],
  );
  const unsupportedSchema = mediaSchemaConfiguration(
    endpoint,
    undefined,
    "provider_media_responses",
  );
  assert.equal(
    unsupportedSchema.providers.find(
      (provider) => provider.provider_id === "provider_media_responses",
    )?.models[0]?.capabilities.supports_output_schema,
    false,
  );
  assert.ok(
    unsupportedSchema.providers
      .filter((provider) => provider.provider_id !== "provider_media_responses")
      .every((provider) => provider.models[0].capabilities.supports_output_schema === true),
  );
  assert.throws(
    () => mediaSchemaConfiguration(endpoint, undefined, "provider_unknown"),
    /provider is unknown/u,
  );
  const withMcp = mediaSchemaConfiguration(endpoint, "http://127.0.0.1:60842/mcp");
  assert.deepEqual(withMcp.mcp_servers, [
    {
      mcp_id: "mcp_media_fixture",
      name: "Ja media fixture",
      transport: "streamable_http",
      endpoint: "http://127.0.0.1:60842/mcp",
      args: [],
      env: {},
      headers: {},
      auth: { kind: "none" },
      enabled: true,
    },
  ]);
});

/** MCP loopback 使用标准 JSON-RPC 方法返回 image content，并保留 invalid Base64 故障注入。 */
test("MCP media fixture serves typed image content over Streamable HTTP", async () => {
  const source = Buffer.concat([imageBytes, Buffer.from("MCP")]);
  const fixture = await startCapabilityProviderFixture(imageBytes, source);
  const post = async (id, method, params = {}) => {
    const response = await fetch(fixture.mcpEndpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
    });
    return { response, body: await response.json() };
  };
  try {
    const initialized = await post(1, "initialize", {});
    assert.equal(initialized.response.status, 200);
    assert.equal(initialized.body.result.protocolVersion, "2025-06-18");
    const listed = await post(2, "tools/list", {});
    assert.equal(listed.body.result.tools[0].name, "read_fixture_image");
    const called = await post(3, "tools/call", {
      name: "read_fixture_image",
      arguments: { corrupt: false },
    });
    assert.equal(called.body.result.content[0].type, "image");
    assert.equal(called.body.result.content[0].mimeType, "image/png");
    assert.deepEqual(Buffer.from(called.body.result.content[0].data, "base64"), source);
    const invalid = await post(4, "tools/call", {
      name: "read_fixture_image",
      arguments: { corrupt: true },
    });
    assert.equal(invalid.body.result.content[0].type, "image");
    assert.equal(invalid.body.result.content[0].data, "invalid-base64");
    assert.deepEqual(fixture.mcpCalls, [
      { toolName: "read_fixture_image", corrupt: false },
      { toolName: "read_fixture_image", corrupt: true },
    ]);
  } finally {
    await fixture.close();
  }
});

/** 保证 Responses Tool 结果以类型化 input_image 原样进入下一次模型请求。 */
test("Responses recognizes image bytes attached to a function_call_output", () => {
  const evidence = inspectProtocolRequest(
    "openai_responses",
    {
      text: { format: { type: "json_schema", schema } },
      input: [
        {
          type: "function_call_output",
          call_id: "call_fixture",
          output: [{ type: "input_image", image_url: "data:image/png;base64," + encodedImage }],
        },
      ],
    },
    imageBytes,
  );
  assert.equal(evidence.schemaDeclared, true);
  assert.deepEqual(evidence.schema, {
    declared: true,
    type: "object",
    required: ["answer"],
    answerEnum: ["JA_SCHEMA_OK"],
  });
  assert.equal(evidence.toolImageCount, 1);
  assert.equal(evidence.toolImageDigestMatches, true);
});

/** 验证 Anthropic tool_result 使用原生 base64 source block，不接受跨协议 data URL 替代。 */
test("Anthropic recognizes image bytes inside a tool_result block", () => {
  const evidence = inspectProtocolRequest(
    "anthropic_messages",
    {
      output_config: { format: { type: "json_schema", schema } },
      messages: [
        {
          role: "user",
          content: [
            {
              type: "tool_result",
              tool_use_id: "call_fixture",
              content: [
                {
                  type: "image",
                  source: { type: "base64", media_type: "image/png", data: encodedImage },
                },
              ],
            },
          ],
        },
      ],
    },
    imageBytes,
  );
  assert.equal(evidence.schemaDeclared, true);
  assert.equal(evidence.toolImageCount, 1);
  assert.equal(evidence.toolImageDigestMatches, true);
});

/** 确认 Chat 可声明结构化输出，但普通 Tool 文本不会被伪装成原生图片能力。 */
test("Chat schema evidence does not mistake ordinary tool text for image support", () => {
  const evidence = inspectProtocolRequest(
    "openai_chat_completions",
    {
      response_format: { type: "json_schema", json_schema: { schema } },
      messages: [{ role: "tool", content: "image output is unsupported" }],
    },
    imageBytes,
  );
  assert.equal(evidence.schemaDeclared, true);
  assert.equal(evidence.imageCount, 0);
  assert.equal(evidence.toolImageCount, 0);
  assert.equal(evidence.toolImageDigestMatches, false);
});
