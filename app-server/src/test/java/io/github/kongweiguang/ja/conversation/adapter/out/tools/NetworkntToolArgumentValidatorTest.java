// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证 Tool Schema 边界不依赖 Provider 或任何 Jackson 主版本类型。 */
final class NetworkntToolArgumentValidatorTest {
    /** 有效对象通过，而类型不匹配只返回字段位置和约束，不回显字段值。 */
    @Test
    void validatesArgumentsWithoutProviderCoupling() {
        String schema = """
                {"type":"object","properties":{"path":{"type":"string"}},
                 "required":["path"],"additionalProperties":false}
                """;
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator(schema);

        assertDoesNotThrow(() -> validator.validate("{\"path\":\"README.md\"}"));
        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> validator.validate("{\"path\":7}"));
        assertEquals("Tool field at /path has the wrong JSON type", failure.getMessage());
        assertFalse(failure.getMessage().contains("7"));
    }

    /** 数值约束只展示 Schema 的静态边界和安全字段位置，不回显传入的秘密值。 */
    @Test
    void describesMinimumLengthWithoutEchoingArgument() {
        String schema = """
                {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":512},
                 "secret":{"type":"string"}},
                 "required":["query"],"additionalProperties":false}
                """;
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator(schema);

        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> validator.validate("{\"query\":\"\",\"secret\":\"PRIVATE_VALUE\"}"));

        assertTrue(failure.getMessage().contains("query"));
        assertTrue(failure.getMessage().contains("minLength"));
        assertTrue(failure.getMessage().contains("1"));
        assertFalse(failure.getMessage().contains("PRIVATE_VALUE"));
    }

    /** exclusive 数值边界必须明确排除等值，避免错误诊断引导模型重复提交同一非法值。 */
    @Test
    void describesExclusiveNumericBoundaries() {
        NetworkntToolArgumentValidator minimum = new NetworkntToolArgumentValidator("""
                {"type":"object","properties":{"value":{"type":"number","exclusiveMinimum":3}},
                 "required":["value"],"additionalProperties":false}
                """);
        ToolSchemaException lower = assertThrows(ToolSchemaException.class,
                () -> minimum.validate("{\"value\":3}"));
        assertTrue(lower.getMessage().contains("exclusiveMinimum"));
        assertTrue(lower.getMessage().contains("greater than 3"));

        NetworkntToolArgumentValidator maximum = new NetworkntToolArgumentValidator("""
                {"type":"object","properties":{"value":{"type":"number","exclusiveMaximum":9}},
                 "required":["value"],"additionalProperties":false}
                """);
        ToolSchemaException upper = assertThrows(ToolSchemaException.class,
                () -> maximum.validate("{\"value\":9}"));
        assertTrue(upper.getMessage().contains("exclusiveMaximum"));
        assertTrue(upper.getMessage().contains("less than 9"));
    }

    /** 非法 Schema、参数内容和第三方异常不会进入公开诊断或 cause 链。 */
    @Test
    void boundsInvalidSchemaDiagnostics() {
        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> new NetworkntToolArgumentValidator("\"not-a-schema-object\""));
        assertFalse(failure.getMessage().contains("not-a-schema-object"));
        assertFalse(failure.getMessage().contains("password"));
        assertEquals(null, failure.getCause());
    }

    /** 非法 JSON 参数同样被归一为固定错误，不能回显敏感字段。 */
    @Test
    void redactsMalformedArguments() {
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator("true");

        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> validator.validate("{\"password\":\"secret\""));

        assertEquals("tool arguments could not be validated", failure.getMessage());
        assertFalse(failure.getMessage().contains("password"));
        assertEquals(null, failure.getCause());
    }

    /** 外部 $ref 不能触发网络或文件加载，只能在本地失败关闭并返回固定错误。 */
    @Test
    void rejectsExternalSchemaResolution() {
        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> new NetworkntToolArgumentValidator(
                        "{\"$ref\":\"https://example.invalid/tool-schema.json\"}").validate("{}"));

        assertFalse(failure.getMessage().contains("example.invalid"));
        assertEquals(null, failure.getCause());
    }
}
