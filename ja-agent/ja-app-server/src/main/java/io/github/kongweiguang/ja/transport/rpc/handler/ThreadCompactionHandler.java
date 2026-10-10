// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 将空闲 Thread 压缩用例映射为严格 JA-RPC DTO，不接触 Repository、配置或 Provider。
 */
public final class ThreadCompactionHandler implements RpcHandler {
    private final RpcSession session;
    private final Object lifecycle = new Object();
    private final Map<String, CancellationSource> active = new HashMap<>();
    private boolean shutdown;

    /**
     * 仅绑定连接代际，压缩资源和状态生命周期继续由应用用例独占。
     */
    public ThreadCompactionHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * 显式穷举应用失败，新增内部类别时编译器会迫使 transport 决定公开恢复语义。
     */
    private static JaRpcException map(ContextCompactionUseCase.Failure failure) {
        JaErrorCatalog error = switch (failure.code()) {
            case THREAD_NOT_FOUND -> JaErrorCatalog.THREAD_NOT_FOUND;
            case CONFLICT -> JaErrorCatalog.CONFLICT;
            case THREAD_BUSY -> JaErrorCatalog.THREAD_BUSY;
            case SUMMARY_FAILURE -> JaErrorCatalog.SUMMARY_FAILURE;
            case CONTEXT_LIMIT -> JaErrorCatalog.CONTEXT_LIMIT;
            case CANCELLED -> JaErrorCatalog.CANCELLED;
            case INVALID_STATE -> JaErrorCatalog.INVALID_STATE;
        };
        return JaRpcException.of(error, "context compaction failed");
    }

    /**
     * 压缩及其取消只作用于当前连接，避免别的客户端停止不属于自己的请求。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.THREAD_COMPACT, RpcMethod.THREAD_COMPACT_CANCEL);
    }

    /**
     * 显式取消必须与压缩在同一连接并发分发；断连只终止事件观察，Engine owner 继续等待权威终态。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        requireAccepting();
        if (command.method() == RpcMethod.THREAD_COMPACT_CANCEL) {
            return cancel(command.params());
        }
        if (command.method() != RpcMethod.THREAD_COMPACT) {
            throw JaRpcException.methodNotFound();
        }
        ObjectNode params = command.params();
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        ContextCompactionUseCase.Command request = new ContextCompactionUseCase.Command(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.revision(params, "expectedThreadRevision"));
        CancellationSource source = new CancellationSource();
        register(request.threadId(), source);
        try {
            return CompletableFuture.completedFuture(result(session.compactions().compact(
                    request, session::publish, source)));
        } catch (ContextCompactionUseCase.Failure failure) {
            throw map(failure);
        } finally {
            unregister(request.threadId(), source);
        }
    }

    /**
     * 整体运行时关闭时先封闭请求准入，再取消已登记压缩，避免排队 RPC 在取消快照后启动。
     * 普通客户端断开只分离事件观察，不得调用此方法。
     */
    public void cancelForShutdown() {
        List<CancellationSource> sources;
        synchronized (lifecycle) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            sources = List.copyOf(active.values());
        }
        RuntimeException failure = null;
        for (CancellationSource source : sources) {
            CancellationSource.CancelResult result = source.cancel("runtime_closed");
            if (result.callbackFailure().isPresent()) {
                RuntimeException callbackFailure = result.callbackFailure().orElseThrow();
                if (failure == null) {
                    failure = callbackFailure;
                }
                else if (failure != callbackFailure) {
                    failure.addSuppressed(callbackFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 关闭快照与新请求登记共享同一把锁，确保被快照的请求必定收到取消。
     */
    private void register(String threadId, CancellationSource source) {
        synchronized (lifecycle) {
            if (shutdown) {
                throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
            }
            if (active.putIfAbsent(threadId, source) != null) {
                throw JaRpcException.of(JaErrorCatalog.THREAD_BUSY, "context compaction is already running");
            }
        }
    }

    /**
     * 活动项仅由持有该取消源的压缩请求移除，避免迟到完成清掉后继状态。
     */
    private void unregister(String threadId, CancellationSource source) {
        synchronized (lifecycle) {
            active.remove(threadId, source);
        }
    }

    /**
     * 允许 cancel 路由与压缩路由共享关闭准入语义。
     */
    private void requireAccepting() {
        synchronized (lifecycle) {
            if (shutdown) {
                throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
            }
        }
    }

    /**
     * 仅当前连接持有的活动压缩可取消；完成竞态由原请求的最终结果说明。
     */
    private CompletionStage<ObjectNode> cancel(ObjectNode params) {
        RpcParams.requireExact(params, "threadId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        CancellationSource source;
        synchronized (lifecycle) {
            source = active.get(threadId);
        }
        boolean accepted = source != null && source.cancel("user_stop").changed();
        return CompletableFuture.completedFuture(session.mapper().createObjectNode().put("accepted", accepted));
    }

    /**
     * 输出精确成功闭集；nullable 身份使用 JSON null，禁止缺字段造成三端解释分叉。
     */
    private ObjectNode result(ContextCompactionUseCase.Result value) {
        ObjectNode result = session.mapper().createObjectNode()
                .put("outcome", value.outcome().name().toLowerCase(Locale.ROOT))
                .put("threadRevision", value.threadRevision())
                .put("inputTokensBefore", value.inputTokensBefore())
                .put("inputTokensAfter", value.inputTokensAfter());
        if (value.compactionId() == null) {
            result.putNull("compactionId");
        }
        else {
            result.put("compactionId", value.compactionId());
        }
        if (value.checkpointId() == null) {
            result.putNull("checkpointId");
        }
        else {
            result.put("checkpointId", value.checkpointId());
        }
        return result;
    }
}
