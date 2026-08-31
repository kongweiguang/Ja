// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 将空闲 Thread 压缩用例映射为严格 JA-RPC DTO，不接触 Repository、配置或 Provider。 */
public final class ThreadCompactionHandler implements RpcHandler {
    private final RpcSession session;

    /** 仅绑定连接代际，压缩资源和状态生命周期继续由应用用例独占。 */
    public ThreadCompactionHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** 声明唯一的显式压缩方法，避免历史 Handler 演变为跨域 service locator。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.THREAD_COMPACT);
    }

    /** 严格解析 CAS 输入并把应用失败收敛为冻结错误目录。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        if (command.method() != RpcMethod.THREAD_COMPACT) throw JaRpcException.methodNotFound();
        ObjectNode params = command.params();
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        ContextCompactionUseCase.Command request = new ContextCompactionUseCase.Command(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.revision(params, "expectedThreadRevision"));
        try {
            return CompletableFuture.completedFuture(result(session.compactions().compact(
                    request, session::publish, session.cancellationToken())));
        } catch (ContextCompactionUseCase.Failure failure) {
            throw map(failure);
        }
    }

    /** 输出精确成功闭集；nullable 身份使用 JSON null，禁止缺字段造成三端解释分叉。 */
    private ObjectNode result(ContextCompactionUseCase.Result value) {
        ObjectNode result = session.mapper().createObjectNode()
                .put("outcome", value.outcome().name().toLowerCase(Locale.ROOT))
                .put("threadRevision", value.threadRevision())
                .put("inputTokensBefore", value.inputTokensBefore())
                .put("inputTokensAfter", value.inputTokensAfter());
        if (value.compactionId() == null) result.putNull("compactionId");
        else result.put("compactionId", value.compactionId());
        if (value.checkpointId() == null) result.putNull("checkpointId");
        else result.put("checkpointId", value.checkpointId());
        return result;
    }

    /** 显式穷举应用失败，新增内部类别时编译器会迫使 transport 决定公开恢复语义。 */
    private static JaRpcException map(ContextCompactionUseCase.Failure failure) {
        JaErrorCatalog error = switch (failure.code()) {
            case THREAD_NOT_FOUND -> JaErrorCatalog.THREAD_NOT_FOUND;
            case CONFLICT -> JaErrorCatalog.CONFLICT;
            case THREAD_BUSY -> JaErrorCatalog.THREAD_BUSY;
            case TOKEN_COUNT_UNAVAILABLE -> JaErrorCatalog.TOKEN_COUNT_UNAVAILABLE;
            case SUMMARY_FAILURE -> JaErrorCatalog.SUMMARY_FAILURE;
            case CONTEXT_LIMIT -> JaErrorCatalog.CONTEXT_LIMIT;
            case CANCELLED -> JaErrorCatalog.CANCELLED;
            case INVALID_STATE -> JaErrorCatalog.INVALID_STATE;
        };
        return JaRpcException.of(error, "context compaction failed");
    }
}
