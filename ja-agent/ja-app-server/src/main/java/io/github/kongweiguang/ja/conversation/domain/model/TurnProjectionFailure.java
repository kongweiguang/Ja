// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * 一次性通知宿主重读权威 Thread 快照；不表示 Turn 失败，也不改变执行状态。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param reason 原因说明
 */
public record TurnProjectionFailure(String threadId,
                                    String turnId,
                                    Reason reason) {
    /**
     * 仅允许稳定、脱敏的投影故障类别跨越 RPC 边界，异常详情留在服务端诊断。
     */
    public TurnProjectionFailure {
        threadId = ContractChecks.identifier(threadId, "threadId");
        turnId = ContractChecks.identifier(turnId, "turnId");
        if (!threadId.startsWith("thr_") || !turnId.startsWith("turn_")) {
            throw new IllegalArgumentException("invalid projection failure identity");
        }
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * 跨端错误码保持闭集，避免把内部异常或 Store 内容暴露给客户端。
     */
    public enum Reason {
        /**
         * 投影失败或草稿失去连续性时请求权威重读；不代表执行失败，未提交正文由后续持久结算补齐。
         */
        PROJECTION_FAILED("projection_failed");

        private final String wireValue;

        /**
         * 固定协议文本，不从异常消息或本地化文案推导原因码。
         */
        Reason(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * 返回唯一 JA-RPC 原因码。
         */
        public String wireValue() {
            return wireValue;
        }
    }
}
