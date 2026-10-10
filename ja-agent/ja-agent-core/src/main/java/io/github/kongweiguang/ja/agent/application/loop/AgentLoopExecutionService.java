// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 供公开 Engine 驱动唯一持久化 Agent Loop 的内部生命周期契约。
 */
public interface AgentLoopExecutionService extends AutoCloseable {
    /**
     * 使用本次已解析运行时准入，保证准入指纹与随后派发的能力视图一致。
     */
    AgentStore.AdmissionReceipt admit(AgentExecutionRequest request, AgentRequestRuntime runtime,
                                      AgentStore.RecordWrite requestBinding);

    /**
     * 执行同一运行时租约；持久等待也结束本轮 dispatch，临时草稿非阻塞转发。
     */
    CompletionStage<AgentExecutionOutcome> execute(AgentExecutionRequest request,
                                                   AgentStore.AdmissionReceipt admission,
                                                   AgentRequestRuntime runtime,
                                                   CancellationCoordinator.CancellationScope cancellation,
                                                   Consumer<AgentEvent> afterCommit,
                                                   TurnEventSink streamSink);

    /**
     * 使用持久准入绑定恢复历史选择，并在唯一游标驱动下继续；等待结果可结束本轮 owner。
     */
    CompletionStage<AgentExecutionOutcome> resume(AgentExecutionBinding binding, AgentRequestRuntime runtime,
                                                  CancellationCoordinator.CancellationScope cancellation,
                                                  Consumer<AgentEvent> afterCommit,
                                                  TurnEventSink streamSink);

    /**
     * 先通过 Store 控制事务提交取消事实，再取消模型和工具共用的执行作用域。
     */
    CompletionStage<AgentStore.CommitReceipt> cancel(AgentIdentity identity,
                                                     CancellationCoordinator.CancellationScope cancellation,
                                                     AgentStore.OperationIdentity operation, String reason,
                                                     Consumer<AgentEvent> afterCommit);

    /**
     * 追加输入并在同一 Store 控制事务投影宿主事实，不替换当前执行。
     */
    CompletionStage<AgentStore.CommitReceipt> appendInput(AgentIdentity identity,
                                                          AgentStore.OperationIdentity operation, String messageId, UserContent input);

    /**
     * 在无模型绑定的控制事务中结算等待和回答，重启迁移中的任务也可保存明确答案。
     */
    CompletionStage<AgentStore.CommitReceipt> answer(AgentIdentity identity,
                                                     AgentStore.OperationIdentity operation, String interactionId, long expectedInteractionRevision,
                                                     List<InteractionAnswer> answers, java.util.Map<String, String> hostMetadata,
                                                     Consumer<AgentEvent> afterCommit);

    /**
     * 显式关闭待回答问题；不伪造答案，也不隐式恢复 Loop。
     */
    CompletionStage<AgentStore.CommitReceipt> closeWait(AgentIdentity identity,
                                                        AgentStore.OperationIdentity operation, String interactionId, long expectedInteractionRevision,
                                                        InteractionStatus status, java.util.Map<String, String> hostMetadata);

    /**
     * 只读当前执行和 conversation revision，调用方据此检测旧列表与队列快照。
     */
    Optional<AgentExecutionStatus> query(AgentIdentity identity);

    /**
     * 重放持久队列事实；不存在执行记录时返回空队列，不从旧应用表猜测待处理输入。
     */
    AgentInputQueue inspectInputQueue(AgentIdentity identity);

    /**
     * 将队列意图转成带 Store queueRevision 的事实，在单次控制事务保存输入正文及宿主投影。
     */
    CompletionStage<InputMutationReceipt> mutateInput(AgentIdentity identity,
                                                      AgentStore.OperationIdentity operation, AgentInputMutation mutation);

    /**
     * 保留非终态暂停原因，使宿主工作流可在显式安全点恢复同一执行。
     */
    CompletionStage<AgentStore.CommitReceipt> suspend(AgentIdentity identity,
                                                      AgentStore.OperationIdentity operation, String reasonCode);

    /**
     * 应用显式工具恢复裁决；宿主须验证授权身份并与裁决事实同事务提交。
     */
    CompletionStage<AgentStore.CommitReceipt> resolveToolRecovery(AgentIdentity identity,
                                                                  AgentStore.OperationIdentity operation, String recoveryId, String callId,
                                                                  long expectedRecoveryRevision, RecoveryCommand.Decision decision, String authorizationId);

    /**
     * 仅关闭 Loop 自己创建的资源；宿主注入的存储、模型与工具仍由宿主管理。
     */
    @Override
    void close();

    /**
     * 入队或编辑回执携带 reducer 计算的 queueRevision，不能由调用者猜测。
     *
     * @param commit 提交
     * @param inputId 输入标识
     * @param queueRevision 队列修订版本
     */
    record InputMutationReceipt(AgentStore.CommitReceipt commit,
                                String inputId,
                                long queueRevision) {
        /**
         * 回执总是绑定一次实际 Store 提交和正向队列修订。
         */
        public InputMutationReceipt {
            java.util.Objects.requireNonNull(commit, "commit");
            if (inputId == null || inputId.isBlank() || queueRevision < 1) {
                throw new IllegalArgumentException("invalid input mutation receipt");
            }
        }
    }
}
