// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import org.noear.solon.server.feathttp.websocket.FeatHttpWebSocketUpgrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.smartboot.feat.core.common.Reset;
import tech.smartboot.feat.core.common.codec.websocket.CloseReason;
import tech.smartboot.feat.core.common.codec.websocket.WebSocket;
import tech.smartboot.feat.core.server.WebSocketRequest;
import tech.smartboot.feat.core.server.WebSocketResponse;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 保留 FeatHTTP 的握手、掩码解码和 Solon 路由，只在完整文本消息边界投递 JA-RPC。
 * 物理帧头预算由前置插件保证；这里复核正文预算并延后 UTF-8 解码，避免拆开多字节字符。
 */
final class WebSocketMessageUpgrade extends FeatHttpWebSocketUpgrade {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketMessageUpgrade.class);
    private final MessageAssembly assembly = new MessageAssembly();
    private volatile WebSocketRequest activeRequest;
    private volatile WebSocketResponse activeResponse;

    /** 保留原生握手与路由注册，同时允许前置守卫在解码分配前使用原生关闭响应。 */
    @Override
    public void onHandShake(WebSocketRequest request, WebSocketResponse response) {
        activeRequest = request;
        activeResponse = response;
        super.onHandShake(request, response);
    }

    /**
     * 控制帧沿用原生行为且不进入正文累计；只有完整、严格 UTF-8 文本进入既有 RPC listener。
     * 原生 Ping 回应只写入缓冲，必须立即刷新，不能让未完成正文阻止客户端收到 Pong。
     * 每个续帧消费后立即清掉原生累积区，避免当前依赖把上一续帧重复拼入下一帧。
     */
    @Override
    public void handle(WebSocketRequest request, WebSocketResponse response) throws Throwable {
        WebSocket frame = (WebSocket) request;
        int opcode = request.getFrameOpcode();
        long messageBytes = assembly.bufferedBytes();
        try {
            switch (opcode) {
                case WebSocket.OPCODE_PING -> {
                    super.handlePing(request, response);
                    response.flush();
                }
                case WebSocket.OPCODE_PONG -> super.handlePong(request, response);
                case WebSocket.OPCODE_CLOSE -> {
                    assembly.clear();
                    super.handle(request, response);
                }
                case WebSocket.OPCODE_TEXT, WebSocket.OPCODE_CONTINUE -> {
                    byte[] payload = request.getPayload();
                    messageBytes += payload.length;
                    String text = assembly.accept(opcode, frame.isFrameFinalFlag(), payload);
                    if (text != null) {
                        handleTextMessage(request, response, text);
                    }
                }
                default -> closeInvalidFrame(request, response, CloseReason.WRONG_CODE, "invalid_frame", messageBytes);
            }
        } catch (BufferOverflowException overflow) {
            closeInvalidFrame(request, response, CloseReason.MSG_TOO_BIG, "message_too_big", messageBytes);
        } catch (CharacterCodingException malformed) {
            closeInvalidFrame(request, response, CloseReason.MSG_CONTAINS_INVALID_DATA, "invalid_utf8", messageBytes);
        } catch (IllegalArgumentException invalid) {
            closeInvalidFrame(request, response, CloseReason.WRONG_CODE, "invalid_fragment_order", messageBytes);
        } catch (RuntimeException failure) {
            onError(request, failure);
        } finally {
            resetNativeContinuation(request, frame);
        }
    }

    /**
     * 守卫只有完成握手后才发送 WebSocket 关闭帧；错误仅记录固定类别、关闭码与字节聚合。
     * 先写具体关闭码，再清理 Solon 连接，避免其普通 close 把错误码替换成正常关闭。
     */
    void closeInvalidFrame(int code, String category, long messageBytes) {
        closeInvalidFrame(activeRequest, activeResponse, code, category, messageBytes);
    }

    /** 直接处理正文失败时使用当前响应，避免依赖守卫安装顺序或再次查询原生连接状态。 */
    private void closeInvalidFrame(WebSocketRequest request, WebSocketResponse response, int code,
                                   String category, long messageBytes) {
        assembly.clear();
        LOG.warn("Ja WebSocket rejected category={} closeCode={} messageBytes={}", category, code, messageBytes);
        if (response != null) {
            try {
                response.close(code, category);
            } finally {
                onClose(request, response, new CloseReason(code, category));
            }
        }
    }

    /** 对端关闭与本地主动拒绝共享清理边界，不保留半条消息或升级响应引用。 */
    @Override
    public void onClose(WebSocketRequest request, WebSocketResponse response, CloseReason reason) {
        assembly.clear();
        activeRequest = null;
        activeResponse = null;
        super.onClose(request, response, reason);
    }

    /** 原生错误不能把正文或宿主环境带入日志；业务 listener 仍负责连接身份的幂等清理。 */
    @Override
    public void onError(WebSocketRequest request, Throwable failure) {
        closeInvalidFrame(request, activeResponse, CloseReason.UNEXPECTED_ERROR, "native_failure",
            assembly.bufferedBytes());
    }

    /** TCP 断线未必带 Close 帧，因此销毁也必须释放部分正文与连接引用。 */
    @Override
    public void destroy() {
        assembly.clear();
        activeRequest = null;
        activeResponse = null;
        super.destroy();
    }

    /**
     * Solon 4.1.0 所带 FeatHTTP 2.3.1 的 Reset 对 CONTINUATION 不清正文，并且 setPayload 会追加。
     * 只在此处临时改 opcode 调用其公开 Reset，再恢复帧身份；不复制解码器或反射私有实现。
     */
    private static void resetNativeContinuation(WebSocketRequest request, WebSocket frame) {
        if (frame.getFrameOpcode() != WebSocket.OPCODE_CONTINUE) {
            return;
        }
        frame.setFrameOpcode(WebSocket.OPCODE_TEXT);
        try {
            ((Reset) request).reset();
        } finally {
            frame.setFrameOpcode(WebSocket.OPCODE_CONTINUE);
        }
    }

    /** 正文缓冲与原生物理帧解码分离；容量增长受同一 RPC 预算约束，清理时释放整个数组。 */
    static final class MessageAssembly {
        private byte[] buffer;
        private int bufferedBytes;
        private boolean fragmented;

        /**
         * 先校验顺序与累计预算再分配；单帧直接解码，分片只在最终续帧到达时严格解码一次。
         * 零长度分片不增加存储，失败与成功终态都清空，下一条消息不会继承旧正文。
         */
        synchronized String accept(int opcode, boolean finalFrame, byte[] payload) throws CharacterCodingException {
            if ((opcode != WebSocket.OPCODE_TEXT && opcode != WebSocket.OPCODE_CONTINUE)
                || (opcode == WebSocket.OPCODE_TEXT && fragmented)
                || (opcode == WebSocket.OPCODE_CONTINUE && !fragmented)) {
                clear();
                throw new IllegalArgumentException("invalid WebSocket fragment order");
            }
            if (payload.length > JaRpcCodec.DEFAULT_MAX_FRAME_BYTES - bufferedBytes) {
                clear();
                throw new BufferOverflowException();
            }
            if (opcode == WebSocket.OPCODE_TEXT && finalFrame) {
                return decode(ByteBuffer.wrap(payload));
            }
            fragmented = true;
            append(payload);
            if (!finalFrame) {
                return null;
            }
            try {
                return decode(buffer == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(buffer, 0, bufferedBytes));
            } finally {
                clear();
            }
        }

        /** 容量按倍数增长但不越过消息上限，避免小续帧造成反复全量复制或超过预算的扩容。 */
        private void append(byte[] payload) {
            if (payload.length == 0) {
                return;
            }
            int required = bufferedBytes + payload.length;
            int capacity = buffer == null ? 0 : buffer.length;
            if (required > capacity) {
                int grown = Math.min(JaRpcCodec.DEFAULT_MAX_FRAME_BYTES, Math.max(required, capacity * 2));
                buffer = buffer == null ? new byte[grown] : Arrays.copyOf(buffer, grown);
            }
            System.arraycopy(payload, 0, buffer, bufferedBytes, payload.length);
            bufferedBytes = required;
        }

        /** REPORT 避免替换非法字符后把损坏的 JSON 当作有效 RPC 文本。 */
        private static String decode(ByteBuffer bytes) throws CharacterCodingException {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        }

        /** 放弃数组而不是只重置游标，连接关闭后不长期保留大消息容量。 */
        synchronized void clear() {
            buffer = null;
            bufferedBytes = 0;
            fragmented = false;
        }

        /** 只暴露聚合字节数用于连接诊断，不暴露正文或任何环境字段。 */
        synchronized int bufferedBytes() {
            return bufferedBytes;
        }
    }
}
