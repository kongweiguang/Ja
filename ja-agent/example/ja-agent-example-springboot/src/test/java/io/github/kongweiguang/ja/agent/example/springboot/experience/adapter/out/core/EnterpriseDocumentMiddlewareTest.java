// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.core;

import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentCapabilities;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.NoteRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证同一执行的多条输入聚合来源不会继承单条消息的附件数量上限。 */
final class EnterpriseDocumentMiddlewareTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test");

    /**
     * 11 个附件分属两条 USER 消息时，服务端归属核验和后续 note 参数状态必须看到完整有序集合。
     */
    @Test
    void aggregatesReferencedAttachmentsAcrossUserMessages() {
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> "att_document_%02d".formatted(index)).toList();
        List<AttachmentMetadata> metadata = ids.stream().map(EnterpriseDocumentMiddlewareTest::metadata).toList();
        AtomicReference<List<String>> requested = new AtomicReference<>();
        AttachmentService attachments = attachmentService(metadata, requested);
        EnterpriseDocumentMiddleware middleware = new EnterpriseDocumentMiddleware(attachments, notes());
        List<ModelMessage> history = List.of(userMessage(ids.subList(0, 5)), userMessage(ids.subList(5, 11)));
        AgentMiddleware.RequestContext context = new AgentMiddleware.RequestContext(IDENTITY, "system prompt", history,
                List.of(), AgentMiddleware.ExtensionStateView.empty(EnterpriseDocumentMiddleware.NAMESPACE, 1));

        AgentMiddleware.ExtensionDecision<AgentMiddleware.RequestPatch> decision = middleware.beforeRequest(context);

        assertEquals(ids, requested.get());
        assertTrue(decision.decision().instructionSuffix().contains(String.join(", ", ids)));
        JsonArray persisted = (JsonArray) decision.statePatch().put().get("sourceAttachmentIds");
        assertEquals(ids, persisted.values().stream().map(JsonText.class::cast).map(JsonText::value).toList());
    }

    /**
     * 只实现本钩子实际调用的元数据查询；其它存储方法失败可确保测试不会偷偷走数据库副作用。
     */
    private static AttachmentService attachmentService(List<AttachmentMetadata> metadata,
                                                       AtomicReference<List<String>> requested) {
        AttachmentRepository repository = (AttachmentRepository) Proxy.newProxyInstance(
                AttachmentRepository.class.getClassLoader(), new Class<?>[] {AttachmentRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("metadata") && arguments.length == 3) {
                        @SuppressWarnings("unchecked")
                        List<String> ids = (List<String>) arguments[2];
                        requested.set(List.copyOf(ids));
                        return metadata.stream().filter(item -> ids.contains(item.id())).toList();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ExecutionRecordCodec recordCodec = (ExecutionRecordCodec) Proxy.newProxyInstance(
                ExecutionRecordCodec.class.getClassLoader(), new Class<?>[] {ExecutionRecordCodec.class},
                (proxy, method, arguments) -> { throw new UnsupportedOperationException(method.getName()); });
        AttachmentCapabilities capabilities = new AttachmentCapabilities(List.of("image/png"), List.of("image/png"),
                50_000_000L, 50_000_000L, 10);
        return new AttachmentService(repository, capabilities, recordCodec);
    }

    /**
     * 构造受服务端验证的元数据，使测试聚焦引用数量而不需要伪造附件内容。
     */
    private static AttachmentMetadata metadata(String id) {
        return new AttachmentMetadata(id, IDENTITY.threadId(), id + ".png", "image/png", 1,
                "a".repeat(64), Instant.parse("2026-10-06T00:00:00Z"), true);
    }

    /**
     * 通过 Core 原生内容类型构造用户消息，确保测试不把普通文字 fallback 当作文件引用。
     */
    private static ModelMessage userMessage(List<String> ids) {
        List<io.github.kongweiguang.ja.agent.domain.model.ModelContent> content = new ArrayList<>();
        for (String id : ids) {
            content.add(new NativeAttachmentContent(id, NativeAttachmentContent.Kind.IMAGE,
                    id + ".png", "image/png", 1, "AA=="));
        }
        return new ModelMessage(ModelRole.USER, content);
    }

    /**
     * 投影阶段仅回收既有凭证；本 beforeRequest 测试没有已提交笔记。
     */
    private static NoteRepository notes() {
        return (NoteRepository) Proxy.newProxyInstance(NoteRepository.class.getClassLoader(),
                new Class<?>[] {NoteRepository.class},
                (proxy, method, arguments) -> method.getName().equals("listForTurn")
                        ? List.of() : throwUnsupported(method.getName()));
    }

    /** 防止测试替身静默接受了未声明的笔记 IO。 */
    private static Object throwUnsupported(String method) {
        throw new UnsupportedOperationException(method);
    }
}
