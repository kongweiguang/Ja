// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import java.util.List;
import java.util.Objects;

/**
 * 对外队列读模型保留稳定字段，并防止 HTTP 序列化期间被调用方改变快照。
 *
 * @param turnId 队列所属 Turn
 * @param revision 队列整体版本
 * @param entries 当前待处理条目
 */
public record QueueView(String turnId,
                        long revision,
                        List<Entry> entries) {
    /**
     * 一个响应必须对应单一不可变的权威队列快照。
     */
    public QueueView {
        Objects.requireNonNull(turnId, "turnId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * 条目版本独立于队列版本，供客户端以 observed revision 执行 CAS。
     *
     * @param inputId 稳定输入标识
     * @param kind 输入类别
     * @param status 当前处理状态
     * @param inputRevision 条目版本
     * @param content 待处理正文、Skill 与附件元数据
     */
    public record Entry(String inputId,
                        String kind,
                        String status,
                        long inputRevision,
                        InputContentView content) {
        /**
         * 队列输入视图必须保持不可变，且元数据与正文来自同一持久消息。
         */
        public Entry {
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(content, "content");
        }
    }
}
