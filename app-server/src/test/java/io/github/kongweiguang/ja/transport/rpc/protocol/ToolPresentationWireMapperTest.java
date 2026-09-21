// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** JA-RPC 问答展示只发布用户可见文案，不让模型使用的原始答案越过边界。 */
class ToolPresentationWireMapperTest {
    /** 多题、多选与跳过保持结构化，Renderer 无需解析摘要或 optionId。 */
    @Test
    void mapsStructuredInteractionAnswers() {
        ToolPresentation presentation = new ToolPresentation(ToolPresentation.Kind.READ, "询问用户",
                ToolPresentation.Status.SUCCESS, null, null, "已回答 2 个问题",
                List.of(
                        new ToolPresentation.InteractionAnswerView("如何同步？",
                                List.of("保留本地改动", "合并远程提交"), false),
                        new ToolPresentation.InteractionAnswerView("是否推送？", List.of(), true)),
                List.of(), null, null, null, null, null, 0L, false, null);

        var wire = new ToolPresentationWireMapper(new ObjectMapper()).map(presentation);

        assertEquals("如何同步？", wire.path("interactionAnswers").get(0).path("question").textValue());
        assertEquals("合并远程提交",
                wire.path("interactionAnswers").get(0).path("answers").get(1).textValue());
        assertEquals(true, wire.path("interactionAnswers").get(1).path("skipped").booleanValue());
        assertFalse(wire.has("outputPreview"));
    }
}
