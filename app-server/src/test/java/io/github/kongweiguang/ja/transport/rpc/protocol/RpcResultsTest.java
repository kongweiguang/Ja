// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import java.util.List;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 锁定历史快照与实时审批事件共享同一小写 wire 词汇表。 */
final class RpcResultsTest {
    /** SQLite 枚举使用大写，thread/read 必须在唯一 RPC 边界归一化而不能泄露存储格式。 */
    @Test
    void mapsPersistedApprovalDecisionToWireVocabulary() {
        ThreadSnapshot.ApprovalItem item = new ThreadSnapshot.ApprovalItem(
                "item_fixture", Instant.EPOCH, "appr_fixture", "turn_fixture",
                "call_fixture", "shell", "需要执行命令", "APPROVE", Instant.EPOCH);

        assertEquals("approve", RpcResults.snapshotItem(new ObjectMapper(), item).path("decision").textValue());
    }

    /** 平坦 item 必须显式携带所属 Turn，避免客户端把第二轮消息错误并入第一轮。 */
    @Test
    void mapsEveryFlatItemToItsPersistedTurnIdentity() {
        ObjectMapper mapper = new ObjectMapper();
        ThreadSnapshot.TextItem text = new ThreadSnapshot.TextItem(
                "item_text", Instant.EPOCH, "turn_second", ThreadSnapshot.TextKind.USER_INPUT, "next", null);
        ThreadSnapshot.ToolItem tool = new ThreadSnapshot.ToolItem(
                "item_tool", Instant.EPOCH, "turn_second", ThreadSnapshot.ToolKind.TOOL_CALL,
                "call_fixture", "read_file", presentation(), 1);

        assertEquals("turn_second", RpcResults.snapshotItem(mapper, text).path("turnId").textValue());
        assertEquals("turn_second", RpcResults.snapshotItem(mapper, tool).path("turnId").textValue());
    }

    /** Turn 历史只公开冻结运行事实和稳定错误码，禁止把 Provider 错误正文带回 UI。 */
    @Test
    void mapsFrozenTurnRuntimeWithoutErrorMessage() {
        TurnRuntimeSnapshot runtime = new TurnRuntimeSnapshot(
                "provider_openai", "model_gpt", "openai", "openai_responses", "gpt-5.6-sol",
                "medium", AccessMode.FULL_ACCESS, "cfg_0123456789abcdef");
        ThreadSnapshot.Turn turn = new ThreadSnapshot.Turn(
                "turn_failed", "failed", runtime, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                "INTERNAL_ERROR", null);

        var result = RpcResults.snapshotTurn(new ObjectMapper(), turn);

        assertEquals("INTERNAL_ERROR", result.path("errorCode").textValue());
        assertEquals("openai_responses", result.path("runtime").path("api").textValue());
        assertEquals("medium", result.path("runtime").path("reasoningLevel").textValue());
        assertFalse(result.has("errorMessage"));
    }

    /** 构造历史 Wire 测试所需的最小安全展示 DTO。 */
    private static ToolPresentation presentation() {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", ToolPresentation.Status.SUCCESS,
                null, "ok", List.of("a.txt"), null, null, null, null, null, 1L, false, null);
    }
}
