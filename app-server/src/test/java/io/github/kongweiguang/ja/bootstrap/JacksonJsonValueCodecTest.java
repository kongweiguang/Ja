// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonBoolean;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证组合根 Codec 不泄漏 Jackson record 形状且保持严格 JSON 精度。 */
final class JacksonJsonValueCodecTest {
    /** 编解码覆盖完整 sealed 闭集，并保留对象插入顺序和显式 JSON null。 */
    @Test
    void roundTripsCompleteJsonValueClosure() {
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        members.put("text", new JsonText("你好\nJA"));
        members.put("number", new JsonNumber(new BigDecimal("1.2300E+100")));
        members.put("array", new JsonArray(List.of(new JsonBoolean(true), JsonNull.INSTANCE)));
        JsonObject expected = new JsonObject(members);
        JacksonJsonValueCodec codec = new JacksonJsonValueCodec(exactMapper());

        String encoded = codec.encode(expected);

        assertEquals("{\"text\":\"你好\\nJA\",\"number\":1.2300E+100,\"array\":[true,null]}", encoded);
        assertEquals(expected, codec.decode(encoded));
    }

    /** 空输入和二进制 Jackson 扩展不能被解释成 JSON 缺省值。 */
    @Test
    void rejectsMissingAndNonJsonInput() {
        JacksonJsonValueCodec codec = new JacksonJsonValueCodec(exactMapper());

        assertThrows(IllegalArgumentException.class, () -> codec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not-json"));
    }

    /** 测试 Mapper 与生产组合根一致保留 BigDecimal scale，避免测试掩盖 Adapter 精度漂移。 */
    private static ObjectMapper exactMapper() {
        return JsonMapper.builder()
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
    }
}
