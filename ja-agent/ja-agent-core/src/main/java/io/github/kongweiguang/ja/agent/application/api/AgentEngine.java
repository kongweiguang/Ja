// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAdmissionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInput;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputMutationReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Agent 执行用例的稳定应用契约；实现细节位于 application.service，装配由组合根负责。
 */
public interface AgentEngine extends AutoCloseable {
    /**
     * 接受一条用户请求并按幂等键返回执行句柄。
     */
    AgentExecutionHandle submit(AgentRequest request);

    /**
     * 新准入或持久重放由本次调用调度前同步交付原始回执；本地句柄重入不重调度。
     * 已有本地回执会同步通知 Consumer，但先前调度可能已开始；若回执仍 pending，本次 Consumer 不调用，改由句柄阶段观察。
     */
    AgentExecutionHandle submit(AgentRequest request, Consumer<AgentAdmissionReceipt> beforeSchedule);

    /**
     * 接受宿主生成的内部请求，不新增可见用户消息。
     */
    AgentExecutionHandle submitInternal(AgentInternalRequest request);

    /**
     * 在内部请求新准入或持久重放由本次调用调度前同步交付原始回执；已有本地回执同步通知，pending 阶段通过句柄观察。
     */
    AgentExecutionHandle submitInternal(AgentInternalRequest request,
                                        Consumer<AgentAdmissionReceipt> beforeSchedule);

    /**
     * 读取已准入的初始队列，保证重试不会混入之后消息。
     */
    Optional<List<AgentInputCommand.Enqueue>> readAdmittedInitialInputs(AgentRequest request);

    /**
     * 读取内部请求对应的已准入初始队列。
     */
    Optional<List<AgentInputCommand.Enqueue>> readAdmittedInitialInputs(AgentInternalRequest request);

    /**
     * 以独立内部身份启动上下文压缩。
     */
    AgentExecutionHandle compact(AgentCompactionRequest request);

    /**
     * 在压缩 execution 新准入或持久重放由本次调用调度前同步交付原始回执；已有本地回执同步通知，pending 阶段通过句柄观察。
     */
    AgentExecutionHandle compact(AgentCompactionRequest request,
                                 Consumer<AgentAdmissionReceipt> beforeSchedule);

    /**
     * 创建有界事件流订阅并绑定当前线程身份。
     */
    AgentStreamSubscription subscribeStream(AgentIdentity identity, int capacity);

    /**
     * 恢复已持久化但尚未完成的执行。
     */
    AgentExecutionHandle resume(AgentIdentity identity);

    /**
     * 查询当前执行状态，不重新派发执行。
     */
    Optional<AgentExecutionStatus> query(AgentIdentity identity);

    /**
     * 追加输入并在 Store 中原子记录控制事实。
     */
    CompletionStage<Void> appendInput(AgentIdentity identity, AgentInput input);

    /**
     * 返回当前待消费输入队列的不可变快照。
     */
    AgentInputQueue inspectInputQueue(AgentIdentity identity);

    /**
     * 请求暂停当前执行并保存原因。
     */
    CompletionStage<Void> suspend(AgentIdentity identity, String operationKey, String reasonCode);

    /**
     * 记录工具恢复裁决并允许安全点继续执行。
     */
    CompletionStage<Void> resolveToolRecovery(AgentIdentity identity, String operationKey,
                                              String recoveryId, String callId, long expectedRecoveryRevision,
                                              io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand.Decision decision,
                                              String authorizationId);

    /**
     * 按 CAS 规则变更待消费输入。
     */
    CompletionStage<AgentInputMutationReceipt> mutateInput(AgentIdentity identity,
                                                           AgentInputCommand command);

    /**
     * 原子结算交互回答。
     */
    CompletionStage<Void> answer(AgentIdentity identity, AgentAnswer answer);

    /**
     * 原子关闭等待交互并推进恢复资格。
     */
    CompletionStage<Void> closeWait(AgentIdentity identity, String interactionId,
                                    long expectedInteractionRevision, InteractionStatus nextStatus,
                                    String operationKey, Map<String, String> hostMetadata);

    /**
     * 记录取消控制事实并尝试停止当前执行。
     */
    CompletionStage<Void> cancel(AgentIdentity identity, String operationKey, String reason);

    /**
     * 读取固定水位上的持久事件页。
     */
    AgentEventPage readEvents(AgentIdentity identity, long afterSequence, int limit);

    /**
     * 订阅当前执行的事件增量。
     */
    AgentEventWatch watchEvents(AgentIdentity identity, long afterSequence);

    /**
     * 关闭执行服务并等待已接管资源收敛。
     */
    @Override
    void close();

    /**
     * 异步关闭执行服务。
     */
    CompletionStage<Void> closeAsync();

    /**
     * 在给定超时内等待关闭完成。
     */
    void awaitClosed(Duration timeout);

    /**
     * 使用默认关闭等待边界等待关闭完成。
     */
    void awaitClosed();
}
