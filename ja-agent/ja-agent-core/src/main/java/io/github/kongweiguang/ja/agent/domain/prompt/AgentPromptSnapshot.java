// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.prompt;

import java.util.Objects;

/**
 * 一次模型调用使用的完整动态 System、修订与固定预算事实。
 *
 * @param systemPrompt 系统提示词
 * @param revision 当前修订版本
 * @param systemTokens 系统提示词占用的 Token 数
 */
public record AgentPromptSnapshot(String systemPrompt,
                                  String revision,
                                  long systemTokens) {
    /**
     * 在快照越过 Provider 端口前冻结全部字符串与计量，避免流式请求期间观察到 Session 漂移；
     * 空 system prompt 合法，表示宿主未配置 Persona，不能由 Core 擅自补造。
     */
    public AgentPromptSnapshot {
        systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        revision = Objects.requireNonNull(revision, "revision");
        if (!revision.startsWith("prompt_") || systemTokens < 0) {
            throw new IllegalArgumentException("invalid Agent prompt snapshot");
        }
    }
}
