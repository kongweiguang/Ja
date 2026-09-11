// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;

/** 验证附件 identity 只在模型与 Codec 双门满足时解析为请求期原生载荷。 */
final class AgentContextMapperTest {
    private static final String ATTACHMENT_ID = "att_12345678";
    private static final String THREAD_ID = "thr_12345678";

    /** 图片双门满足时读取完整受管内容，并保持 Base64 与媒体元数据不变。 */
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

    /** 文本模型不应探测附件内容，必须直接保留 read_attachment 安全入口。 */
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

    /** Codec 空能力必须在任何元数据或内容读取前回退，未知 Adapter 不会获得用户字节。 */
    @Test
    void fallsBackWithoutReadingWhenCodecDoesNotDeclareCapability() {
        AtomicInteger reads = new AtomicInteger();
        ModelPort.ModelRequest request = request(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE),
                ModelPort.NativeAttachmentSupport.none(),
                reader(new byte[]{1, 2, 3, 4}, "image", "image/png", reads));

        assertInstanceOf(TextContent.class, request.messages().getFirst().content().getFirst());
        assertEquals(0, reads.get());
    }

    /** 其它二进制即使模型支持图片也只暴露 Tool identity，不执行、解压或读取完整内容。 */
    @Test
    void fallsBackForGenericBinaryAfterBoundedInspection() {
        AtomicInteger reads = new AtomicInteger();
        ModelPort.ModelRequest request = request(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024),
                reader(new byte[]{1, 2, 3, 4}, "binary", "application/octet-stream", reads));

        assertInstanceOf(TextContent.class, request.messages().getFirst().content().getFirst());
        assertEquals(1, reads.get());
    }

    /** 请求累计超过 Codec 总量时只让后续附件回退，已成功路由的前序附件保持确定性。 */
    @Test
    void fallsBackWhenNativeRequestTotalBudgetIsExhausted() {
        byte[] content = new byte[600];
        ContextMessage message = new ContextMessage("msg_test", "turn_test", 1,
                ContextMessage.Role.USER,
                List.of(new ContextMessage.AttachmentBlock(ATTACHMENT_ID),
                        new ContextMessage.AttachmentBlock(ATTACHMENT_ID)), 32);
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                List.of(message), SummaryDocument.empty(), 32, Optional.empty(), false);

        ModelPort.ModelRequest request = new AgentContextMapper(new TestJsonValueCodec()).toModelRequest(
                prompt, configuration(Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE)),
                new AgentPromptSnapshot("system", "prompt_test", 1), List.of(), null, 1,
                THREAD_ID, reader(content, "image", "image/png", new AtomicInteger()),
                support(NativeAttachmentContent.Kind.IMAGE, "image/png", 1_024));

        assertInstanceOf(NativeAttachmentContent.class, request.messages().getFirst().content().get(0));
        assertInstanceOf(TextContent.class, request.messages().getFirst().content().get(1));
    }

    /** 同一 Provider/API/model/端点的历史 reasoning 必须原样进入下一次请求并保持块顺序。 */
    @Test
    void restoresMatchingReasoningBlockIntoNextRequest() {
        ReasoningContent reasoning = reasoning("provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("before"), reasoning, new TextContent("after")));
        List<ContextMessage> context = new AgentContextMapper(new TestJsonValueCodec())
                .fromSnapshot(snapshot(new ConversationRepository.StoredMessage(
                        "item_assistant", "turn_test", 1, assistant, Instant.parse("2026-08-25T12:00:00Z"))),
                        "turn_test");

        ModelPort.ModelRequest request = requestFor(context, configuration(Set.of(ModelPort.InputModality.TEXT)));

        assertEquals(List.of(TextContent.class, ReasoningContent.class, TextContent.class),
                request.messages().getFirst().content().stream().map(ModelContent::getClass).toList());
        assertEquals(reasoning.nativeJson(), ((ReasoningContent) request.messages().getFirst().content().get(1))
                .nativeJson());
    }

    /** Provider、model 或端点改变时必须删除 opaque 块；仅含不匹配 reasoning 的消息不得制造空请求消息。 */
    @Test
    void filtersReasoningWhenRequestIdentityChanges() {
        ReasoningContent reasoning = reasoning("provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("answer"), reasoning));
        ModelMessage reasoningOnly = new ModelMessage(ModelRole.ASSISTANT, List.of(reasoning));
        List<ContextMessage> context = new AgentContextMapper(new TestJsonValueCodec())
                .fromSnapshot(snapshot(
                        new ConversationRepository.StoredMessage("item_assistant", "turn_test", 1,
                                assistant, Instant.parse("2026-08-25T12:00:00Z")),
                        new ConversationRepository.StoredMessage("item_reasoning", "turn_test", 2,
                                reasoningOnly, Instant.parse("2026-08-25T12:00:01Z"))), "turn_test");

        ModelPort.ModelConfiguration changed = configuration(Set.of(ModelPort.InputModality.TEXT),
                "provider_other", "model_other", "test-model", URI.create("http://127.0.0.1:60843"));
        ModelPort.ModelRequest request = requestFor(context, changed);

        assertEquals(1, request.messages().size());
        assertEquals(List.of(TextContent.class), request.messages().getFirst().content().stream()
                .map(ModelContent::getClass).toList());
        assertTrue(request.messages().stream().noneMatch(message -> message.content().isEmpty()));
    }

    /** 跨 Provider/model/API 时保留公开 summary 为 assistant 文本，但不回放原生 opaque 块。 */
    @Test
    void downgradesPublicReasoningToAssistantTextWhenRequestIdentityChanges() {
        ReasoningContent reasoning = reasoningWithSummary("provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("answer"), reasoning));
        List<ContextMessage> context = new AgentContextMapper(new TestJsonValueCodec())
                .fromSnapshot(snapshot(new ConversationRepository.StoredMessage(
                        "item_assistant", "turn_test", 1, assistant, Instant.parse("2026-08-25T12:00:00Z"))),
                        "turn_test");

        ModelPort.ModelRequest request = requestFor(context, configuration(Set.of(ModelPort.InputModality.TEXT),
                "provider_other", "model_other", "test-model", URI.create("http://127.0.0.1:60843")));

        assertEquals(List.of("answer", "visible summary"), request.messages().getFirst().content().stream()
                .map(content -> assertInstanceOf(TextContent.class, content).text()).toList());
    }

    /** 构造一条仅含附件 identity 的上下文，并通过真实 Mapper 双门生成冻结请求。 */
    private static ModelPort.ModelRequest request(
            Set<ModelPort.InputModality> modalities,
            ModelPort.NativeAttachmentSupport support,
            ManagedAttachmentReader reader) {
        ContextMessage message = new ContextMessage("msg_test", "turn_test", 1,
                ContextMessage.Role.USER, List.of(new ContextMessage.AttachmentBlock(ATTACHMENT_ID)), 16);
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                List.of(message), SummaryDocument.empty(), 16, Optional.empty(), false);
        return new AgentContextMapper(new TestJsonValueCodec()).toModelRequest(
                prompt, configuration(modalities), new AgentPromptSnapshot("system", "prompt_test", 1),
                List.of(), null, 1, THREAD_ID, reader, support);
    }

    /** 以当前单 Turn 历史构造最小权威快照；消息 ordinal 明确控制 reasoning 的保序位置。 */
    private static ConversationRepository.ThreadSnapshot snapshot(
            ConversationRepository.StoredMessage... messages) {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        return new ConversationRepository.ThreadSnapshot("thr_test", "ws_test", "title", preferences(), 1,
                List.of(new ConversationRepository.TurnSnapshot("thr_test", "turn_test", TurnState.COMPLETED,
                        now, now, now, 1, 1)), List.of(messages), now, now);
    }

    /** 通过 Mapper 入口生成 Provider 请求，确保过滤后的 reasoning-only 消息不会落成空 message。 */
    private static ModelPort.ModelRequest requestFor(
            List<ContextMessage> context, ModelPort.ModelConfiguration configuration) {
        ContextOrchestrator.PreparedPrompt prompt = new ContextOrchestrator.PreparedPrompt(
                context, SummaryDocument.empty(), 64, Optional.empty(), false);
        return new AgentContextMapper(new TestJsonValueCodec()).toModelRequest(
                prompt, configuration, new AgentPromptSnapshot("system", "prompt_test", 1),
                List.of(), null, 1, THREAD_ID,
                reader(new byte[0], "image", "image/png", new AtomicInteger()),
                ModelPort.NativeAttachmentSupport.none());
    }

    /** 生成包含原生 Responses reasoning item 的持久块，载荷同时覆盖未来回传所需 opaque 字段。 */
    private static ReasoningContent reasoning(String providerId, String modelId, String upstreamModel,
                                              URI endpoint) {
        return new ReasoningContent(providerId, modelId, "openai_responses", upstreamModel,
                ReasoningContent.endpointFingerprint(endpoint), "reasoning",
                "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"opaque\"}");
    }

    /** 构造含公开 Responses summary 的 reasoning，验证跨身份降级不暴露 encrypted_content。 */
    private static ReasoningContent reasoningWithSummary(String providerId, String modelId,
                                                         String upstreamModel, URI endpoint) {
        return new ReasoningContent(providerId, modelId, "openai_responses", upstreamModel,
                ReasoningContent.endpointFingerprint(endpoint), "reasoning",
                "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":["
                        + "{\"type\":\"summary_text\",\"text\":\"visible summary\"}],"
                        + "\"encrypted_content\":\"opaque\"}");
    }

    /** 发布单媒体 Codec 规则，使测试只观察 Mapper 的双门与总量行为。 */
    private static ModelPort.NativeAttachmentSupport support(
            NativeAttachmentContent.Kind kind, String mediaType, long maximum) {
        return new ModelPort.NativeAttachmentSupport(
                List.of(new ModelPort.NativeAttachmentRule(kind, Set.of(mediaType), maximum)), maximum);
    }

    /** 构造带显式输入模态的冻结测试配置，不创建网络或凭据副作用。 */
    private static ModelPort.ModelConfiguration configuration(Set<ModelPort.InputModality> modalities) {
        return configuration(modalities, "provider_test", "model_test", "test-model",
                URI.create("http://127.0.0.1:60842"));
    }

    /** 构造可切换身份的测试配置，覆盖 provider/model/endpoint 任一边界变化。 */
    private static ModelPort.ModelConfiguration configuration(Set<ModelPort.InputModality> modalities,
                                                               String providerId, String modelId,
                                                               String upstreamModel, URI endpoint) {
        return new ModelPort.ModelConfiguration(providerId, modelId, "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, upstreamModel, endpoint, "fixture-only-api-key",
                Duration.ofSeconds(1), Duration.ofSeconds(1), modalities,
                ModelPort.GenerationOptions.defaults());
    }

    /**
     * 模拟 AttachmentService 的 64 KiB range 与 binary Base64 契约，读取计数可证明 fallback 未扩大 IO。
     */
    private static ManagedAttachmentReader reader(
            byte[] content, String mediaKind, String mediaType, AtomicInteger reads) {
        return request -> {
            reads.incrementAndGet();
            int start = Math.toIntExact(request.offsetBytes());
            int end = Math.min(content.length, start + request.maxBytes());
            byte[] part = Arrays.copyOfRange(content, start, end);
            return new ManagedAttachmentReader.ReadResult(
                    ATTACHMENT_ID, "image.png", content.length, mediaKind, mediaType,
                    start, end, end == content.length, "base64",
                    Base64.getEncoder().encodeToString(part));
        };
    }
}
