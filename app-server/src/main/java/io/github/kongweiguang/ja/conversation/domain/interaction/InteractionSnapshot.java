// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import java.util.Objects;
import java.util.Optional;

/** Read/事件对账使用的完整投影；无 requestId 的 read 返回 Thread 当前活动请求。 */
public record InteractionSnapshot(String threadId, long eventSequence,
                                 Optional<InteractionRequest> request,
                                 Optional<InteractionDraft> draft,
                                 InteractionResumeState resumeState) {
    /** 旧的领域构造便于只关心问答内容的测试；生产 Repository 会传入同事务读取的 Turn 状态。 */
    public InteractionSnapshot(String threadId, long eventSequence,
                               Optional<InteractionRequest> request,
                               Optional<InteractionDraft> draft) {
        this(threadId, eventSequence, request, draft, InteractionResumeState.from(request, null));
    }

    /** 快照字段全部来自同一事务，resumeState 不允许由客户端或 UI 本地状态推断。 */
    public InteractionSnapshot {
        if (threadId == null || eventSequence < 0) throw new IllegalArgumentException("invalid interaction snapshot");
        request = Objects.requireNonNull(request, "request");
        draft = Objects.requireNonNull(draft, "draft");
        resumeState = Objects.requireNonNull(resumeState, "resumeState");
    }
}
