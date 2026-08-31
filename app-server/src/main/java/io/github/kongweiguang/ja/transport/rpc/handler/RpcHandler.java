// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * 注册到封闭方法子集的窄 JA-RPC 领域 Handler。
 */
public interface RpcHandler {
    /**
     * 返回该 Handler 独占的精确首发方法集合。
     */
    Set<RpcMethod> methods();

    /**
     * 校验并执行一个类型化命令，不负责 frame 编解码和请求关联。
     */
    CompletionStage<ObjectNode> handle(RpcCommand command);
}
