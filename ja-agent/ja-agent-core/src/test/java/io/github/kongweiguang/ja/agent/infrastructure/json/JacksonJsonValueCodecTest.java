// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.json;

import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 持久 JSON 必须严格保留业务值，不允许解析器宽松行为改变调用身份。 */
final class JacksonJsonValueCodecTest {
    /** JSON codec 往返保留 BigDecimal 精度，供工具事实和 binding 指纹共用稳定值语义。 */
    @Test
    void preservesDecimalValuesOnRoundTrip() {
        JacksonJsonValueCodec codec = new JacksonJsonValueCodec();
        JsonObject value = new JsonObject(Map.of("amount", new JsonNumber(new BigDecimal("1234567890.000123456789012345678900")),
                "label", new JsonText("decimal")));

        assertEquals(value, codec.decode(codec.encode(value)));
    }

    /** JSON 文本有重复键或第二个根值时明确拒绝，不依赖解析器默认的最后值覆盖。 */
    @Test
    void rejectsDuplicateFieldsAndTrailingRootValues() {
        JacksonJsonValueCodec codec = new JacksonJsonValueCodec();

        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"name\":1,\"name\":2}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{} {}"));
    }

    /**
     * 保留严格解析根因时关闭 Jackson 源文定位，避免异常链或常规日志回显用户 JSON。
     */
    @Test
    void decodeFailureKeepsDiagnosticCauseWithoutSourceText() {
        JacksonJsonValueCodec codec = new JacksonJsonValueCodec();
        String sentinel = "private-payload-sentinel";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> codec.decode("{\"secret\":\"" + sentinel + "\" broken}"));

        assertNotNull(failure.getCause());
        assertEquals("JSON value could not be decoded", failure.getMessage());
        assertFalse(failure.toString().contains(sentinel));
        assertFalse(failure.getCause().toString().contains(sentinel));
    }
}
