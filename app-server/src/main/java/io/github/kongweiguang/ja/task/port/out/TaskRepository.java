// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.port.out;

import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.task.domain.TaskModels;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Task 的 SQLite owner 端口；观察句柄和事件订阅刻意留在上层内存生命周期。 */
public interface TaskRepository extends AutoCloseable {
    /**
     * 在同一 SQLite 快照校验 parent revision，并冻结最新 checkpoint summary、retained split 与
     * 其后有界消息；无 checkpoint 且历史超限时失败，禁止静默恢复无限归档。
     */
    TaskModels.EffectiveContextSnapshot freezeEffectiveContext(String parentThreadId,
                                                               long expectedParentRevision);

    /** 创建没有首 Turn 的独立侧边任务；所有元数据和 CREATED 活动必须同一事务提交。 */
    TaskModels.Summary admitIdleChild(TaskModels.ChildAdmission child);

    /** 队列 reserve 成功后原子创建 Child Thread、首 Turn、seed、lineage、活动和投影。 */
    ConversationRepository.AdmissionReceipt admitChild(TaskModels.ChildAdmission child,
                                                        ConversationRepository.TurnAdmission turn);

    /** 读取一条 Task 摘要，不物化其 Timeline 正文。 */
    Optional<TaskModels.Summary> findTask(String taskThreadId);

    /** 一次读取当前根任务全部后代，V1 的 64 上限使结果天然有界。 */
    List<TaskModels.Summary> listTree(String rootThreadId);

    /** 单次有界读取根 Timeline 的最近活动及对应摘要，不扫描 Child 历史。 */
    List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit);

    /** 只读取选中详情的 seed 与有界低频 activity/mailbox，不读取 Child 消息历史。 */
    Optional<TaskModels.Detail> readTask(String taskThreadId, long afterActivitySequence,
                                         long afterMailboxSequence, int limit);

    /** QueueOnly 消息只提交 Mailbox，不创建或唤醒 Turn，也不触碰 Task Activity 投影。 */
    TaskModels.MessageEnqueueReceipt enqueueMessage(TaskModels.MailboxEnvelope mailbox);

    /**
     * 在任何 revision 校验或 Turn reserve 前回读已绑定 Follow-up；内容冲突必须在仓储边界稳定拒绝。
     */
    Optional<TaskModels.FollowUpAdmissionReceipt> findFollowUpByIdempotency(
            TaskModels.MailboxEnvelope mailbox);

    /** Follow-up 在一个事务内入 Mailbox、创建目标 Turn 并绑定，并返回幂等事实的真实身份。 */
    TaskModels.FollowUpAdmissionReceipt admitFollowUp(TaskModels.FollowUpAdmission admission);

    /** 以 projection revision 推进活动已读边界，避免客户端时钟参与未读计算。 */
    TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision,
                                long throughActivitySequence, Instant occurredAt);

    /** 追加活动并以同一 CAS 更新状态投影，供审批、恢复、取消和进度低频快照复用。 */
    TaskModels.Summary recordActivity(TaskModels.ActivityMutation mutation);

    /** 只返回 ATTACHED 后代，父 Turn 取消不会误伤独立侧边任务。 */
    List<TaskModels.Summary> attachedDescendants(String parentThreadId);

    /** 从父 Turn 已提交的取消事实派生仍未收敛的 ATTACHED 传播欠账，供启动绑定恢复。 */
    List<TaskModels.CancellationPropagation> pendingCancellationPropagations();

    /** 显式整树删除在确认无非终态 Turn 后原子隐藏 Thread 并移除 Task 元数据。 */
    int deleteTree(String taskThreadId, long expectedTaskRevision, Instant occurredAt);

    /** 原子把临时侧聊及现有后代切到 CLOSING，返回协调取消所需的稳定子树身份。 */
    List<String> beginSideChatClose(String taskThreadId);

    /** 仅在协调器已收敛全部 Turn 后物理清理临时侧聊图；非临时或 OPEN 标记不得删除。 */
    int deleteClosedSideChat(String taskThreadId);

    /** 返回持久临时侧聊标记，供 server 协调启动恢复和关闭重试。 */
    List<TemporarySideChat> listTemporarySideChats();

    /** Adapter 不拥有 datasource；close 只阻止迟到调用。 */
    @Override
    void close();

    /** 临时侧聊 marker 的稳定状态；不存在 marker 的旧侧聊不属于可物理 purge 范围。 */
    enum TemporarySideChatState {
        /** 新建侧聊尚可接受输入和派发。 */
        OPEN,
        /** 关闭闸门已赢得竞争，只允许取消/清理路径继续。 */
        CLOSING
    }

    /** 对外暴露最小 marker 投影，不携带数据库时间或 SQL 行形状。 */
    record TemporarySideChat(String threadId, TemporarySideChatState state) {
        /** 身份和状态在端口边界固定，避免协调器接收任意字符串。 */
        public TemporarySideChat {
            if (threadId == null || !threadId.startsWith("thr_") || threadId.length() > 128
                    || !threadId.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid temporary side chat threadId");
            }
            Objects.requireNonNull(state, "state");
        }
    }
}
