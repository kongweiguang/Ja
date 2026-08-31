// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 验证 Tool Schema 边界不依赖 Provider 或任何 Jackson 主版本类型。 */
final class NetworkntToolArgumentValidatorTest {
    /** 有效对象通过，而类型不匹配只返回固定的有界本地错误。 */
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
        assertEquals("tool arguments do not match the JSON Schema", failure.getMessage());
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
