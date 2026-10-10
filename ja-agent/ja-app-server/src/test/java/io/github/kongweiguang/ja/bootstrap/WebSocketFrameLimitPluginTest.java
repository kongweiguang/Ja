// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 入站守卫在 FeatHTTP 解码前限制物理帧，WebSocketMessageUpgrade 再组装完整逻辑消息。 */
final class WebSocketFrameLimitPluginTest {
    /** 框架写模式的缓冲容量可能远大于新数据，尾部脏字节不能成为伪造帧头。 */
    @Test
    void scansOnlyBytesAddedByCurrentRead() {
        ByteBuffer readBuffer = ByteBuffer.allocate(64);
        readBuffer.put(new byte[]{(byte) 0x81, (byte) 0x81, 1, 2, 3, 4, (byte) ('x' ^ 1)});
        readBuffer.put(7, (byte) 0x81);
        readBuffer.put(8, (byte) 0xff);
        readBuffer.put(9, (byte) 0x80);
        ByteBuffer incoming = WebSocketFrameLimitPlugin.justRead(readBuffer, 7);
        assertEquals(7, incoming.remaining());
        WebSocketFrameLimitPlugin.FrameHeaders scanner = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertTrue(scanner.accept(incoming));
        assertTrue(scanner.accept(ByteBuffer.wrap(new byte[]{(byte) 0x89, (byte) 0x80, 0, 0, 0, 0})));
    }

    /** 扩展长度拆在多次网络读取中时，补齐帧头即拒绝超过 JA-RPC 预算的载荷声明。 */
    @Test
    void rejectsSplitOversizedHeaderBeforePayload() {
        WebSocketFrameLimitPlugin.FrameHeaders scanner = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertTrue(scanner.accept(ByteBuffer.wrap(new byte[]{(byte) 0x81, (byte) 0xff, 0, 0})));
        assertTrue(scanner.accept(ByteBuffer.wrap(new byte[]{0, 0, 0})));
        assertFalse(scanner.accept(ByteBuffer.wrap(new byte[]{0x40, 0, 1, 0, 0, 0, 0})));
    }

    /** 正常短帧与紧随其后的已掩码控制帧共享缓冲时，从下一帧头重新计数。 */
    @Test
    void acceptsAdjacentBoundedFrames() {
        WebSocketFrameLimitPlugin.FrameHeaders scanner = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertTrue(scanner.accept(ByteBuffer.wrap(new byte[]{(byte) 0x81, (byte) 0x81, 1, 2, 3, 4,
            (byte) ('x' ^ 1), (byte) 0x89, (byte) 0x80, 0, 0, 0, 0})));
        assertFalse(scanner.accept(ByteBuffer.wrap(new byte[]{(byte) 0x81, (byte) 0xff,
            (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})));
    }

    /** 首片和末续片可分别通过物理帧守卫，完整消息的 UTF-8 解码与派发由 upgrade 负责。 */
    @Test
    void acceptsBoundedTextAndContinuationFrames() {
        WebSocketFrameLimitPlugin.FrameHeaders scanner = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertTrue(scanner.accept(clientFrame(0x01, "{\"status\":".getBytes(StandardCharsets.UTF_8))));
        assertTrue(scanner.accept(clientFrame(0x80, "ready}".getBytes(StandardCharsets.UTF_8))));
    }

    /** 客户端必须掩码；拒绝非法帧不应交给 FeatHTTP 分配或分派。 */
    @Test
    void rejectsUnmaskedClientFrames() {
        WebSocketFrameLimitPlugin.FrameHeaders scanner = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertFalse(scanner.accept(ByteBuffer.wrap(new byte[]{(byte) 0x81, 0})));
    }

    /** 控制帧必须 FIN 且负载不超过 125 字节；守卫在框架解码前拒绝非法声明。 */
    @Test
    void rejectsFragmentedAndOversizedControlFrames() {
        WebSocketFrameLimitPlugin.FrameHeaders fragmentedPing = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertFalse(fragmentedPing.accept(clientFrame(0x09, new byte[0])));

        WebSocketFrameLimitPlugin.FrameHeaders fragmentedClose = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertFalse(fragmentedClose.accept(clientFrame(0x08, new byte[0])));

        WebSocketFrameLimitPlugin.FrameHeaders oversizedPong = new WebSocketFrameLimitPlugin.FrameHeaders();
        assertFalse(oversizedPong.accept(controlFrameHeader(0x8a, 126)));
    }

    /** 客户端帧固定四字节 mask；小型 fixture 足以覆盖 FIN、opcode 和控制帧头语义。 */
    private static ByteBuffer clientFrame(int firstByte, byte[] payload) {
        byte[] mask = {1, 2, 3, 4};
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(firstByte);
        frame.write(0x80 | payload.length);
        frame.writeBytes(mask);
        for (int index = 0; index < payload.length; index++) {
            frame.write(payload[index] ^ mask[index % mask.length]);
        }
        return ByteBuffer.wrap(frame.toByteArray());
    }

    /** 只构造扩展长度帧头即可验证控制帧约束，避免为非法长度分配测试载荷。 */
    private static ByteBuffer controlFrameHeader(int firstByte, int payloadLength) {
        return ByteBuffer.wrap(new byte[]{(byte) firstByte, (byte) (0x80 | 126),
                0, (byte) payloadLength, 1, 2, 3, 4});
    }
}
