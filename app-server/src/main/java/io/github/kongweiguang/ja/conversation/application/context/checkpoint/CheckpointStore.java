// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.checkpoint;

import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
     * 将预期 revision 与其摘要源绑定，防止适配器把 Checkpoint 写入错误 Thread 或源版本。
     */
    record CommitRequest(String threadId, long expectedThreadRevision, ContextCheckpoint checkpoint) {
        /**
         * 在进入事务前校验 Thread 和源 revision 一致，缩小持久层需要防御的状态空间。
         */
        public CommitRequest {
            if (threadId == null || threadId.isBlank() || expectedThreadRevision < 0) {
                throw new IllegalArgumentException("invalid checkpoint commit request");
            }
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            if (!threadId.equals(checkpoint.threadId())
                || checkpoint.sourceRevision() != expectedThreadRevision) {
                throw new IllegalArgumentException("checkpoint commit source mismatch");
            }
        }
    }

    /**
     * 表示 CAS 获胜者或幂等复用结果，并携带提交后的权威 Thread revision 与事件身份。
     */
    record CommittedCheckpoint(ContextCheckpoint checkpoint, long threadRevision, String eventId,
                               boolean newlyCommitted) {
        /**
         * 校验回执拥有合法 revision 和稳定事件 ID，避免发布不可关联的压缩通知。
         */
        public CommittedCheckpoint {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            if (threadRevision < 0) {
                throw new IllegalArgumentException("threadRevision must be non-negative");
            }
            eventId = requiredEventId(eventId);
        }

        /**
         * 为首次插入构造回执；事件 ID 从 Checkpoint 身份确定性派生以支持幂等发布。
         */
        public static CommittedCheckpoint created(ContextCheckpoint checkpoint, long threadRevision) {
            return new CommittedCheckpoint(checkpoint, threadRevision, eventIdFor(checkpoint), true);
        }

        /**
         * 为并发获胜记录构造复用回执，明确禁止调用方再次发布压缩事件。
         */
        public static CommittedCheckpoint reused(ContextCheckpoint checkpoint, long threadRevision) {
            return new CommittedCheckpoint(checkpoint, threadRevision, eventIdFor(checkpoint), false);
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
