// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import java.util.concurrent.CompletionStage;

/**
 * 为模型流事件提供有序背压边界，Provider 不得绕过该边界直接发布 Turn 事件。
 */
@FunctionalInterface
public interface ModelEventSink {
    /**
     * 接收一个已规范化模型事件，完成阶段决定 Provider 是否可以继续读取响应体。
     */
    CompletionStage<Void> onEvent(ModelPort.ModelEvent event);
}
