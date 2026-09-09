// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort.FinishReason;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;
import java.util.Objects;
import java.util.Set;

/**
 * 观察已确定执行边界的进程内端口；事件只携带安全元数据，观察器不得参与业务决策或资源释放。
 */
public interface ExecutionObserver {
    /** 返回进程内稳定身份，供确定性排序、重复检测与安全诊断使用。 */
    String id();

    /** 返回显式优先级；同优先级按稳定身份排序。 */
    default int order() {
        return 0;
    }

    /** 明确声明需要的事件类型，分发器不会把无关事件交给实现自行筛选。 */
    Set<EventKind> subscriptions();

    /** 同步观察安全元数据；任何异常都由分发器隔离，不能改变内核结果。 */
    void observe(Event event);

    /** 可订阅的执行边界闭集。 */
    enum EventKind {
        /** 一次 Turn 执行尝试已真实进入执行器。 */
        TURN_STARTED,
        /** 一次 Turn 执行尝试已得到终态或明确失败。 */
        TURN_COMPLETED,
        /** 一次冻结模型请求即将调用 Provider。 */
        MODEL_STARTED,
        /** 一次 Provider 调用已经收口。 */
        MODEL_COMPLETED,
        /** 一个 Tool 已越过持久 started 边界。 */
        TOOL_STARTED,
        /** 一个 Tool 的核心结果已经结算。 */
        TOOL_COMPLETED,
        /** 一个公开事件已经完成持久提交。 */
        COMMITTED
    }

    /** 开始后的规范收口类别；拒绝与超时不会被折叠成普通失败。 */
    enum CompletionStatus {
        /** 调用按协议成功完成。 */
        SUCCEEDED,
        /** 调用以稳定内部或外部错误结束。 */
        FAILED,
        /** 调用在越过实际执行边界前被策略或权限拒绝。 */
        REJECTED,
        /** 调用响应 Turn 或 Loop 的取消请求结束。 */
        CANCELLED,
        /** 调用因稳定 deadline 或 timeout 边界结束。 */
        TIMED_OUT
    }

    /** 所有观察事件显式公开类型，避免按实现类名决定订阅。 */
    sealed interface Event permits TurnStarted, TurnCompleted, ModelStarted, ModelCompleted,
            ToolStarted, ToolCompleted, Committed {
        /** 返回稳定事件类型，用于预先构造的订阅路由。 */
        EventKind kind();
    }

    /** 一次真实 Turn 执行尝试已经进入执行器；排队或预检失败不会产生该事件。 */
    record TurnStarted(String threadId, String turnId) implements Event {
        /** 要求完整稳定身份，观察器无需回读可变运行时。 */
        public TurnStarted {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.TURN_STARTED; }
    }

    /** 一次 Turn 执行尝试已经得到终态，或在终态前以明确失败边界退出。 */
    record TurnCompleted(
            String threadId,
            String turnId,
            CompletionStatus status,
            String code) implements Event {
        /** 只保留稳定分类，不携带异常正文、Prompt 或用户数据。 */
        public TurnCompleted {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            Objects.requireNonNull(status, "status");
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.TURN_COMPLETED; }
    }

    /** 一次冻结请求即将越过真实 Provider 调用边界，仅公开请求身份与轮次。 */
    record ModelStarted(String threadId, String turnId, String requestId, int round) implements Event {
        /** 拒绝缺失身份和非法轮次，避免观察数据与真实调用脱节。 */
        public ModelStarted {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            requestId = ContractChecks.identifier(requestId, "requestId");
            if (round < 1) throw new IllegalArgumentException("round must be positive");
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.MODEL_STARTED; }
    }

    /** Provider 调用已经成功或失败收口；用量可空，但永不公开请求配置或凭据。 */
    record ModelCompleted(
            String threadId,
            String turnId,
            String requestId,
            int round,
            CompletionStatus status,
            FinishReason finishReason,
            ModelUsage usage,
            String code) implements Event {
        /** 保持与 started 相同身份，并只允许成功事件携带完成原因。 */
        public ModelCompleted {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            requestId = ContractChecks.identifier(requestId, "requestId");
            if (round < 1) throw new IllegalArgumentException("round must be positive");
            Objects.requireNonNull(status, "status");
            if ((status == CompletionStatus.SUCCEEDED) != (finishReason != null)) {
                throw new IllegalArgumentException("model finish reason must match success status");
            }
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.MODEL_COMPLETED; }
    }

    /** 单个 Tool 已持久提交 started 事实并将越过真实执行边界。 */
    record ToolStarted(
            String threadId,
            String turnId,
            String callId,
            String toolName,
            int ordinal,
            ToolSideEffect sideEffect) implements Event {
        /** 只保留调用身份和静态安全分类，不公开参数与工作区路径。 */
        public ToolStarted {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            callId = ContractChecks.identifier(callId, "callId");
            toolName = ContractChecks.identifier(toolName, "toolName");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
            Objects.requireNonNull(sideEffect, "sideEffect");
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.TOOL_STARTED; }
    }

    /** 单个 Tool 结果及其 Goal ledger 已结算并持久提交，只公开结果分类和耗时。 */
    record ToolCompleted(
            String threadId,
            String turnId,
            String callId,
            String toolName,
            int ordinal,
            CompletionStatus status,
            ToolOutcome outcome,
            String code,
            long durationMillis) implements Event {
        /** 结果正文和结构化内容保持在内核，不越过诊断观察端口。 */
        public ToolCompleted {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            callId = ContractChecks.identifier(callId, "callId");
            toolName = ContractChecks.identifier(toolName, "toolName");
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(outcome, "outcome");
            if (durationMillis < 0) throw new IllegalArgumentException("durationMillis must be non-negative");
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.TOOL_COMPLETED; }
    }

    /** 一个公开事件已经持久提交，只投影类型和 revision，不复制事件正文。 */
    record Committed(String threadId, String turnId, String eventType, long threadRevision) implements Event {
        /** 已提交观察只接受非负 revision 与安全类型名。 */
        public Committed {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            eventType = ContractChecks.identifier(eventType, "eventType");
            if (threadRevision < 0) throw new IllegalArgumentException("threadRevision must be non-negative");
        }

        /** 返回固定订阅类型。 */
        @Override public EventKind kind() { return EventKind.COMMITTED; }
    }

}
