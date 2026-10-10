// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.store.local.infrastructure.integration;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用独立 SQLite 执行验证待消费附件故障、修复与取消都遵守同一持久队列边界。 */
final class QueuedAttachmentIntegrationTest {
    @TempDir
    Path directory;

    /** 附件临时不可读时保留队列正文；宿主更新后恢复执行只产生一次接受事实。 */
    @Test
    void repairsUnavailableQueuedAttachmentBeforeResuming() throws Exception {
        AgentIdentity identity = businessIdentity("execution-attachment-repair");
        String attachmentId = "customer-invoice-42";
        ToggleAttachmentReader reader = new ToggleAttachmentReader(false);
        GatedModel model = new GatedModel();
        SQLiteAgentStore store = sqlite("repair-queued-attachment.sqlite");
        try (AgentEngine engine = engine(store, model, reader)) {
            AgentExecutionHandle handle = engine.submit(AgentRequest.text(identity, "开始处理", "submit-attachment-repair"));
            assertTrue(model.firstRequest.await(10, TimeUnit.SECONDS), "initial model request should start");

            AgentInputCommand.Enqueue queued = new AgentInputCommand.Enqueue("queue-attachment-op",
                    "queue-attachment-input", AgentInputCommand.Kind.MESSAGE,
                    new UserContent(List.of(new AttachmentContent(attachmentId))), Map.of());
            engine.mutateInput(identity, queued).toCompletableFuture().get(10, TimeUnit.SECONDS);
            model.finishFirstRequest("先前回复");

            AgentExecutionOutcome.Suspended waiting = assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertTrue(waiting.interactionId().isEmpty(), "attachment repair is a queue suspension, not an interaction wait");
            AgentInputQueue.Entry attention = engine.inspectInputQueue(identity).entries().getFirst();
            assertEquals(queued.inputId(), attention.inputId());
            assertEquals(AgentInputQueue.Status.NEEDS_ATTENTION, attention.status());
            assertEquals("ATTACHMENT_UNAVAILABLE", attention.issue().code());
            assertEquals(1, model.requestCount.get());
            assertEquals(1, reader.requests.size());
            assertEquals(0, acceptedInputCount(engine, identity));

            reader.available.set(true);
            engine.mutateInput(identity, new AgentInputCommand.Update("repair-attachment-op", queued.inputId(),
                    attention.inputRevision(), new UserContent(List.of(new AttachmentContent(attachmentId))), Map.of()))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(AgentInputQueue.Status.PENDING,
                    engine.inspectInputQueue(identity).entries().getFirst().status());

            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertTrue(engine.inspectInputQueue(identity).entries().isEmpty());
            assertEquals(2, model.requestCount.get());
            assertEquals(1, acceptedInputCount(engine, identity));
            assertEquals(2, reader.requests.size());
            for (ManagedAttachmentReader.ReadRequest request : reader.requests) {
                assertEquals(attachmentId, request.attachmentId());
                assertEquals(identity.threadId(), request.threadId());
            }
        }
    }

    /** 取消发生在附件安全点之前时保留原队列事实，不读、不接受也不再次派发该输入。 */
    @Test
    void cancellationBeforeAttachmentSafePointDoesNotReplayQueuedInput() throws Exception {
        AgentIdentity identity = businessIdentity("execution-attachment-cancel");
        String attachmentId = "billing-document-9";
        ToggleAttachmentReader reader = new ToggleAttachmentReader(true);
        GatedModel model = new GatedModel();
        SQLiteAgentStore store = sqlite("cancel-queued-attachment.sqlite");
        try (AgentEngine engine = engine(store, model, reader)) {
            AgentExecutionHandle handle = engine.submit(AgentRequest.text(identity, "读取账单", "submit-attachment-cancel"));
            assertTrue(model.firstRequest.await(10, TimeUnit.SECONDS), "model request should reach its cancellable wait");
            AgentInputCommand.Enqueue queued = new AgentInputCommand.Enqueue("cancel-attachment-op",
                    "cancel-attachment-input", AgentInputCommand.Kind.MESSAGE,
                    new UserContent(List.of(new AttachmentContent(attachmentId))), Map.of());
            engine.mutateInput(identity, queued).toCompletableFuture().get(10, TimeUnit.SECONDS);

            engine.cancel(identity, "cancel-before-attachment-read", "user cancelled")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionOutcome.Completed cancelled = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(TurnState.CANCELLED, cancelled.result().state());
            AgentInputQueue.Entry pending = engine.inspectInputQueue(identity).entries().getFirst();
            assertEquals(queued.inputId(), pending.inputId());
            assertEquals(AgentInputQueue.Status.PENDING, pending.status());
            assertEquals(0, reader.requests.size());
            assertEquals(0, acceptedInputCount(engine, identity));
            assertEquals(1, model.requestCount.get());
        }
    }

    /** 普通业务标识无需 Ja 的附件、Thread 前缀即可通过公开值类型校验。 */
    @Test
    void attachmentContractsAcceptHostOwnedIdentifiers() {
        String attachmentId = "business-report-17";
        String threadId = "quarterly-review";
        assertEquals(attachmentId, new AttachmentContent(attachmentId).attachmentId());
        assertEquals(attachmentId, new ManagedAttachmentReader.ReadRequest(attachmentId, threadId, 0, 4)
                .attachmentId());
        assertEquals(threadId, new ManagedAttachmentReader.NativeReadRequest(attachmentId, threadId, 1024)
                .threadId());
        assertEquals(attachmentId, new ManagedAttachmentReader.Descriptor(attachmentId, "report.txt", 7,
                "text", "text/plain").attachmentId());
    }

    /** 测试用身份刻意采用业务租户、会话和调用方标识，避免依赖 Ja 工作区或 Turn 命名。 */
    private static AgentIdentity businessIdentity(String turnId) {
        return new AgentIdentity("tenant_billing", "quarterly_review", turnId, "billing_worker");
    }

    /** 每测试显式创建独立 Core schema，事件、队列与恢复仍走真实事务实现。 */
    private SQLiteAgentStore sqlite(String filename) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve(filename));
        SQLiteAgentStore.bootstrap(dataSource);
        return new SQLiteAgentStore(dataSource);
    }

    /** 用明确模型选择和真实 Store 构建 Engine，只替换供应商与宿主附件读端口。 */
    private static AgentEngine engine(AgentStore store, GatedModel model, ManagedAttachmentReader attachments) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration("business-provider",
                "business-model", "cfg_business_test", ModelPort.Api.OPENAI_RESPONSES, "model-business",
                URI.create("https://example.invalid/v1"), "test-secret", Duration.ofSeconds(2),
                Duration.ofSeconds(10), Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
        return new AgentEngineBuilder().store(store).model(configuration)
                .contextBudget(new ContextBudget(16_384, 2_048, false)).attachments(attachments)
                .extensions(registry -> registry.registerModelProvider("business-provider", model)).build();
    }

    /** 只计数持久接受事件，借 Store 分配的游标核实队列项没有被重复消费。 */
    private static long acceptedInputCount(AgentEngine engine, AgentIdentity identity) {
        return engine.readEvents(identity, 0, 1000).events().stream()
                .filter(event -> event.eventType().equals("agent.input.accepted")).count();
    }

    /** 可按测试节奏切换读取可用性，并记录完整的非 Ja 读取身份。 */
    private static final class ToggleAttachmentReader implements ManagedAttachmentReader {
        private final AtomicBoolean available;
        private final List<ReadRequest> requests = new CopyOnWriteArrayList<>();

        /** 初始状态直接决定第一次附件安全点是成功还是可恢复挂起。 */
        private ToggleAttachmentReader(boolean initiallyAvailable) {
            available = new AtomicBoolean(initiallyAvailable);
        }

        /** 仅在可用时返回稳定脱敏描述；失败类型与真实附件 bridge 的临时不可读边界一致。 */
        @Override
        public Descriptor inspect(String attachmentId, String threadId) {
            requests.add(new ReadRequest(attachmentId, threadId, 0, 4));
            if (!available.get()) {
                throw new ReadFailure(new IOException("temporary attachment outage"));
            }
            return new Descriptor(attachmentId, "invoice.txt", 7, "text", "text/plain");
        }

        /** 为契约完整性实现有界读取；主流程只需 inspect，不读取或保存附件正文。 */
        @Override
        public ReadResult read(ReadRequest request) {
            Descriptor descriptor = inspect(request.attachmentId(), request.threadId());
            byte[] bytes = "invoice".getBytes(StandardCharsets.UTF_8);
            int from = Math.toIntExact(request.offsetBytes());
            int to = (int) Math.min(bytes.length, request.offsetBytes() + request.maxBytes());
            String content = new String(bytes, from, to - from, StandardCharsets.UTF_8);
            return new ReadResult(descriptor.attachmentId(), descriptor.displayName(), descriptor.sizeBytes(),
                    descriptor.mediaKind(), descriptor.mediaType(), request.offsetBytes(), to, to == bytes.length,
                    "utf-8", content);
        }
    }

    /** 第一次请求由测试显式放行或取消，第二次请求立即回答以验证恢复后的循环仍可继续。 */
    private static final class GatedModel implements ModelPort {
        private final AtomicInteger requestCount = new AtomicInteger();
        private final CountDownLatch firstRequest = new CountDownLatch(1);
        private final AtomicReference<ModelEventSink> firstSink = new AtomicReference<>();
        private final CompletableFuture<ModelOutcome> firstOutcome = new CompletableFuture<>();

        /** 每轮使用固定可信估值，避免测试被供应商网络或估值波动影响。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "a".repeat(64));
        }

        /** 只阻塞首次模型响应，保证测试能在真实执行期间向持久队列追加附件。 */
        @Override
        public java.util.concurrent.CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                                        CancellationToken cancellation) {
            if (requestCount.incrementAndGet() == 1) {
                firstSink.set(sink);
                CancellationToken.Registration registration = cancellation.onCancellation(
                        () -> firstOutcome.completeExceptionally(new CancellationException("cancelled by host")));
                firstOutcome.whenComplete((value, failure) -> registration.close());
                firstRequest.countDown();
                return firstOutcome;
            }
            sink.onEvent(new TextDelta("附件修复后已继续处理")).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }

        /** 在队列已原子追加后结束首轮模型调用，让唯一安全点检查该附件。 */
        private void finishFirstRequest(String response) {
            firstSink.get().onEvent(new TextDelta(response)).toCompletableFuture().join();
            firstOutcome.complete(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(100, 10, 110)));
        }
    }
}
