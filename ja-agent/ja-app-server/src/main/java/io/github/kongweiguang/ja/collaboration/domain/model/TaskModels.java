// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Task bounded context 的不可变值闭集；领域端口不暴露 Jackson、MyBatis 或 SQLite 行形状。
 */
public final class TaskModels {
    /**
     * 纯类型容器禁止实例化，避免被 Solon 误识别为服务。
     */
    private TaskModels() {
    }

    /**
     * 所有外部 ID 复用相同的可打印字符边界，但保留各自前缀以阻止类型混用。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 可空关联只有 null 表示缺失，空串不作为兼容占位。
     */
    private static String optionalIdentifier(String value, String prefix, String field) {
        return value == null ? null : identifier(value, prefix, field);
    }

    /**
     * 任务名是用户可见路径片段，禁止控制字符破坏日志、树行或协议帧。
     */
    private static String taskName(String value) {
        String normalized = boundedText(value, "taskName", 96).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("invalid taskName");
        }
        return normalized;
    }

    /**
     * 受限文本保持原值，不做会改变幂等签名的 Unicode 归一化。
     */
    private static String boundedText(String value, String field, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * Task 的产品身份决定生命周期所有权，不能由运行状态或 Thread 深度反推。
     */
    public enum Kind {
        /**
         * 独立侧边任务由用户直接管理，父 Turn 结束后仍可继续运行。
         */
        SIDE_TASK,
        /**
         * Subagent 受创建它的父任务约束，父链取消时必须同步收敛。
         */
        SUBAGENT
    }

    /**
     * 生命周期明确父任务终态是否传播，避免调度层按 Kind 重复猜测。
     */
    public enum Lifecycle {
        /**
         * 独立生命周期保留用户显式控制权，不随父 Turn 自动终止。
         */
        INDEPENDENT,
        /**
         * 附属生命周期继承父链取消与树删除边界。
         */
        ATTACHED
    }

    /**
     * 上下文继承模式冻结创建时可见范围，恢复时不得重新读取父 Thread 漂移后的状态。
     */
    public enum InheritanceMode {
        /**
         * 冻结父 Turn 的有效上下文、引用和权限上限，供 Side Task 延续工作。
         */
        EFFECTIVE_CONTEXT,
        /**
         * 仅保留任务简报，Subagent 不隐式继承父会话历史。
         */
        BRIEF_ONLY
    }

    /**
     * Task 投影的稳定状态闭集；Turn 终态不可改写，Plan 执行可通过新 admission 显式重开 Task 投影。
     */
    public enum State {
        /**
         * 侧边任务创建事实已提交但尚未发送首个 follow-up，因此不存在 Turn/provider。
         */
        IDLE,
        /**
         * 创建事实已提交但尚未取得执行所有权。
         */
        QUEUED,
        /**
         * Child Turn 已被运行时接纳并正在推进。
         */
        RUNNING,
        /**
         * 执行等待用户审批，保持原 Turn 与资源所有权事实。
         */
        WAITING_APPROVAL,
        /**
         * 执行等待普通补充信息；它与权限审批是不同的交互和恢复状态。
         */
        WAITING_INPUT,
        /**
         * 进程恢复或运行条件中断后停止自动推进，必须显式恢复。
         */
        SUSPENDED,
        /**
         * 已成功产生终态事实，后续重试只能幂等回读。
         */
        COMPLETED,
        /**
         * 已失败关闭且保留安全摘要，不允许隐式重新执行。
         */
        FAILED,
        /**
         * 已提交取消终态，父链传播和用户取消共享该不可逆结果。
         */
        CANCELLED
    }

    /**
     * Activity 描述投影变化的低频事实，供父 Timeline 和任务详情共享同一审计来源。
     */
    public enum ActivityKind {
        /**
         * 独立侧边 Thread 已创建；该活动不绑定 Turn，不能被解释为已执行。
         */
        CREATED,
        /**
         * Task 已接纳新的执行 Turn；空闲任务首轮和显式新轮次均使用该活动。
         */
        DISPATCHED,
        /**
         * 普通 Mailbox 消息已进入目标任务队列。
         */
        MESSAGE_SENT,
        /**
         * Follow-up 消息和目标 Child Turn 已原子接纳。
         */
        FOLLOW_UP_QUEUED,
        /**
         * 运行中安全摘要已更新，但不代表状态发生迁移。
         */
        PROGRESS,
        /**
         * Child Turn 已进入审批等待边界。
         */
        WAITING_APPROVAL,
        /**
         * Child Turn 已提出普通澄清并等待用户补充，不代表工具需要审批。
         */
        WAITING_INPUT,
        /**
         * 用户显式恢复了此前暂停的执行。
         */
        RESUMED,
        /**
         * 任务成功终结并可能向父 Mailbox 发送最终答复。
         */
        COMPLETED,
        /**
         * 任务失败关闭，Activity 只携带可公开的安全摘要。
         */
        FAILED,
        /**
         * 取消意图赢得终态竞争。
         */
        CANCELLED,
        /**
         * 启动恢复将非终态 Child 收敛到需人工恢复的状态。
         */
        SUSPENDED
    }

    /**
     * Mailbox 类型区分普通通信、启动新 Turn 的输入与不可丢的终态答复。
     */
    public enum MailboxKind {
        /**
         * 普通消息只在目标任务下一安全点注入，不创建新 Turn。
         */
        MESSAGE,
        /**
         * Follow-up 在目标任务空闲时创建具体 Turn，并与消息原子绑定。
         */
        FOLLOW_UP,
        /**
         * Child 终态向父 Thread 回传的答复不受普通容量淘汰。
         */
        FINAL_ANSWER
    }

    /**
     * Mailbox 状态冻结 exactly-once 消费边界，绑定和消费都必须与 USER fact 同事务推进。
     */
    public enum MailboxState {
        /**
         * 尚未被具体 Turn 取得的 FIFO 消息。
         */
        PENDING,
        /**
         * 已由具体 Turn 预占，但尚未提交对应 USER fact。
         */
        BOUND,
        /**
         * 对应 USER fact 已提交，消息不可再次投递。
         */
        CONSUMED,
        /**
         * 在消费前被显式取消或随终态释放，不再参与 FIFO。
         */
        CANCELLED
    }

    /**
     * Plan 新 Turn 显式重新打开 Task 聚合时的业务来源。
     */
    public enum ContinuationKind {
        /**
         * Plan owner 请求一次已批准计划执行。
         */
        PLAN
    }

    /**
     * 创建前的上下文种子不接受调用方提供 fingerprint；空的有效历史以 null 表示没有 Core seed。
     *
     * @param contextSeedId 初始上下文快照标识
     * @param parentThreadId 父对话标识
     * @param parentTurnId 父执行标识
     * @param parentRevision 父任务修订版本
     * @param inheritanceMode 继承模式
     * @param taskBrief 任务简述
     * @param initialContextSeed 创建侧边任务时冻结的初始上下文
     * @param references 引用列表
     * @param permissionCeiling 执行可使用的权限上限
     * @param createdAt 创建时间
     */
    public record ContextSeedDraft(String contextSeedId,
                                   String parentThreadId,
                                   String parentTurnId,
                                   long parentRevision,
                                   InheritanceMode inheritanceMode,
                                   UserContent taskBrief,
                                   InitialContextSeed initialContextSeed,
                                   JsonArray references,
                                   JsonObject permissionCeiling,
                                   Instant createdAt) {
        /**
         * 创建事务持久化 Core typed seed；BRIEF_ONLY 不携带可重新读取的父上下文。
         */
        public ContextSeedDraft {
            contextSeedId = identifier(contextSeedId, "seed_", "contextSeedId");
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            parentTurnId = optionalIdentifier(parentTurnId, "turn_", "parentTurnId");
            if (parentRevision < 0) {
                throw new IllegalArgumentException("invalid parentRevision");
            }
            Objects.requireNonNull(inheritanceMode, "inheritanceMode");
            if (inheritanceMode == InheritanceMode.BRIEF_ONLY) {
                Objects.requireNonNull(taskBrief, "taskBrief");
            }
            if (inheritanceMode == InheritanceMode.BRIEF_ONLY && initialContextSeed != null) {
                throw new IllegalArgumentException("brief-only task cannot carry inherited Core context");
            }
            Objects.requireNonNull(references, "references");
            Objects.requireNonNull(permissionCeiling, "permissionCeiling");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 已落库种子包含唯一 canonical fingerprint；空父上下文保留继承模式但没有 Core seed 记录。
     *
     * @param contextSeedId 初始上下文快照标识
     * @param parentThreadId 父对话标识
     * @param parentTurnId 父执行标识
     * @param parentRevision 父任务修订版本
     * @param inheritanceMode 继承模式
     * @param taskBrief 任务简述
     * @param initialContextSeed 创建侧边任务时冻结的初始上下文
     * @param references 引用列表
     * @param permissionCeiling 执行可使用的权限上限
     * @param fingerprint 当前内容或配置的校验指纹
     * @param createdAt 创建时间
     */
    public record ContextSeed(String contextSeedId,
                              String parentThreadId,
                              String parentTurnId,
                              long parentRevision,
                              InheritanceMode inheritanceMode,
                              UserContent taskBrief,
                              InitialContextSeed initialContextSeed,
                              JsonArray references,
                              JsonObject permissionCeiling,
                              String fingerprint,
                              Instant createdAt) {
        /**
         * 从存储恢复的 fingerprint 必须保持固定小写 SHA-256 形状。
         */
        public ContextSeed {
            new ContextSeedDraft(contextSeedId, parentThreadId, parentTurnId, parentRevision,
                    inheritanceMode, taskBrief, initialContextSeed, references, permissionCeiling, createdAt);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid context fingerprint");
            }
        }
    }

    /**
     * SIDE_TASK 创建前冻结的 Core 有效上下文；空父历史的 seed 为 null。权限对象只给出 SQLite 可证明的 accessMode 上限，
     * Coordinator 再用当前 Turn 已冻结的 Tool/Skill/MCP 能力做交集，不能据此提权。
     *
     * @param parentThreadId 父对话标识
     * @param parentRevision 父任务修订版本
     * @param initialContextSeed 创建侧边任务时冻结的初始上下文
     * @param references 引用列表
     * @param permissionCeiling 执行可使用的权限上限
     */
    public record EffectiveContextSnapshot(String parentThreadId,
                                           long parentRevision,
                                           InitialContextSeed initialContextSeed,
                                           JsonArray references,
                                           JsonObject permissionCeiling) {
        /**
         * 快照 revision 与来源、ordinal、消息和摘要绑定，禁止首轮再读取漂移后的父 Thread。
         */
        public EffectiveContextSnapshot {
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            if (parentRevision < 0) {
                throw new IllegalArgumentException("invalid parentRevision");
            }
            Objects.requireNonNull(references, "references");
            Objects.requireNonNull(permissionCeiling, "permissionCeiling");
        }
    }

    /**
     * 隐藏续跑 admission 的 Task CAS 与审计活动输入，不含可伪造的 USER 正文。
     *
     * @param taskThreadId 任务对话标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param kind 类型分类
     * @param expectedTaskRevision 预期任务修订版本
     * @param activityId 活动标识
     * @param activitySummary 活动摘要
     * @param occurredAt 事件发生时间
     */
    public record ContinuationAdmission(String taskThreadId,
                                        String turnId,
                                        ContinuationKind kind,
                                        long expectedTaskRevision,
                                        String activityId,
                                        JsonObject activitySummary,
                                        Instant occurredAt) {
        /**
         * 续跑身份、来源与 Task 快照在进入同连接事务前冻结。
         */
        public ContinuationAdmission {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            if (expectedTaskRevision < 0) {
                throw new IllegalArgumentException("invalid Task revision");
            }
            activityId = identifier(activityId, "activity_", "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * Child 首次 admission 把 Thread 定义、lineage、冻结 seed 与父活动绑定为一个事务输入。
     *
     * @param childThread 侧边任务对话
     * @param parentThreadId 父对话标识
     * @param expectedParentRevision 预期父任务修订版本
     * @param originTurnId 原始执行标识
     * @param taskName 任务名称
     * @param kind 类型分类
     * @param lifecycle 生命周期
     * @param contextSeed 初始上下文快照
     * @param activityId 活动标识
     * @param activitySummary 活动摘要
     */
    public record ChildAdmission(ThreadDefinition childThread,
                                 String parentThreadId,
                                 long expectedParentRevision,
                                 String originTurnId,
                                 String taskName,
                                 Kind kind,
                                 Lifecycle lifecycle,
                                 ContextSeedDraft contextSeed,
                                 String activityId,
                                 JsonObject activitySummary) {
        /**
         * Kind 与 lifecycle 只接受产品定义的两个合法组合，防止组合根创建第三种隐式对象。
         */
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

    /**
     * 创建后不可变的 Thread 血缘事实。
     *
     * @param taskThreadId 任务对话标识
     * @param parentThreadId 父对话标识
     * @param rootThreadId 任务树根对话标识
     * @param originTurnId 原始执行标识
     * @param taskName 任务名称
     * @param depth 深度
     * @param kind 类型分类
     * @param lifecycle 生命周期
     * @param contextSeedId 初始上下文快照标识
     * @param createdAt 创建时间
     */
    public record Lineage(String taskThreadId,
                          String parentThreadId,
                          String rootThreadId,
                          String originTurnId,
                          String taskName,
                          int depth,
                          Kind kind,
                          Lifecycle lifecycle,
                          String contextSeedId,
                          Instant createdAt) {
        /**
         * 数据库行仍需在领域边界复核深度和身份，损坏状态不能透传给调度层。
         */
        public Lineage {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            originTurnId = optionalIdentifier(originTurnId, "turn_", "originTurnId");
            taskName = TaskModels.taskName(taskName);
            if (depth < 1 || depth > 4) {
                throw new IllegalArgumentException("invalid task depth");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lifecycle, "lifecycle");
            contextSeedId = identifier(contextSeedId, "seed_", "contextSeedId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * Child 每次运行时需要的最小身份投影；名称来自对应 Thread 快照，避免把可变运行状态或正文
     * 复制进 System prompt，也让重启、压缩和后续 Turn 都重新读取同一份持久事实。
     *
     * @param taskThreadId 任务对话标识
     * @param parentThreadId 父对话标识
     * @param rootThreadId 任务树根对话标识
     * @param taskName 任务名称
     * @param parentTaskName 父任务名称
     * @param rootTaskName 根任务名称
     * @param kind 类型分类
     */
    public record RuntimeIdentity(String taskThreadId,
                                  String parentThreadId,
                                  String rootThreadId,
                                  String taskName,
                                  String parentTaskName,
                                  String rootTaskName,
                                  Kind kind) {
        /**
         * 身份必须携带持久 lineage 的 Kind，标题保留原值但拒绝空值与 NUL。
         */
        public RuntimeIdentity {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            taskName = TaskModels.taskName(taskName);
            parentTaskName = threadName(parentTaskName, "parentTaskName");
            rootTaskName = threadName(rootTaskName, "rootTaskName");
            Objects.requireNonNull(kind, "kind");
        }

        /**
         * Thread 标题允许自然语言但不能让结构化身份携带 NUL 或无界文本。
         */
        private static String threadName(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        }
    }

    /**
     * 右栏常量级读取的权威投影，不以扫描完整 Child Timeline 计算状态。
     *
     * @param taskThreadId 任务对话标识
     * @param rootThreadId 任务树根对话标识
     * @param revision 当前修订版本
     * @param state 当前状态
     * @param latestActivitySequence 最近活动序号
     * @param lastSeenActivitySequence 最近已读活动的序号
     * @param unreadCount 未读数量
     * @param descendantCount 后代任务数量
     * @param runningDescendantCount 正在运行的后代任务数
     * @param needsAttentionCount 需要处理的任务数
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     * @param updatedAt 最后更新时间
     */
    public record Projection(String taskThreadId,
                             String rootThreadId,
                             long revision,
                             State state,
                             long latestActivitySequence,
                             Long lastSeenActivitySequence,
                             int unreadCount,
                             int descendantCount,
                             int runningDescendantCount,
                             int needsAttentionCount,
                             String latestSafeSummary,
                             Instant startedAt,
                             Instant completedAt,
                             Instant updatedAt) {
        /**
         * 终态时间与状态闭集必须一致，计数保持 V1 的树容量上限。
         */
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
            if (terminal == (completedAt == null)) {
                throw new IllegalArgumentException("invalid terminal time");
            }
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * Task Summary 合并不可变 lineage 与可变 projection，防止调用方观察拆分读。
     *
     * @param lineage 任务来源
     * @param projection 投影
     */
    public record Summary(Lineage lineage,
                          Projection projection) {
        /**
         * 两部分必须描述同一 Child Thread 和根，否则列表会产生错误树归属。
         */
        public Summary {
            Objects.requireNonNull(lineage, "lineage");
            Objects.requireNonNull(projection, "projection");
            if (!lineage.taskThreadId().equals(projection.taskThreadId())
                || !lineage.rootThreadId().equals(projection.rootThreadId())) {
                throw new IllegalArgumentException("task summary identity mismatch");
            }
        }
    }

    /**
     * 低频、追加式活动是父 Timeline 与详情页共同消费的安全事实。
     *
     * @param sequence 当前记录的顺序号
     * @param activityId 活动标识
     * @param rootThreadId 任务树根对话标识
     * @param taskThreadId 任务对话标识
     * @param actorThreadId 发起操作的对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param summary 摘要
     * @param createdAt 创建时间
     */
    public record Activity(long sequence,
                           String activityId,
                           String rootThreadId,
                           String taskThreadId,
                           String actorThreadId,
                           String causalTurnId,
                           ActivityKind kind,
                           JsonObject summary,
                           Instant createdAt) {
        /**
         * 活动序号只能由 SQLite 分配，调用方不能伪造零值占位。
         */
        public Activity {
            if (sequence < 1) {
                throw new IllegalArgumentException("invalid activity sequence");
            }
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

    /**
     * 父 Timeline 的一条持久活动同时携带对应 Task 摘要，避免展示层再发起树扫描或详情读取。
     *
     * @param activity 活动
     * @param task 任务
     */
    public record ActivityProjection(Activity activity,
                                     Summary task) {
        /**
         * Activity 与摘要必须属于同一根和同一 Child，损坏关系不能进入主 Timeline。
         */
        public ActivityProjection {
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(task, "task");
            if (!activity.taskThreadId().equals(task.lineage().taskThreadId())
                || !activity.rootThreadId().equals(task.lineage().rootThreadId())) {
                throw new IllegalArgumentException("task activity projection identity mismatch");
            }
        }
    }

    /**
     * Mailbox 请求的幂等键以发送方为命名空间，QueueOnly 和 Follow-up 共用同一事实形状。
     *
     * @param messageId 消息标识
     * @param senderThreadId 投递消息的来源对话标识
     * @param targetThreadId 投递消息的目标对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param content 内容
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param createdAt 创建时间
     */
    public record MailboxEnvelope(String messageId,
                                  String senderThreadId,
                                  String targetThreadId,
                                  String causalTurnId,
                                  MailboxKind kind,
                                  UserContent content,
                                  String operationKey,
                                  Instant createdAt) {
        /**
         * 普通通信拒绝自投递；FOLLOW_UP 是用户在当前会话的输入，必须允许自身作为接纳来源。
         */
        public MailboxEnvelope {
            messageId = identifier(messageId, "msg_", "messageId");
            senderThreadId = identifier(senderThreadId, "thr_", "senderThreadId");
            targetThreadId = identifier(targetThreadId, "thr_", "targetThreadId");
            if (senderThreadId.equals(targetThreadId) && kind != MailboxKind.FOLLOW_UP) {
                throw new IllegalArgumentException("self message is invalid");
            }
            causalTurnId = optionalIdentifier(causalTurnId, "turn_", "causalTurnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            operationKey = boundedText(operationKey, "operationKey", 128);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 已持久化 Mailbox 行保留发送方标题快照，供发送方被删除后仍能显示外发来源。
     *
     * @param sequence 当前记录的顺序号
     * @param messageId 消息标识
     * @param rootThreadId 任务树根对话标识
     * @param senderThreadId 投递消息的来源对话标识
     * @param senderTitle 发送方标题
     * @param targetThreadId 投递消息的目标对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param content 内容
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param state 当前状态
     * @param boundTurnId 绑定的执行标识
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     * @param consumedAt 已消费时间
     */
    public record MailboxMessage(long sequence,
                                 String messageId,
                                 String rootThreadId,
                                 String senderThreadId,
                                 String senderTitle,
                                 String targetThreadId,
                                 String causalTurnId,
                                 MailboxKind kind,
                                 UserContent content,
                                 String operationKey,
                                 MailboxState state,
                                 String boundTurnId,
                                 Instant createdAt,
                                 Instant updatedAt,
                                 Instant consumedAt) {
        /**
         * PENDING/BOUND/CONSUMED/CANCELLED 的空值组合必须与 V1 CHECK 保持一致。
         */
        public MailboxMessage {
            if (sequence < 1) {
                throw new IllegalArgumentException("invalid mailbox sequence");
            }
            new MailboxEnvelope(messageId, senderThreadId, targetThreadId, causalTurnId, kind,
                    content, operationKey, createdAt);
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            Objects.requireNonNull(state, "state");
            boundTurnId = optionalIdentifier(boundTurnId, "turn_", "boundTurnId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if ((state == MailboxState.BOUND) != (boundTurnId != null && consumedAt == null)
                || (state == MailboxState.CONSUMED) == (consumedAt == null)) {
                throw new IllegalArgumentException("invalid mailbox state facts");
            }
        }
    }

    /**
     * QueueOnly 事务回执只携带 Mailbox 事实，不把跨会话投递伪装成 Task Activity。
     *
     * @param mailbox 收件箱
     * @param inserted 是否本次新增
     */
    public record MessageEnqueueReceipt(MailboxMessage mailbox,
                                        boolean inserted) {
        /**
         * 是否首次插入由 SQLite 唯一键竞争的事务 winner 决定，而非应用层猜测。
         */
        public MessageEnqueueReceipt {
            Objects.requireNonNull(mailbox, "mailbox");
        }
    }

    /**
     * 已提交父取消事实但仍有 ATTACHED 直接子 Turn 未终结时形成的可恢复传播欠账。
     *
     * @param parentThreadId 父对话标识
     * @param parentTurnId 父执行标识
     */
    public record CancellationPropagation(String parentThreadId,
                                          String parentTurnId) {
        /**
         * 欠账只保存稳定因果身份；具体后代每次处理时从 SQLite 权威 lineage 重读。
         */
        public CancellationPropagation {
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            parentTurnId = identifier(parentTurnId, "turn_", "parentTurnId");
        }
    }

    /**
     * Thread fork 的精确回复边界、完整中立历史和首轮有效上下文；raw facts 与压缩 seed 分开保存。
     *
     * @param parentThreadId 来源 Thread
     * @param parentRevision 冻结的来源 revision
     * @param sourceItemId 可见结算答复身份
     * @param sourceTurnId 答复实际所属执行 Turn 身份
     * @param contextSourceThreadId 中立模型历史身份所属的真实来源 Thread
     * @param contextSourceItemId 中立模型历史中所选答复的真实来源 item
     * @param contextMessages 截止回复的中立历史事实
     * @param initialContextSeed 新 Thread 首轮仅消费一次的有效上下文
     */
    public record ThreadForkContextSnapshot(String parentThreadId,
                                            long parentRevision,
                                            String sourceItemId,
                                            String sourceTurnId,
                                            String contextSourceThreadId,
                                            String contextSourceItemId,
                                            List<ContextMessage> contextMessages,
                                            InitialContextSeed initialContextSeed) {
        /** 原始事实保持完整且有序，领域 snapshot 不得让调用方退化为整 Turn 截断。 */
        public ThreadForkContextSnapshot {
            parentThreadId = identifier(parentThreadId, "thr_", "parentThreadId");
            sourceItemId = identifier(sourceItemId, "item_", "sourceItemId");
            sourceTurnId = identifier(sourceTurnId, "turn_", "sourceTurnId");
            contextSourceThreadId = identifier(contextSourceThreadId, "thr_", "contextSourceThreadId");
            contextSourceItemId = identifier(contextSourceItemId, "item_", "contextSourceItemId");
            if (parentRevision < 0) {
                throw new IllegalArgumentException("invalid parentRevision");
            }
            contextMessages = List.copyOf(Objects.requireNonNull(contextMessages, "contextMessages"));
            Objects.requireNonNull(initialContextSeed, "initialContextSeed");
            if (!initialContextSeed.sourceIdentity().threadId().equals(contextSourceThreadId)
                || !initialContextSeed.sourceIdentity().turnId().equals(sourceTurnId)) {
                throw new IllegalArgumentException("fork seed source identity is inconsistent");
            }
        }
    }

    /**
     * 侧聊关闭阶段只允许仓储返回已持久 CLOSING 树或已完成关闭回执。
     */
    public enum SideChatClosePhase {
        /** 关闭闸门已提交，协调器仍须停止 owner、取消并等待 Turn 终态。 */
        CLOSING,
        /** 关闭回执与物理清理已在同一事务提交，调用方可直接返回成功。 */
        CLOSED
    }

    /**
     * 单次原子开始关闭返回的权威 Thread 集合；CLOSED 表示这是已完成操作的持久回执。
     *
     * @param rootThreadId 请求关闭的独立侧聊根
     * @param ownerRootThreadId 接收关闭事件的顶层 Task 树根
     * @param phase 关闭阶段
     * @param threadIds 本次关闭冻结的完整子树身份
     */
    public record SideChatCloseState(String rootThreadId,
                                     String ownerRootThreadId,
                                     SideChatClosePhase phase,
                                     List<String> threadIds) {
        /**
         * 规范升序身份用于取消、清理和回执比较，避免不同 SQL 遍历顺序改变成功结果。
         */
        public SideChatCloseState {
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            ownerRootThreadId = identifier(ownerRootThreadId, "thr_", "ownerRootThreadId");
            if (rootThreadId.equals(ownerRootThreadId)) {
                throw new IllegalArgumentException("side chat must have a distinct owner root");
            }
            Objects.requireNonNull(phase, "phase");
            threadIds = sideChatThreadIds(rootThreadId, threadIds);
            requireOwnerOutsideClosedTree(ownerRootThreadId, threadIds);
        }

        /**
         * 关闭范围只以不可变列表对外读取，防止调用方改变后续取消与清理共用的冻结身份集合。
         */
        public List<String> threadIds() {
            return List.copyOf(threadIds);
        }
    }

    /**
     * 成功清理后的稳定回执；按 root 持久保存，使响应丢失或进程重启后的幂等重试仍回同一范围。
     *
     * @param rootThreadId 已关闭的独立侧聊根
     * @param ownerRootThreadId 接收关闭事件的顶层 Task 树根
     * @param closedThreadIds 已物理清理的完整 Thread 子树
     */
    public record SideChatCloseReceipt(String rootThreadId,
                                       String ownerRootThreadId,
                                       List<String> closedThreadIds) {
        /**
         * 回执必须包含 root 且不能丢弃重复或非法身份，协议层可据此精确释放整棵 UI 预览树。
         */
        public SideChatCloseReceipt {
            rootThreadId = identifier(rootThreadId, "thr_", "rootThreadId");
            ownerRootThreadId = identifier(ownerRootThreadId, "thr_", "ownerRootThreadId");
            if (rootThreadId.equals(ownerRootThreadId)) {
                throw new IllegalArgumentException("side chat must have a distinct owner root");
            }
            closedThreadIds = sideChatThreadIds(rootThreadId, closedThreadIds);
            requireOwnerOutsideClosedTree(ownerRootThreadId, closedThreadIds);
        }

        /**
         * 回执范围保持不可变，使重试广播与协议清理始终读取同一已提交集合。
         */
        public List<String> closedThreadIds() {
            return List.copyOf(closedThreadIds);
        }
    }

    /**
     * 路由 owner 必须存活在被删除树之外，否则关闭事件会指向自身已销毁的订阅根。
     */
    private static void requireOwnerOutsideClosedTree(String ownerRootThreadId, List<String> threadIds) {
        if (threadIds.contains(ownerRootThreadId)) {
            throw new IllegalArgumentException("side chat owner root must survive close");
        }
    }

    /**
     * 校验持久关闭范围并生成跨重试稳定的顺序，拒绝在应用层补造缺失 root 或静默去重。
     */
    private static List<String> sideChatThreadIds(String rootThreadId, List<String> threadIds) {
        List<String> values = List.copyOf(Objects.requireNonNull(threadIds, "threadIds"));
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String threadId = identifier(value, "thr_", "threadId");
            if (!unique.add(threadId)) {
                throw new IllegalArgumentException("duplicate side chat threadId");
            }
        }
        if (!unique.contains(rootThreadId)) {
            throw new IllegalArgumentException("side chat close set must include rootThreadId");
        }
        return unique.stream().sorted().toList();
    }

    /**
     * Follow-up 把 Mailbox、目标 Child 新 Turn 和活动投影绑定为一次原子接纳。
     *
     * @param mailbox 收件箱
     * @param turn 执行轮次
     * @param expectedTaskRevision 预期任务修订版本
     * @param activityId 活动标识
     * @param activitySummary 活动摘要
     */
    public record FollowUpAdmission(MailboxEnvelope mailbox,
                                    TurnAdmission turn,
                                    long expectedTaskRevision,
                                    String activityId,
                                    JsonObject activitySummary) {
        /**
         * 只有 FOLLOW_UP 可以创建 Turn，且 Turn 必须属于消息目标 Thread。
         */
        public FollowUpAdmission {
            Objects.requireNonNull(mailbox, "mailbox");
            if (mailbox.kind() != MailboxKind.FOLLOW_UP) {
                throw new IllegalArgumentException("not a follow-up");
            }
            Objects.requireNonNull(turn, "turn");
            if (!mailbox.targetThreadId().equals(turn.threadId()) || expectedTaskRevision < 0) {
                throw new IllegalArgumentException("follow-up target mismatch");
            }
            activityId = identifier(activityId, "activity_", "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
        }
    }

    /**
     * USER 准入只附加 Task 侧投影，Turn、消息、游标和附件正文仍以 AgentStore 为唯一写入者。
     *
     * @param turn 执行轮次
     * @param expectedTaskRevision 预期任务修订版本
     */
    public record UserTurnAdmission(TurnAdmission turn,
                                    long expectedTaskRevision) {
        /**
         * Task revision 与 Core admission 身份必须在进入同连接仓储前冻结。
         */
        public UserTurnAdmission {
            Objects.requireNonNull(turn, "turn");
            if (expectedTaskRevision < 0) {
                throw new IllegalArgumentException("invalid Task revision");
            }
        }
    }

    /**
     * Follow-up 原子事务同时返回实际 Turn 与 Mailbox 行，幂等重放不得使用本次草稿身份。
     *
     * @param admission 准入
     * @param mailbox 收件箱
     */
    public record FollowUpAdmissionReceipt(AdmissionReceipt admission,
                                           MailboxMessage mailbox) {
        /**
         * Follow-up 只在 USER fact 与 Task Turn 同事务提交后返回 CONSUMED 回执。
         */
        public FollowUpAdmissionReceipt {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(mailbox, "mailbox");
            if (mailbox.kind() != MailboxKind.FOLLOW_UP || mailbox.state() != MailboxState.CONSUMED
                || !admission.turnId().equals(mailbox.boundTurnId())) {
                throw new IllegalArgumentException("follow-up receipt identity mismatch");
            }
        }
    }

    /**
     * 可变 Task 状态与活动通过单一 CAS 提交，供审批、恢复和取消共享。
     *
     * @param taskThreadId 任务对话标识
     * @param expectedTaskRevision 预期任务修订版本
     * @param state 当前状态
     * @param activityId 活动标识
     * @param actorThreadId 发起操作的对话标识
     * @param causalTurnId 触发当前事实的执行标识
     * @param kind 类型分类
     * @param summary 摘要
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param occurredAt 事件发生时间
     */
    public record ActivityMutation(String taskThreadId,
                                   long expectedTaskRevision,
                                   State state,
                                   String activityId,
                                   String actorThreadId,
                                   String causalTurnId,
                                   ActivityKind kind,
                                   JsonObject summary,
                                   String latestSafeSummary,
                                   Instant occurredAt) {
        /**
         * 状态投影和 Activity 必须使用同一时间，避免排序与 revision 对外不一致。
         */
        public ActivityMutation {
            taskThreadId = identifier(taskThreadId, "thr_", "taskThreadId");
            if (expectedTaskRevision < 0) {
                throw new IllegalArgumentException("invalid task revision");
            }
            Objects.requireNonNull(state, "state");
            activityId = identifier(activityId, "activity_", "activityId");
            actorThreadId = identifier(actorThreadId, "thr_", "actorThreadId");
            causalTurnId = optionalIdentifier(causalTurnId, "turn_", "causalTurnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 终态扩展只包含 Task 侧事实，Conversation terminal winner 在同一事务内调用它。
     *
     * @param taskThreadId 任务对话标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param state 当前状态
     * @param activityId 活动标识
     * @param activitySummary 活动摘要
     * @param latestSafeSummary 最近确认可用于恢复的摘要
     * @param finalAnswer 最终答案
     * @param occurredAt 事件发生时间
     */
    public record TerminalSettlement(String taskThreadId,
                                     String turnId,
                                     State state,
                                     String activityId,
                                     JsonObject activitySummary,
                                     String latestSafeSummary,
                                     MailboxEnvelope finalAnswer,
                                     Instant occurredAt) {
        /**
         * 仅三个终态可进入扩展，FINAL_ANSWER 的发送方和因果 Turn 必须与 Child 对齐。
         */
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

    /**
     * Task 详情分页把三类低频事实绑定到同一个投影 revision。
     *
     * @param task 任务
     * @param thread 对话
     * @param contextSeed 初始上下文快照
     * @param activities 活动列表
     * @param mailbox 收件箱
     */
    public record Detail(Summary task,
                         ThreadSnapshot thread,
                         ContextSeed contextSeed,
                         List<Activity> activities,
                         List<MailboxMessage> mailbox) {
        /**
         * 集合在离开 SqlSession 前冻结，禁止延迟加载逃逸事务快照。
         */
        public Detail {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(contextSeed, "contextSeed");
            activities = List.copyOf(Objects.requireNonNull(activities, "activities"));
            mailbox = List.copyOf(Objects.requireNonNull(mailbox, "mailbox"));
        }
    }
}
