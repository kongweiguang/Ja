// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.acl;

import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionGateway;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionRegistration;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;

import java.util.Objects;
import java.util.Optional;

/**
 * Task admission 的 ACL 适配器；把 Conversation 注册表及事务参与者留在 infrastructure。
 */
public final class TaskAdmissionGatewayAdapter implements TaskAdmissionGateway {
    private final TaskRepository tasks;
    private final JaAdmissionParticipants admissions;
    private final ExecutionRecordCodec records;
    private final ExecutionCursorCodec cursors;
    private final ConversationBoundaryAdapter conversation;

    /**
     * 组合根注入 Task 查询、Conversation 临时注册表和两种持久编码器，避免应用服务自行拼装参与者。
     */
    public TaskAdmissionGatewayAdapter(TaskRepository tasks, JaAdmissionParticipants admissions,
                                      ExecutionRecordCodec records, ExecutionCursorCodec cursors) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.records = Objects.requireNonNull(records, "records");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.conversation = new ConversationBoundaryAdapter();
    }

    /**
     * 登记 Child admission；根 Turn 与 Task revision 在同一 facts 快照中冻结。
     */
    @Override
    public TaskAdmissionRegistration registerChild(TaskModels.ChildAdmission child,
                                                   TurnStartRequest request,
                                                   TaskModels.RuntimeIdentity runtimeIdentity,
                                                   SubagentPolicy parentPolicy) {
        TaskModels.ChildAdmission value = Objects.requireNonNull(child, "child");
        TurnStartRequest start = Objects.requireNonNull(request, "request");
        TaskModels.RuntimeIdentity taskIdentity = Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
        SubagentPolicy policy = Objects.requireNonNull(parentPolicy, "parentPolicy");
        validateRuntimeIdentity(value, taskIdentity);
        String rootTurnId = tasks.rootTurnIdForAdmission(value.parentThreadId(), value.originTurnId(), true);
        AgentIdentity identity = JaAdmissionParticipants.identityFor(value.childThread().threadId(), start.turnId());
        AdmissionFacts facts = new AdmissionFacts(identity, start.workspaceId(), TurnOrigin.CHILD_TASK,
                "task/create", start.requestedAt(), null, value.originTurnId(), rootTurnId,
                null, null, null, null, start.expectedThreadRevision(),
                Optional.of(new AdmissionFacts.ThreadInitialization(value.childThread().workspaceId(),
                        value.childThread().title(), conversation.toConversation(value.childThread().preferences()),
                        value.childThread().createdAt())));
        TaskAdmissionTransactionParticipant participant = TaskAdmissionTransactionParticipant.forChild(
                tasks, facts, value, policy, records, cursors);
        TaskCapabilityCeilingPort.RuntimeSnapshot runtimeSnapshot = new TaskCapabilityCeilingPort.RuntimeSnapshot(
                identity, Optional.of(value.contextSeed().permissionCeiling()),
                Optional.of(runtimeIdentity(taskIdentity)), policy);
        return wrap(admissions.register(identity,
                new JaAdmissionParticipants.PreparedAdmission(facts, participant,
                        Optional.of(runtimeSnapshot))));
    }

    /**
     * 登记 Follow-up admission；只把 mailbox、预期 revision 与本次活动摘要送入 ACL。
     */
    @Override
    public TaskAdmissionRegistration registerFollowUp(TaskModels.MailboxEnvelope envelope,
                                                      long expectedTaskRevision,
                                                      TurnStartRequest request,
                                                      String activityId,
                                                      JsonObject activitySummary) {
        TaskModels.MailboxEnvelope message = Objects.requireNonNull(envelope, "envelope");
        TurnStartRequest start = Objects.requireNonNull(request, "request");
        JsonObject summary = Objects.requireNonNull(activitySummary, "activitySummary");
        String parentTurnId = message.causalTurnId();
        String rootTurnId = parentTurnId == null ? null
                : tasks.rootTurnIdForAdmission(null, parentTurnId, false);
        AgentIdentity identity = JaAdmissionParticipants.identityFor(message.targetThreadId(), start.turnId());
        AdmissionFacts facts = new AdmissionFacts(identity, start.workspaceId(), TurnOrigin.CHILD_TASK,
                "task/followup", start.requestedAt(), null, parentTurnId, rootTurnId,
                null, null, null, null, start.expectedThreadRevision(), Optional.empty());
        TaskAdmissionTransactionParticipant.FollowUpDraft draft =
                new TaskAdmissionTransactionParticipant.FollowUpDraft(message, expectedTaskRevision,
                        Objects.requireNonNull(activityId, "activityId"), summary, start.initialContextSeed());
        TaskAdmissionTransactionParticipant participant = TaskAdmissionTransactionParticipant.forFollowUp(
                tasks, facts, draft, records, cursors);
        return wrap(admissions.register(identity,
                new JaAdmissionParticipants.PreparedAdmission(facts, participant, Optional.empty())));
    }

    /**
     * Admission 前校验 snapshot 身份与 Child draft 使用同一实体和 Kind，避免不同任务的事实被绑在一起。
     */
    private static void validateRuntimeIdentity(TaskModels.ChildAdmission child,
                                                TaskModels.RuntimeIdentity identity) {
        if (!child.childThread().threadId().equals(identity.taskThreadId())
            || !child.parentThreadId().equals(identity.parentThreadId())
            || !child.taskName().equals(identity.taskName())
            || child.kind() != identity.kind()) {
            throw new IllegalArgumentException("Child runtime identity does not match admission");
        }
    }

    /**
     * 将 Collaboration lineage 投影到 Conversation runtime 端口拥有的稳定 DTO。
     */
    private static TaskCapabilityCeilingPort.RuntimeIdentity runtimeIdentity(
            TaskModels.RuntimeIdentity identity) {
        return new TaskCapabilityCeilingPort.RuntimeIdentity(identity.taskThreadId(), identity.parentThreadId(),
                identity.rootThreadId(), identity.taskName(), identity.parentTaskName(), identity.rootTaskName(),
                identity.kind() == TaskModels.Kind.SIDE_TASK
                        ? TaskCapabilityCeilingPort.Kind.SIDE_TASK : TaskCapabilityCeilingPort.Kind.SUBAGENT);
    }

    /**
     * 统一把 Conversation Registration 包装成本域句柄，避免 application 看到外域注册类型。
     */
    private static TaskAdmissionRegistration wrap(JaAdmissionParticipants.Registration registration) {
        return registration::close;
    }
}
