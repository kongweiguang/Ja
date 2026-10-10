// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.execution;

import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 提交事件只引用同事务执行事实，正文和工具输出不重复复制到通知协议。
 */
public sealed interface ExecutionEvent {
    /**
     * 统一使用可移植标识符，禁止隐式加入 Ja 的特定前缀要求。
     */
    private static String id(String value) {
        return ContractChecks.identifier(value, "event reference");
    }

    /**
     * 冻结有序引用并拒绝重复，避免重放时一次事实被投影两次。
     */
    private static List<String> ids(List<String> values) {
        List<String> frozen = Objects.requireNonNull(values, "references").stream().map(ExecutionEvent::id).toList();
        if (new HashSet<>(frozen).size() != frozen.size())
            {
                throw new IllegalArgumentException("duplicate event reference");
            }
        return frozen;
    }

    /**
     * 持久化接口只公开两种可终结的压缩结果。
     */
    enum CompactionOutcome {
        /**
         * 检查点已提交，模型请求上下文已缩小。
         */
        COMPACTED,
        /**
         * 持久规划确认无需生成更小的上下文。
         */
        UNCHANGED
    }

    /**
     * 区分持久取消、挂起意图与恢复时消费的挂起请求。
     */
    enum ControlAction {
        /**
         * 持久取消请求阻止后续外部派发。
         */
        CANCEL_REQUESTED,
        /**
         * 持久挂起请求等待在下一个安全点消费。
         */
        SUSPEND_REQUESTED,
        /**
         * 恢复执行已原子消费挂起请求。
         */
        SUSPEND_CONSUMED,
        /**
         * 有序输入队列发生变化，但不表示已消费任何条目。
         */
        INPUT_CHANGED,
        /**
         * 等待的回答或关闭状态已在同一事务结算。
         */
        WAIT_RESOLVED,
        /**
         * 经明确授权的工具恢复决定已持久记录。
         */
        TOOL_RECOVERY_DECIDED
    }

    /**
     * 准入来源区分用户输入、内部指令和不生成对话消息的上下文压缩。
     *
     * @param kind 类型分类
     * @param inputMessageId 输入消息标识
     * @param instructionMessageId 指令消息标识
     */
    record Admitted(String kind,
                    String inputMessageId,
                    String instructionMessageId) implements ExecutionEvent {
        /**
         * 输入引用必须与准入种类精确对应，防止内部执行伪造成用户输入。
         */
        public Admitted {
            Objects.requireNonNull(kind, "kind");
            boolean valid = switch (kind) {
                case "USER_INPUT" -> inputMessageId != null && instructionMessageId == null;
                case "INTERNAL" -> inputMessageId == null && instructionMessageId != null;
                case "COMPACTION" -> inputMessageId == null && instructionMessageId == null;
                default -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("invalid admission message identity");
            }
            if (inputMessageId != null) {
                inputMessageId = id(inputMessageId);
            }
            if (instructionMessageId != null) {
                instructionMessageId = id(instructionMessageId);
            }
        }
    }

    /**
     * 持久状态变更由领域状态机约束，不能发布虚假的自环或终态反转。
     *
     * @param from 起点
     * @param to 目标
     */
    record StateChanged(TurnState from,
                        TurnState to) implements ExecutionEvent {
        /**
         * 事件验证与执行状态迁移共用同一规则。
         */
        public StateChanged {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!from.canTransitionTo(to)) {
                throw new IllegalArgumentException("invalid execution state transition");
            }
        }
    }

    /**
     * 仅在完整模型消息保存后发布，失败请求的半截正文不会成为历史。
     *
     * @param messageRecordId 消息记录标识
     * @param modelRound 模型调用轮次
     * @param toolCallIds 工具调用标识集合
     * @param usageRecordId 用量记录标识
     */
    record ModelCompleted(String messageRecordId,
                          int modelRound,
                          List<String> toolCallIds,
                          String usageRecordId) implements ExecutionEvent {
        /**
         * 保存原生工具调用顺序，重复身份直接拒绝。
         */
        public ModelCompleted {
            messageRecordId = id(messageRecordId);
            if (modelRound < 1) {
                throw new IllegalArgumentException("model round must be positive");
            }
            toolCallIds = ids(toolCallIds);
            usageRecordId = id(usageRecordId);
        }
    }

    /**
     * Provider STOP 且未产生工具调用时，助手答复在任何排队输入消费前已成为独立、可见的结算边界。
     *
     * @param messageRecordId 已提交助手消息记录
     */
    record AssistantSettled(String messageRecordId) implements ExecutionEvent {
        /** 结算事件必须指向本次提交中已存在的稳定助手记录，不依赖终态或“最近一条”推断。 */
        public AssistantSettled {
            messageRecordId = id(messageRecordId);
        }
    }

    /**
     * STARTED 事实提交后才能发布工具开始事件，并随后越过实际执行边界。
     *
     * @param callId 模型生成的工具调用标识
     * @param batchId 批次标识
     * @param toolName 工具名称
     * @param ordinal 当前项的顺序位置
     */
    record ToolStarted(String callId,
                       String batchId,
                       String toolName,
                       int ordinal) implements ExecutionEvent {
        /**
         * 调用与批次身份必须明确，不能通过序号猜测关联。
         */
        public ToolStarted {
            callId = id(callId);
            batchId = id(batchId);
            toolName = id(toolName);
            if (ordinal < 0) {
                throw new IllegalArgumentException("negative tool ordinal");
            }
        }
    }

    /**
     * 工具事实和模型可见消息分别有稳定引用，投影不能修改真实成败。
     *
     * @param callId 模型生成的工具调用标识
     * @param state 当前状态
     * @param resultRecordId 结果记录标识
     * @param toolMessageRecordId 工具消息记录标识
     * @param errorCode 错误代码
     */
    record ToolCompleted(String callId,
                         ToolState state,
                         String resultRecordId,
                         String toolMessageRecordId,
                         String errorCode) implements ExecutionEvent {
        /**
         * 不将准备中或执行中的工具伪装成完成；错误分类可以独立于模型可见文字。
         */
        public ToolCompleted {
            callId = id(callId);
            resultRecordId = id(resultRecordId);
            toolMessageRecordId = id(toolMessageRecordId);
            Objects.requireNonNull(state, "state");
            if (!Set.of(ToolState.SUCCEEDED, ToolState.FAILED, ToolState.CANCELLED).contains(state)) {
                throw new IllegalArgumentException("tool has not completed");
            }
            if (errorCode != null) {
                errorCode = id(errorCode);
            }
            if (state == ToolState.SUCCEEDED && errorCode != null)
                {
                    throw new IllegalArgumentException("successful tool has error");
                }
        }
    }

    /**
     * 工具恢复裁决与消费结果均通过精确决策记录引用投影，避免按当前状态猜测恢复历史。
     *
     * @param recoveryId 恢复标识
     * @param callId 模型生成的工具调用标识
     * @param revision 当前修订版本
     * @param decisionRecordId 决策记录标识
     */
    record ToolRecoveryChanged(String recoveryId,
                               String callId,
                               long revision,
                               String decisionRecordId) implements ExecutionEvent {
        /**
         * 每次恢复状态变化必须前进版本，并引用同一事务保存的严格决策记录。
         */
        public ToolRecoveryChanged {
            recoveryId = id(recoveryId);
            callId = id(callId);
            decisionRecordId = id(decisionRecordId);
            if (revision < 1) {
                throw new IllegalArgumentException("invalid tool recovery revision");
            }
        }
    }

    /**
     * 等待可以来自工具或完成前扩展，不强制存在工具调用。
     *
     * @param interactionId 交互标识
     * @param kind 类型分类
     * @param requestRecordId 请求记录标识
     */
    record WaitRequested(String interactionId,
                         String kind,
                         String requestRecordId) implements ExecutionEvent {
        /**
         * 以持久问题记录为权威内容，事件只传身份和等待类别。
         */
        public WaitRequested {
            interactionId = id(interactionId);
            kind = id(kind);
            requestRecordId = id(requestRecordId);
        }
    }

    /**
     * 问题、回答和恢复资格已在同一提交内结算。
     *
     * @param interactionId 交互标识
     * @param answerIds 答案标识集合
     */
    record WaitResolved(String interactionId,
                        List<String> answerIds) implements ExecutionEvent {
        /**
         * 回答顺序保留，空答案不能冒充已解决的等待。
         */
        public WaitResolved {
            interactionId = id(interactionId);
            answerIds = ids(answerIds);
            if (answerIds.isEmpty()) {
                throw new IllegalArgumentException("wait resolution needs answers");
            }
        }
    }

    /**
     * 输入已持久接收，消费时点仍由执行游标决定。
     *
     * @param messageId 消息标识
     */
    record InputAccepted(String messageId) implements ExecutionEvent {
        /**
         * 事件身份与持久输入绑定，防止客户端重试制造第二条输入。
         */
        public InputAccepted {
            messageId = id(messageId);
        }
    }

    /**
     * 唯一终态事务提交后才能向观察者宣布完成、失败或取消。
     *
     * @param state 当前状态
     * @param summary 摘要
     * @param errorCode 错误代码
     * @param errorMessage 错误消息
     * @param messageRecordId 消息记录标识
     * @param usageRecordId 用量记录标识
     */
    record Terminal(TurnState state,
                    String summary,
                    String errorCode,
                    String errorMessage,
                    String messageRecordId,
                    String usageRecordId) implements ExecutionEvent {
        /**
         * 成功不得携带错误，挂起或等待也不得伪装终态。
         */
        public Terminal {
            Objects.requireNonNull(state, "state");
            if (!state.terminal()) {
                throw new IllegalArgumentException("execution is not terminal");
            }
            summary = Objects.requireNonNull(summary, "summary");
            if (errorCode != null) {
                errorCode = id(errorCode);
            }
            if (messageRecordId != null) {
                messageRecordId = id(messageRecordId);
            }
            if (usageRecordId != null) {
                usageRecordId = id(usageRecordId);
            }
            if (state == TurnState.COMPLETED && (errorCode != null || errorMessage != null)) {
                throw new IllegalArgumentException("completed execution has error");
            }
        }
    }

    /**
     * 相同模型的可恢复故障被安排重试，attempt 是审计计数而非终止预算。
     *
     * @param modelRequestId 一次真实模型请求的标识
     * @param attempt 尝试次数
     * @param delayMillis 下次重试前的等待时间，单位为毫秒
     * @param reasonCode 原因代码
     */
    record RetryScheduled(String modelRequestId,
                          int attempt,
                          long delayMillis,
                          String reasonCode) implements ExecutionEvent {
        /**
         * 明确退避间隔与脱敏分类，不向事件日志写入 Provider 异常对象。
         */
        public RetryScheduled {
            modelRequestId = id(modelRequestId);
            reasonCode = id(reasonCode);
            if (attempt < 1 || delayMillis < 0) {
                throw new IllegalArgumentException("invalid retry schedule");
            }
        }
    }

    /**
     * 普通助手请求派发前的本地估算已与 UNKNOWN Usage 同事务持久化。
     *
     * @param usageRecordId 估算用量记录标识
     */
    record ContextUsageEstimated(String usageRecordId) implements ExecutionEvent {
        /**
         * 仅引用已提交的 usage 事实，估算不会被解释为 Provider 实报用量。
         */
        public ContextUsageEstimated {
            usageRecordId = id(usageRecordId);
        }
    }

    /**
     * Summary Provider 开始前提交已计量输入与冻结源版本，恢复事件可重建先后顺序。
     *
     * @param compactionId 上下文压缩标识
     * @param sourceRevision 来源修订版本
     * @param inputTokensBefore 处理前的输入 Token 数
     */
    record ContextCompactionStarted(String compactionId,
                                    long sourceRevision,
                                    long inputTokensBefore) implements ExecutionEvent {
        /**
         * 禁止在摘要调用开始后补报 Started，计量与源版本必须来自同一冻结计划。
         */
        public ContextCompactionStarted {
            compactionId = id(compactionId);
            if (sourceRevision < 0 || inputTokensBefore < 0)
                {
                    throw new IllegalArgumentException("invalid compaction start");
                }
        }
    }

    /**
     * 持久化压缩结果可从单个事件恢复，UNCHANGED 明确不引用检查点。
     *
     * @param compactionId 上下文压缩标识
     * @param outcome 执行结果
     * @param checkpointId 检查点标识
     * @param checkpointRecordId 检查点记录标识
     * @param sourceRevision 来源修订版本
     * @param threadRevision 对话修订版本
     * @param inputTokensBefore 处理前的输入 Token 数
     * @param inputTokensAfter 处理后的输入 Token 数
     */
    record ContextCompacted(String compactionId,
                            CompactionOutcome outcome,
                            String checkpointId,
                            String checkpointRecordId,
                            long sourceRevision,
                            long threadRevision,
                            long inputTokensBefore,
                            long inputTokensAfter) implements ExecutionEvent {
        /**
         * 持久业务身份与存储定位符分开保存；未变化结果不得从当前 Store 状态猜测检查点。
         */
        public ContextCompacted {
            compactionId = id(compactionId);
            Objects.requireNonNull(outcome, "outcome");
            if (checkpointId != null) {
                checkpointId = id(checkpointId);
            }
            if (checkpointRecordId != null) {
                checkpointRecordId = id(checkpointRecordId);
            }
            boolean compacted = outcome == CompactionOutcome.COMPACTED;
            if (compacted == (checkpointId == null) || compacted == (checkpointRecordId == null)
                || sourceRevision < 0 || threadRevision < 0
                || inputTokensBefore < 0 || inputTokensAfter < 0) {
                throw new IllegalArgumentException("invalid compaction result");
            }
            if (threadRevision < sourceRevision
                || (compacted && inputTokensAfter >= inputTokensBefore)) {
                throw new IllegalArgumentException("compaction result regresses revision or does not shrink");
            }
            if (!compacted && inputTokensAfter != inputTokensBefore) {
                throw new IllegalArgumentException("unchanged context cannot change token measurement");
            }
        }
    }

    /**
     * 压缩失败使用稳定错误分类，不把异常消息或未提交摘要写入持久事件。
     *
     * @param compactionId 上下文压缩标识
     * @param sourceRevision 来源修订版本
     * @param inputTokensBefore 处理前的输入 Token 数
     * @param reasonCode 原因代码
     */
    record ContextCompactionFailed(String compactionId,
                                   long sourceRevision,
                                   long inputTokensBefore,
                                   String reasonCode) implements ExecutionEvent {
        /**
         * Failed 必须关联已经开始的压缩，并保留启动时的源版本和输入计量。
         */
        public ContextCompactionFailed {
            compactionId = id(compactionId);
            reasonCode = id(reasonCode);
            if (sourceRevision < 0 || inputTokensBefore < 0)
                {
                    throw new IllegalArgumentException("invalid compaction failure");
                }
        }
    }

    /**
     * 控制意图与消费事实随 Store CAS 一起发布，观察者不从临时 API 调用推断状态。
     *
     * @param action 动作
     * @param controlRecordId 控制记录标识
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param reasonCode 原因代码
     */
    record ControlRecorded(ControlAction action,
                           String controlRecordId,
                           String operationKey,
                           String reasonCode) implements ExecutionEvent {
        /**
         * cancel/suspend 请求含稳定原因码；引用具体业务事实的事件可不复制其载荷原因。
         */
        public ControlRecorded {
            Objects.requireNonNull(action, "action");
            controlRecordId = id(controlRecordId);
            operationKey = id(operationKey);
            if (action == ControlAction.CANCEL_REQUESTED || action == ControlAction.SUSPEND_REQUESTED) {
                reasonCode = id(reasonCode);
            } else if (reasonCode != null) {
                reasonCode = id(reasonCode);
            }
        }
    }
}
