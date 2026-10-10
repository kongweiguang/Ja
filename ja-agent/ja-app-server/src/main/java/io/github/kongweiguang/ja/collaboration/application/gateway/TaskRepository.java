// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.domain.model.TurnAdmission;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 的 SQLite owner 端口；观察句柄和事件订阅刻意留在上层内存生命周期。
 */
public interface TaskRepository extends AutoCloseable {
    /**
     * 校验 parent revision，并从指定来源 Turn 冻结 Core typed history 与 checkpoint；之后不得回读父 Thread。
     */
    TaskModels.EffectiveContextSnapshot freezeEffectiveContext(String parentThreadId,
                                                               String sourceTurnId,
                                                               long expectedParentRevision);

    /**
     * 按持久化回复 item 精确冻结模型事实；活动 Thread 可继续运行，但工具调用或未结算正文不得成为边界。
     */
    TaskModels.ThreadForkContextSnapshot freezeThreadForkContext(String parentThreadId,
                                                                 String sourceItemId,
                                                                 long expectedParentRevision);

    /**
     * 只返回 Task 创建时保存且尚无任何执行的 SIDE_TASK 背景快照；不重新读取 parent。
     */
    Optional<InitialContextSeed> findInitialContextSeedForFirstTurn(String taskThreadId);

    /**
     * 创建没有首 Turn 的独立侧边任务；所有元数据和 CREATED 活动必须同一事务提交。
     */
    TaskModels.Summary admitIdleChild(TaskModels.ChildAdmission child);

    /**
     * 读取并验证父 Turn 的 causal root；真正 admission 时仍需在写事务内再次核验。
     */
    String rootTurnIdForAdmission(String parentThreadId, String parentTurnId, boolean requireNonTerminal);

    /**
     * Child 首 Turn 同时复核运行时已冻结的父策略，避免 commit 与 pending 工具快照分属不同配置代际。
     */
    void applyChildAdmission(TaskModels.ChildAdmission child, TurnAdmission turn,
                             AdmissionFacts facts, SubagentPolicy expectedParentPolicy);

    /**
     * 只在 AgentStore admission participant 的同一连接补齐 Task USER 投影并校验冻结首轮背景。
     */
    void applyUserTurnAdmission(TaskModels.UserTurnAdmission admission, AdmissionFacts facts);

    /**
     * Plan 执行 Turn 的 Activity 与 QUEUED Task 投影随 Core admission 在同一事务提交。
     */
    void applyContinuationAdmission(TaskModels.ContinuationAdmission admission, AdmissionFacts facts);

    /**
     * 读取一条 Task 摘要，不物化其 Timeline 正文。
     */
    Optional<TaskModels.Summary> findTask(String taskThreadId);

    /**
     * 一次读取当前根任务全部后代，V1 的 64 上限使结果天然有界。
     */
    List<TaskModels.Summary> listTree(String rootThreadId);

    /**
     * 单次有界读取根 Timeline 的最近活动及对应摘要，不扫描 Child 历史。
     */
    List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit);

    /**
     * 只读取选中详情的 seed 与有界低频 activity/mailbox，不读取 Child 消息历史。
     */
    Optional<TaskModels.Detail> readTask(String taskThreadId, long afterActivitySequence,
                                         long afterMailboxSequence, int limit);

    /**
     * QueueOnly 消息只提交 Mailbox，不创建或唤醒 Turn，也不触碰 Task Activity 投影。
     */
    TaskModels.MessageEnqueueReceipt enqueueMessage(TaskModels.MailboxEnvelope mailbox);

    /**
     * 在任何 revision 校验或 Turn reserve 前回读已绑定 Follow-up；内容冲突必须在仓储边界稳定拒绝。
     */
    Optional<TaskModels.FollowUpAdmissionReceipt> findFollowUpByIdempotency(
            TaskModels.MailboxEnvelope mailbox);

    /**
     * 只在 AgentStore admission participant 的同一连接中写 Follow-up/Mailbox 业务事实。
     */
    void applyFollowUpAdmission(TaskModels.FollowUpAdmission admission, AdmissionFacts facts);

    /**
     * 以 projection revision 推进活动已读边界，避免客户端时钟参与未读计算。
     */
    TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision,
                                long throughActivitySequence, Instant occurredAt);

    /**
     * 追加活动并以同一 CAS 更新状态投影，供审批、恢复、取消和进度低频快照复用。
     */
    TaskModels.Summary recordActivity(TaskModels.ActivityMutation mutation);

    /**
     * 只返回 ATTACHED 后代，父 Turn 取消不会误伤独立侧边任务。
     */
    List<TaskModels.Summary> attachedDescendants(String parentThreadId);

    /**
     * 从父 Turn 已提交的取消事实派生仍未收敛的 ATTACHED 传播欠账，供启动绑定恢复。
     */
    List<TaskModels.CancellationPropagation> pendingCancellationPropagations();

    /**
     * 显式整树删除在确认无非终态 Turn 后原子隐藏 Thread 并移除 Task 元数据。
     */
    int deleteTree(String taskThreadId, long expectedTaskRevision, Instant occurredAt);

    /**
     * 原子读取已完成回执或把临时侧聊及现有后代切到 CLOSING，并冻结关闭 root、通知 owner root 与完整身份集合。
     */
    TaskModels.SideChatCloseState beginSideChatClose(String taskThreadId);

    /**
     * 在同一事务内重验冻结树与 owner root，保存永久幂等回执并物理清理；失败不能留下成功回执。
     */
    TaskModels.SideChatCloseReceipt deleteClosedSideChat(String taskThreadId,
                                                         String expectedOwnerRootThreadId,
                                                         List<String> expectedThreadIds);

    /**
     * 返回持久临时侧聊标记，供 server 协调启动恢复和关闭重试。
     */
    List<TemporarySideChat> listTemporarySideChats();

    /**
     * Adapter 不拥有 datasource；close 只阻止迟到调用。
     */
    @Override
    void close();

    /**
     * 临时侧聊 marker 的稳定状态；不存在 marker 的旧侧聊不属于可物理 purge 范围。
     */
    enum TemporarySideChatState {
        /**
         * 新建侧聊尚可接受输入和派发。
         */
        OPEN,
        /**
         * 关闭闸门已赢得竞争，只允许取消/清理路径继续。
         */
        CLOSING
    }

    /**
     * 对外暴露最小 marker 投影，不携带数据库时间或 SQL 行形状。
     *
     * @param threadId 所属对话的稳定标识
     * @param state 当前状态
     */
    record TemporarySideChat(String threadId,
                             TemporarySideChatState state) {
        /**
         * 身份和状态在端口边界固定，避免协调器接收任意字符串。
         */
        public TemporarySideChat {
            if (threadId == null || !threadId.startsWith("thr_") || threadId.length() > 128
                || !threadId.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid temporary side chat threadId");
            }
            Objects.requireNonNull(state, "state");
        }
    }
}
