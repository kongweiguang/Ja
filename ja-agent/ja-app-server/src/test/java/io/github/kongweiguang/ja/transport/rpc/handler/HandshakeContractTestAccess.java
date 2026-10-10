// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/** 为 runtime 测试复用握手合同投影，不公开生产 Handler 的包级映射函数。 */
public final class HandshakeContractTestAccess {
    /** 禁止构造无状态合同桥，避免测试持有第二份协议常量。 */
    private HandshakeContractTestAccess() {
    }

    /** 返回生产 Handler 生成的能力词汇表，使测试不复制严格 JA-RPC 合同。 */
    public static ObjectNode capabilities(ObjectMapper mapper) {
        return HandshakeHandler.capabilities(Objects.requireNonNull(mapper, "mapper"));
    }

    /** 返回生产 Handler 生成的资源限制，使测试与真实握手保持同源。 */
    public static ObjectNode limits(ObjectMapper mapper) {
        return HandshakeHandler.limits(Objects.requireNonNull(mapper, "mapper"));
    }
}
