// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.domain;

import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Task bounded context 的不可变值闭集；领域端口不暴露 Jackson、MyBatis 或 SQLite 行形状。
 */
public final class TaskModels {
    /** Task 的产品身份决定生命周期所有权，不能由运行状态或 Thread 深度反推。 */
    public enum Kind {
        /** 独立侧边任务由用户直接管理，父 Turn 结束后仍可继续运行。 */
        SIDE_TASK,
        /** Subagent 受创建它的父任务约束，父链取消时必须同步收敛。 */
        SUBAGENT
    }

    /** 生命周期明确父任务终态是否传播，避免调度层按 Kind 重复猜测。 */
    public enum Lifecycle {
        /** 独立生命周期保留用户显式控制权，不随父 Turn 自动终止。 */
        INDEPENDENT,
        /** 附属生命周期继承父链取消与树删除边界。 */
        ATTACHED
    }

    /** 上下文继承模式冻结创建时可见范围，恢复时不得重新读取父 Thread 漂移后的状态。 */
    public enum InheritanceMode {
        /** 冻结父 Turn 的有效上下文、引用和权限上限，供 Side Task 延续工作。 */
        EFFECTIVE_CONTEXT,
        /** 仅保留任务简报，Subagent 不隐式继承父会话历史。 */
        BRIEF_ONLY
    }

    /** Task 投影的稳定状态闭集；终态不可逆，暂停态只能通过显式恢复离开。 */
    public enum State {
        /** 创建事实已提交但尚未取得执行所有权。 */
        QUEUED,
        /** Child Turn 已被运行时接纳并正在推进。 */
        RUNNING,
        /** 执行等待用户审批，保持原 Turn 与资源所有权事实。 */
        WAITING_APPROVAL,
        /** 进程恢复或运行条件中断后停止自动推进，必须显式恢复。 */
        SUSPENDED,
        /** 已成功产生终态事实，后续重试只能幂等回读。 */
        COMPLETED,
        /** 已失败关闭且保留安全摘要，不允许隐式重新执行。 */
        FAILED,
        /** 已提交取消终态，父链传播和用户取消共享该不可逆结果。 */
        CANCELLED
    }

    /** Activity 描述投影变化的低频事实，供父 Timeline 和任务详情共享同一审计来源。 */
    public enum ActivityKind {
        /** Child Task 与首次 Turn 已原子创建。 */
        DISPATCHED,
        /** 普通 Mailbox 消息已进入目标任务队列。 */
        MESSAGE_SENT,
        /** Follow-up 消息和目标 Child Turn 已原子接纳。 */
        FOLLOW_UP_QUEUED,
        /** 运行中安全摘要已更新，但不代表状态发生迁移。 */
        PROGRESS,
        /** Child Turn 已进入审批等待边界。 */
        WAITING_APPROVAL,
        /** 用户显式恢复了此前暂停的执行。 */
        RESUMED,
        /** 任务成功终结并可能向父 Mailbox 发送最终答复。 */
        COMPLETED,
        /** 任务失败关闭，Activity 只携带可公开的安全摘要。 */
        FAILED,
        /** 取消意图赢得终态竞争。 */
        CANCELLED,
        /** 启动恢复将非终态 Child 收敛到需人工恢复的状态。 */
        SUSPENDED
    }

    /** Mailbox 类型区分普通通信、启动新 Turn 的输入与不可丢的终态答复。 */
    public enum MailboxKind {
        /** 普通消息只在目标任务下一安全点注入，不创建新 Turn。 */
        MESSAGE,
        /** Follow-up 在目标任务空闲时创建具体 Turn，并与消息原子绑定。 */
        FOLLOW_UP,
        /** Child 终态向父 Thread 回传的答复不受普通容量淘汰。 */
        FINAL_ANSWER
    }

    /** Mailbox 状态冻结 exactly-once 消费边界，绑定和消费都必须与 USER fact 同事务推进。 */
    public enum MailboxState {
        /** 尚未被具体 Turn 取得的 FIFO 消息。 */
        PENDING,
        /** 已由具体 Turn 预占，但尚未提交对应 USER fact。 */
        BOUND,
        /** 对应 USER fact 已提交，消息不可再次投递。 */
        CONSUMED,
        /** 在消费前被显式取消或随终态释放，不再参与 FIFO。 */
        CANCELLED
    }

    /** 创建前的上下文种子不接受调用方提供 fingerprint，避免不同层各自定义摘要签名。 */
    public record ContextSeedDraft(String contextSeedId, String parentThreadId, String parentTurnId,
                                   long parentRevision, InheritanceMode inheritanceMode,
                                   UserContent taskBrief, JsonObject effectiveContext,
                                   JsonArray references, JsonObject permissionCeiling, Instant createdAt) {
        /** EFFECTIVE_CONTEXT 与 BRIEF_ONLY 的空值规则在进入持久层前即固定。 */
        public ContextSeedDraft {
            contextSeedId = identifier(contextSeedId, "seed_", "contextSeedId");
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            parentTurnId = optionalIdentifier(parentTurnId, "turn_", "parentTurnId");
            if (parentRevision < 0) throw new IllegalArgumentException("invalid parentRevision");
            Objects.requireNonNull(inheritanceMode, "inheritanceMode");
            Objects.requireNonNull(taskBrief, "taskBrief");
            if ((inheritanceMode == InheritanceMode.EFFECTIVE_CONTEXT) != (effectiveContext != null)) {
                throw new IllegalArgumentException("inheritance mode does not match effective context");
            }
            Objects.requireNonNull(references, "references");
            Objects.requireNonNull(permissionCeiling, "permissionCeiling");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** 已落库种子包含唯一 canonical fingerprint，供恢复和界面核对冻结边界。 */
    public record ContextSeed(String contextSeedId, String parentThreadId, String parentTurnId,
                              long parentRevision, InheritanceMode inheritanceMode,
                              UserContent taskBrief, JsonObject effectiveContext,
                              JsonArray references, JsonObject permissionCeiling,
                              String fingerprint, Instant createdAt) {
        /** 从存储恢复的 fingerprint 必须保持固定小写 SHA-256 形状。 */
        public ContextSeed {
            new ContextSeedDraft(contextSeedId, parentThreadId, parentTurnId, parentRevision,
                    inheritanceMode, taskBrief, effectiveContext, references, permissionCeiling, createdAt);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid context fingerprint");
            }
        }
    }

    /**
     * SIDE_TASK 创建前冻结的真实有效上下文；权限对象只给出 SQLite 可证明的 accessMode 上限，
     * Coordinator 再用当前 Turn 已冻结的 Tool/Skill/MCP 能力做交集，不能据此提权。
     */
    public record EffectiveContextSnapshot(String parentThreadId, long parentRevision,
                                           JsonObject context, JsonArray references,
                                           JsonObject permissionCeiling) {
        /** 快照 revision 与三份 canonical JSON 必须绑定，禁止调用方之后原地补写。 */
        public EffectiveContextSnapshot {
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            if (parentRevision < 0) throw new IllegalArgumentException("invalid parentRevision");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(references, "references");
            Objects.requireNonNull(permissionCeiling, "permissionCeiling");
        }
    }

    /** Child 首次 admission 把 Thread 定义、lineage、冻结 seed 与父活动绑定为一个事务输入。 */
    public record ChildAdmission(ConversationRepository.ThreadDefinition childThread,
                                 String parentThreadId, long expectedParentRevision,
                                 String originTurnId, String taskName, Kind kind, Lifecycle lifecycle,
                                 ContextSeedDraft contextSeed, String activityId,
                                 JsonObject activitySummary) {
        /** Kind 与 lifecycle 只接受产品定义的两个合法组合，防止组合根创建第三种隐式对象。 */
        public ChildAdmission {
            Objects.requireNonNull(childThread, "childThread");
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            if (childThread.threadId().equals(parentThreadId) || expectedParentRevision < 0) {
                throw new IllegalArgumentException("invalid child relationship");
            }
            originTurnId = optionalIdentifier(originTurnId, "turn_", "originTurnId");
            taskName = TaskModels.taskName(taskName);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lifecycle, "lifecycle");
            if ((kind == Kind.SIDE_TASK) != (lifecycle == Lifecycle.INDEPENDENT)) {
                throw new IllegalArgumentException("task kind and lifecycle do not match");
            }
            Objects.requireNonNull(contextSeed, "contextSeed");
            if (!parentThreadId.equals(contextSeed.parentThreadId())
                    || expectedParentRevision != contextSeed.parentRevision()
                    || !Objects.equals(originTurnId, contextSeed.parentTurnId())) {
                throw new IllegalArgumentException("context seed does not match child relationship");
            }
            activityId = identifier(activityId, "activity_", "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
        }
    }

    /** 创建后不可变的 Thread 血缘事实。 */
    public record Lineage(String taskThreadId, String parentThreadId, String rootThreadId,
                          String originTurnId, String taskName, int depth, Kind kind,
                          Lifecycle lifecycle, String contextSeedId, Instant createdAt) {
        /** 数据库行仍需在领域边界复核深度和身份，损坏状态不能透传给调度层。 */
        public Lineage {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            originTurnId = optionalIdentifier(originTurnId, "turn_", "originTurnId");
            taskName = TaskModels.taskName(taskName);
            if (depth < 1 || depth > 4) throw new IllegalArgumentException("invalid task depth");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lifecycle, "lifecycle");
            contextSeedId = identifier(contextSeedId, "seed_", "contextSeedId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** 右栏常量级读取的权威投影，不以扫描完整 Child Timeline 计算状态。 */
    public record Projection(String taskThreadId, String rootThreadId, long revision, State state,
                             long latestActivitySequence, Long lastSeenActivitySequence, int unreadCount,
                             int descendantCount, int runningDescendantCount, int needsAttentionCount,
                             String latestSafeSummary, Instant startedAt, Instant completedAt, Instant updatedAt) {
        /** 终态时间与状态闭集必须一致，计数保持 V1 的树容量上限。 */
        public Projection {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            Objects.requireNonNull(state, "state");
            if (revision < 0 || latestActivitySequence < 1 || unreadCount < 0
                    || descendantCount < 0 || descendantCount > 64
                    || runningDescendantCount < 0 || runningDescendantCount > 64
                    || needsAttentionCount < 0 || needsAttentionCount > 64) {
                throw new IllegalArgumentException("invalid task projection");
            }
            if (lastSeenActivitySequence != null
                    && (lastSeenActivitySequence < 1 || lastSeenActivitySequence > latestActivitySequence)) {
                throw new IllegalArgumentException("invalid seen boundary");
            }
            boolean terminal = state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED;
            if (terminal != (completedAt != null)) throw new IllegalArgumentException("invalid terminal time");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** Task Summary 合并不可变 lineage 与可变 projection，防止调用方观察拆分读。 */
    public record Summary(Lineage lineage, Projection projection) {
        /** 两部分必须描述同一 Child Thread 和根，否则列表会产生错误树归属。 */
        public Summary {
            Objects.requireNonNull(lineage, "lineage");
            Objects.requireNonNull(projection, "projection");
            if (!lineage.taskThreadId().equals(projection.taskThreadId())
                    || !lineage.rootThreadId().equals(projection.rootThreadId())) {
                throw new IllegalArgumentException("task summary identity mismatch");
            }
        }
    }

    /** 低频、追加式活动是父 Timeline 与详情页共同消费的安全事实。 */
    public record Activity(long sequence, String activityId, String rootThreadId, String taskThreadId,
                           String actorThreadId, String causalTurnId, ActivityKind kind,
                           JsonObject summary, Instant createdAt) {
        /** 活动序号只能由 SQLite 分配，调用方不能伪造零值占位。 */
        public Activity {
            if (sequence < 1) throw new IllegalArgumentException("invalid activity sequence");
            activityId = identifier(activityId, "activity_", "activityId");
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            actorThreadId = identifier(actorThreadId, "thr_", "actorThreadId");
            causalTurnId = optionalIdentifier(causalTurnId, "turn_", "causalTurnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** 父 Timeline 的一条持久活动同时携带对应 Task 摘要，避免展示层再发起树扫描或详情读取。 */
    public record ActivityProjection(Activity activity, Summary task) {
        /** Activity 与摘要必须属于同一根和同一 Child，损坏关系不能进入主 Timeline。 */
        public ActivityProjection {
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(task, "task");
            if (!activity.taskThreadId().equals(task.lineage().taskThreadId())
                    || !activity.rootThreadId().equals(task.lineage().rootThreadId())) {
                throw new IllegalArgumentException("task activity projection identity mismatch");
            }
        }
    }

    /** Mailbox 请求的幂等键以发送方为命名空间，QueueOnly 和 Follow-up 共用同一事实形状。 */
    public record MailboxEnvelope(String messageId, String senderThreadId, String targetThreadId,
                                  String causalTurnId, MailboxKind kind, UserContent content,
                                  String idempotencyKey, Instant createdAt) {
        /** 自发消息和控制字符幂等键在进入数据库前失败，避免依赖约束异常分类。 */
        public MailboxEnvelope {
            messageId = identifier(messageId, "msg_", "messageId");
            senderThreadId = identifier(senderThreadId, "thr_", "senderThreadId");
            targetThreadId = identifier(targetThreadId, "thr_", "targetThreadId");
            if (senderThreadId.equals(targetThreadId)) throw new IllegalArgumentException("self message is invalid");
            causalTurnId = optionalIdentifier(causalTurnId, "turn_", "causalTurnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            idempotencyKey = boundedText(idempotencyKey, "idempotencyKey", 128);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** 已持久化 Mailbox 行保留绑定 Turn 和消费时间，供恢复时 exactly-once 判断。 */
    public record MailboxMessage(long sequence, String messageId, String rootThreadId,
                                 String senderThreadId, String targetThreadId, String causalTurnId,
                                 MailboxKind kind, UserContent content, String idempotencyKey,
                                 MailboxState state, String boundTurnId, Instant createdAt,
                                 Instant updatedAt, Instant consumedAt) {
        /** PENDING/BOUND/CONSUMED/CANCELLED 的空值组合必须与 V1 CHECK 保持一致。 */
        public MailboxMessage {
            if (sequence < 1) throw new IllegalArgumentException("invalid mailbox sequence");
            new MailboxEnvelope(messageId, senderThreadId, targetThreadId, causalTurnId, kind,
                    content, idempotencyKey, createdAt);
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            Objects.requireNonNull(state, "state");
            boundTurnId = optionalIdentifier(boundTurnId, "turn_", "boundTurnId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if ((state == MailboxState.BOUND) != (boundTurnId != null && consumedAt == null)
                    || (state == MailboxState.CONSUMED) != (consumedAt != null)) {
                throw new IllegalArgumentException("invalid mailbox state facts");
            }
        }
    }

    /** QueueOnly 事务回执同时携带实际 Activity 投影 owner，避免 root 目标被误当作 Child Task。 */
    public record MessageEnqueueReceipt(MailboxMessage mailbox, Summary projectionOwner,
                                        boolean inserted) {
        /** Mailbox 与投影必须属于同一根；是否首次插入由事务 winner 决定而非应用层猜测。 */
        public MessageEnqueueReceipt {
            Objects.requireNonNull(mailbox, "mailbox");
            Objects.requireNonNull(projectionOwner, "projectionOwner");
            if (!mailbox.rootThreadId().equals(projectionOwner.lineage().rootThreadId())) {
                throw new IllegalArgumentException("mailbox projection owner root mismatch");
            }
        }
    }

    /** 已提交父取消事实但仍有 ATTACHED 直接子 Turn 未终结时形成的可恢复传播欠账。 */
    public record CancellationPropagation(String parentThreadId, String parentTurnId) {
        /** 欠账只保存稳定因果身份；具体后代每次处理时从 SQLite 权威 lineage 重读。 */
        public CancellationPropagation {
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            parentTurnId = identifier(parentTurnId, "turn_", "parentTurnId");
        }
    }

    /** Follow-up 把 Mailbox、目标 Child 新 Turn 和活动投影绑定为一次原子接纳。 */
    public record FollowUpAdmission(MailboxEnvelope mailbox,
                                    ConversationRepository.TurnAdmission turn,
                                    long expectedTaskRevision, String activityId,
                                    JsonObject activitySummary) {
        /** 只有 FOLLOW_UP 可以创建 Turn，且 Turn 必须属于消息目标 Thread。 */
        public FollowUpAdmission {
            Objects.requireNonNull(mailbox, "mailbox");
            if (mailbox.kind() != MailboxKind.FOLLOW_UP) throw new IllegalArgumentException("not a follow-up");
            Objects.requireNonNull(turn, "turn");
            if (!mailbox.targetThreadId().equals(turn.threadId()) || expectedTaskRevision < 0) {
                throw new IllegalArgumentException("follow-up target mismatch");
            }
            activityId = identifier(activityId, "activity_", "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
        }
    }

    /** Follow-up 原子事务同时返回实际 Turn 与 Mailbox 行，幂等重放不得使用本次草稿身份。 */
    public record FollowUpAdmissionReceipt(ConversationRepository.AdmissionReceipt admission,
                                           MailboxMessage mailbox) {
        /** Mailbox 必须已绑定到回执 Turn，防止上层调度一个未持久化的 phantom Turn。 */
        public FollowUpAdmissionReceipt {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(mailbox, "mailbox");
            if (mailbox.kind() != MailboxKind.FOLLOW_UP || mailbox.state() != MailboxState.BOUND
                    || !admission.turnId().equals(mailbox.boundTurnId())) {
                throw new IllegalArgumentException("follow-up receipt identity mismatch");
            }
        }
    }

    /** 可变 Task 状态与活动通过单一 CAS 提交，供审批、恢复和取消共享。 */
    public record ActivityMutation(String taskThreadId, long expectedTaskRevision, State state,
                                   String activityId, String actorThreadId, String causalTurnId,
                                   ActivityKind kind, JsonObject summary, String latestSafeSummary,
                                   Instant occurredAt) {
        /** 状态投影和 Activity 必须使用同一时间，避免排序与 revision 对外不一致。 */
        public ActivityMutation {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            if (expectedTaskRevision < 0) throw new IllegalArgumentException("invalid task revision");
            Objects.requireNonNull(state, "state");
            activityId = identifier(activityId, "activity_", "activityId");
            actorThreadId = identifier(actorThreadId, "thr_", "actorThreadId");
            causalTurnId = optionalIdentifier(causalTurnId, "turn_", "causalTurnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** 终态扩展只包含 Task 侧事实，Conversation terminal winner 在同一事务内调用它。 */
    public record TerminalSettlement(String taskThreadId, String turnId, State state,
                                     String activityId, JsonObject activitySummary,
                                     String latestSafeSummary, MailboxEnvelope finalAnswer,
                                     Instant occurredAt) {
        /** 仅三个终态可进入扩展，FINAL_ANSWER 的发送方和因果 Turn 必须与 Child 对齐。 */
        public TerminalSettlement {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            turnId = identifier(turnId, "turn_", "turnId");
            if (state != State.COMPLETED && state != State.FAILED && state != State.CANCELLED) {
                throw new IllegalArgumentException("terminal task state is required");
            }
            activityId = identifier(activityId, "activity_", "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
            if (finalAnswer != null && (finalAnswer.kind() != MailboxKind.FINAL_ANSWER
                    || !taskThreadId.equals(finalAnswer.senderThreadId())
                    || !turnId.equals(finalAnswer.causalTurnId()))) {
                throw new IllegalArgumentException("invalid final answer envelope");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** Task 详情分页把三类低频事实绑定到同一个投影 revision。 */
    public record Detail(Summary task, ContextSeed contextSeed, List<Activity> activities,
                         List<MailboxMessage> mailbox) {
        /** 集合在离开 SqlSession 前冻结，禁止延迟加载逃逸事务快照。 */
        public Detail {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(contextSeed, "contextSeed");
            activities = List.copyOf(Objects.requireNonNull(activities, "activities"));
            mailbox = List.copyOf(Objects.requireNonNull(mailbox, "mailbox"));
        }
    }

    /** 所有外部 ID 复用相同的可打印字符边界，但保留各自前缀以阻止类型混用。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 可空关联只有 null 表示缺失，空串不作为兼容占位。 */
    private static String optionalIdentifier(String value, String prefix, String field) {
        return value == null ? null : identifier(value, prefix, field);
    }

    /** 任务名是用户可见路径片段，禁止控制字符破坏日志、树行或协议帧。 */
    private static String taskName(String value) {
        String normalized = boundedText(value, "taskName", 96).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("invalid taskName");
        return normalized;
    }

    /** 受限文本保持原值，不做会改变幂等签名的 Unicode 归一化。 */
    private static String boundedText(String value, String field, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 纯类型容器禁止实例化，避免被 Solon 误识别为服务。 */
    private TaskModels() { }
}
