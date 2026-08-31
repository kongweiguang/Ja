// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import java.util.concurrent.CompletionStage;

/**
 * 接收独立于 Turn 终态的 Thread 元数据通知，生产传输必须保持同一连接内的发布顺序。
 */
@FunctionalInterface
public interface ThreadMetadataEventSink {
    /** 标题事务提交后发布权威 Thread 投影；失败不能回滚已经提交的标题。 */
    CompletionStage<Void> publish(ThreadMetadataEvent event);
}
