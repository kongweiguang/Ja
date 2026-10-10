// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 逐帧断言终态前的可见效果，避免完整 SSE 回放掩盖整段正文被缓冲的问题。 */
final class OpenAiResponsesStateTest {

    /** item.done 已关闭输出顺序；缺少可回放载荷不能让后继正文等到 response.completed。 */
    @Test
    void completedSummaryOnlyReasoningReleasesEachTextDeltaBeforeTerminal() throws Exception {
        for (String encrypted : List.of("absent", "empty", "terminal")) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                    URI.create("http://127.0.0.1:9"), ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            OpenAiResponsesState state = new OpenAiResponsesState(configuration);
            ObjectNode reasoning = AbstractStreamingModelAdapter.JSON.createObjectNode()
                    .put("id", "reason_summary_only").put("type", "reasoning");
            reasoning.putArray("summary");
            if ("empty".equals(encrypted)) {
                reasoning.put("encrypted_content", "");
            }
            reduce(state, event("response.created", 0).set("response",
                    AbstractStreamingModelAdapter.JSON.readTree(ModelAdapterTestSupport.openAiResponse(
                            "resp_summary_only_text", "in_progress"))));
            reduce(state, event("response.output_item.added", 1)
                    .put("output_index", 0).set("item", reasoning));
            List<ModelPort.ModelEvent> summary = reduce(state,
                    event("response.reasoning_summary_text.delta", 2)
                            .put("output_index", 0).put("summary_index", 0)
                            .put("item_id", "reason_summary_only").put("delta", "公开摘要"));
            assertEquals("公开摘要", assertInstanceOf(
                    ModelPort.ReasoningSummaryDelta.class, summary.getFirst()).text());
            reduce(state, event("response.reasoning_summary_text.done", 3)
                    .put("output_index", 0).put("summary_index", 0)
                    .put("item_id", "reason_summary_only").put("text", "公开摘要"));
            reasoning.withArray("summary").addObject().put("type", "summary_text").put("text", "公开摘要");
            List<ModelPort.ModelEvent> closed = reduce(state, event("response.output_item.done", 4)
                    .put("output_index", 0).set("item", reasoning));
            assertEquals(1, closed.size());
            ModelPort.ReasoningBlockDeferred reserved = assertInstanceOf(
                    ModelPort.ReasoningBlockDeferred.class, closed.getFirst());
            assertEquals("reasoning_0", reserved.slotId());
            assertNull(reserved.content());

            List<String> fragments = List.of("最终总结：", "逐步显示", "中文与 Emoji 🙂");
            for (int index = 0; index < fragments.size(); index++) {
                List<ModelPort.ModelEvent> visible = reduce(state, event("response.output_text.delta", 5 + index)
                        .put("output_index", 1).put("content_index", 0)
                        .put("item_id", "message_summary_only").put("delta", fragments.get(index)));
                assertEquals(1, visible.size(), "each delta must be visible before response.completed");
                assertEquals(fragments.get(index), assertInstanceOf(ModelPort.TextDelta.class,
                        visible.getFirst()).text());
            }
            String text = String.join("", fragments);
            reduce(state, event("response.output_text.done", 8).put("output_index", 1)
                    .put("content_index", 0).put("item_id", "message_summary_only").put("text", text));
            ObjectNode message = AbstractStreamingModelAdapter.JSON.createObjectNode()
                    .put("id", "message_summary_only").put("type", "message")
                    .put("role", "assistant").put("status", "completed");
            message.putArray("content").addObject().put("type", "output_text").put("text", text)
                    .putArray("annotations");
            if ("terminal".equals(encrypted)) {
                reasoning.put("encrypted_content", "opaque_terminal");
            }
            String completed = ModelAdapterTestSupport.openAiResponse("resp_summary_only_text", "completed",
                    null, "[" + reasoning + "," + message + "]");
            List<ModelPort.ModelEvent> terminal = reduce(state, event("response.completed", 9).set("response",
                    AbstractStreamingModelAdapter.JSON.readTree(completed)));
            if ("terminal".equals(encrypted)) {
                assertEquals(1, terminal.size());
                ModelPort.ReasoningBlockDeferred filled = assertInstanceOf(
                        ModelPort.ReasoningBlockDeferred.class, terminal.getFirst());
                assertEquals(reserved.slotId(), filled.slotId());
                assertEquals("opaque_terminal", AbstractStreamingModelAdapter.JSON.readTree(
                        filled.content().nativeJson()).path("encrypted_content").textValue());
            } else {
                assertTrue(terminal.isEmpty(),
                        "terminal reconciliation must not replay text or fabricate an opaque reasoning block");
            }
        }
    }

    /** 通过同一状态机入口消费帧，测试不依赖网络计时或暴露生产测试 façade。 */
    private static List<ModelPort.ModelEvent> reduce(OpenAiResponsesState state, ObjectNode event) {
        return state.reduce(event.path("type").textValue(), event, ignored -> null);
    }

    /** 每帧显式携带协议序号，使正文可见性断言保持真实顺序约束。 */
    private static ObjectNode event(String name, long sequence) {
        return AbstractStreamingModelAdapter.JSON.createObjectNode()
                .put("type", name).put("sequence_number", sequence);
    }
}
