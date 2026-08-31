// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定纯 JDK JSON 值的顺序、不可变性、null 语义与 Jackson 精确往返。 */
final class JacksonJsonValuesTest {
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** 构造时递归冻结集合且保留成员顺序，调用方后续修改不能改变已准入 JSON。 */
    @Test
    void preservesOrderAndDeepImmutability() {
        List<JsonValue> sourceArray = new ArrayList<>(List.of(new JsonText("初始")));
        Map<String, JsonValue> sourceObject = new LinkedHashMap<>();
        sourceObject.put("first", new JsonArray(sourceArray));
        sourceObject.put("second", JsonNull.INSTANCE);
        JsonObject object = new JsonObject(sourceObject);

        sourceArray.add(new JsonBoolean(true));
        sourceObject.put("third", new JsonNumber(3));

        assertEquals(List.of("first", "second"), List.copyOf(object.members().sequencedKeySet()));
        assertEquals(List.of(new JsonText("初始")), ((JsonArray) object.get("first")).values());
        assertThrows(UnsupportedOperationException.class,
                () -> object.members().put("forbidden", new JsonBoolean(false)));
        assertThrows(UnsupportedOperationException.class,
                () -> ((JsonArray) object.get("first")).values().add(JsonNull.INSTANCE));
    }

    /** 字段缺失与显式 JSON null 必须可区分，Java null 不能伪装成任一种状态。 */
    @Test
    void distinguishesMissingFromExplicitNullAndRejectsJavaNull() {
        JsonObject object = new JsonObject(Map.of("present", JsonNull.INSTANCE));

        assertTrue(object.containsKey("present"));
        assertEquals(JsonNull.INSTANCE, object.get("present"));
        assertFalse(object.containsKey("missing"));
        assertEquals(null, object.get("missing"));
        assertThrows(NullPointerException.class, () -> new JsonArray(java.util.Arrays.asList((JsonValue) null)));
        Map<String, JsonValue> invalid = new LinkedHashMap<>();
        invalid.put("invalid", null);
        assertThrows(NullPointerException.class, () -> new JsonObject(invalid));
    }

    /** Unicode、转义、超大整数、小数和指数按 BigDecimal 精度及对象顺序双向往返。 */
    @Test
    void roundTripsUnicodeEscapesNumbersAndNull() throws Exception {
        JsonNode wire = mapper.readTree("""
                {"文字":"行1\\n\\\"引号\\\"","big":1234567890123456789012345678901234567890,"decimal":0.000000000000000000123400,"exponent":1.2300e+100,"null":null}
                """);
        JsonValue value = JacksonJsonValues.fromNode(wire);
        JsonNode restored = JacksonJsonValues.toNode(mapper, value);
        JsonObject object = (JsonObject) value;

        assertEquals(List.of("文字", "big", "decimal", "exponent", "null"),
                List.copyOf(object.members().sequencedKeySet()));
        assertEquals("行1\n\"引号\"", ((JsonText) object.get("文字")).value());
        assertEquals(new BigDecimal("1234567890123456789012345678901234567890"),
                ((JsonNumber) object.get("big")).value());
        assertEquals(new BigDecimal("0.000000000000000000123400"),
                ((JsonNumber) object.get("decimal")).value());
        assertEquals(new BigDecimal("1.2300e+100"), ((JsonNumber) object.get("exponent")).value());
        assertEquals(JsonNull.INSTANCE, object.get("null"));
        assertEquals(value, JacksonJsonValues.fromNode(restored));
    }

    /** Jackson 缺失、二进制和 Java null 不属于严格 JSON 树，必须在 Adapter 边界失败。 */
    @Test
    void rejectsUnsupportedJacksonAndJavaValues() {
        assertThrows(IllegalArgumentException.class, () -> JacksonJsonValues.fromNode(null));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonJsonValues.fromNode(mapper.getNodeFactory().missingNode()));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonJsonValues.fromNode(mapper.getNodeFactory().binaryNode(new byte[]{1, 2})));
    }
}
