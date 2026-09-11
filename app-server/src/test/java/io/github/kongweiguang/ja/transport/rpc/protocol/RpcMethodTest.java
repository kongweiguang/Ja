// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁定侧聊 Mailbox 与关闭入口的封闭 Wire 方法词汇，防止 Java/Rust/TypeScript 合同漂移。
 */
final class RpcMethodTest {
    /**
     * 验证新方法名既能从枚举生成精确 Wire 名称，也能由协议名称解析回同一枚举实例。
     */
    @Test
    void mapsSideChatMethodsToCurrentWireNames() {
        assertEquals("thread/message/send", RpcMethod.THREAD_MESSAGE_SEND.wireName());
        assertSame(RpcMethod.THREAD_MESSAGE_SEND, RpcMethod.fromWireName("thread/message/send"));

        assertEquals("task/close", RpcMethod.TASK_CLOSE.wireName());
        assertSame(RpcMethod.TASK_CLOSE, RpcMethod.fromWireName("task/close"));
    }

    /**
     * 拒绝已淘汰的 task/message/send，避免旧客户端名称因白名单遗漏而重新进入公共协议。
     */
    @Test
    void rejectsRetiredTaskMessageWireName() {
        assertFalse(RpcMethod.wireNames().contains("task/message/send"));
        assertThrows(JaRpcException.class, () -> RpcMethod.fromWireName("task/message/send"));
    }
}
