// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.concurrent.CompletionStage;

/**
 * Ja 入站传输事件端口；实现可用异步写入阶段对调用方施加背压。
 */
@FunctionalInterface
public interface ContextCompactionEventSink {
    /**
     * 发布一个已持久提交的压缩生命周期事件。
     */
    CompletionStage<Void> publish(ContextCompactionEvent event);
}
