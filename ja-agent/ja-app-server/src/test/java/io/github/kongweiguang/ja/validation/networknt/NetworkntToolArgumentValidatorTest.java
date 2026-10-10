// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.validation.networknt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    /** 标准 JSON Schema required 必须拒绝缺字段，不能让拼错的扩展关键字静默放行。 */
    @Test
    void rejectsMissingRequiredProperties() {
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator("""
                {"type":"object","properties":{"path":{"type":"string"}},
                 "required":["path"],"additionalProperties":false}
                """);

        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> validator.validate("{}"));

        assertTrue(failure.getMessage().contains("required"));
        assertFalse(failure.getMessage().contains("secret"));
    }

    /** 只预检真实 Schema pattern，const/default/enum/examples 中长得像 Schema 的数据保持原生语义。 */
    @Test
    void doesNotInterpretLiteralSchemaValuesAsSubschemas() {
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator("""
                {"type":"object","properties":{
                  "constant":{"const":{"pattern":"["}},
                  "defaulted":{"type":"object","default":{"pattern":"["}},
                  "choice":{"enum":[{"pattern":"["}]},
                  "example":{"type":"object","examples":[{"pattern":"["}]}
                },"required":["constant","choice"],"additionalProperties":false}
                """);

        assertDoesNotThrow(() -> validator.validate("""
                {"constant":{"pattern":"["},"choice":{"pattern":"["}}
                """));
    }

    /** 非法 Schema 正则中的合成秘密不得进入 Networknt 自身日志或异常链。 */
    @Test
    void rejectsMalformedPatternBeforeThirdPartyLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.networknt.schema.regex.PatternValidator");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);
        try {
            String schema = """
                    {"type":"object","properties":{"credential":{"type":"string",
                     "pattern":"[SYNTHETIC_PATTERN_SECRET"}}}
                    """;

            ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                    () -> new NetworkntToolArgumentValidator(schema));

            assertEquals("tool input schema is invalid", failure.getMessage());
            assertEquals(null, failure.getCause());
            assertTrue(appender.list.isEmpty());
            assertFalse(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                    .contains("SYNTHETIC_PATTERN_SECRET")));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            appender.stop();
        }
    }

    /** 有效表达式仍由 Networknt 的默认 JDK regex 实现完整解释，而非由预检层替代。 */
    @Test
    void validPatternKeepsNetworkntValidationSemantics() {
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator("""
                {"type":"object","properties":{"value":{"type":"string","pattern":"^[a-z]+$"}},
                 "required":["value"],"additionalProperties":false}
                """);

        assertDoesNotThrow(() -> validator.validate("{\"value\":\"valid\"}"));
        ToolSchemaException failure = assertThrows(ToolSchemaException.class,
                () -> validator.validate("{\"value\":\"NOT_valid\"}"));
        assertTrue(failure.getMessage().contains("pattern"));
    }

    /** 超过旧 4 MiB 应用门槛的合法 Tool JSON 仍进入 Schema 校验。 */
    @Test
    void validatesLargeArgumentsWithoutATurnSizeLimit() {
        NetworkntToolArgumentValidator validator = new NetworkntToolArgumentValidator("""
                {"type":"object","properties":{"payload":{"type":"string"}},
                 "required":["payload"],"additionalProperties":false}
                """);
        String arguments = "{\"payload\":\"" + "x".repeat(20_100_000) + "\"}";

        assertDoesNotThrow(() -> validator.validate(arguments));
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



