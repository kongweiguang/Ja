// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 MCP SDK 弱类型对象图只在 Adapter 内出现，并能无损往返严格 JSON。 */
final class McpJsonValuesTest {
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** SDK Map 的顺序、数字和 null 转为 JsonObject 后再恢复时保持语义。 */
    @Test
    void roundTripsSdkArgumentsWithoutLeakingAliases() {
        LinkedHashMap<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("text", "你好");
        sdk.put("number", new BigDecimal("12345678901234567890.00100"));
        sdk.put("nested", Arrays.asList(null, true));

        JsonObject value = McpJsonValues.objectFromSdk(mapper, sdk);
        Map<String, Object> restored = McpJsonValues.toSdkArguments(mapper, value);

        assertEquals(List.of("text", "number", "nested"), List.copyOf(value.members().sequencedKeySet()));
        assertEquals(new JsonText("你好"), value.get("text"));
        assertEquals(new JsonNumber(new BigDecimal("12345678901234567890.00100")), value.get("number"));
        assertEquals(JsonNull.INSTANCE,
                ((io.github.kongweiguang.ja.foundation.json.JsonArray) value.get("nested")).values().getFirst());
        sdk.put("later", "mutation");
        assertEquals(List.of("text", "number", "nested"), List.copyOf(restored.keySet()));
    }

    /** Tool Schema 必须是对象，二进制 Java 值也不能借 SDK 转换进入 Kernel。 */
    @Test
    void rejectsNonObjectSchemaAndBinaryJavaValue() {
        assertThrows(IllegalArgumentException.class, () -> McpJsonValues.objectFromSdk(mapper, List.of("array")));
        assertThrows(IllegalArgumentException.class, () -> McpJsonValues.fromSdk(mapper, new byte[]{1, 2}));
    }
}
