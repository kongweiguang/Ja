// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证通用模型请求映射中的附件双门、总量上限与跨模型 reasoning 恢复语义。 */
final class AgentContextMapperAttachmentTest {
    private static final String ATTACHMENT_ID = "att_12345678";
    private static final String THREAD_ID = "thr_12345678";
    private static final String TURN_ID = "turn_test";
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final AgentStore.ConversationKey KEY = new AgentStore.ConversationKey(
            "tenant_test", THREAD_ID, "user_test");

    private final ObjectMapper jackson = new ObjectMapper();
    private final StorePayloadCodec records = new StorePayloadCodec(jackson);
    private final AgentContextMapper mapper = new AgentContextMapper(new JacksonJsonValueCodec(jackson), records);

    /** 图片经模型与 Codec 双门批准后才读取完整字节，并保留原始 Base64 与媒体元数据。 */
    @Test
    void routesSupportedImageAsNativeRequestContent() {
        byte[] content = new byte[]{1, 2, 3, 4, 5, 6, 7};
        ModelPort.ModelRequest request = request(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024),
                reader(content, "image", "image/png", new AtomicInteger()));

        NativeAttachmentContent attachment = assertInstanceOf(
                NativeAttachmentContent.class, request.messages().getFirst().content().getFirst());
        assertEquals(ATTACHMENT_ID, attachment.attachmentId());
        assertEquals("image.png", attachment.displayName());
        assertEquals(Base64.getEncoder().encodeToString(content), attachment.base64Data());
    }

    /** 错位的 range 元数据不能被拼成另一份看似完整的原生附件。 */
    @Test
    void rejectsNativeAttachmentRangeWithUnexpectedOffset() {
        byte[] content = new byte[]{1, 2, 3, 4};
        ManagedAttachmentReader reader = request -> new ManagedAttachmentReader.ReadResult(
                ATTACHMENT_ID, "image.png", content.length, "image", "image/png",
                1, content.length, true, "base64", Base64.getEncoder().encodeToString(content));

        assertThrows(IllegalStateException.class, () -> reader.readNative(
                new ManagedAttachmentReader.NativeReadRequest(ATTACHMENT_ID, THREAD_ID, content.length)));
    }

    /** 读适配器越过单片上限时在 Base64 解码前失败，避免异常响应放大瞬时内存。 */
    @Test
    void rejectsNativeAttachmentChunkLargerThanRequestedRange() {
        byte[] content = new byte[64 * 1024 + 1];
        ManagedAttachmentReader reader = request -> new ManagedAttachmentReader.ReadResult(
                ATTACHMENT_ID, "image.png", content.length, "image", "image/png",
                0, content.length, true, "base64", Base64.getEncoder().encodeToString(content));

        assertThrows(IllegalStateException.class, () -> reader.readNative(
                new ManagedAttachmentReader.NativeReadRequest(ATTACHMENT_ID, THREAD_ID, content.length)));
    }

    /** 未声明图片模态的模型只获得读取工具提示，不应探测附件正文。 */
    @Test
    void fallsBackWithoutReadingWhenModelDoesNotDeclareModality() {
        AtomicInteger reads = new AtomicInteger();
        ModelPort.ModelRequest request = request(Set.of(ModelPort.InputModality.TEXT),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024),
                reader(new byte[]{1, 2, 3, 4}, "image", "image/png", reads));

        TextContent fallback = assertInstanceOf(TextContent.class,
                request.messages().getFirst().content().getFirst());
        assertEquals(0, reads.get());
        assertEquals("Attachment " + ATTACHMENT_ID
                + " is available through the read_attachment tool.", fallback.text());
    }

    /** Codec 未声明原生能力时，在任何附件读取前生成安全回退文本。 */
    @Test
    void fallsBackWithoutReadingWhenCodecDoesNotDeclareCapability() {
        AtomicInteger reads = new AtomicInteger();
        ModelPort.ModelRequest request = request(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE),
                ModelPort.NativeAttachmentSupport.none(),
                reader(new byte[]{1, 2, 3, 4}, "image", "image/png", reads));

        assertInstanceOf(TextContent.class, request.messages().getFirst().content().getFirst());
        assertEquals(0, reads.get());
    }

    /** 非图片二进制只允许一次有界元数据检查，不把完整内容交给模型 Codec。 */
    @Test
    void fallsBackForGenericBinaryAfterBoundedInspection() {
        AtomicInteger reads = new AtomicInteger();
        ModelPort.ModelRequest request = request(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024),
                reader(new byte[]{1, 2, 3, 4}, "binary", "application/octet-stream", reads));

        assertInstanceOf(TextContent.class, request.messages().getFirst().content().getFirst());
        assertEquals(1, reads.get());
    }

    /** 总量超限只回退后续附件，先前已安全路由的内容保持原顺序。 */
    @Test
    void fallsBackWhenNativeRequestTotalBudgetIsExhausted() {
        byte[] content = new byte[600];
        ContextMessage message = new ContextMessage("msg_test", TURN_ID, 1,
                ContextMessage.Role.USER, List.of(new ContextMessage.AttachmentBlock(ATTACHMENT_ID),
                new ContextMessage.AttachmentBlock(ATTACHMENT_ID)), 32);
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                List.of(message), SummaryDocument.empty(), 32, Optional.empty(), false);

        ModelPort.ModelRequest request = mapper.toModelRequest(prompt,
                configuration(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE)),
                new AgentPromptSnapshot("system", "prompt_test", 1), List.of(), null, 1, THREAD_ID,
                reader(content, "image", "image/png", new AtomicInteger()),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024));

        assertInstanceOf(NativeAttachmentContent.class, request.messages().getFirst().content().get(0));
        assertInstanceOf(TextContent.class, request.messages().getFirst().content().get(1));
    }

    /** 宿主要求真实原生资料时，能力不支持必须在任何读取或Provider派发前失败。 */
    @Test
    void rejectsUnsupportedNativeInputWhenHostDisallowsReferenceFallback() {
        AtomicInteger reads = new AtomicInteger();
        ContextException failure = assertThrows(ContextException.class, () -> request(
                Set.of(ModelPort.InputModality.TEXT),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024),
                nativeOnly(reader(new byte[]{1, 2, 3, 4}, "image", "image/png", reads))));
        assertEquals(ContextException.Code.INVALID_STATE, failure.code());
        assertEquals(0, reads.get());
    }

    /** 相同附件出现两次各占实际请求预算；严格宿主整体拒绝，不能只发送第一份后声称两份均已读取。 */
    @Test
    void rejectsActualRequestTotalOverflowWhenHostDisallowsReferenceFallback() {
        ContextMessage message = new ContextMessage("msg_test", TURN_ID, 1, ContextMessage.Role.USER,
                List.of(new ContextMessage.AttachmentBlock(ATTACHMENT_ID),
                        new ContextMessage.AttachmentBlock(ATTACHMENT_ID)), 32);
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                List.of(message), SummaryDocument.empty(), 32, Optional.empty(), false);
        ContextException failure = assertThrows(ContextException.class, () -> mapper.toModelRequest(prompt,
                configuration(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE)),
                new AgentPromptSnapshot("system", "prompt_test", 1), List.of(), null, 1, THREAD_ID,
                nativeOnly(reader(new byte[600], "image", "image/png", new AtomicInteger())),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024)));
        assertEquals(ContextException.Code.CONTEXT_LIMIT, failure.code());
    }

    /** 只改变宿主路由约束，读取及元数据继续委托同一Reader，避免测试另造原生解码实现。 */
    private static ManagedAttachmentReader nativeOnly(ManagedAttachmentReader reader) {
        return new ManagedAttachmentReader() {
            /** 保留真实分片边界，观察失败是否发生在标准Mapper流程。 */
            @Override
            public ReadResult read(ReadRequest request) {
                return reader.read(request);
            }

            /** 此宿主没有附件读取工具，文字引用无法满足用户要求。 */
            @Override
            public boolean allowsReferenceFallback() {
                return false;
            }
        };
    }

    /** 同一模型请求身份下，opaque reasoning 块按原位置恢复给下一 Provider 调用。 */
    @Test
    void restoresMatchingReasoningBlockIntoNextRequest() {
        ReasoningContent reasoning = reasoning("provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("before"), reasoning, new TextContent("after")));
        List<ContextMessage> context = history(assistant);

        ModelPort.ModelRequest request = requestFor(context, configuration(Set.of(ModelPort.InputModality.TEXT)));

        assertEquals(List.of(TextContent.class, ReasoningContent.class, TextContent.class),
                request.messages().getFirst().content().stream().map(ModelContent::getClass).toList());
        assertEquals(reasoning.nativeJson(), ((ReasoningContent) request.messages().getFirst().content().get(1))
                .nativeJson());
    }

    /** Provider、model 或 endpoint 变化时丢弃旧 opaque 块，并过滤因此变空的 reasoning-only 消息。 */
    @Test
    void filtersReasoningWhenRequestIdentityChanges() {
        ReasoningContent reasoning = reasoning("provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("answer"), reasoning));
        ModelMessage reasoningOnly = new ModelMessage(ModelRole.ASSISTANT, List.of(reasoning));
        List<ContextMessage> context = history(assistant, reasoningOnly);

        ModelPort.ModelConfiguration changed = configuration(Set.of(ModelPort.InputModality.TEXT),
                "provider_other", "model_other", "test-model", URI.create("http://127.0.0.1:60843"));
        ModelPort.ModelRequest request = requestFor(context, changed);

        assertEquals(1, request.messages().size());
        assertEquals(List.of(TextContent.class), request.messages().getFirst().content().stream()
                .map(ModelContent::getClass).toList());
        assertTrue(request.messages().stream().noneMatch(message -> message.content().isEmpty()));
    }

    /** 身份改变时保留公开 reasoning 摘要为普通 assistant 文本，但不回放 opaque 原生内容。 */
    @Test
    void downgradesPublicReasoningToAssistantTextWhenRequestIdentityChanges() {
        ReasoningContent reasoning = reasoningWithSummary("provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("answer"), reasoning));
        List<ContextMessage> context = history(assistant);

        ModelPort.ModelRequest request = requestFor(context, configuration(Set.of(ModelPort.InputModality.TEXT),
                "provider_other", "model_other", "test-model", URI.create("http://127.0.0.1:60843")));

        assertEquals(List.of("answer", "visible summary"), request.messages().getFirst().content().stream()
                .map(content -> assertInstanceOf(TextContent.class, content).text()).toList());
    }

    /** 超长公开摘要必须完整拆分，不能在 Provider 身份变化时截掉历史尾部或拆开代理对。 */
    @Test
    void preservesLongPublicReasoningAcrossProviderIdentityChanges() {
        String visible = "a".repeat(1_048_575) + "😀end";
        ReasoningContent reasoning = new ReasoningContent("provider_test", "model_test", "openai_responses",
                "test-model", ReasoningContent.endpointFingerprint(URI.create("http://127.0.0.1:60842")),
                "reasoning", "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\""
                        + visible + "\"}]}");
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(reasoning));
        List<ContextMessage> context = history(assistant);

        ModelPort.ModelRequest request = requestFor(context, configuration(Set.of(ModelPort.InputModality.TEXT),
                "provider_other", "model_other", "test-model", URI.create("http://127.0.0.1:60843")));
        List<ModelContent> parts = request.messages().getFirst().content();
        assertTrue(parts.size() > 1);
        assertEquals(visible, parts.stream()
                .map(part -> assertInstanceOf(TextContent.class, part).text())
                .collect(java.util.stream.Collectors.joining()));
    }

    /** 用 StorePayloadCodec 构造模型事实，避免旧 Ja ThreadSnapshot 成为 Core 的测试依赖。 */
    private List<ContextMessage> history(ModelMessage... messages) {
        AgentStore.ExecutionSnapshot execution = new AgentStore.ExecutionSnapshot(
                new AgentStore.ExecutionKey(KEY, TURN_ID), 1, "COMPLETED", 1, 1,
                io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(), 0, NOW, NOW);
        List<AgentStore.ConversationRecord> stored = new ArrayList<>();
        for (int index = 0; index < messages.length; index++) {
            String messageId = "message_" + index;
            AgentStore.RecordWrite write = records.encode("record_" + index,
                    new ExecutionFacts.AssistantMessage(messageId, messages[index], "", null, 1), NOW);
            stored.add(new AgentStore.ConversationRecord(KEY, execution.key(), write.recordId(),
                    write.recordType(), write.payloadSchemaVersion(), index + 1L, write.payload(), NOW));
        }
        AgentStore.ConversationSnapshot snapshot = new AgentStore.ConversationSnapshot(
                KEY, 1, 0, 1, NOW, NOW, stored.size());
        return mapper.fromCompleteSnapshot(
                new AgentLoopPersistence.ConversationHistory(snapshot, List.of(execution), stored), Set.of(TURN_ID));
    }

    /** 通过真实 Mapper 入口生成 Provider 请求，确保历史过滤后不会生成空 message。 */
    private ModelPort.ModelRequest requestFor(List<ContextMessage> context, ModelPort.ModelConfiguration configuration) {
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                context, SummaryDocument.empty(), 64, Optional.empty(), false);
        return mapper.toModelRequest(prompt, configuration, new AgentPromptSnapshot("system", "prompt_test", 1),
                List.of(), null, 1, THREAD_ID, reader(new byte[0], "image", "image/png", new AtomicInteger()),
                ModelPort.NativeAttachmentSupport.none());
    }

    /** 构造只含附件身份的上下文，测量读取是否严格受双重能力声明约束。 */
    private ModelPort.ModelRequest request(Set<ModelPort.InputModality> modalities,
            ModelPort.NativeAttachmentSupport support, ManagedAttachmentReader reader) {
        ContextMessage message = new ContextMessage("msg_test", TURN_ID, 1,
                ContextMessage.Role.USER, List.of(new ContextMessage.AttachmentBlock(ATTACHMENT_ID)), 16);
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                List.of(message), SummaryDocument.empty(), 16, Optional.empty(), false);
        return mapper.toModelRequest(prompt, configuration(modalities),
                new AgentPromptSnapshot("system", "prompt_test", 1), List.of(), null, 1, THREAD_ID,
                reader, support);
    }

    /** 构造含 Responses 原生 reasoning 项的记录块，覆盖下一轮同身份透传所需字段。 */
    private static ReasoningContent reasoning(String providerId, String modelId, String upstreamModel, URI endpoint) {
        return new ReasoningContent(providerId, modelId, "openai_responses", upstreamModel,
                ReasoningContent.endpointFingerprint(endpoint), "reasoning",
                "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"opaque\"}");
    }

    /** 构造具有可见 summary 的 reasoning 块，验证跨请求身份时的安全降级。 */
    private static ReasoningContent reasoningWithSummary(String providerId, String modelId,
            String upstreamModel, URI endpoint) {
        return new ReasoningContent(providerId, modelId, "openai_responses", upstreamModel,
                ReasoningContent.endpointFingerprint(endpoint), "reasoning",
                """
                    {"type":"reasoning","id":"rs_1","summary":[\
                    {"type":"summary_text","text":"visible summary"}],\
                    "encrypted_content":"opaque"}\
                    """);
    }

    /** 固定单媒体读取规则，避免测试因模型或 Codec 默认能力改变而失去明确前提。 */
    private static ModelPort.NativeAttachmentSupport support(
            NativeAttachmentContent.Kind kind, String mediaType, long maximum) {
        return new ModelPort.NativeAttachmentSupport(
                List.of(new ModelPort.NativeAttachmentRule(kind, Set.of(mediaType), maximum)), maximum);
    }

    /** 创建显式输入模态和稳定端点的测试配置，不打开真实 Provider 连接。 */
    private static ModelPort.ModelConfiguration configuration(Set<ModelPort.InputModality> modalities) {
        return configuration(modalities, "provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
    }

    /** 只切换 request identity 字段，精确验证 opaque reasoning 的跨模型隔离。 */
    private static ModelPort.ModelConfiguration configuration(Set<ModelPort.InputModality> modalities,
            String providerId, String modelId, String upstreamModel, URI endpoint) {
        return new ModelPort.ModelConfiguration(providerId, modelId, "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, upstreamModel, endpoint, "fixture-only-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1), modalities,
                ModelPort.GenerationOptions.defaults());
    }

    /** 仿照附件端口按有界 range 返回 Base64 分片，读取计数用于断言能力门控。 */
    private static ManagedAttachmentReader reader(
            byte[] content, String mediaKind, String mediaType, AtomicInteger reads) {
        return request -> {
            reads.incrementAndGet();
            int start = Math.toIntExact(request.offsetBytes());
            int end = Math.min(content.length, start + request.maxBytes());
            byte[] part = Arrays.copyOfRange(content, start, end);
            return new ManagedAttachmentReader.ReadResult(ATTACHMENT_ID, "image.png", content.length,
                    mediaKind, mediaType, start, end, end == content.length, "base64",
                    Base64.getEncoder().encodeToString(part));
        };
    }
}
