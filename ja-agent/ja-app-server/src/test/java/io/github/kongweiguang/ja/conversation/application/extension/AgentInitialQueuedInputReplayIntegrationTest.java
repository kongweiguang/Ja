// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.extension;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.test.JsonValueTestSupport;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 经真实 SQLite admission、重启回执读取和重复提交核验内部 MESSAGE 只绑定、消费一次。 */
final class AgentInitialQueuedInputReplayIntegrationTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-27T00:00:00Z");
    private static final String MESSAGE_TEXT = "MAILBOX_MESSAGE_ONCE";

    @TempDir
    private Path temporary;

    /** 重建内部请求必须复用首次准入批次；即使响应丢失且邮箱状态已变化也不能改指纹或重消费。 */
    @Test
    void restoresCommittedInternalBatchAfterResponseLossAndConsumesItOnce() throws Exception {
        Path database = temporary.resolve("internal-message-retry.sqlite");
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_internal_retry", "turn_internal_retry", "user_local");
        AgentInputCommand.Enqueue queued = new AgentInputCommand.Enqueue("mail_operation_original", "mail_input_original",
                AgentInputCommand.Kind.MESSAGE, new UserContent(List.of(new TextContent(MESSAGE_TEXT))),
                Map.of("ja.mailbox.messageId", "msg_original", "ja.mailbox.senderThreadId", "thr_sender"));
        AtomicInteger providerCalls = new AtomicInteger();
        AgentInternalRequest submitted = request(identity).withInitialQueuedInputs(List.of(queued));

        try (SQLiteAgentStore store = SQLiteAgentStore.open(database);
             AgentEngine engine = engine(store, providerCalls)) {
            AgentExecutionOutcome outcome = engine.submitInternal(submitted).completion().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome);
            assertTrue(engine.inspectInputQueue(identity).entries().isEmpty());
        }

        try (SQLiteAgentStore reopened = SQLiteAgentStore.open(database);
             AgentEngine retryEngine = engine(reopened, providerCalls)) {
            AgentInternalRequest reconstructed = request(identity);
            List<AgentInputCommand.Enqueue> originalBatch = retryEngine.readAdmittedInitialInputs(reconstructed)
                    .orElseThrow(() -> new AssertionError("committed admission receipt was not found"));
            assertEquals(List.of(queued), originalBatch);

            AgentExecutionOutcome replayed = retryEngine.submitInternal(
                    reconstructed.withInitialQueuedInputs(originalBatch)).completion().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Completed.class, replayed);
            assertEquals(1, providerCalls.get(), "idempotent retry must not dispatch the model again");
            assertEquals(1, queuedUserMessages(reopened, identity),
                    "the persisted mailbox body must become one model USER fact");

            AgentInputCommand.Enqueue changed = new AgentInputCommand.Enqueue("mail_operation_changed",
                    "mail_input_changed", AgentInputCommand.Kind.MESSAGE,
                    new UserContent(List.of(new TextContent("different later message"))), queued.hostMetadata());
            assertThrows(IllegalArgumentException.class,
                    () -> retryEngine.submitInternal(reconstructed.withInitialQueuedInputs(List.of(changed))));
        }
    }

    /** 使用相同稳定身份与正文重建请求，避免重试时钟或运行时默认值改变幂等内容。 */
    private static AgentInternalRequest request(AgentIdentity identity) {
        return new AgentInternalRequest(identity, new ContextInstruction("msg_instruction_retry", "continue task"),
                "operation_internal_retry", Optional.empty(), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), ACCEPTED_AT);
    }

    /** 返回执行队列与请求绑定的 USER 事实数，区分原始排队正文和一次性消费结果。 */
    private static long queuedUserMessages(AgentStore store, AgentIdentity identity) {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), identity.turnId());
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        return store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution, 0, snapshot.lastRecordSequence(), 1_000))
                .records().stream()
                .filter(record -> record.recordType().equals("agent.user.message"))
                .filter(record -> JsonValueTestSupport.containsText(record.payload(), MESSAGE_TEXT))
                .count();
    }

    /** 真实 Engine 使用本地模型完成一次请求，因而覆盖 Store 回执、恢复和输入队列消费的完整边界。 */
    private static AgentEngine engine(SQLiteAgentStore store, AtomicInteger providerCalls) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration("provider_test", "model_test",
                "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "upstream_test", URI.create("https://example.invalid"),
                "secret_test", Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 32, null));
        ModelPort model = new ModelPort() {
            /** 固定 tokenizer 估算，隔离 provider 与外部 token 服务。 */
            @Override
            public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
                return new InputTokenEstimate(16, 16, "a".repeat(64));
            }

            /** 首个 Provider 请求必须看到一次 MESSAGE；完成后重放不得再次触发模型。 */
            @Override
            public CompletionStage<ModelOutcome> start(
                    ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
                cancellation.throwIfCancellationRequested();
                long visible = request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                        .filter(text -> text.text().equals(MESSAGE_TEXT)).count();
                assertEquals(1, visible, "the mailbox MESSAGE must appear once in the first model context");
                providerCalls.incrementAndGet();
                sink.onEvent(new TextDelta("done"));
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                        new ModelUsage(4, 1, 5)));
            }
        };
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().freeze();
        AgentPromptSession prompt = new FixedAgentPromptSession(ContextBudget.capabilities(100_000, 8_192, true));
        SummaryModel.Factory summaries = binding -> new NoopSummaryModel();
        ManagedAttachmentReader attachments = attachment -> {
            throw new AssertionError("text-only MESSAGE must not read an attachment");
        };
        AgentRequestRuntimeResolver resolver = (request, base, recovered) -> new AgentRequestRuntime(configuration,
                model, summaries, extensions, prompt, "b".repeat(64), AgentStoreTransactionParticipant.NONE,
                attachments, new ToolProjectionLimits(64_000, 16_000), Map.of(), () -> { });
        return new AgentEngineBuilder().store(store).runtimeResolver(resolver).parallelism(2).build();
    }

    /** 压缩 Provider 必须完整装配，但本短任务不应进入摘要流程。 */
    private static final class NoopSummaryModel implements SummaryModel {
        /** 固定估算以使意外摘要调用仍不访问网络。 */
        @Override public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
            return new ModelPort.InputTokenEstimate(1, 1, "c".repeat(64));
        }

        /** 只提供空摘要结果；测试用例会由模型直接完成，不应依赖其内容。 */
        @Override public Result summarize(SummaryPrompt prompt) {
            return new Result(SummaryDocument.empty(), CheckpointUsage.none());
        }
    }
}
