// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 客户端操作回执必须保持入队结果与审批结果互斥，并暴露稳定 operationKey 身份。
 */
final class ClientOperationReceiptTest {
    private static final String OPERATION_KEY = "op_00000000000000000000000000000001";

    /**
     * 普通 Turn 准入只允许排队结果，审批字段必须同时缺省。
     */
    @Test
    void acceptsQueuedOperationWithoutApprovalResult() {
        assertDoesNotThrow(() -> receipt("turn/start", true, null, null));
    }

    /**
     * 审批提交必须完整携带决定，并且不能同时声称已进入 Turn 队列。
     */
    @Test
    void acceptsApprovalOperationWithDecision() {
        assertDoesNotThrow(() -> receipt("approval/respond", false, "approval_1", "approve"));
    }

    /**
     * 回执直接公开统一 operationKey 分量，调用方无需保留 clientOperationId 别名。
     */
    @Test
    void exposesOperationKey() {
        assertEquals(OPERATION_KEY, receipt("turn/start", true, null, null).operationKey());
    }

    /**
     * 拒绝只有审批标识的回执，避免非审批方法携带孤立审批身份。
     */
    @Test
    void rejectsQueuedOperationWithApprovalIdOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> receipt("turn/start", true, "approval_1", null));
    }

    /**
     * 拒绝只有审批决定的回执，避免非审批方法携带无法归属的决定。
     */
    @Test
    void rejectsQueuedOperationWithDecisionOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> receipt("turn/start", true, null, "approve"));
    }

    /**
     * 拒绝同时具备审批字段的非审批回执，确保结果形态由方法身份唯一决定。
     */
    @Test
    void rejectsQueuedOperationWithCompleteApprovalResult() {
        assertThrows(IllegalArgumentException.class,
                () -> receipt("turn/start", true, "approval_1", "approve"));
    }

    /**
     * 固定其它字段为有效值，只变化审批字段组合以隔离被测闭集约束。
     */
    private static ClientOperationReceipt receipt(String method, boolean queued, String approvalId,
                                                  String decision) {
        return new ClientOperationReceipt(OPERATION_KEY, method, "0".repeat(64),
                "thread_1", "turn_1", 0, queued, approvalId, decision);
    }
}
