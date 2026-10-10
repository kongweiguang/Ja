// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.application.gateway.JaThreadDefinition;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTurnAdmission;
import io.github.kongweiguang.ja.collaboration.domain.model.AdmissionReceipt;
import io.github.kongweiguang.ja.collaboration.domain.model.CollaborationPreferences;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.collaboration.domain.model.TurnAdmission;

import java.util.Objects;

/**
 * conversation 出站类型的唯一 ACL；Task 领域值不再直接承载这些外部类型。
 *
 * <p>此类只做无副作用值转换，真正的持久化和运行时桥接仍由 infrastructure 组合根提供。</p>
 */
public final class ConversationAcl {
    /** 禁止实例化值转换工具。 */
    private ConversationAcl() {
    }

    /**
     * 把 Task 偏好转换为 conversation admission 所需的偏好；不复制未公开配置字段。
     */
    public static ThreadPreferences toConversation(CollaborationPreferences preferences) {
        CollaborationPreferences value = Objects.requireNonNull(preferences, "preferences");
        return new ThreadPreferences(value.providerId(), value.modelId(), value.reasoningLevel(),
                io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode.valueOf(
                        value.accessMode().name()),
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.valueOf(
                        value.collaborationMode().name()),
                ThreadPreferences.TitleSource.valueOf(value.titleSource().name()));
    }

    /**
     * 从已读取的 conversation 偏好建立 Task 冻结值，避免 Task 再持有原领域对象引用。
     */
    public static CollaborationPreferences fromConversation(ThreadPreferences preferences) {
        ThreadPreferences value = Objects.requireNonNull(preferences, "preferences");
        return new CollaborationPreferences(value.providerId(), value.modelId(), value.reasoningLevel(),
                CollaborationPreferences.AccessMode.valueOf(value.accessMode().name()),
                CollaborationPreferences.CollaborationMode.valueOf(value.collaborationMode().name()),
                CollaborationPreferences.TitleSource.valueOf(value.titleSource().name()));
    }

    /**
     * 将领域 Thread 定义封装成 conversation 的出站创建输入，保留创建时的全部版本事实。
     */
    public static JaThreadDefinition toConversation(ThreadDefinition definition) {
        ThreadDefinition value = Objects.requireNonNull(definition, "definition");
        return new JaThreadDefinition(value.threadId(), value.workspaceId(), value.title(),
                toConversation(value.preferences()), value.createdAt());
    }

    /**
     * 将 conversation admission 回执投影为 Collaboration 自有版本值，避免领域保存外部 record。
     */
    public static AdmissionReceipt receipt(io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionReceipt receipt) {
        io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionReceipt value =
                Objects.requireNonNull(receipt, "receipt");
        return new AdmissionReceipt(value.threadId(), value.turnId(), value.threadRevision(),
                value.turnMutationVersion(), value.provisionalTitle());
    }

    /**
     * 将 conversation Thread 投影压缩为 Task 详情所需的自有快照，避免跨域返回原 record。
     */
    public static ThreadSnapshot snapshot(ThreadSummary summary) {
        ThreadSummary value = Objects.requireNonNull(summary, "summary");
        return new ThreadSnapshot(value.threadId(), value.workspaceId(), value.title(), value.workspaceKind(),
                fromConversation(value.preferences()),
                ThreadSnapshot.Status.valueOf(value.status().name()), value.pinned(), value.latestTurnStatus(),
                value.latestTurnSeen(), value.revision(), value.createdAt(), value.updatedAt(), value.latestTurnId());
    }

    /**
     * RPC 仍以 conversation 投影输出时，在 transport 边界临时恢复其类型，不让 Task 领域携带该依赖。
     */
    public static ThreadSummary toConversation(ThreadSnapshot snapshot) {
        ThreadSnapshot value = Objects.requireNonNull(snapshot, "snapshot");
        return new ThreadSummary(value.threadId(), value.workspaceId(), value.title(), value.workspaceKind(),
                toConversation(value.preferences()),
                ThreadSummary.Status.valueOf(value.status().name()), value.pinned(), value.latestTurnStatus(),
                value.latestTurnSeen(), value.revision(), value.createdAt(), value.updatedAt(), value.latestTurnId());
    }

    /**
     * 将 conversation 的 Turn 输入复制为领域 admission，保留 Core 已确认的 message identity。
     */
    public static TurnAdmission admission(JaTurnAdmission admission) {
        JaTurnAdmission value = Objects.requireNonNull(admission, "admission");
        ModelMessage message = Objects.requireNonNull(value.userMessage(), "userMessage");
        return new TurnAdmission(value.threadId(), value.turnId(), value.messageId(), message,
                value.attachmentIds(), value.expectedThreadRevision(), value.requestedAt(), value.initialExecution());
    }
}

