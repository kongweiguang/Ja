// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.prompt;

import java.util.Objects;

/** 一次模型调用使用的完整动态 System、修订与固定预算事实。 */
public record AgentPromptSnapshot(String systemPrompt, String revision, long systemTokens) {
    /**
     * 在快照越过 Provider 端口前冻结全部字符串与计量，避免流式请求期间观察到 Session 漂移。
     */
    public AgentPromptSnapshot {
        systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        revision = Objects.requireNonNull(revision, "revision");
        if (systemPrompt.isBlank() || !revision.startsWith("prompt_") || systemTokens < 1) {
            throw new IllegalArgumentException("invalid Agent prompt snapshot");
        }
    }
}
