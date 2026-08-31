// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import java.util.concurrent.CompletionStage;

/** 为 Thread 级压缩生命周期提供有序且可施加背压的发布端口。 */
@FunctionalInterface
public interface ContextCompactionEventSink {
    /** 发布单个生命周期事件；完成阶段失败会关闭当前压缩操作。 */
    CompletionStage<Void> publish(ContextCompactionEvent event);
}
