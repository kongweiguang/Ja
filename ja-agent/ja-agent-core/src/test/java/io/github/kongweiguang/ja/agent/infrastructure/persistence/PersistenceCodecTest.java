// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Core 消息持久化边界，不把请求期载荷误存为历史事实。 */
final class PersistenceCodecTest {
    /** 损坏消息不能被强制转换成成功的 Tool 结果，也不能接受重复键或残缺正文。 */
    @Test
    void rejectsAmbiguousMessageBlocks() {
        PersistenceCodec codec = new PersistenceCodec(new ObjectMapper());
        for (String payload : List.of("", "null", "[]",
                "[{\"kind\":\"text\",\"text\":\"first\",\"text\":\"second\"}]",
                "[{\"kind\":\"text\",\"text\":\"valid\"}] {}",
                "[{\"kind\":\"text\",\"text\":\"valid\",\"extra\":true}]",
                "[{\"kind\":\"tool_result\",\"callId\":\"call_123\",\"content\":\"failed\",\"error\":\"true\"}]")) {
            StorageException failure = assertThrows(StorageException.class,
                    () -> codec.readMessage(ModelRole.TOOL.name(), payload), payload);
            assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        }
    }

    /** 检查点用量不能把缺字段、字符串或小数恢复成可信的整数凭证。 */
    @Test
    void rejectsIncompleteOrCoercedCheckpointValues() {
        PersistenceCodec codec = new PersistenceCodec(new ObjectMapper());
        assertEquals(CheckpointUsage.none(), codec.readCheckpointUsage(codec.writeCheckpointUsage(CheckpointUsage.none())));
        for (String payload : List.of("null", "{}",
                "{\"inputTokens\":\"1\",\"outputTokens\":0,\"totalTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0}",
                "{\"inputTokens\":1.5,\"outputTokens\":0,\"totalTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0}")) {
            assertThrows(StorageException.class, () -> codec.readCheckpointUsage(payload), payload);
        }
        assertEquals(SummaryDocument.empty(), codec.readSummary(codec.writeSummary(SummaryDocument.empty())));
        assertThrows(StorageException.class, () -> codec.readSummary("null"));
        assertThrows(StorageException.class, () -> codec.readRetainedSplit(""));
    }

    /** Codec 在构造时冻结精度规则，宿主随后修改 mapper 不得改变已授权的工具参数。 */
    @Test
    void freezesExactToolArguments() {
        ObjectMapper mapper = new ObjectMapper();
        PersistenceCodec codec = new PersistenceCodec(mapper);
        JsonObject arguments = new JsonObject(Map.of("amount",
                new JsonNumber(new BigDecimal("12345678901234567890.123456789000"))));
        ModelMessage message = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new ToolCallContent("call_decimal", "transfer", arguments)));
        mapper.configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS, false);

        assertEquals(message, codec.readMessage(ModelRole.ASSISTANT.name(), codec.writeMessage(message)));
    }

    /** 原生附件字节只能存在于冻结 Provider 请求，消息历史必须在写入前明确拒绝。 */
    @Test
    void rejectsRequestScopedNativeAttachmentPayload() {
        ModelMessage message = new ModelMessage(ModelRole.USER, List.of(
                new NativeAttachmentContent("att_12345678", NativeAttachmentContent.Kind.IMAGE,
                        "image.png", "image/png", 1, "AQ==")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PersistenceCodec(new ObjectMapper()).writeMessage(message));

        assertEquals("native attachment payload is request-scoped and cannot be persisted",
                failure.getMessage());
    }

    /** Provider 参数解析错误必须与 assistant 调用一同回读，恢复时不能把坏输入送入执行器。 */
    @Test
    void persistsProviderToolArgumentFailureMarker() {
        PersistenceCodec codec = new PersistenceCodec(new ObjectMapper());
        ModelMessage source = new ModelMessage(ModelRole.ASSISTANT, List.of(
                new ToolCallContent("call_bad_json", "write", JsonObjects.builder().build(),
                        "TOOL_ARGUMENTS_INVALID")));

        ModelMessage restored = codec.readMessage(ModelRole.ASSISTANT.name(), codec.writeMessage(source));

        ToolCallContent call = (ToolCallContent) restored.content().getFirst();
        assertEquals("TOOL_ARGUMENTS_INVALID", call.preflightFailureCode());
        assertTrue(call.arguments().isEmpty());
    }

    /** 消息正文大小不由 Codec 静默截断；上下文预算和 Tool 投影负责请求侧收缩。 */
    @Test
    void roundTripsLongAssistantAndToolContentInFull() {
        PersistenceCodec codec = new PersistenceCodec(new ObjectMapper());
        String body = "完整输出".repeat(1_050_000);
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent(body)));
        ModelMessage tool = new ModelMessage(ModelRole.TOOL,
                List.of(new ToolResultContent("call_long", body, false)));

        ModelMessage restoredAssistant = codec.readMessage(
                ModelRole.ASSISTANT.name(), codec.writeMessage(assistant));
        ModelMessage restoredTool = codec.readMessage(ModelRole.TOOL.name(), codec.writeMessage(tool));

        assertEquals(body, ((TextContent) restoredAssistant.content().getFirst()).text());
        assertEquals(body, ((ToolResultContent) restoredTool.content().getFirst()).content());
    }
}
