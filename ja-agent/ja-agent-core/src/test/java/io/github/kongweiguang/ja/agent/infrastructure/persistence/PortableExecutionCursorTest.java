// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Common;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.KnownUsage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.ProviderPending;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Ready;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.SummaryProgress;

/** 保证恢复游标独立于 Ja 来源和路径，并且损坏状态不能自动退回重新执行。 */
class PortableExecutionCursorTest {
    private final TurnExecutionStateCodec codec = new TurnExecutionStateCodec(new ObjectMapper());

    /** 业务自定义身份可直接恢复，无需伪造 Ja 的 request/item/batch 前缀。 */
    @Test
    void restoresPortableStatesWithoutJaMetadata() {
        Common common = common();
        Ready ready = new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
        ModelRequestIdentity model = new ModelRequestIdentity("provider", "model", "chat", "upstream", null, null, 8192, 1024, JsonObject.empty());
        List<TurnExecutionState> states = List.of(ready,
                new TurnExecutionState.ProviderPending(common, "request", "message", TurnExecutionState.ProviderPurpose.ASSISTANT, model, "a".repeat(64), ready),
                new TurnExecutionState.Tools(common, "batch", "message", 0, 2, 1));
        for (TurnExecutionState state : states) {
            JsonObject cursor = codec.write(state);
            String json = io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory.standard().encode(cursor);
            assertFalse(json.contains("origin"));
            assertFalse(json.contains("workspace"));
            assertFalse(json.contains("accessMode"));
            assertFalse(json.contains("deadlineAt"));
            assertFalse(json.contains("Budget"));
            assertEquals(state, codec.read(codec.schemaVersion(), cursor));
        }
    }

    /** 验证摘要各持久阶段和零输出预算请求均可原样恢复，不把已删除的 Ja 游标字段带回 Core。 */
    @Test
    void roundTripsSummaryStagesAndZeroOutputIdentity() {
        Common common = common();
        KnownUsage usage = new TurnExecutionState.KnownUsage(10, 2, 12, 1, 0);
        SummaryProgress candidate = TurnExecutionState.SummaryProgress.candidate("{}", 5, 2, "c".repeat(64), usage);
        SummaryProgress repair = new TurnExecutionState.SummaryProgress("{}", 5, 2, "c".repeat(64),
                TurnExecutionState.SummaryStage.REPAIR, 3, 7, List.of("EMPTY_DOCUMENT"), "d".repeat(64), usage);
        SummaryProgress failed = new TurnExecutionState.SummaryProgress("{}", 5, 2, "c".repeat(64),
                TurnExecutionState.SummaryStage.FAILED, 3, 7, List.of("OUTPUT_BUDGET_EXCEEDED"), "e".repeat(64), usage);
        for (SummaryProgress progress : List.of(candidate, repair, failed)) {
            Ready state = new TurnExecutionState.Ready(common, TurnExecutionState.Next.SUMMARY, progress);
            assertEquals(state, codec.read(codec.schemaVersion(), codec.write(state)));
        }

        ModelRequestIdentity zeroOutput = new ModelRequestIdentity("provider", "model", "chat", "upstream",
                null, null, 8192, 0, JsonObject.empty());
        Ready resume = new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
        ProviderPending pending = new TurnExecutionState.ProviderPending(common, "request", "message",
                TurnExecutionState.ProviderPurpose.ASSISTANT, zeroOutput, "f".repeat(64), resume);
        assertEquals(pending, codec.read(codec.schemaVersion(), codec.write(pending)));
    }

    /** 损坏游标若把 Provider 用途与 READY 分支拆开，恢复可能按错误类型结算 UNKNOWN usage。 */
    @Test
    void rejectsProviderPurposeThatDoesNotMatchResumeCursor() {
        Common common = common();
        ModelRequestIdentity profile = new ModelRequestIdentity("provider", "model", "chat", "upstream",
                null, null, 8192, 1024, JsonObject.empty());
        Ready assistantResume = new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
        ProviderPending assistant = new ProviderPending(common, "request", "message",
                TurnExecutionState.ProviderPurpose.ASSISTANT, profile, "a".repeat(64), assistantResume);
        JsonObject assistantAsSummary = PersistenceTestJson.mutate(codec.write(assistant), json ->
                json.replace("\"purpose\":\"ASSISTANT\"", "\"purpose\":\"SUMMARY\""));
        assertThrows(StorageException.class, () -> codec.read(codec.schemaVersion(), assistantAsSummary));

        SummaryProgress summary = SummaryProgress.candidate("{}", 5, 2, "c".repeat(64),
                new KnownUsage(10, 2, 12, 1, 0));
        Ready summaryResume = new TurnExecutionState.Ready(common, TurnExecutionState.Next.SUMMARY, summary);
        ProviderPending pendingSummary = new ProviderPending(common, "summary-request", "summary-message",
                TurnExecutionState.ProviderPurpose.SUMMARY, profile, "b".repeat(64), summaryResume);
        JsonObject summaryAsAssistant = PersistenceTestJson.mutate(codec.write(pendingSummary), json ->
                json.replace("\"next\":\"SUMMARY\"", "\"next\":\"ASSISTANT\""));
        assertThrows(StorageException.class, () -> codec.read(codec.schemaVersion(), summaryAsAssistant));
    }

    /** 格式升级只能由明确迁移处理，不接受旧来源字段、重复键或尾部第二个文档。 */
    @Test
    void rejectsStaleAndAmbiguousCursorFormats() {
        JsonObject cursor = codec.write(new TurnExecutionState.Ready(common(), TurnExecutionState.Next.ASSISTANT, null));
        assertThrows(StorageException.class, () -> codec.read(1, cursor));
        JsonObject staleVersion = PersistenceTestJson.mutate(cursor, json -> json.replace(
                "\"schemaVersion\":" + codec.schemaVersion(),
                "\"schemaVersion\":" + (codec.schemaVersion() - 1)));
        assertThrows(StorageException.class, () -> codec.read(codec.schemaVersion(), staleVersion));
        JsonObject extraField = PersistenceTestJson.mutate(cursor,
                json -> json.replace("\"modelRound\":0", "\"modelRound\":0,\"origin\":\"USER\""));
        assertThrows(StorageException.class, () -> codec.read(codec.schemaVersion(), extraField));
    }

    /** 计数推进仅改变请求 ordinal，不能丢失压缩检查点或活动 Skill 证据。 */
    @Test
    void advancesOnlyTheProviderOrdinal() {
        Ready ready = new TurnExecutionState.Ready(common(), TurnExecutionState.Next.ASSISTANT, null);
        Ready advanced = ready.advanceProviderOrdinal();
        assertEquals(2, advanced.common().nextProviderOrdinal());
        assertEquals(ready.common().modelRound(), advanced.common().modelRound());
        assertEquals(ready.common().activeSkills(), advanced.common().activeSkills());
    }

    /** 超大用量必须按数学和校验，不能整数溢出后误认成合法统计。 */
    @Test
    void rejectsUsageArithmeticOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new TurnExecutionState.KnownUsage(Long.MAX_VALUE, 1, Long.MAX_VALUE, 0, 0));
    }

    /** 初始游标显式从首个 Provider ordinal 开始，不依赖应用默认配置。 */
    private static TurnExecutionState.Common common() {
        return new TurnExecutionState.Common(0, 0, 1, null, List.of());
    }
}
