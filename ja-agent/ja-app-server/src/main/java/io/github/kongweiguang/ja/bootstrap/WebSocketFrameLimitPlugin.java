// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.smartboot.socket.Plugin;
import io.github.smartboot.socket.StateMachineEnum;
import io.github.smartboot.socket.transport.AioSession;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import tech.smartboot.feat.core.common.codec.websocket.CloseReason;
import tech.smartboot.feat.core.common.codec.websocket.WebSocket;
import tech.smartboot.feat.core.server.impl.HttpEndpoint;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FeatHTTP 按物理帧声明长度分配缓冲；此插件先验证帧边界与整条 RPC 消息的累计预算。
 * 仅为 Ja 路由安装原生 Upgrade 的消息适配，HTTP 请求大小仍由既有监听器政策约束。
 */
final class WebSocketFrameLimitPlugin implements Plugin<HttpEndpoint> {
    private final Map<AioSession, Ingress> ingress = new ConcurrentHashMap<>();
    private final String rpcPath;

    /** 路径由 daemon 唯一声明，避免消息守卫与实际 RPC listener 漂移。 */
    WebSocketFrameLimitPlugin(String rpcPath) {
        this.rpcPath = rpcPath;
    }

    /**
     * preProcess 早于原生 uriCheck，必须用已解析的请求行 URI，不能查询尚为空的 requestURI。
     * 握手和首帧同包时先检查剩余字节；升级对象发布前也要登记后续读取。
     */
    @Override
    public boolean preProcess(AioSession session, HttpEndpoint request) {
        if (ingress.containsKey(session) || !"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            return true;
        }
        try {
            if (!rpcPath.equals(URI.create(request.getUri()).getPath())) {
                return true;
            }
        } catch (IllegalArgumentException invalidUri) {
            // 无效 URI 直接断开，不能让标准解析异常把查询参数带入框架诊断。
            session.close();
            return false;
        }
        Ingress guard = new Ingress(new FrameHeaders(), new WebSocketMessageUpgrade());
        ingress.put(session, guard);
        try {
            if (guard.headers().accept(session.readBuffer().duplicate())) {
                // 原生 processor 只在 handler 为空时安装默认项；保留握手与解码，只替换消息边界。
                request.setServerHandler(httpRequest -> {
                    guard.upgrade().captureHttpRequest(httpRequest);
                    guard.upgrade().setupSubProtocol(httpRequest);
                    httpRequest.upgrade(guard.upgrade());
                });
                return true;
            }
        } catch (IllegalStateException closed) {
            ingress.remove(session, guard);
            return false;
        }
        ingress.remove(session, guard);
        session.close();
        return false;
    }

    /** 已登记连接的新字节在解码前检查；不能等待 Upgrade 对象，避免握手交界漏扫。 */
    @Override
    public void afterRead(AioSession session, int readSize) {
        if (readSize <= 0) {
            return;
        }
        Ingress guard = ingress.get(session);
        if (guard == null) {
            return;
        }
        try {
            FrameHeaders scanner = guard.headers();
            if (!scanner.accept(justRead(session.readBuffer(), readSize))) {
                try {
                    guard.upgrade().closeInvalidFrame(scanner.rejectionCode, scanner.rejectionCategory,
                        scanner.messageBytes);
                } finally {
                    session.close();
                }
            }
        } catch (IllegalStateException closed) {
            ingress.remove(session, guard);
        }
    }

    /** SmartSocket 此时仍是写模式；只交给守卫本次新增的字节，不扫描容量尾部的旧数据。 */
    static ByteBuffer justRead(ByteBuffer readBuffer, int readSize) {
        ByteBuffer bytes = readBuffer.duplicate();
        int end = bytes.position();
        bytes.limit(end);
        bytes.position(end - readSize);
        return bytes;
    }

    /** 连接关闭时丢弃最多十四字节的帧头状态，避免长期 daemon 留住旧会话。 */
    @Override
    public void stateEvent(StateMachineEnum state, AioSession session, Throwable failure) {
        if (state == StateMachineEnum.SESSION_CLOSED) {
            Ingress guard = ingress.remove(session);
            if (guard != null) {
                guard.upgrade().destroy();
            }
        }
    }

    /**
     * 每个连接只拥有一份预算与原生升级对象；关闭时一起释放。
     *
     * @param headers 解码前的物理帧与累计消息预算守卫
     * @param upgrade 完成原生握手、消息组装及连接清理的唯一升级对象
     */
    private record Ingress(FrameHeaders headers,
                           WebSocketMessageUpgrade upgrade) { }

    /**
     * 只读取固定长度的帧头并跳过载荷；帧内容仍完全交给 FeatHTTP 的 WebSocket 实现。
     * FIN 与控制帧只改变消息状态，不读取正文；累计声明长度在解码器分配之前即受同一预算限制。
     */
    static final class FrameHeaders {
        private final byte[] header = new byte[14];
        private int headerRead;
        private int headerSize = 2;
        private long payloadRemaining;
        private boolean fragmented;
        private long messageBytes;
        private int rejectionCode;
        private String rejectionCategory;

        /** 固定十四字节足够检查最长掩码帧头；正文按声明长度跳过，不为守卫另建载荷缓冲。 */
        boolean accept(ByteBuffer bytes) {
            if (rejectionCategory != null) {
                return false;
            }
            while (bytes.hasRemaining()) {
                if (payloadRemaining > 0) {
                    int count = (int) Math.min(payloadRemaining, bytes.remaining());
                    bytes.position(bytes.position() + count);
                    payloadRemaining -= count;
                    continue;
                }
                header[headerRead++] = bytes.get();
                if (headerRead == 2) {
                    int opcode = header[0] & 0x0f;
                    boolean control = (opcode & 0x08) != 0;
                    int indicator = header[1] & 0x7f;
                    if ((header[0] & 0x70) != 0 || (header[1] & 0x80) == 0
                        || (control && ((header[0] & 0x80) == 0 || indicator > 125))
                        || (opcode != WebSocket.OPCODE_TEXT && opcode != WebSocket.OPCODE_CONTINUE
                            && opcode != WebSocket.OPCODE_CLOSE && opcode != WebSocket.OPCODE_PING
                            && opcode != WebSocket.OPCODE_PONG)) {
                        return reject(CloseReason.WRONG_CODE, "invalid_frame");
                    }
                    if ((opcode == WebSocket.OPCODE_TEXT && fragmented)
                        || (opcode == WebSocket.OPCODE_CONTINUE && !fragmented)) {
                        return reject(CloseReason.WRONG_CODE, "invalid_fragment_order");
                    }
                    headerSize = 2 + (indicator == 126 ? 2 : indicator == 127 ? 8 : 0)
                        + 4;
                }
                if (headerRead == headerSize) {
                    int indicator = header[1] & 0x7f;
                    long length = indicator;
                    if (indicator >= 126) {
                        length = 0;
                        int count = indicator == 126 ? 2 : 8;
                        for (int i = 0; i < count; i++) {
                            length = (length << 8) | (header[2 + i] & 0xffL);
                        }
                    }
                    if (length < 0 || (indicator == 126 && length < 126)
                        || (indicator == 127 && length <= 65_535)) {
                        return reject(CloseReason.WRONG_CODE, "invalid_length");
                    }
                    int opcode = header[0] & 0x0f;
                    if (opcode == WebSocket.OPCODE_CLOSE && length == 1) {
                        return reject(CloseReason.WRONG_CODE, "invalid_close");
                    }
                    if (opcode == WebSocket.OPCODE_TEXT || opcode == WebSocket.OPCODE_CONTINUE) {
                        long prior = opcode == WebSocket.OPCODE_TEXT ? 0 : messageBytes;
                        if (length > JaRpcCodec.DEFAULT_MAX_FRAME_BYTES - prior) {
                            messageBytes = length > Long.MAX_VALUE - prior ? Long.MAX_VALUE : prior + length;
                            return reject(CloseReason.MSG_TOO_BIG, "message_too_big");
                        }
                        messageBytes = prior + length;
                        fragmented = (header[0] & 0x80) == 0;
                        if (!fragmented) {
                            messageBytes = 0;
                        }
                    } else if (opcode == WebSocket.OPCODE_CLOSE) {
                        fragmented = false;
                        messageBytes = 0;
                    }
                    payloadRemaining = length;
                    headerRead = 0;
                    headerSize = 2;
                }
            }
            return true;
        }

        /** 拒绝后固定诊断类别，后续读操作不会重新接受已损坏的消息序列。 */
        private boolean reject(int code, String category) {
            rejectionCode = code;
            rejectionCategory = category;
            return false;
        }
    }
}
