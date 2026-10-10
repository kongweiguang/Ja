// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.mcp;

import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.McpDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** MCP 目录视图须与 SDK 会话脱离并保持只读。 */
final class McpServiceToolViewTest {
    /** 深度快照防止 SDK 复用的 Schema 容器在目录返回后改变 HTTP 响应。 */
    @Test
    void freezesNestedSchemaAndPreservesNull() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("const", null);
        List<Object> required = new ArrayList<>(List.of("field"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("field", field);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("properties", properties);
        schema.put("required", required);
        McpDirectory.ToolView view = new McpDirectory.ToolView("example", "Example", schema);

        field.put("type", "string");
        required.set(0, "changed");
        Map<?, ?> frozenProperties = (Map<?, ?>) view.inputSchema().get("properties");
        Map<?, ?> frozenField = (Map<?, ?>) frozenProperties.get("field");
        List<?> frozenRequired = (List<?>) view.inputSchema().get("required");

        assertNull(frozenField.get("const"));
        assertEquals(List.of("field"), frozenRequired);
        assertThrows(UnsupportedOperationException.class, frozenField::clear);
        assertThrows(UnsupportedOperationException.class, frozenRequired::clear);
    }
}
