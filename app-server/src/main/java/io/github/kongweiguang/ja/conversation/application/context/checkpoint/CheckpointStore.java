// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.checkpoint;

import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

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
     * 读取 Thread revision 与可选 Checkpoint 的同一一致性快照，供慢摘要前冻结源事实。
     */
    Snapshot read(String threadId);

    /**
     * 原子提交 Checkpoint、推进 Thread revision 并返回确定性事件身份，禁止半可见压缩事实。
     */
    CommittedCheckpoint commit(CommitRequest request);

    /**
     * 读取或创建当前稳定投影阶段；默认实现仅服务纯内存测试，生产 Adapter 必须覆盖为持久化读取，
     * 否则进程重启会丢失选择，不能作为真实运行时的降级路径。
     */
    default ProjectionSnapshot prepareProjection(ProjectionRequest request) {
        return ProjectionSnapshot.ephemeral(request, ProjectionReason.INITIAL);
    }

    /**
     * 为摘要提交或唯一 overflow 重试建立显式新阶段；默认只保持单测可运行，生产实现必须按
     * Thread/source/binding 幂等落库，防止强杀后同一阶段重复分配。
     */
    default ProjectionSnapshot advanceProjection(ProjectionRequest request, ProjectionReason reason) {
        return ProjectionSnapshot.ephemeral(request, reason);
    }

    /**
     * 在任何 Provider IO 前写入首次选择并回读获胜映射；默认实现绝不用于生产，避免让测试 stub
     * 迫使所有纯策略用例引入 SQLite 依赖。
     */
    default ProjectionSnapshot persistProjection(ProjectionSnapshot snapshot,
                                                  Map<String, ContextPolicy.ToolProjection> selections) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.withSelections(selections);
    }

    /** 投影阶段只能因首次请求、摘要、模型/Tool binding 或唯一 overflow 恢复而变化。 */
    enum ProjectionReason {
        /** 首次向模型发送上下文时建立基线阶段，后续普通追加不得替换它。 */
        INITIAL,
        /** 已提交摘要改变历史边界，必须在新阶段重新选择投影。 */
        CHECKPOINT,
        /** 模型绑定变化可能改变可用上下文窗口，不能复用旧阶段的预算结论。 */
        MODEL_BINDING,
        /** Tool schema 变化会改变请求信封，需隔离此前冻结的选择。 */
        TOOL_BINDING,
        /** 单次溢出恢复建立唯一替代阶段，避免每次重试持续改写旧前缀。 */
        OVERFLOW
    }

    /** 当前发送环境的非敏感绑定摘要；原始 Profile、Tool schema 和提示正文不进入投影表。 */
    record ProjectionBinding(String modelBinding, String toolBinding) {
        /** 只接受内容寻址摘要，避免阶段表成为任意配置或 Tool 正文的持久化通道。 */
        public ProjectionBinding {
            if (!sha256(modelBinding) || !sha256(toolBinding)) {
                throw new IllegalArgumentException("invalid projection binding");
            }
        }

        /** 兼容纯策略构造器，不可用于生产请求绑定。 */
        public static ProjectionBinding unbound() {
            return new ProjectionBinding("0".repeat(64), "0".repeat(64));
        }
    }

    /** 一个阶段及其已冻结的 Tool 输出选择；entries 只记录已进入模型请求的结果。 */
    record ProjectionSnapshot(String stageId, String threadId, long stageNumber, long sourceRevision,
                              ProjectionReason reason, ProjectionBinding binding,
                              Map<String, ContextPolicy.ToolProjection> selections) {
        /** 约束阶段身份与映射，拒绝不透明 ID、负版本或可变 Map 进入持久化边界。 */
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

        /** 纯实现使用短生命周期身份，生产 Adapter 不得返回该方法生成的阶段。 */
        static ProjectionSnapshot ephemeral(ProjectionRequest request, ProjectionReason reason) {
            Objects.requireNonNull(request, "request");
            return new ProjectionSnapshot("projection_" + UUID.randomUUID(), request.threadId(), 1,
                    request.sourceRevision(), reason, request.binding(), Map.of());
        }

        /** 返回复制后的不可变选择，保持记录阶段本身和 source/binding 不变。 */
        ProjectionSnapshot withSelections(Map<String, ContextPolicy.ToolProjection> values) {
            return new ProjectionSnapshot(stageId, threadId, stageNumber, sourceRevision, reason, binding, values);
        }
    }

    /** 进入存储前冻结阶段归属和时间；时间由服务端时钟提供，客户端不能回填。 */
    record ProjectionRequest(String threadId, long sourceRevision, ProjectionBinding binding, Instant occurredAt) {
        /** 防御 Thread/source/binding 失配，防止普通上下文规划写到另一个会话。 */
        public ProjectionRequest {
            if (threadId == null || threadId.isBlank() || sourceRevision < 0) {
                throw new IllegalArgumentException("invalid projection request");
            }
            binding = Objects.requireNonNull(binding, "binding");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** 统一校验 SHA-256 小写表达，供绑定字段保持与其它内容寻址记录一致。 */
    private static boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * 将预期 revision 与其摘要源绑定，防止适配器把 Checkpoint 写入错误 Thread 或源版本。
     */
    record CommitRequest(String threadId, long expectedThreadRevision, ContextCheckpoint checkpoint,
                         Optional<TurnOperation> turnOperation) {
        /** 手动压缩保持 Thread-only 提交，不伪造 Turn Operation。 */
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
     */
    record TurnOperation(String turnId, long expectedTurnMutationVersion,
                         TurnExecutionState.Ready completedExecution) {
        /** Turn 身份、CAS token 与完成状态必须完整，Adapter 不从历史推断。 */
        public TurnOperation {
            if (turnId == null || !turnId.startsWith("turn_") || expectedTurnMutationVersion < 0) {
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
     */
    record CommittedCheckpoint(ContextCheckpoint checkpoint, long threadRevision, String eventId,
                               boolean newlyCommitted, Long turnMutationVersion) {
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

        /** 自动 Turn 压缩同时返回推进后的 mutation version。 */
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
     * 冻结一次压缩规划使用的 Thread revision 与最近 Checkpoint，二者必须来自同一读视图。
     */
    record Snapshot(String threadId, long threadRevision, Optional<ContextCheckpoint> checkpoint) {
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
     */
    record ContextCheckpoint(
            String checkpointId,
            String threadId,
            long throughOrdinal,
            long retainedFromOrdinal,
            long sourceRevision,
            Optional<ContextPolicy.RetainedSplit> retainedSplit,
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
