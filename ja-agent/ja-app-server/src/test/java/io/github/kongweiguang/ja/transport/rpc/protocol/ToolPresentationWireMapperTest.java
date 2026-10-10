// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
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

        ObjectNode wire = new ToolPresentationWireMapper(new ObjectMapper()).map(presentation);

        assertEquals("如何同步？", wire.path("interactionAnswers").get(0).path("question").textValue());
        assertEquals("合并远程提交",
                wire.path("interactionAnswers").get(0).path("answers").get(1).textValue());
        assertEquals(true, wire.path("interactionAnswers").get(1).path("skipped").booleanValue());
        assertFalse(wire.has("outputPreview"));
        assertFalse(wire.has("media"));
    }

    /** 图片在实时和历史共用映射中必须保留身份，但不能公开物理路径或编码正文。 */
    @Test
    void mapsManagedImageReferenceWithoutPixelContent() {
        ToolMediaReference image = new ToolMediaReference(
                "att_01234567-89ab-cdef-0123-456789abcdef", "image/png", "截图.png", 410_043);
        ToolPresentation presentation = new ToolPresentation(ToolPresentation.Kind.READ, "读取图片",
                ToolPresentation.Status.SUCCESS, null, null, null, List.of(), List.of("截图.png"),
                null, null, null, null, null, 0L, false, null, null, List.of(image));

        ObjectNode wire = new ToolPresentationWireMapper(new ObjectMapper()).map(presentation);

        assertEquals(1, wire.path("media").size());
        assertEquals(4, wire.path("media").get(0).size());
        assertEquals(image.attachmentId(), wire.path("media").get(0).path("attachmentId").textValue());
        assertEquals("image/png", wire.path("media").get(0).path("mediaType").textValue());
        assertEquals("截图.png", wire.path("media").get(0).path("displayName").textValue());
        assertEquals(410_043, wire.path("media").get(0).path("sizeBytes").longValue());
    }
}
