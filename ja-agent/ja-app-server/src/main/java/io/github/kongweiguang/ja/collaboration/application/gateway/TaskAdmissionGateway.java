// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;

/**
 * Collaboration 自有 admission 网关；将 Conversation 的事务参与者隐藏在基础设施 ACL 后。
 */
public interface TaskAdmissionGateway {
    /**
     * 为 Child 首轮登记冻结事实，返回值仅承担本次 startChild 调用的作用域清理。
     */
    TaskAdmissionRegistration registerChild(TaskModels.ChildAdmission child, TurnStartRequest request,
                                            TaskModels.RuntimeIdentity runtimeIdentity,
                                            SubagentPolicy parentPolicy);

    /**
     * 为 Follow-up 登记 mailbox 与 Task CAS，确保重试继续复用原幂等事实。
     */
    TaskAdmissionRegistration registerFollowUp(TaskModels.MailboxEnvelope envelope,
                                                long expectedTaskRevision,
                                                TurnStartRequest request,
                                                String activityId,
                                                JsonObject activitySummary);
}
