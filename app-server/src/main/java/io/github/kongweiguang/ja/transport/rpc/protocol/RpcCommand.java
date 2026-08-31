// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * 已完成 frame 角色校验的类型化 RPC 命令；参数副本隔离异步 handler 与读线程。
 */
public record RpcCommand(RpcMethod method, ObjectNode params) {
    /**
     * 深拷贝可变 JSON 参数，避免调用方在异步执行期间改变命令语义。
     */
    public RpcCommand {
        Objects.requireNonNull(method, "method");
        params = Objects.requireNonNull(params, "params").deepCopy();
    }

    /**
     * 每次读取都返回独立参数树，防止某个 Handler 的规范化或测试夹具修改共享命令。
     */
    @Override
    public ObjectNode params() {
        return params.deepCopy();
    }
}
