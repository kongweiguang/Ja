// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 严格 SSE 语法测试确保 JSON 进入状态机前不存在 Provider 分帧歧义。 */
final class ProviderSseReaderTest {
    /** 确保注释、混合换行、多行 data 以及 EOF 终止尾帧均保持确定性。 */
    @Test
    void readsMixedBoundariesAndMultilineJson() throws Exception {
        String stream = ": keepalive\r\nevent: ping\rdata: {\"type\":\"ping\",\r"
                + "data: \"value\":1}\n\n"
                + "event: ping\ndata: {\"type\":\"ping\"}";
        ProviderSseReader reader = reader(stream.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, reader.next().data().path("value").asInt());
        assertEquals("ping", reader.next().name());
        assertNull(reader.next());
    }

    /** 拒绝重复 event 字段，不采用最后一个 Provider 值。 */
    @Test
    void rejectsDuplicateEventField() {
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\nevent: ping\ndata: {\"type\":\"ping\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8)).next());
        assertEquals("TEST_EVENT", failure.code());
    }

    /** 每个语义帧必须同时包含白名单事件名和 data 字段。 */
    @Test
    void rejectsMissingEventOrData() {
        assertThrows(ProviderProtocolException.class, () ->
                reader("data: {\"type\":\"ping\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8)).next());
        assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\n\n".getBytes(StandardCharsets.UTF_8)).next());
    }

    /** 将 SSE 事件名与根 JSON type 作为一个原子判别条件。 */
    @Test
    void rejectsEventTypeMismatch() {
        assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\ndata: {\"type\":\"other\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8)).next());
    }

    /** 未知 SSE 字段可能改变重放或路由语义，因此不得静默忽略。 */
    @Test
    void rejectsUnknownSseField() {
        assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\nid: 7\ndata: {\"type\":\"ping\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8)).next());
    }

    /** 在任何状态变更选取值之前拒绝重复 JSON 键。 */
    @Test
    void rejectsDuplicateJsonKey() {
        assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\ndata: {\"type\":\"ping\",\"type\":\"ping\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8)).next());
    }

    /** 禁止把畸形 UTF-8 规范化为替代字符。 */
    @Test
    void rejectsMalformedUtf8() {
        byte[] prefix = "event: ping\ndata: {\"type\":\"ping\",\"x\":\""
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        body[prefix.length] = (byte) 0x80;
        System.arraycopy(suffix, 0, body, prefix.length + 1, suffix.length);

        assertThrows(ProviderProtocolException.class, () -> reader(body).next());
    }

    /** 由共享严格 Mapper 限制恶意 JSON 嵌套深度。 */
    @Test
    void rejectsExcessiveJsonDepth() {
        String body = "event: ping\ndata: {\"type\":\"ping\",\"x\":"
                + "[".repeat(70) + "0" + "]".repeat(70) + "}\n\n";
        assertThrows(ProviderProtocolException.class, () ->
                reader(body.getBytes(StandardCharsets.UTF_8)).next());
    }

    /** 在执行严格分帧解析前，用生产字节上限包装测试输入。 */
    private static ProviderSseReader reader(byte[] bytes) {
        return new ProviderSseReader(
                new BoundedSseInputStream(
                        new ByteArrayInputStream(bytes), 2_048, 16_384, 32),
                Set.of("ping"), "TEST_EVENT");
    }
}
