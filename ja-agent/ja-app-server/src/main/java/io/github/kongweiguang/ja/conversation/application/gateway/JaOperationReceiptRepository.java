// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;

import java.util.Optional;

/**
 * Ja RPC 幂等回执的只读查询口；写入只能随 AgentStore 准入或等待结算事务完成。
 */
public interface JaOperationReceiptRepository {
    /**
     * 读取已有客户端操作回执，不根据请求时间或当前 Turn 状态推测提交成功。
     */
    Optional<ClientOperationReceipt> readClientOperation(String operationKey);

    /**
     * 读取输入入队幂等回执，结果只含安全身份和类别，不返回输入正文。
     */
    Optional<InputReceipt> readInputOperation(String operationKey);

    /**
     * 输入回执的最小身份投影；正文始终从 AgentStore 的 queue record 读取。
     *
     * @param operationKey 操作标识
     * @param fingerprint 当前内容或配置的校验指纹
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param inputId 输入标识
     * @param kind 类型分类
     */
    record InputReceipt(String operationKey,
                        String fingerprint,
                        String threadId,
                        String turnId,
                        String inputId,
                        AgentInputQueue.Kind kind) {
    }
}
