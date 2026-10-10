// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 提供脱敏健康检查与优雅关闭准入，不暴露配置文档或底层错误。
 */
public final class HealthShutdownHandler implements RpcHandler {
    private static final int MAX_SHUTDOWN_SCAN_PAGES = 512;
    private final RpcSession session;

    /**
     * 将生命周期状态绑定到脱敏配置健康边界，且不读取配置文档。
     */
    public HealthShutdownHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 返回由该 Handler 独占处理的健康、恢复与客户端生命周期路由集合。
     * @author kongweiguang
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.RUNTIME_CONTEXT_REGISTER, RpcMethod.RUNTIME_HEALTH,
                RpcMethod.RUNTIME_CLIENT_CLOSE, RpcMethod.RUNTIME_STORAGE_RETRY, RpcMethod.RUNTIME_SHUTDOWN);
    }

    /**
     * 执行有界健康检查，或在返回关闭确认前停止新请求准入。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        return CompletableFuture.completedFuture(switch (command.method()) {
            case RUNTIME_HEALTH -> health(command.params());
            case RUNTIME_CLIENT_CLOSE -> closeNativeClient(command.params());
            case RUNTIME_STORAGE_RETRY -> retry(command.params());
            case RUNTIME_SHUTDOWN -> shutdown(command.params());
            case RUNTIME_CONTEXT_REGISTER -> throw JaRpcException.invalidParams();
            default -> throw JaRpcException.methodNotFound();
        });
    }

    /**
     * 返回稳定健康与恢复事实，不向调用方暴露路径、凭据或 SQL 内容。
     */
    private ObjectNode health(ObjectNode params) {
        RpcParams.requireExact(params);
        session.requireConnected();
        boolean available = session.servicesAvailable();
        ObjectNode result = session.mapper().createObjectNode().put("status", available ? "ready" : "blocked");
        ArrayNode components = result.putArray("components");
        components.addObject().put("name", "sidecar").put("status", "healthy");
        components.addObject().put("name", "sqlite").put("status", available ? "healthy" : "unavailable");
        components.addObject().put("name", "kernel").put("status", available ? "healthy" : "unavailable");
        ConfigurationUseCase.HealthResult configuration = session.configurationUseCase().health();
        String status = configuration.status() == ConfigurationUseCase.HealthStatus.HEALTHY
                ? "healthy" : "degraded";
        ObjectNode component = components.addObject().put("name", "configuration").put("status", status);
        if (!configuration.diagnostics().isEmpty()) {
            ArrayNode sanitized = component.putArray("diagnostics");
            for (String diagnostic : configuration.diagnostics().stream().limit(16).toList()) {
                if (diagnostic.matches("[A-Z][A-Z0-9_]{0,63}")) {
                    sanitized.add(diagnostic);
                }
            }
        }
        RpcServicesFactory.RecoveryStatus recovery = session.storageRecovery();
        if (recovery != null) {
            result.putObject("storageRecovery").put("id", recovery.id()).put("phase", recovery.phase())
                    .put("reason", recovery.reason()).put("historyAvailable", recovery.historyAvailable())
                    .put("backupAvailable", recovery.backupAvailable()).put("rebuilt", recovery.rebuilt());
        }
        return result;
    }

    /**
     * 只等待当前 Host 的临时资源达到持久关闭回执；Storage blocked 且未开放 Task 服务时空 owner 可直接确认。
     */
    private ObjectNode closeNativeClient(ObjectNode params) {
        RpcParams.requireExact(params, "timeoutMs");
        long timeoutMs = RpcParams.wholeNumber(params, "timeoutMs");
        if (timeoutMs < 1 || timeoutMs > 20_000) {
            throw JaRpcException.invalidParams();
        }
        session.requireConnected();
        NativeClientOwner owner = NativeExecutionContext.shared().currentClientOwner()
                .orElseThrow(JaRpcException::invalidParams);
        try {
            if (session.servicesAvailable()) {
                session.tasks().closeNativeClient(owner,
                        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            } else {
                NativeExecutionContext.shared().beginNativeClientClose(owner);
                NativeExecutionContext.shared().completeNativeClientClose(owner);
            }
        } catch (TaskRepositoryException failure) {
            throw TaskHandler.map(failure);
        }
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /** 重试后读取权威健康状态；若仍阻塞，调用方可按新事实决定下一步。 */
    private ObjectNode retry(ObjectNode params) {
        RpcParams.requireExact(params);
        session.retryStorage();
        return health(params);
    }

    /**
     * 停止新请求与 Turn 准入；RpcServer 在开始有界清理前先发送该确认。
     */
    private ObjectNode shutdown(ObjectNode params) {
        RpcParams.requireOnly(params, "force");
        boolean force = false;
        if (params.has("force")) {
            if (!params.get("force").isBoolean()) {
                throw JaRpcException.invalidParams();
            }
            force = params.get("force").booleanValue();
        }
        session.requireConnected();
        if (!force && session.servicesAvailable() && hasPendingWork()) {
            throw JaRpcException.of(io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog.INVALID_STATE,
                    "runtime has pending work");
        }
        session.beginShutdown("host_shutdown");
        return session.mapper().createObjectNode().put("accepted", true)
                .put("status", "shutting_down");
    }

    /**
     * 无 force 关闭保守扫描所有可导航会话的活动与待输入事实；超过有界页数视为仍有工作，
     * 避免把可恢复审批、Plan 执行或问答当作空闲；扫描完成后再原子关闭 Turn 准入。
     */
    private boolean hasPendingWork() {
        String cursor = null;
        for (int page = 0; page < MAX_SHUTDOWN_SCAN_PAGES; page++) {
            CursorPage<ThreadDiscovery> found = session.threads().discoverThreads(
                    new ThreadDiscovery.Query("all", null, cursor, 200));
            for (ThreadDiscovery thread : found.items()) {
                if (switch (thread.status()) {
                    case QUEUED, RUNNING, WAITING_APPROVAL, SUSPENDED -> true;
                    default -> false;
                }) {
                    return true;
                }
                Optional<InteractionSnapshot> interaction = session.interactions().read(thread.threadId(), null);
                if (interaction.isPresent() && interaction.orElseThrow().request().isPresent()
                    && interaction.orElseThrow().request().orElseThrow().status() == InteractionStatus.PENDING) {
                    return true;
                }
                Optional<PlanUseCase.PlanTurnContext> plan = session.plans().currentPlanContext(thread.threadId());
                if (plan.isPresent() && plan.orElseThrow().status() != PlanModels.PlanStatus.COMPLETED
                    && plan.orElseThrow().status() != PlanModels.PlanStatus.STOPPED) {
                        return true;
                    }
            }
            cursor = found.nextCursor();
            if (cursor == null) {
                return !session.turns().stopAcceptingIfQuiescent();
            }
        }
        return true;
    }
}
