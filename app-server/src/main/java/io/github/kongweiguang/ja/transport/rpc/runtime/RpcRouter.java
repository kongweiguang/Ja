// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.handler.RpcHandler;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 不可变方法路由器，在构造阶段拒绝重复方法所有权。
 */
final class RpcRouter {
    private final Map<RpcMethod, RpcHandler> routes;

    /**
     * 构造封闭路由表，禁止后续配置动态增加任意传输方法。
     */
    RpcRouter(List<RpcHandler> handlers) {
        Map<RpcMethod, RpcHandler> values = new LinkedHashMap<>();
        for (RpcHandler handler : handlers) {
            Objects.requireNonNull(handler, "handler");
            for (RpcMethod method : handler.methods()) {
                if (values.putIfAbsent(method, handler) != null) {
                    throw new IllegalArgumentException("duplicate RPC method owner");
                }
            }
        }
        routes = Map.copyOf(values);
    }

    /**
     * 路由一个已校验请求，不提供兼容别名或 fallback 分派。
     */
    CompletionStage<ObjectNode> dispatch(String method, ObjectNode params) {
        RpcMethod rpcMethod = RpcMethod.fromWireName(method);
        RpcHandler handler = routes.get(rpcMethod);
        if (handler == null) throw JaRpcException.methodNotFound();
        return handler.handle(new RpcCommand(rpcMethod, params));
    }
}
