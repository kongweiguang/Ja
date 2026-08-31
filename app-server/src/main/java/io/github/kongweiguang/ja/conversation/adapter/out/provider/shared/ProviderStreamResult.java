// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.List;
import java.util.Objects;

/**
 * 将纯状态机的最终结果与待提交语义事件一起返回，状态机本身不调用外部事件接收器。
 */
public record ProviderStreamResult(ModelPort.ModelOutcome outcome, List<ModelPort.ModelEvent> events) {
    /**
     * 冻结事件列表，避免状态机完成后调用方改变提交顺序。
     */
    public ProviderStreamResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    /**
     * 按 Provider 已验证的顺序提交最终事件，所有外部 IO 都留在适配器边界。
     */
    public ModelPort.ModelOutcome emitTo(StreamContext context) {
        Objects.requireNonNull(context, "context");
        events.forEach(context::emit);
        return outcome;
    }
}
