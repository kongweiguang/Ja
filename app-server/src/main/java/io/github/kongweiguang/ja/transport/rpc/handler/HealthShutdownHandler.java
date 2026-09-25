// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels;

import java.time.Duration;

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
     * 返回该 Handler 独占的两个冻结生命周期方法。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.RUNTIME_CONTEXT_REGISTER, RpcMethod.RUNTIME_HEALTH,
                RpcMethod.RUNTIME_SHUTDOWN);
    }

    /**
     * 执行有界健康检查，或在返回关闭确认前停止新请求准入。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        return CompletableFuture.completedFuture(switch (command.method()) {
            case RUNTIME_HEALTH -> health(command.params());
            case RUNTIME_SHUTDOWN -> shutdown(command.params());
            case RUNTIME_CONTEXT_REGISTER -> throw JaRpcException.invalidParams();
            default -> throw JaRpcException.methodNotFound();
        });
    }

    /**
     * 只报告稳定组件分类，明确排除路径、Secret、异常文本与 SQL。
     */
    private ObjectNode health(ObjectNode params) {
        RpcParams.requireExact(params);
        session.requireReady();
        ObjectNode result = session.mapper().createObjectNode().put("status", "ready");
        ArrayNode components = result.putArray("components");
        components.addObject().put("name", "sidecar").put("status", "healthy");
        components.addObject().put("name", "sqlite").put("status", "healthy");
        components.addObject().put("name", "kernel").put("status", "healthy");
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
        return result;
    }

    /**
     * 停止新请求与 Turn 准入；RpcServer 在开始有界清理前先发送该确认。
     */
    private ObjectNode shutdown(ObjectNode params) {
        RpcParams.requireOnly(params, "force");
        boolean force = false;
        if (params.has("force")) {
            if (!params.get("force").isBoolean()) throw JaRpcException.invalidParams();
            force = params.get("force").booleanValue();
        }
        session.requireReady();
        if (!force && hasPendingWork()) {
            throw JaRpcException.of(io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog.INVALID_STATE,
                    "runtime has pending work");
        }
        session.beginShutdown("host_shutdown");
        return session.mapper().createObjectNode().put("accepted", true)
                .put("status", "shutting_down");
    }

    /**
     * 无 force 关闭保守扫描所有可导航会话的活动与待输入事实；超过有界页数视为仍有工作，
     * 避免后台在未知数据库范围内将可恢复审批、Plan 或 Goal 当作空闲。
     */
    private boolean hasPendingWork() {
        if (!session.turns().awaitQuiescence(Duration.ZERO)) return true;
        String cursor = null;
        for (int page = 0; page < MAX_SHUTDOWN_SCAN_PAGES; page++) {
            var found = session.threads().discoverThreads(new ThreadDiscovery.Query("all", null, cursor, 200));
            for (ThreadDiscovery thread : found.items()) {
                if (switch (thread.status()) {
                    case QUEUED, RUNNING, WAITING_APPROVAL, SUSPENDED -> true;
                    default -> false;
                }) return true;
                var interaction = session.interactions().read(thread.threadId(), null);
                if (interaction.isPresent() && interaction.orElseThrow().request().isPresent()
                    && interaction.orElseThrow().request().orElseThrow().status() == InteractionStatus.PENDING) {
                    return true;
                }
                var goal = session.goals().currentGoalContext(thread.threadId());
                if (goal.isPresent() && goal.orElseThrow().status() != GoalModels.GoalStatus.ACHIEVED
                    && goal.orElseThrow().status() != GoalModels.GoalStatus.STOPPED) return true;
                var plan = session.goals().currentPlanContext(thread.threadId());
                if (plan.isPresent() && plan.orElseThrow().status() != GoalModels.PlanStatus.COMPLETED
                    && plan.orElseThrow().status() != GoalModels.PlanStatus.STOPPED) return true;
            }
            cursor = found.nextCursor();
            if (cursor == null) return false;
        }
        return true;
    }
}
