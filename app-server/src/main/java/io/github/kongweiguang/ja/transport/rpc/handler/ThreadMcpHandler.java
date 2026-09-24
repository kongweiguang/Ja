// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.port.in.ThreadMcpUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 只公开目标会话的脱敏 MCP 观测，不持有服务连接。 */
public final class ThreadMcpHandler implements RpcHandler {
    private final RpcSession session;

    /** 仅保存连接会话，使会话与工作区权限仍由应用用例判断。 */
    public ThreadMcpHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** 显式连接测试归设置页；此 Handler 只服务会话只读界面。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.THREAD_MCP_READ);
    }

    /** 严格校验请求形状后，将会话权限交给应用用例。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        return switch (command.method()) {
            case THREAD_MCP_READ -> CompletableFuture.completedFuture(read(command.params()));
            default -> throw JaRpcException.methodNotFound();
        };
    }

    /** 只读取最近观测，绝不打开 MCP 传输。 */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireExact(params, "threadId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        try {
            ThreadMcpUseCase.ReadResult result = session.threadMcp().read(threadId);
            return project(threadId, result);
        } catch (ThreadMcpUseCase.Failure failure) {
            throw mapFailure(failure);
        }
    }

    /** 响应前拒绝不匹配的会话身份，防止泄漏其它会话状态。 */
    private ObjectNode project(String requestedThreadId, ThreadMcpUseCase.ReadResult result) {
        Objects.requireNonNull(result, "thread MCP result");
        if (!requestedThreadId.equals(result.threadId())) {
            throw new IllegalStateException("Thread MCP result identity changed");
        }
        return RpcResults.threadMcp(session.mapper(), result);
    }

    /** 将稳定应用失败映射为既有 JA-RPC 错误词汇，不公开内部错误码。 */
    private static JaRpcException mapFailure(Throwable source) {
        Throwable failure = unwrap(source);
        if (!(failure instanceof ThreadMcpUseCase.Failure mcpFailure)) {
            throw new IllegalStateException("unexpected Thread MCP failure", failure);
        }
        return switch (mcpFailure.code()) {
            case "THREAD_NOT_FOUND" -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable");
            case "WORKSPACE_UNAVAILABLE" -> JaRpcException.of(JaErrorCatalog.WORKSPACE_NOT_FOUND,
                    "thread workspace is unavailable");
            case "MCP_CONFIGURATION_UNAVAILABLE" ->
                    JaRpcException.of(JaErrorCatalog.MCP_SERVER_UNAVAILABLE, "MCP server is unavailable");
            default -> throw new IllegalStateException("unmapped Thread MCP failure", failure);
        };
    }

    /** 移除异步异常包装，未知失败仍归类为内部错误。 */
    private static Throwable unwrap(Throwable source) {
        Throwable failure = source;
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
