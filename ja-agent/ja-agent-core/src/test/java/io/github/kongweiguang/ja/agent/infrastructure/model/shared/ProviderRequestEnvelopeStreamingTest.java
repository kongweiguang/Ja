// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import okhttp3.RequestBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Provider 正文在内存冻结后以同一字节流发送且绝不隐式重放。 */
final class ProviderRequestEnvelopeStreamingTest {
    /**
     * Okio 正文须与冻结时的长度和摘要完全一致；发送后清空树，第二次写入明确失败而不是提交空正文。
     */
    @Test
    void streamsOnlyTheFrozenBodyAndRejectsReplay() throws Exception {
        ObjectNode request = AbstractStreamingModelAdapter.JSON.createObjectNode();
        request.put("model", "frozen-model");
        request.putArray("messages").addObject().put("role", "user").put("content", "冻结内容");
        byte[] expectedBody = AbstractStreamingModelAdapter.serializeRequest(request);
        ProviderRequestEnvelope envelope = ProviderRequestEnvelope.freeze(
                request, ModelPort.Api.OPENAI_CHAT_COMPLETIONS);
        request.put("model", "mutated-after-freeze");

        RequestBody body = envelope.requestBody();
        Buffer sink = new Buffer();
        body.writeTo(sink);
        byte[] actualBody = sink.readByteArray();

        assertTrue(body.isOneShot());
        assertEquals(expectedBody.length, body.contentLength());
        assertArrayEquals(expectedBody, actualBody);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actualBody)),
                envelope.fingerprint());
        assertThrows(IllegalStateException.class, () -> body.writeTo(new Buffer()));
        assertThrows(IllegalStateException.class, envelope::sendBody);
    }
}
