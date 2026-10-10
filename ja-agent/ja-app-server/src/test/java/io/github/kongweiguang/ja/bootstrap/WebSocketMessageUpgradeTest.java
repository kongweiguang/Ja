// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import org.junit.jupiter.api.Test;

import java.nio.BufferOverflowException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定 WebSocket 逻辑消息的组装、严格 UTF-8 与有界生命周期。 */
final class WebSocketMessageUpgradeTest {
    /** 跨物理帧切分中文码点时只在最后一片解码和发布一次完整消息。 */
    @Test
    void assemblesUtf8AcrossMultipleContinuationFramesOnce() throws Exception {
        WebSocketMessageUpgrade.MessageAssembly assembly = new WebSocketMessageUpgrade.MessageAssembly();
        String expected = "{\"value\":\"重庆已恢复\"}";
        byte[] payload = expected.getBytes(StandardCharsets.UTF_8);
        int split = "{\"value\":\"".getBytes(StandardCharsets.UTF_8).length;
        byte[] first = Arrays.copyOfRange(payload, 0, split + 1);
        byte[] second = Arrays.copyOfRange(payload, split + 1, split + 2);
        byte[] finalPart = Arrays.copyOfRange(payload, split + 2, payload.length);

        assertNull(assembly.accept(1, false, first));
        assertNull(assembly.accept(0, false, second));
        assertEquals(expected, assembly.accept(0, true, finalPart));
        assertEquals(0, assembly.bufferedBytes());
    }

    /** 4 MiB 消息在上限内完整接纳；只允许最终续片返回一次解码正文。 */
    @Test
    void acceptsExactlyTheRpcMessageLimit() throws Exception {
        WebSocketMessageUpgrade.MessageAssembly assembly = new WebSocketMessageUpgrade.MessageAssembly();
        int limit = JaRpcCodec.DEFAULT_MAX_FRAME_BYTES;
        byte[] first = filled(limit / 2, (byte) 'a');
        byte[] last = filled(limit - first.length, (byte) 'b');

        assertNull(assembly.accept(1, false, first));
        assertEquals(limit / 2, assembly.bufferedBytes());
        String complete = assembly.accept(0, true, last);
        assertEquals(limit, complete.length());
        assertEquals('a', complete.charAt(0));
        assertEquals('b', complete.charAt(limit - 1));
        assertEquals(0, assembly.bufferedBytes());
    }

    /** 聚合多片的总字节超过 JA-RPC 单帧上限后立即拒绝，不得截断成可执行请求。 */
    @Test
    void rejectsAggregateAboveRpcMessageLimit() throws Exception {
        WebSocketMessageUpgrade.MessageAssembly assembly = new WebSocketMessageUpgrade.MessageAssembly();
        int limit = JaRpcCodec.DEFAULT_MAX_FRAME_BYTES;
        assertNull(assembly.accept(1, false, filled(limit / 2, (byte) 'x')));

        assertThrows(BufferOverflowException.class,
                () -> assembly.accept(0, true, filled(limit / 2 + 1, (byte) 'y')));
        assembly.clear();
        assertEquals(0, assembly.bufferedBytes());
    }

    /** 孤续片和活动消息内重复 TEXT 都是协议错误，不能重新起一条消息覆盖缓冲。 */
    @Test
    void rejectsOrphanContinuationAndRepeatedTextFrame() throws Exception {
        WebSocketMessageUpgrade.MessageAssembly orphan = new WebSocketMessageUpgrade.MessageAssembly();
        assertThrows(IllegalArgumentException.class,
                () -> orphan.accept(0, true, new byte[]{'x'}));

        WebSocketMessageUpgrade.MessageAssembly repeated = new WebSocketMessageUpgrade.MessageAssembly();
        assertNull(repeated.accept(1, false, new byte[]{'x'}));
        assertThrows(IllegalArgumentException.class,
                () -> repeated.accept(1, true, new byte[]{'y'}));
        repeated.clear();
        assertEquals(0, repeated.bufferedBytes());
    }

    /** 最终帧仍含非法 UTF-8 时严格失败并释放已累计字节，不替换字符后派发。 */
    @Test
    void rejectsMalformedUtf8AndClearsPartialMessage() throws Exception {
        WebSocketMessageUpgrade.MessageAssembly assembly = new WebSocketMessageUpgrade.MessageAssembly();
        assertNull(assembly.accept(1, false, new byte[]{(byte) 0xc3}));

        assertThrows(CharacterCodingException.class,
                () -> assembly.accept(0, true, new byte[]{0x28}));
        assertEquals(0, assembly.bufferedBytes());
    }

    /** 只验证生产上限需要的固定载荷，避免边界用例重复构造可变字符串。 */
    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
