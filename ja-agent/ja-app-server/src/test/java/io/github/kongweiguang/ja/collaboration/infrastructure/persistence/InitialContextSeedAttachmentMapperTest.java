// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fork Seed 的附件映射必须同时覆盖用户附件块与历史 Tool 图片引用。
 */
final class InitialContextSeedAttachmentMapperTest {
    /**
     * 顺序去重并重映射两种附件载体，同时保留 Seed 消息身份和媒体展示元数据。
     */
    @Test
    void remapsAttachmentBlocksAndToolMediaWithoutChangingSeedOrder() {
        InitialContextSeed seed = seed();

        assertEquals(List.of("source_input", "source_image"),
                InitialContextSeedAttachmentMapper.attachmentIds(seed));
        InitialContextSeed copied = InitialContextSeedAttachmentMapper.remap(seed,
                Map.of("source_input", "child_input", "source_image", "child_image"));

        assertEquals(seed.sourceIdentity(), copied.sourceIdentity());
        assertEquals(seed.sourceOrdinal(), copied.sourceOrdinal());
        ContextMessage.AttachmentBlock attachment = (ContextMessage.AttachmentBlock)
                copied.messages().getFirst().blocks().getFirst();
        ContextMessage.ToolResultBlock result = (ContextMessage.ToolResultBlock)
                copied.messages().getLast().blocks().getFirst();
        assertEquals("child_input", attachment.attachmentId());
        assertEquals("child_image", result.output().media().getFirst().attachmentId());
        assertEquals("image/png", result.output().media().getFirst().mediaType());
    }

    /**
     * 不完整授权映射必须失败，不能让来源 Thread 附件句柄穿过 Fork。
     */
    @Test
    void missingSourceMappingFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> InitialContextSeedAttachmentMapper.remap(seed(), Map.of("source_input", "child_input")));
    }

    /** 构造同时含用户附件与历史图片的最小可配对 Seed。 */
    private static InitialContextSeed seed() {
        List<ContextMessage> messages = List.of(
                new ContextMessage("message_user", "turn_parent", 1, ContextMessage.Role.USER,
                        List.of(new ContextMessage.AttachmentBlock("source_input")), 2),
                new ContextMessage("message_call", "turn_parent", 2, ContextMessage.Role.ASSISTANT,
                        List.of(new ContextMessage.ToolCallBlock("call_view", "view_image", "{}")), 2),
                new ContextMessage("message_result", "turn_parent", 3, ContextMessage.Role.TOOL,
                        List.of(new ContextMessage.ToolResultBlock("call_view", "view_image",
                                ContextMessage.ToolOutput.full("Image inspected", null, null, null,
                                        List.of(new ToolMediaReference("source_image", "image/png", "screen.png", 128))))), 2));
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                "tenant_parent", "thread_parent", "turn_parent"), 3, messages, Optional.empty());
    }
}
