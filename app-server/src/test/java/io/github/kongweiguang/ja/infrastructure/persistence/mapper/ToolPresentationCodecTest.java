// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Tool 展示持久化必须保留人类可读问答，同时不依赖模型结果或稳定选项 ID。 */
class ToolPresentationCodecTest {
    /** 新问答字段按题目和多选标签往返，JSON 中不应出现内部身份。 */
    @Test
    void roundTripsStructuredInteractionAnswers() {
        ToolPresentationCodec codec = new ToolPresentationCodec(new ObjectMapper());
        ToolPresentation value = new ToolPresentation(ToolPresentation.Kind.READ, "询问用户",
                ToolPresentation.Status.SUCCESS, null, null, "已回答 2 个问题",
                List.of(
                        new ToolPresentation.InteractionAnswerView("如何同步？",
                                List.of("保留本地改动", "合并远程提交"), false),
                        new ToolPresentation.InteractionAnswerView("是否推送？", List.of(), true)),
                List.of(), null, null, null, null, null, 0L, false, null);

        String encoded = codec.write(value);
        ToolPresentation restored = codec.read(encoded);

        assertEquals(value, restored);
        assertFalse(encoded.contains("questionId"));
        assertFalse(encoded.contains("optionId"));
    }

    /** 结构化问答字段出现前的普通 Tool 历史仍应读取为空集合，避免升级破坏真实会话。 */
    @Test
    void readsPresentationWithoutInteractionAnswers() {
        ToolPresentationCodec codec = new ToolPresentationCodec(new ObjectMapper());

        ToolPresentation restored = codec.read("""
                {"kind":"read","title":"读取","status":"success","summary":"完成",\
                "relativePaths":[],"truncated":false}
                """);

        assertEquals(List.of(), restored.interactionAnswers());
    }
}
