// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 Operation JSON 的无反射 round-trip 与 fail-closed 语义。 */
final class TurnExecutionStateCodecTest {
    private final TurnExecutionStateCodec codec = new TurnExecutionStateCodec(new ObjectMapper());

    /** READY 的三个 Summary 子阶段、Provider intent 与 Tool cursor 都必须完整无损恢复。 */
    @Test
    void roundTripsEveryExecutionVariant() {
        TurnExecutionState.Common common = new TurnExecutionState.Common(2, 3, 4, "checkpoint_1",
                List.of(new TurnExecutionState.ActiveSkill("skill_java")),
                Instant.parse("2026-09-01T00:17:00Z"),
                io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.GOAL_CONTINUATION);
        TurnExecutionState.Ready ready = new TurnExecutionState.Ready(common,
                TurnExecutionState.Next.ASSISTANT, null);
        TurnExecutionState.KnownUsage usage = new TurnExecutionState.KnownUsage(10, 2, 12, 1, 0);
        TurnExecutionState.SummaryProgress candidate = TurnExecutionState.SummaryProgress.candidate(
                "{}", 5, 2, "c".repeat(64), usage);
        TurnExecutionState.SummaryProgress repair = new TurnExecutionState.SummaryProgress(
                "{}", 5, 2, "c".repeat(64), TurnExecutionState.SummaryStage.REPAIR,
                3, 7, List.of("EMPTY_DOCUMENT"), "d".repeat(64), usage);
        TurnExecutionState.SummaryProgress fallback = new TurnExecutionState.SummaryProgress(
                "{}", 5, 2, "c".repeat(64), TurnExecutionState.SummaryStage.FALLBACK_PENDING,
                3, 7, List.of("OUTPUT_BUDGET_EXCEEDED"), "d".repeat(64), usage);
        List<TurnExecutionState> values = List.of(ready,
                new TurnExecutionState.Ready(common, TurnExecutionState.Next.SUMMARY, candidate),
                new TurnExecutionState.Ready(common, TurnExecutionState.Next.SUMMARY, repair),
                new TurnExecutionState.Ready(common, TurnExecutionState.Next.SUMMARY, fallback),
                new TurnExecutionState.ProviderPending(common, "request_1", "item_1",
                        TurnExecutionState.ProviderPurpose.ASSISTANT, profile(), "b".repeat(64), ready),
                new TurnExecutionState.Tools(common, "batch_2", "item_2", 5, 7, 6));

        values.forEach(value -> assertEquals(value, codec.read(codec.write(value))));
    }

    /** 未知字段和非当前 schema 都不能被 runtime 猜测读取。 */
    @Test
    void rejectsUnknownFieldsAndVersions() {
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                null, List.of(), Instant.parse("2026-09-01T00:01:00Z"),
                io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER);
        String valid = codec.write(new TurnExecutionState.Ready(
                common, TurnExecutionState.Next.ASSISTANT, null));

        assertThrows(StorageException.class, () -> codec.read(valid.replaceFirst("\\{", "{\"extra\":1,")));
        assertThrows(StorageException.class, () -> codec.read(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    }

    /** ProviderPending 冻结完整非敏感请求画像，恢复时不依赖可变的 Turn 级 runtime。 */
    private static ProviderRequestProfile profile() {
        return new ProviderRequestProfile("provider_test", "model_test", "openai_responses",
                "gpt-test", "medium", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.PLAN, "cfg_test", "prompt_1", "a".repeat(64), 100_000, 8_192);
    }
}
