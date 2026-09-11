// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.port.in;

import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.task.domain.TaskModels;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** 用户侧边任务、Agent Subagent 与 JA-RPC 共用的唯一 Task 应用端口。 */
public interface TaskUseCase extends AutoCloseable {
    /** 用户创建冻结完整有效上下文的独立侧边任务。 */
    TaskModels.Summary createSideTask(CreateCommand command);

    /** Agent 创建 brief-only 且附着于当前父 Turn 的 Subagent。 */
    StartResult spawnAgent(SpawnCommand command);

    /** 返回当前根任务的完整 Child 树摘要，不读取任何 Child Transcript。 */
    List<TaskModels.Summary> listTree(String rootThreadId);

    /** 返回根 Thread 主 Timeline 所需的最近持久活动，不读取任何 Child Transcript。 */
    List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit);

    /** 读取一个选中 Task 的有界 seed、Activity 与 Mailbox。 */
    TaskModels.Detail read(String taskThreadId, long afterActivitySequence, long afterMailboxSequence, int limit);

    /** 每次 Provider 请求重新读取 Child 的持久身份；Kind 决定 Side Task 与 Subagent 的权限校验边界。 */
    default Optional<TaskModels.RuntimeIdentity> readRuntimeIdentity(String taskThreadId) {
        return Optional.empty();
    }

    /** 为选中详情建立高频订阅；调用方关闭句柄时必须 unobserve。 */
    Observation observe(String taskThreadId, long expectedTaskRevision);

    /** 释放当前连接拥有的 observation；未知句柄失败关闭。 */
    void unobserve(String observationId);

    /** 通过服务端 Activity sequence 推进已读边界。 */
    TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision, long throughActivitySequence);

    /** QueueOnly 消息只进入 Mailbox，不启动空闲 Child。 */
    MessageReceipt sendMessage(MessageCommand command);

    /** Follow-up 原子绑定 Mailbox 与新 Child Turn，提交后才调度。 */
    FollowUpResult followUp(FollowUpCommand command);

    /**
     * 继续一个已由请求者委派的 Subagent；实现必须在应用边界复核真实委派链，不能把普通消息
     * 的跨会话投递权限扩大为启动任意 Task 的权限。
     */
    default FollowUpResult continueAgentFrom(String requesterThreadId, FollowUpCommand command) {
        throw new UnsupportedOperationException("agent continuation is unavailable");
    }

    /** 取消目标 Task 当前 Turn，并按 ATTACHED 规则递归传播。 */
    TaskModels.Summary cancel(String taskThreadId, long expectedTaskRevision);

    /** 关闭临时侧聊；独立生命周期在此收口，重复调用保持幂等且不恢复已投递消息。 */
    void closeSideChat(String taskThreadId);

    /** Host 关闭确认之前完成临时侧聊清理；确认后宿主可能立即回收整个进程树。 */
    void closeTemporarySideChats(long shutdownDeadlineNanos);

    /** Agent 取消额外绑定请求者身份，应用层必须证明请求者与目标位于同一根任务树。 */
    TaskModels.Summary cancelFrom(String requesterThreadId, String taskThreadId, long expectedTaskRevision);

    /** 显式且重复确认身份后删除整棵 Task 树。 */
    int deleteTree(String taskThreadId, long expectedTaskRevision, String confirmTaskThreadId);

    /** Agent 等待任一目标终态或需要处理；等待只由事件唤醒并响应父 Turn 取消。 */
    CompletionStage<WaitResult> waitAgents(Set<String> taskThreadIds, Duration timeout, CancellationToken cancellation);

    /** Agent 等待额外绑定请求者身份，防止模型用猜测 ID 观察其它根任务树。 */
    CompletionStage<WaitResult> waitAgentsFrom(String requesterThreadId, Set<String> taskThreadIds,
                                               Duration timeout, CancellationToken cancellation);

    /** 注册唯一运行连接的投影出口；关闭订阅只影响展示，不取消 Task。 */
    AutoCloseable subscribe(TaskEventSink sink);

    /** 只回收内存 observation、waiter 与定时器，不删除或取消持久 Task。 */
    @Override
    void close();

    /** Side task 与 Subagent 创建共用的冻结结果。 */
    record StartResult(TaskModels.Summary task, String turnId) {
        /** Task 与 Turn 身份必须完整，调用方不得返回本地草稿 ID。 */
        public StartResult {
            Objects.requireNonNull(task, "task");
            if (turnId == null || !turnId.startsWith("turn_")) throw new IllegalArgumentException("invalid turnId");
        }
    }

    /** Follow-up 必须直接返回本次幂等 Mailbox 身份，禁止 transport 二次扫描猜测关联行。 */
    record FollowUpResult(TaskModels.Summary task, String turnId, String messageId) {
        /** 三个身份均来自同一次原子 admission；重试时返回已提交的原始身份。 */
        public FollowUpResult {
            Objects.requireNonNull(task, "task");
            if (turnId == null || !turnId.startsWith("turn_")
                    || messageId == null || !messageId.startsWith("msg_")) {
                throw new IllegalArgumentException("invalid follow-up result");
            }
        }
    }

    /** 用户侧边任务接收可选的模型/权限覆盖；缺省时完整继承父 Thread 的有效偏好。 */
    record CreateCommand(String parentThreadId, String parentTurnId, long expectedParentRevision,
                         String taskName, CreatePreferences preferences) {
        /** 创建命令不允许空名称或非正 Deadline。 */
        public CreateCommand {
            Objects.requireNonNull(parentThreadId, "parentThreadId");
            if (expectedParentRevision < 0 || taskName == null || taskName.isBlank()) {
                throw new IllegalArgumentException("invalid side task command");
            }
        }
    }

    /** 用户侧边任务的显式覆盖包含完整执行偏好，不携带仅由服务端维护的 titleSource。 */
    record CreatePreferences(String providerId, String modelId, String reasoningLevel,
                             AccessMode accessMode, CollaborationMode collaborationMode) {
        /** 只接受稳定选择器和协议闭集；模型能力存在性由首轮 runtime resolver 在 admission 前验证。 */
        public CreatePreferences {
            if (providerId == null || !providerId.startsWith("provider_")
                    || providerId.length() > 128
                    || !providerId.substring("provider_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                    || modelId == null || !modelId.startsWith("model_") || modelId.length() > 128
                    || !modelId.substring("model_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid side task model selection");
            }
            if (reasoningLevel != null
                    && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
                throw new IllegalArgumentException("invalid side task reasoning level");
            }
            Objects.requireNonNull(accessMode, "accessMode");
            Objects.requireNonNull(collaborationMode, "collaborationMode");
        }
    }

    /** Subagent 必须绑定创建它的父 Turn，并显式传入 brief，默认不继承父 Transcript。 */
    record SpawnCommand(String parentThreadId, String parentTurnId, String taskName,
                        UserContent brief, Duration deadline, ThreadPreferences frozenPreferences,
                        JsonObject capabilityCeiling) {
        /** 父 Turn 是 ATTACHED 生命周期的取消传播根，不能缺省。 */
        public SpawnCommand {
            Objects.requireNonNull(parentThreadId, "parentThreadId");
            if (parentTurnId == null || !parentTurnId.startsWith("turn_")
                    || taskName == null || taskName.isBlank()) {
                throw new IllegalArgumentException("invalid subagent command");
            }
            Objects.requireNonNull(brief, "brief");
            Objects.requireNonNull(deadline, "deadline");
            if (deadline.toMillis() < 1_000 || deadline.toMillis() > 86_400_000) {
                throw new IllegalArgumentException("invalid task deadline");
            }
            Objects.requireNonNull(frozenPreferences, "frozenPreferences");
            Objects.requireNonNull(capabilityCeiling, "capabilityCeiling");
        }
    }

    /** QueueOnly 和 Follow-up 共用显式发送方、目标、内容与幂等键。 */
    record MessageCommand(String senderThreadId, String targetThreadId, UserContent content,
                          String idempotencyKey, String causalTurnId) {
        /** 用户在侧聊自身提交输入时身份相同；是否允许自投递由带消息种类的 Mailbox 边界裁决。 */
        public MessageCommand {
            Objects.requireNonNull(senderThreadId, "senderThreadId");
            Objects.requireNonNull(targetThreadId, "targetThreadId");
            Objects.requireNonNull(content, "content");
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("invalid task message command");
            }
        }
    }

    /** Follow-up 额外携带 Task projection CAS，禁止在已变化状态上静默启动新 Turn。 */
    record FollowUpCommand(MessageCommand message, long expectedTaskRevision, Duration deadline) {
        /** revision 与 Deadline 在进入队列前固定。 */
        public FollowUpCommand {
            Objects.requireNonNull(message, "message");
            if (expectedTaskRevision < 0) throw new IllegalArgumentException("invalid task revision");
            Objects.requireNonNull(deadline, "deadline");
            if (deadline.toMillis() < 1_000 || deadline.toMillis() > 86_400_000) {
                throw new IllegalArgumentException("invalid task deadline");
            }
        }
    }

    /** Mailbox 回执只返回稳定身份和 SQLite sequence。 */
    record MessageReceipt(String messageId, long mailboxSequence) {
        /** sequence 为零意味着消息从未提交。 */
        public MessageReceipt {
            if (messageId == null || !messageId.startsWith("msg_") || mailboxSequence < 1) {
                throw new IllegalArgumentException("invalid mailbox receipt");
            }
        }
    }

    /** Observation revision 用于丢帧后触发快照重读。 */
    record Observation(String observationId, String taskThreadId, long revision) {
        /** 句柄只在当前进程有效，但仍使用稳定前缀避免与 Task ID 混用。 */
        public Observation {
            if (observationId == null || !observationId.startsWith("observe_")
                    || taskThreadId == null || !taskThreadId.startsWith("thr_") || revision < 0) {
                throw new IllegalArgumentException("invalid observation");
            }
        }
    }

    /** Wait 只返回发生变化的目标摘要；超时显式区分而不伪造成空完成。 */
    record WaitResult(List<TaskModels.Summary> tasks, boolean timedOut) {
        /** 结果冻结，调用方不能在 Tool 返回后修改结构化内容。 */
        public WaitResult {
            tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        }
    }
}
