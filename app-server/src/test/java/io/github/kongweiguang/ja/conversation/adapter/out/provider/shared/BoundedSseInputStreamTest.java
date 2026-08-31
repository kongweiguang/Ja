// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 原始 SSE 保护流测试覆盖分帧、容量、skip 安全和关闭所有权。 */
final class BoundedSseInputStreamTest {
    /** 在 LF、CRLF、CR、注释和未终止尾帧之间保持原始字节不变。 */
    @Test
    void preservesRawBytesAcrossAllLineEndings() throws Exception {
        byte[] source = (": ping\r\nevent: message_start\r\ndata: {}\r\n\r\n"
                + "event: ping\ndata: {}\n\n"
                + "event: message_stop\rdata: {}\r\r"
                + "event: ping\ndata: {}").getBytes(StandardCharsets.UTF_8);
        try (BoundedSseInputStream stream = new BoundedSseInputStream(
                new ByteArrayInputStream(source), 1_024, 4_096, 8)) {
            assertArrayEquals(source, stream.readAllBytes());
        }
    }

    /** 即使 CRLF 被拆到两个单字节读取中也能正确识别。 */
    @Test
    void handlesCrLfAcrossReadBoundaries() throws Exception {
        byte[] source = "event: ping\r\ndata: {}\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        try (BoundedSseInputStream stream = bounded(source, 128, 256, 1)) {
            for (byte expected : source) assertEquals(Byte.toUnsignedInt(expected), stream.read());
            assertEquals(-1, stream.read());
        }
    }

    /** 原始帧超过单事件上限前立即拒绝，避免继续扩张正文。 */
    @Test
    void rejectsOversizedEvent() {
        byte[] source = "data: 123456789\n\n".getBytes(StandardCharsets.US_ASCII);
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> bounded(source, 8, 128, 2).readAllBytes());
        assertEquals("EVENT_LIMIT", failure.code());
    }

    /** 响应达到精确总字节上限后仍有数据时拒绝。 */
    @Test
    void rejectsOversizedResponse() {
        byte[] source = "data: {}\n\n".getBytes(StandardCharsets.US_ASCII);
        long responseLimit = source.length - 1L;
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> bounded(source, responseLimit, responseLimit, 2).readAllBytes());
        assertEquals("RESPONSE_LIMIT", failure.code());
    }

    /** 注释也计为非空帧，使心跳洪泛仍受帧数限制。 */
    @Test
    void rejectsExcessiveFrameCount() {
        byte[] source = ": one\n\n: two\n\n".getBytes(StandardCharsets.US_ASCII);
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> bounded(source, 64, 128, 1).readAllBytes());
        assertEquals("RESPONSE_LIMIT", failure.code());
    }

    /** skip 仍通过受限读取，不能绕过原始事件容量校验。 */
    @Test
    void skipStillAppliesValidation() {
        byte[] source = "data: 123456789\n\n".getBytes(StandardCharsets.US_ASCII);
        BoundedSseInputStream stream = new BoundedSseInputStream(
                new ByteArrayInputStream(source), 8, 256, 2);
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> stream.skip(source.length));
        assertEquals("EVENT_LIMIT", failure.code());
    }

    /** 禁止回放、幂等关闭借用的响应体，并让关闭状态立即阻止后续读取。 */
    @Test
    void disablesReplayAndDelegatesClose() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        InputStream delegate = new ByteArrayInputStream(new byte[0]) {
            /** 记录桥接层关闭所有权，不引入额外可关闭资源。 */
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };
        BoundedSseInputStream stream = new BoundedSseInputStream(delegate, 8, 8, 1);
        assertFalse(stream.markSupported());
        assertThrows(IOException.class, stream::reset);
        stream.close();
        stream.close();
        assertEquals(1, closeCount.get());
        assertThrows(IOException.class, stream::read);
    }

    /** 为不验证 Provider 事件白名单的用例创建仅做容量限制的流。 */
    private static BoundedSseInputStream bounded(byte[] source, long eventBytes,
                                                  long responseBytes, long events) {
        return new BoundedSseInputStream(
                new ByteArrayInputStream(source), eventBytes, responseBytes, events);
    }
}
