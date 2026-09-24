// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 验证面向模型的网关动作符合严格 Schema 并拒绝空动作。 */
final class McpAgentToolSchemaTest {
    /** action 为非空必填枚举，其余辅助参数仍为必填属性。 */
    @Test
    void actionSchemaIsNonNullableAndUsesTheFourSupportedActions() {
        ToolSpec spec = McpAgentTool.modelSpec();
        JsonObject schema = spec.inputSchema();
        JsonObject properties = (JsonObject) schema.get("properties");
        JsonObject action = (JsonObject) properties.get("action");
        JsonArray required = (JsonArray) schema.get("required");
        JsonArray actions = (JsonArray) action.get("enum");

        assertEquals("string", ((JsonText) action.get("type")).value());
        assertTrue(actions.values().stream().noneMatch(value -> value == JsonNull.INSTANCE));
        assertEquals(List.of("status", "search", "describe", "call"),
                actions.values().stream().map(value -> ((JsonText) value).value()).toList());
        assertEquals(List.of("action", "serverId", "toolName", "query", "offset", "argumentsJson"),
                required.values().stream().map(value -> ((JsonText) value).value()).toList());
        assertTrue(properties.get("serverId") instanceof JsonObject);
    }
}
