// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.RetainedSplit;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义摘要 Checkpoint 的唯一持久化端口，并以 Thread revision 作为并发写入仲裁依据。
 */
public interface CheckpointStore {
    /**
     * 统一校验 SHA-256 小写表达，供绑定字段保持与其它内容寻址记录一致。
     */
    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * 从 Checkpoint 身份确定性派生事件 ID；超界或非常规身份退化为名称 UUID。
     */
    static String eventIdFor(ContextCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String suffix = checkpoint.checkpointId().startsWith("checkpoint_")
                ? checkpoint.checkpointId().substring("checkpoint_".length())
                : checkpoint.checkpointId();
        String candidate = "evt_context_" + suffix;
        if (candidate.length() <= 108 && suffix.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            return candidate;
        }
        return "evt_context_" + UUID.nameUUIDFromBytes(
                checkpoint.checkpointId().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按 JA-RPC 事件词汇校验回执身份，阻断换行和超长标识进入通知链。
     */
    private static String requiredEventId(String eventId) {
        if (eventId == null || !eventId.startsWith("evt_") || eventId.length() > 108
            || !eventId.substring("evt_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid checkpoint event id");
        }
        return eventId;
    }

    /**
     * 读取 Thread revision 与可选 Checkpoint 的同一一致性快照，供慢摘要前冻结源事实。
     */
    Snapshot read(String threadId);

    /**
     * 原子提交 Checkpoint、推进 Thread revision 并返回确定性事件身份，禁止半可见压缩事实。
     */
    CommittedCheckpoint commit(CommitRequest request);

    /**
     * 读取或创建当前稳定投影阶段；每个实现必须明确提供持久化语义，禁止隐式内存替代。
     */
    ProjectionSnapshot prepareProjection(ProjectionRequest request);

    /**
     * 为摘要提交或 overflow 恢复建立显式阶段，按 source/binding 幂等保存，防止重启后重新分配。
     */
    ProjectionSnapshot advanceProjection(ProjectionRequest request, ProjectionReason reason);

    /**
     * 在任何 Provider IO 前持久化首次选择并回读获胜映射，失败必须阻止该请求派发。
     */
    ProjectionSnapshot persistProjection(ProjectionSnapshot snapshot,
                                         Map<String, ToolProjection> selections);

    /**
     * 投影阶段只能因首次请求、摘要、模型/Tool binding 或唯一 overflow 恢复而变化。
     */
    enum ProjectionReason {
        /**
         * 首次向模型发送上下文时建立基线阶段，后续普通追加不得替换它。
         */
        INITIAL,
        /**
         * 已提交摘要改变历史边界，必须在新阶段重新选择投影。
         */
        CHECKPOINT,
        /**
         * 模型绑定变化可能改变可用上下文窗口，不能复用旧阶段的预算结论。
         */
        MODEL_BINDING,
        /**
         * Tool schema 变化会改变请求信封，需隔离此前冻结的选择。
         */
        TOOL_BINDING,
        /**
         * 单次溢出恢复建立唯一替代阶段，避免每次重试持续改写旧前缀。
         */
        OVERFLOW
    }

    /**
     * 当前发送环境的非敏感绑定摘要；原始 Profile、Tool schema 和提示正文不进入投影表。
     *
     * @param modelBinding 模型绑定
     * @param toolBinding 工具绑定
     */
    record ProjectionBinding(String modelBinding,
                             String toolBinding) {
        /**
         * 只接受内容寻址摘要，避免阶段表成为任意配置或 Tool 正文的持久化通道。
         */
        public ProjectionBinding {
            if (!sha256(modelBinding) || !sha256(toolBinding)) {
                throw new IllegalArgumentException("invalid projection binding");
            }
        }

        /**
         * 标记尚未冻结模型和工具选择的阶段；持久投影入口会拒绝该绑定。
         */
        public static ProjectionBinding unbound() {
            return new ProjectionBinding("0".repeat(64), "0".repeat(64));
        }
    }

    /**
     * 一个阶段及其已冻结的 Tool 输出选择；entries 只记录已进入模型请求的结果。
     *
     * @param stageId 阶段标识
     * @param threadId 所属对话的稳定标识
     * @param stageNumber 阶段编号
     * @param sourceRevision 来源修订版本
     * @param reason 原因说明
     * @param binding 绑定
     * @param selections 选择列表
     */
    record ProjectionSnapshot(String stageId,
                              String threadId,
                              long stageNumber,
                              long sourceRevision,
                              ProjectionReason reason,
                              ProjectionBinding binding,
                              Map<String, ToolProjection> selections) {
        /**
         * 约束阶段身份与映射，拒绝不透明 ID、负版本或可变 Map 进入持久化边界。
         */
        public ProjectionSnapshot {
            if (stageId == null || !stageId.startsWith("projection_") || stageId.length() > 128
                || threadId == null || threadId.isBlank() || stageNumber < 1 || sourceRevision < 0) {
                throw new IllegalArgumentException("invalid projection snapshot");
            }
            reason = Objects.requireNonNull(reason, "reason");
            binding = Objects.requireNonNull(binding, "binding");
            selections = Map.copyOf(Objects.requireNonNull(selections, "selections"));
            if (selections.keySet().stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 256)) {
                throw new IllegalArgumentException("invalid projection selections");
            }
        }

        /**
         * 返回复制后的不可变选择，保持记录阶段本身和 source/binding 不变。
         */
        public ProjectionSnapshot withSelections(Map<String, ToolProjection> values) {
            return new ProjectionSnapshot(stageId, threadId, stageNumber, sourceRevision, reason, binding, values);
        }
    }

    /**
     * 进入存储前冻结阶段归属和时间；时间由服务端时钟提供，客户端不能回填。
     *
     * @param threadId 所属对话的稳定标识
     * @param sourceRevision 来源修订版本
     * @param binding 绑定
     * @param occurredAt 事件发生时间
     */
    record ProjectionRequest(String threadId,
                             long sourceRevision,
                             ProjectionBinding binding,
                             Instant occurredAt) {
        /**
         * 防御 Thread/source/binding 失配，防止普通上下文规划写到另一个会话。
         */
        public ProjectionRequest {
            if (threadId == null || threadId.isBlank() || sourceRevision < 0) {
                throw new IllegalArgumentException("invalid projection request");
            }
            binding = Objects.requireNonNull(binding, "binding");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 将预期 revision 与其摘要源绑定，防止适配器把 Checkpoint 写入错误 Thread 或源版本。
     *
     * @param threadId 所属对话的稳定标识
     * @param expectedThreadRevision 预期对话修订版本
     * @param checkpoint 检查点
     * @param turnOperation 执行轮次操作（可选）
     */
    record CommitRequest(String threadId,
                         long expectedThreadRevision,
                         ContextCheckpoint checkpoint,
                         Optional<TurnOperation> turnOperation) {
        /**
         * 手动压缩保持 Thread-only 提交，不伪造 Turn Operation。
         */
        public CommitRequest(String threadId, long expectedThreadRevision, ContextCheckpoint checkpoint) {
            this(threadId, expectedThreadRevision, checkpoint, Optional.empty());
        }

        /**
         * 在进入事务前校验 Thread 和源 revision 一致，缩小持久层需要防御的状态空间。
         */
        public CommitRequest {
            if (threadId == null || threadId.isBlank() || expectedThreadRevision < 0) {
                throw new IllegalArgumentException("invalid checkpoint commit request");
            }
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            turnOperation = Objects.requireNonNull(turnOperation, "turnOperation");
            if (!threadId.equals(checkpoint.threadId())
                || checkpoint.sourceRevision() != expectedThreadRevision) {
                throw new IllegalArgumentException("checkpoint commit source mismatch");
            }
        }
    }

    /**
     * 自动压缩把最终 READY(ASSISTANT) 与 checkpoint 放进同一事务，避免崩溃留下已提交摘要但旧游标。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param expectedTurnMutationVersion 提交时预期的执行状态变更版本
     * @param completedExecution 已完成的执行记录
     */
    record TurnOperation(String turnId,
                         long expectedTurnMutationVersion,
                         TurnExecutionState.Ready completedExecution) {
        /**
         * 不要求 Ja 的 turn_ 命名；调用方传入 execution identity，Adapter 不从历史猜身份。
         */
        public TurnOperation {
            turnId = ContractChecks.identifier(turnId, "turnId");
            if (expectedTurnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid checkpoint Turn Operation");
            }
            Objects.requireNonNull(completedExecution, "completedExecution");
            if (completedExecution.next() != TurnExecutionState.Next.ASSISTANT) {
                throw new IllegalArgumentException("checkpoint must complete into READY(ASSISTANT)");
            }
        }
    }

    /**
     * 表示 CAS 获胜者或幂等复用结果，并携带提交后的权威 Thread revision 与事件身份。
     *
     * @param checkpoint 检查点
     * @param threadRevision 对话修订版本
     * @param eventId 事件标识
     * @param newlyCommitted 是否为本次新提交的结果
     * @param turnMutationVersion 当前执行的状态变更版本
     */
    record CommittedCheckpoint(ContextCheckpoint checkpoint,
                               long threadRevision,
                               String eventId,
                               boolean newlyCommitted,
                               Long turnMutationVersion) {
        /**
         * 校验回执拥有合法 revision 和稳定事件 ID，避免发布不可关联的压缩通知。
         */
        public CommittedCheckpoint {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            if (threadRevision < 0) {
                throw new IllegalArgumentException("threadRevision must be non-negative");
            }
            if (turnMutationVersion != null && turnMutationVersion < 0) {
                throw new IllegalArgumentException("turnMutationVersion must be non-negative");
            }
            eventId = requiredEventId(eventId);
        }

        /**
         * 为首次插入构造回执；事件 ID 从 Checkpoint 身份确定性派生以支持幂等发布。
         */
        public static CommittedCheckpoint created(ContextCheckpoint checkpoint, long threadRevision) {
            return new CommittedCheckpoint(checkpoint, threadRevision, eventIdFor(checkpoint), true, null);
        }

        /**
         * 自动 Turn 压缩同时返回推进后的 mutation version。
         */
        public static CommittedCheckpoint created(ContextCheckpoint checkpoint, long threadRevision,
                                                  long turnMutationVersion) {
            return new CommittedCheckpoint(checkpoint, threadRevision, eventIdFor(checkpoint), true,
                    turnMutationVersion);
        }

        /**
         * 为并发获胜记录构造复用回执，明确禁止调用方再次发布压缩事件。
         */
        public static CommittedCheckpoint reused(ContextCheckpoint checkpoint, long threadRevision) {
            return new CommittedCheckpoint(checkpoint, threadRevision, eventIdFor(checkpoint), false, null);
        }

        /**
         * 使用 Checkpoint ID 作为持久回执身份，使恢复流程能关联原始压缩提交。
         */
        public String receiptId() {
            return checkpoint.checkpointId();
        }

        /**
         * 暴露面向调用方的首次提交语义，避免泄露存储层字段命名。
         */
        public boolean isNew() {
            return newlyCommitted;
        }
    }

    /**
     * 表示慢摘要期间源 revision 被并发推进，调用方必须重新读取而不能覆盖获胜者。
     */
    final class CommitConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * 保留持久层诊断文本，同时由应用服务翻译为稳定的 CAS 冲突分类。
         */
        public CommitConflict(String message) {
            super(message);
        }
    }

    /**
     * 冻结一次压缩规划使用的 Thread revision 与最近 Checkpoint，二者必须来自同一读视图。
     *
     * @param threadId 所属对话的稳定标识
     * @param threadRevision 对话修订版本
     * @param checkpoint 检查点（可选）
     */
    record Snapshot(String threadId,
                    long threadRevision,
                    Optional<ContextCheckpoint> checkpoint) {
        /**
         * 保证可选 Checkpoint 属于当前 Thread 且不来自未来 revision。
         */
        public Snapshot {
            if (threadId == null || threadId.isBlank() || threadRevision < 0) {
                throw new IllegalArgumentException("invalid checkpoint snapshot");
            }
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            checkpoint.ifPresent(value -> {
                if (!threadId.equals(value.threadId()) || value.sourceRevision() > threadRevision) {
                    throw new IllegalArgumentException("checkpoint snapshot source mismatch");
                }
            });
        }

        /**
         * 表达尚无压缩历史的有效快照，避免用空对象或伪造 Checkpoint 占位。
         */
        public static Snapshot empty(String threadId, long threadRevision) {
            return new Snapshot(threadId, threadRevision, Optional.empty());
        }
    }

    /**
     * 持久化累积摘要、保留边界和 split-turn 证据，用于进程恢复后确定性重建提示。
     *
     * @param checkpointId 检查点标识
     * @param threadId 所属对话的稳定标识
     * @param throughOrdinal 覆盖到的顺序位置
     * @param retainedFromOrdinal 保留区间的起始顺序位置
     * @param sourceRevision 来源修订版本
     * @param retainedSplit 保留消息的拆分边界（可选）
     * @param summary 摘要
     * @param estimatedTokens 估算的 Token 数
     * @param envelopeFingerprint 协议封装指纹
     * @param strategyVersion 策略版本
     * @param usage 用量
     * @param createdAt 创建时间
     */
    record ContextCheckpoint(String checkpointId,
                             String threadId,
                             long throughOrdinal,
                             long retainedFromOrdinal,
                             long sourceRevision,
                             Optional<RetainedSplit> retainedSplit,
                             SummaryDocument summary,
                             int estimatedTokens,
                             String envelopeFingerprint,
                             String strategyVersion,
                             CheckpointUsage usage,
                             Instant createdAt) {
        /**
         * 校验 ordinal、源 revision 与切分后缀边界一致，防止恢复时重复或遗漏历史事实。
         */
        public ContextCheckpoint {
            checkpointId = required(checkpointId, "checkpointId");
            threadId = required(threadId, "threadId");
            if (throughOrdinal < 0 || retainedFromOrdinal < 0 || sourceRevision < 0
                || estimatedTokens < 0 || retainedFromOrdinal < throughOrdinal) {
                throw new IllegalArgumentException("invalid context checkpoint boundary");
            }
            retainedSplit = Objects.requireNonNull(retainedSplit, "retainedSplit");
            retainedSplit.ifPresent(split -> {
                if (split.retainedMessage().ordinal() != throughOrdinal
                    || retainedFromOrdinal != throughOrdinal) {
                    throw new IllegalArgumentException("retained split does not match checkpoint boundary");
                }
            });
            summary = Objects.requireNonNull(summary, "summary");
            if (envelopeFingerprint == null || !envelopeFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid checkpoint envelope fingerprint");
            }
            strategyVersion = required(strategyVersion, "strategyVersion");
            usage = Objects.requireNonNull(usage, "usage");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }

        /**
         * 限制持久化身份和策略版本为单行有界文本，避免控制字符污染存储与日志。
         */
        private static String required(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256
                || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        }
    }
}
