// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;


import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.SuspendConsumption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommandConsumption;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;

import java.time.Instant;

/**
 * 为循环提供版本化等待与工具恢复事实编码，不让应用用例依赖 JSON 实现。
 */
public interface ExecutionControlCodec {
    /**
     * 控制事实的类型归 Store 协议所有，编排、投影与编码实现必须使用同一份标识。
     */
    String WAIT_RECORD_TYPE = "agent.interaction.wait";
    /**
     * 工具恢复裁决与授权命令分开保存，不能把待核验状态当成已提交结果。
     */
    String TOOL_RECOVERY_RECORD_TYPE = "agent.tool.recovery.decision";
    /**
     * 外部副作用的显式恢复授权是独立事实，恢复时须按原操作身份消费。
     */
    String RECOVERY_COMMAND_RECORD_TYPE = "agent.tool.recovery.command";
    /**
     * 已消费授权的记录避免进程恢复后重复处理同一裁决。
     */
    String RECOVERY_COMMAND_CONSUMPTION_RECORD_TYPE = "agent.tool.recovery.command.consumed";
    /**
     * 队列变化采用追加命令，正文由同事务中的消息记录保存。
     */
    String INPUT_QUEUE_COMMAND_RECORD_TYPE = "agent.input.queue.command";
    /**
     * 回答信封与等待聚合分开保存，用于同事务核验幂等提交。
     */
    String ANSWER_SUBMISSION_RECORD_TYPE = "agent.interaction.answer";
    /**
     * 取消请求是持久意图，不能以进程内取消令牌替代。
     */
    String CANCEL_REQUEST_RECORD_TYPE = "agent.execution.cancel-request";
    /**
     * 主动挂起与结果未知的挂起分别记录，恢复规则不能混用。
     */
    String SUSPEND_REQUEST_RECORD_TYPE = "agent.execution.suspend-request";
    /**
     * 挂起消费引用具体请求，后续并发请求仍保留给下一安全点。
     */
    String SUSPEND_CONSUMED_RECORD_TYPE = "agent.execution.suspend-consumed";
    /**
     * 等待事实含宿主扩展属性，格式变化必须独立升级。
     */
    int WAIT_SCHEMA_VERSION = 2;
    /**
     * 工具恢复裁决的稳定格式版本，不随等待事实升级。
     */
    int TOOL_RECOVERY_SCHEMA_VERSION = 1;
    /**
     * 恢复授权及消费共享同一格式版本，类型仍保持独立。
     */
    int RECOVERY_COMMAND_SCHEMA_VERSION = 1;
    /**
     * 输入队列命令版本独立于其它控制事实。
     */
    int INPUT_QUEUE_SCHEMA_VERSION = 1;
    /**
     * 回答信封格式必须保留客户端幂等元数据。
     */
    int ANSWER_SUBMISSION_SCHEMA_VERSION = 1;
    /**
     * 取消与挂起请求共享字段格式，通过记录类型区分动作。
     */
    int EXECUTION_CONTROL_SCHEMA_VERSION = 1;
    /**
     * 未知工具结果挂起执行时写入的 Core 事实类型。
     */
    String SUSPENSION_RECORD_TYPE = "agent.execution.suspension";
    /**
     * 挂起标记的严格 schema 版本。
     */
    int SUSPENSION_SCHEMA_VERSION = 1;

    /**
     * 编码结果未知导致的挂起事实，不把它伪装成用户发起的控制请求。
     */
    AgentStore.RecordWrite encodeSuspension(String recordId, String reasonCode, Instant occurredAt);

    /**
     * 严格解码挂起事实，拒绝未知类型、版本和字段。
     */
    String decodeSuspension(AgentStore.StoredRecord record);

    /**
     * 编码一条队列变更命令；调用方在同一 Store 事务中写入被引用的输入正文。
     */
    AgentStore.RecordWrite encodeInputQueueCommand(String recordId, AgentInputQueue.Command command,
                                                   Instant occurredAt);

    /**
     * 严格解码追加命令，供恢复 reducer 重建顺序、版本与待消费队列。
     */
    AgentInputQueue.Command decodeInputQueueCommand(AgentStore.StoredRecord record);

    /**
     * 事务参与者只能看到尚未分配序号的写入值，因此提供不伪造 StoredRecord 的严格解码入口。
     */
    AgentInputQueue.Command decodeInputQueueCommand(AgentStore.RecordWrite record);

    /**
     * 持久化一次完整回答及宿主幂等元数据，供同事务 participant 复核。
     */
    AgentStore.RecordWrite encodeAnswerSubmission(String recordId, InteractionAnswerSubmission answer);

    /**
     * 严格读取回答提交信封，不能把 answer record 当作 wait 本身的答案状态。
     */
    InteractionAnswerSubmission decodeAnswerSubmission(AgentStore.StoredRecord record);

    /**
     * 供同连接事务参与者校验待写回答，不能人为构造 StoredRecord 绕过序号分配。
     */
    InteractionAnswerSubmission decodeAnswerSubmission(AgentStore.RecordWrite record);

    /**
     * 持久化完整等待问题、回答与扩展所有者属性。
     */
    AgentStore.RecordWrite encodeWait(String recordId, InteractionWait wait, Instant occurredAt);

    /**
     * 严格恢复等待信封，损坏或未知版本必须失败而不能丢弃扩展状态。
     */
    InteractionWait decodeWait(AgentStore.StoredRecord record);

    /**
     * 将取消事实放入控制事务，使重启后不会把已请求取消的执行继续派发。
     */
    AgentStore.RecordWrite encodeCancelRequest(String recordId, ControlRequest request);

    /**
     * 只接受明确版本的取消事实，未知内容不得被解释成允许继续。
     */
    ControlRequest decodeCancelRequest(AgentStore.StoredRecord record);

    /**
     * 将挂起意图持久化，供唯一执行者在安全点转换状态。
     */
    AgentStore.RecordWrite encodeSuspendRequest(String recordId, ControlRequest request);

    /**
     * 恢复挂起意图；控制事务不能被错误地降级为普通状态查询。
     */
    ControlRequest decodeSuspendRequest(AgentStore.StoredRecord record);

    /**
     * 记录恢复消费的具体 suspend 请求；新请求必须留给下一安全点。
     */
    AgentStore.RecordWrite encodeSuspendConsumption(String recordId, SuspendConsumption consumption);

    /**
     * 恢复消费引用，是否对应可见请求由 ExecutionStateView reducer 校验。
     */
    SuspendConsumption decodeSuspendConsumption(AgentStore.StoredRecord record);

    /**
     * 保存派发尝试的可恢复核对状态，未知结果不得被解释成未执行。
     */
    AgentStore.RecordWrite encodeToolRecoveryDecision(String recordId, ToolRecoveryDecision decision,
                                                      Instant occurredAt);

    /**
     * 恢复工具核对状态，不能用当前能力描述替换持久证据。
     */
    ToolRecoveryDecision decodeToolRecoveryDecision(AgentStore.StoredRecord record);

    /**
     * 保存宿主对未知副作用的显式裁决；该命令本身不写工具结果或推进执行游标。
     */
    AgentStore.RecordWrite encodeRecoveryCommand(String recordId, RecoveryCommand command);

    /**
     * 严格读取恢复授权命令，后续是否可派发仍由工具 reconciliation 证据决定。
     */
    RecoveryCommand decodeRecoveryCommand(AgentStore.StoredRecord record);

    /**
     * 追加命令消费关联事实，阻止 Session 恢复时重复处理同一授权。
     */
    AgentStore.RecordWrite encodeRecoveryCommandConsumption(String recordId,
                                                            RecoveryCommandConsumption consumption);

    /**
     * 恢复消费关联事实，不将消费本身解释为 Applied 或 NotApplied。
     */
    RecoveryCommandConsumption decodeRecoveryCommandConsumption(AgentStore.StoredRecord record);
}

