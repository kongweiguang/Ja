// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.in.web.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证工具 Schema 的 JSON null 值与顶层只读快照在 DTO 边界保持不变。 */
final class ExperienceToolViewTest {
    /**
     * JSON Schema 允许 null，且调用方不能借嵌套 Map/List 修改已发布的响应快照。
     */
    @Test
    void preservesNullValuesAndFreezesNestedSchemaValues() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("const", null);
        List<Object> required = new ArrayList<>(List.of("value"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("value", property);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("nullable", null);
        ExperienceToolView view = new ExperienceToolView("example", "Example", schema);
        property.put("type", "string");
        properties.clear();
        required.set(0, "changed");

        assertNull(view.inputSchema().get("nullable"));
        Map<?, ?> frozenProperties = (Map<?, ?>) view.inputSchema().get("properties");
        Map<?, ?> frozenProperty = (Map<?, ?>) frozenProperties.get("value");
        List<?> frozenRequired = (List<?>) view.inputSchema().get("required");
        assertNull(frozenProperty.get("const"));
        assertEquals(List.of("value"), frozenRequired);
        assertThrows(UnsupportedOperationException.class, () -> view.inputSchema().put("type", "string"));
        assertThrows(UnsupportedOperationException.class, frozenProperties::clear);
        assertThrows(UnsupportedOperationException.class, frozenProperty::clear);
        assertThrows(UnsupportedOperationException.class, frozenRequired::clear);
    }

    /** 循环对象图不是 JSON，必须以明确输入错误结束而不能无限递归。 */
    @Test
    void rejectsCyclicSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("self", schema);

        assertThrows(IllegalArgumentException.class, () -> new ExperienceToolView("example", "Example", schema));
    }

    /** 远端或扩展 Schema 的深度有上限，避免递归复制耗尽工作线程栈。 */
    @Test
    void rejectsExcessiveSchemaNesting() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> current = schema;
        for (int depth = 0; depth < 129; depth++) {
            Map<String, Object> nested = new LinkedHashMap<>();
            current.put("nested", nested);
            current = nested;
        }

        assertThrows(IllegalArgumentException.class, () -> new ExperienceToolView("example", "Example", schema));
    }
}
