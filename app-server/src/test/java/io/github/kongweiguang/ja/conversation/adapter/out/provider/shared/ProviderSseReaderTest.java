// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** 元数据字段和未知扩展事件不影响受支持事件的语义归约。 */
    @Test
    void skipsExtensionEventAndAcceptsSseMetadata() throws Exception {
        ProviderSseReader reader = reader(("id: metadata-only\nretry: 1000\nfuture-field\n\n"
                + "event: vendor.extension\ndata: arbitrary\n\n"
                + "event: ping\nid: 7\nretry: 1000\nvendor-field: ignored\n"
                + "data: {\"type\":\"ping\"}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("ping", reader.next().name());
        assertNull(reader.next());
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

        ProviderProtocolException malformed = assertThrows(ProviderProtocolException.class,
                () -> reader(body).next());
        assertEquals("TEST_EVENT", malformed.code());
        assertFalse(malformed.retryable());
    }

    /** UTF-8 多字节字符只收到合法前缀后 EOF，必须等待新请求而不是要求用户手动继续。 */
    @Test
    void treatsUtf8CodePointCutByEofAsRecoverable() {
        byte[] prefix = "event: ping\ndata: {\"type\":\"ping\",\"text\":\""
                .getBytes(StandardCharsets.UTF_8);
        byte[] body = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        body[prefix.length] = (byte) 0xE4;
        body[prefix.length + 1] = (byte) 0xB8;

        ProviderProtocolException truncated = assertThrows(ProviderProtocolException.class,
                () -> reader(body).next());
        assertEquals("STREAM_TRUNCATED", truncated.code());
        assertEquals("MODEL_STREAM_INVALID", truncated.terminalErrorCode());
        assertTrue(truncated.retryable());
    }

    /** 由共享严格 Mapper 限制恶意 JSON 嵌套深度。 */
    @Test
    void rejectsExcessiveJsonDepth() {
        String body = "event: ping\ndata: {\"type\":\"ping\",\"x\":"
                + "[".repeat(70) + "0" + "]".repeat(70) + "}\n\n";
        assertThrows(ProviderProtocolException.class, () ->
                reader(body.getBytes(StandardCharsets.UTF_8)).next());
    }

    /** 传输在 JSON 值中途结束才自动恢复；明确闭合的坏帧保持确定性错误。 */
    @Test
    void distinguishesIncompleteEofFromMalformedCompleteFrame() {
        ProviderProtocolException truncated = assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\ndata: {\"type\":\"ping\",\"value\":"
                        .getBytes(StandardCharsets.UTF_8)).next());
        assertEquals("STREAM_TRUNCATED", truncated.code());
        assertEquals("MODEL_STREAM_INVALID", truncated.terminalErrorCode());
        assertTrue(truncated.retryable());

        ProviderProtocolException malformed = assertThrows(ProviderProtocolException.class, () ->
                reader("event: ping\ndata: {\"type\":\"ping\",\"value\":}\n\n"
                        .getBytes(StandardCharsets.UTF_8)).next());
        assertEquals("TEST_EVENT", malformed.code());
        assertFalse(malformed.retryable());
    }

    /** Chat Completions 的 data-only SSE 使用相同的 EOF 判别，避免真实断流要求用户手动继续。 */
    @Test
    void chatReaderRetriesOnlyIncompleteEofFrames() {
        ProviderProtocolException truncated = assertThrows(ProviderProtocolException.class, () ->
                new OpenAiChatSseReader(new ByteArrayInputStream(
                        "data: {\"choices\":[".getBytes(StandardCharsets.UTF_8))).next());
        assertEquals("STREAM_TRUNCATED", truncated.code());
        assertEquals("MODEL_STREAM_INVALID", truncated.terminalErrorCode());
        assertTrue(truncated.retryable());

        ProviderProtocolException malformed = assertThrows(ProviderProtocolException.class, () ->
                new OpenAiChatSseReader(new ByteArrayInputStream(
                        "data: {\"choices\":}\n\n".getBytes(StandardCharsets.UTF_8))).next());
        assertEquals("OPENAI_CHAT_EVENT", malformed.code());
        assertFalse(malformed.retryable());
    }

    /** 在执行严格分帧解析前，用生产字节上限包装测试输入。 */
    private static ProviderSseReader reader(byte[] bytes) {
        return new ProviderSseReader(
                new BoundedSseInputStream(
                        new ByteArrayInputStream(bytes), 2_048),
                Set.of("ping"), "TEST_EVENT");
    }
}
