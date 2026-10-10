// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 领域值的跨边界不变量测试。
 */
class TaskModelsTest {
    /** 关闭结果必须是完整、唯一且稳定排序的根树，避免调用方猜测或补造清理范围。 */
    @Test
    void sideChatCloseValuesRequireCompleteUniqueRootedThreadSet() {
        TaskModels.SideChatCloseState state = new TaskModels.SideChatCloseState("thr_side", "thr_owner",
                TaskModels.SideChatClosePhase.CLOSING, List.of("thr_side", "thr_child"));
        TaskModels.SideChatCloseReceipt receipt = new TaskModels.SideChatCloseReceipt("thr_side", "thr_owner",
                List.of("thr_side", "thr_child"));

        assertEquals(List.of("thr_child", "thr_side"), state.threadIds());
        assertEquals("thr_owner", state.ownerRootThreadId());
        assertEquals(state.threadIds(), receipt.closedThreadIds());
        assertEquals(state.ownerRootThreadId(), receipt.ownerRootThreadId());
        assertThrows(IllegalArgumentException.class, () -> new TaskModels.SideChatCloseReceipt(
                "thr_side", "thr_owner", List.of("thr_side", "thr_side")));
        assertThrows(IllegalArgumentException.class, () -> new TaskModels.SideChatCloseReceipt(
                "thr_side", "thr_owner", List.of("thr_child")));
        assertThrows(IllegalArgumentException.class, () -> new TaskModels.SideChatCloseReceipt(
                "thr_side", "thr_side", List.of("thr_side")));
        assertThrows(IllegalArgumentException.class, () -> new TaskModels.SideChatCloseReceipt(
                "thr_side", "thr_owner", List.of("thr_owner", "thr_side")));
    }
}
