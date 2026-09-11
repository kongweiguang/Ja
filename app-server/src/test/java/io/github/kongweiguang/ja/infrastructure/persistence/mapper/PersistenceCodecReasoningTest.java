// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 reasoning opaque 块在 Java 权威消息历史中的有序、脱敏 round-trip。 */
final class PersistenceCodecReasoningTest {
    /** 原生块与普通文本混排后必须无损恢复，确保下次请求可重建 wire 顺序。 */
    @Test
    void roundTripsReasoningBlockInAssistantOrder() {
        PersistenceCodec codec = new PersistenceCodec(new ObjectMapper());
        ReasoningContent reasoning = new ReasoningContent(
                "provider_test", "model_test", "openai_responses", "openrouter/vendor/model",
                ReasoningContent.endpointFingerprint(URI.create("https://api.example/v1")), "reasoning",
                "{\"type\":\"reasoning\",\"encrypted_content\":\"secret\"}");
        ModelMessage source = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("before"), reasoning, new TextContent("after")));

        String json = codec.writeMessage(source);
        ModelMessage restored = codec.readMessage(ModelRole.ASSISTANT.name(), json);

        assertEquals(source, restored);
        assertTrue(json.contains("encrypted_content"));
        assertFalse(restored.toString().contains("secret"));
    }
}
