// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

/** 用户明确提交一次带 revision CAS 的 RETRY 或 SKIP 裁决。
 *
 * @param threadId 恢复项所属的 Thread 标识
 * @param turnId 恢复项所属的原始 Turn 标识
 * @param recoveryId 待核实副作用的恢复项身份
 * @param callId 需要核实结果的原始工具调用身份
 * @param expectedRecoveryRevision 用户当前观察到的恢复项版本
 * @param operationKey 本次用户裁决的稳定重试键
 * @param decision 用户明确选择的 RETRY 或 SKIP 动作
 */
public record RecoveryResolveCommand(String threadId,
                                     String turnId,
                                     String recoveryId,
                                     String callId,
                                     long expectedRecoveryRevision,
                                     String operationKey,
                                     String decision) {
    /** 请求不承载用户身份；Core 会在服务端 owner identity 下复核恢复项。 */
    public RecoveryResolveCommand {
        new TurnCommand(threadId, turnId);
        if (recoveryId == null || callId == null || operationKey == null || decision == null
            || expectedRecoveryRevision < 1) {
            throw new IllegalArgumentException("invalid recovery command");
        }
        if (!decision.equals("RETRY") && !decision.equals("SKIP")) {
            throw new IllegalArgumentException("invalid recovery decision");
        }
    }
}
