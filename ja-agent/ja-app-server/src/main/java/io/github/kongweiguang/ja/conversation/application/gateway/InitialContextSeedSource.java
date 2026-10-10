// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;

import java.util.Optional;

/**
 * 提供 Thread 首次准入时已冻结的背景上下文，调用方不能从当前父会话重新拼装历史。
 */
public interface InitialContextSeedSource {
    /**
     * 只读取创建时持久化的 seed；普通 Thread 与已消费的 seed 返回空值。
     */
    Optional<InitialContextSeed> findForFirstTurn(String threadId);
}
