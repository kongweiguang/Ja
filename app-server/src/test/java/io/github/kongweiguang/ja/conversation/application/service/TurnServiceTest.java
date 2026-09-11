// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;


import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;

import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;

import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.application.loop.QueuedInputBoundary;
import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TurnExecutionPlan;
import io.github.kongweiguang.ja.conversation.application.loop.TurnQueue;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.ToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.NetworkntToolArgumentValidation;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryGenerator;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceReferenceValidator;
import java.nio.file.Path;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.profile;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;

/** Turn 用例服务回归集，锁定准入、取消确认、终态提交、失败结算与关闭时限。 */
final class TurnServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);
    private static final WorkspaceReferenceValidator WORKSPACE_REFERENCES = request ->
            new WorkspaceReferenceValidator.ValidatedReference(
                    request.workspaceId(), request.relativePath(), request.kind());

    /** 锁定准入接受 Base64URL generation 前缀字符，避免合法实例标识被误拒绝。 */
    @Test
    void admissionAcceptsBase64UrlGenerationPrefixCharacters() {
        assertEquals("cfg_-Abc123",
                profile("provider_test", "model_test", "cfg_-Abc123").configGeneration());
        assertEquals("cfg__Abc123",
                profile("provider_test", "model_test", "cfg__Abc123").configGeneration());
    }

    /** Runtime 解析失败不得预留队列、写入准入事务或产生需要猜测释放的半成品租约。 */
    @Test
    void runtimeResolutionFailureLeavesAdmissionUntouched() {
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("model must not run"));
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        TurnRuntimeResolver resolver = new TurnRuntimeResolver() {
            /** 模拟配置不可用，并确保失败发生在任何资源所有权返回之前。 */
            @Override public RuntimeLease resolve(TurnRuntimeRequest request) {
                throw new IllegalStateException("runtime unavailable");
            }

            /** 本测试不执行工作区预热。 */
            @Override public void prepareWorkspace(Path workspaceRoot) {
            }
        };
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations, resolver, CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            assertThrows(IllegalStateException.class, () -> service.start(request("turn_resolution"),
                    event -> CompletableFuture.completedFuture(null)));
            assertEquals(0, store.revision());
        }
    }

    /** Resume 只有真实 CAS 竞争映射为 FIFO 顺序冲突。 */
    @Test
    void resumeMapsOnlyStorageCasConflictToOrderConflict() {
        RuntimeException failure = resumeFailure(StorageException.Code.CAS_CONFLICT);
        TurnUseCase.TurnResumeException mapped = assertInstanceOf(
                TurnUseCase.TurnResumeException.class, failure);
        assertEquals(TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT, mapped.failure());
    }

    /** Resume 目标在 CAS 前消失时映射为不可恢复，而不是误导调用方重排 FIFO。 */
    @Test
    void resumeMapsStorageNotFoundToNotResumable() {
        RuntimeException failure = resumeFailure(StorageException.Code.NOT_FOUND);
        TurnUseCase.TurnResumeException mapped = assertInstanceOf(
                TurnUseCase.TurnResumeException.class, failure);
        assertEquals(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE, mapped.failure());
    }

    /** 事务或 IO 故障必须保持基础设施失败，禁止伪装成可重试业务冲突。 */
    @Test
    void resumePreservesUnexpectedStorageFailure() {
        RuntimeException failure = resumeFailure(StorageException.Code.TRANSACTION);
        StorageException storage = assertInstanceOf(StorageException.class, failure);
        assertEquals(StorageException.Code.TRANSACTION, storage.code());
    }

    /** Resume 环境解析失败保持原始稳定分类，不再伪装成已删除的 Turn 指纹错误。 */
    @Test
    void resumeMapsExpectedRuntimeResolutionFailuresToFingerprintMismatch() {
        List<RuntimeException> failures = List.of(
                new TurnRuntimeResolver.RuntimeMismatchException("configured Skill is unavailable"),
                new ConfigurationError(ConfigurationError.Code.MISSING_PROVIDER_OR_MODEL,
                        "provider is unavailable"),
                new ConfigurationError(ConfigurationError.Code.CORRUPT_CONFIG,
                        "configuration is invalid"));

        for (RuntimeException failure : failures) {
            assertEquals(failure.getClass(), resumeResolutionFailure(failure).getClass());
        }
    }

    /** 配置 IO 故障不是运行时漂移，必须保留原分类供上层告警和重试。 */
    @Test
    void resumePreservesRuntimeResolutionInfrastructureFailure() {
        ConfigurationError failure = assertInstanceOf(ConfigurationError.class,
                resumeResolutionFailure(new ConfigurationError(
                        ConfigurationError.Code.IO_FAILURE, "configuration storage unavailable")));

        assertEquals(ConfigurationError.Code.IO_FAILURE, failure.code());
    }

    /**
     * Resume 必须恢复 execution 引用的摘要与 Skill 名称，同时把 latest checkpoint 摘要作为
     * 下一 Provider 轮次输入；Skill 正文版本不属于持久恢复身份。
     */
    @Test
    void resumeRestoresReferencedPromptAndUsesLatestSummaryForNextProvider() throws Exception {
        RecordingStore store = new RecordingStore();
        List<TurnExecutionState.ActiveSkill> activeSkills = List.of(
                new TurnExecutionState.ActiveSkill("skill_review"));
        store.prepareResumeCandidate("provider prompt summary", "latest checkpoint summary", activeSkills);
        TrackingPromptSession prompt = new TrackingPromptSession(activeSkills);
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(new AtomicInteger(), prompt), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.resume(
                    "turn_resume", 0, event -> CompletableFuture.completedFuture(null));

            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(List.of("provider prompt summary", "latest checkpoint summary",
                            "latest checkpoint summary"),
                    prompt.restoredSummaries);
            assertEquals(activeSkills, prompt.restoredSkills.get());
            assertEquals("latest checkpoint summary", prompt.preparedSummaries.getFirst());
        }
    }

    /** 强杀恢复后的首轮仍拥有一次自动标题机会，并且标题输入必须使用持久化的原始用户请求。 */
    @Test
    void resumedFirstTurnPreservesAutomaticTitleOwnership() throws Exception {
        RecordingStore store = new RecordingStore();
        store.prepareTitleEligibleResumeCandidate("original request");
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AtomicReference<AutomaticThreadTitleScheduler.Request> titleRequest = new AtomicReference<>();
        AutomaticThreadTitleScheduler titles = (request, sink) -> {
            titleRequest.set(request);
            return CompletableFuture.completedFuture(null);
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(), CLOCK, titles, WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.resume(
                    "turn_resume", 0, event -> CompletableFuture.completedFuture(null));

            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertNotNull(titleRequest.get());
            assertEquals("original request", titleRequest.get().firstUserRequest());
        }
    }

    /** 关闭准入后返回的冻结租约必须立即且仅释放一次，不能进入 active owner。 */
    @Test
    void rejectedAdmissionReleasesRuntimeLeaseExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("model must not run"));
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(releases), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            service.stopAccepting();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> service.start(request("turn_rejected"),
                            event -> CompletableFuture.completedFuture(null)));
            assertEquals(1, releases.get());
            assertEquals(0, store.revision());
        }
    }

    /** 协作模式参与冻结运行时身份，Plan 请求绝不能借用 Default Prompt/Tool catalog。 */
    @Test
    void rejectsResolvedRuntimeWithDifferentCollaborationMode() {
        AtomicInteger releases = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("model must not run"));
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(releases,
                             new FixedAgentPromptSession(ContextBudget.capabilities(1_000_000, 8_192, true)),
                             io.github.kongweiguang.ja.conversation.domain.CollaborationMode.PLAN),
                     CLOCK, AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.start(request("turn_mode_mismatch"),
                            event -> CompletableFuture.completedFuture(null)));
            assertEquals(1, releases.get());
            assertEquals(0, store.revision());
        }
    }

    /** 未启用自动标题时准入、planning 与 dispatch 短租约必须逐一且只释放一次。 */
    @Test
    void completedTurnReleasesRuntimeLeaseExactlyOnce() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(releases), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test"),
                    event -> CompletableFuture.completedFuture(null));
            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(1)));
            assertEquals(3, releases.get());
        }
    }

    /** attachment-only Turn 把真实 ID 同时写入消息块和 admission 关系，不持久化伪造用户提示。 */
    @Test
    void attachmentOnlyTurnBindsIdsAndPersistsAttachmentContent() throws Exception {
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(
                    request("turn_test", "", List.of("att_first", "att_second")),
                    event -> CompletableFuture.completedFuture(null));
            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            ConversationRepository.TurnAdmission admission = store.lastAdmission();
            assertEquals(List.of("att_first", "att_second"), admission.attachmentIds());
            assertEquals(List.of(new AttachmentContent("att_first"), new AttachmentContent("att_second")),
                    admission.userMessage().content());
        }
    }

    /** 成功 Turn 不等待自动标题；标题 worker 只在真正发送前打开并独立释放最新请求租约。 */
    @Test
    void completedTurnSchedulesTitleWithoutBlockingAndKeepsRequestLease() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AtomicReference<AutomaticThreadTitleScheduler.Request> titleRequest = new AtomicReference<>();
        CompletableFuture<Void> titleCompletion = new CompletableFuture<>();
        AutomaticThreadTitleScheduler titles = (request, sink) -> {
            titleRequest.set(request);
            return titleCompletion;
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(releases), CLOCK, titles, WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "first request"),
                    event -> CompletableFuture.completedFuture(null));
            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertNotNull(titleRequest.get());
            assertEquals("first request", titleRequest.get().firstUserRequest());
            try (AutomaticThreadTitleScheduler.RequestRuntime runtime =
                         titleRequest.get().runtimeFactory().open(Duration.ofSeconds(1))) {
                assertEquals("provider_test", runtime.configuration().providerId());
            }
            assertEquals(4, releases.get());
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(1)));
            titleCompletion.complete(null);
            assertEquals(4, releases.get());
        }
    }

    /** 首轮失败即消费唯一自动命名机会；短标题保留，但失败终态绝不能触发模型标题任务。 */
    @Test
    void failedFirstTurnNeverSchedulesAutomaticTitle() throws Exception {
        RecordingStore store = new RecordingStore();
        ModelPort failed = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable"));
        AtomicInteger titleCalls = new AtomicInteger();
        AutomaticThreadTitleScheduler titles = (request, sink) -> {
            titleCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        };
        AgentLoop loop = new AgentLoop(metered(failed), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(), CLOCK, titles, WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_failed_first", "first request"),
                    event -> CompletableFuture.completedFuture(null));
            assertEquals(TurnState.FAILED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(0, titleCalls.get());
        }
    }

    /** 后续 Turn 的 admission 不再返回标题所有权；即使成功也不能补做首轮自动命名。 */
    @Test
    void successfulLaterTurnWithoutProvisionalOwnershipNeverSchedulesAutomaticTitle() throws Exception {
        RecordingStore store = new RecordingStore();
        store.disableProvisionalTitle();
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AtomicInteger titleCalls = new AtomicInteger();
        AutomaticThreadTitleScheduler titles = (request, sink) -> {
            titleCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(), CLOCK, titles, WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_later", "later request"),
                    event -> CompletableFuture.completedFuture(null));
            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(0, titleCalls.get());
        }
    }

    /** admission 的短标题事件必须先于 Agent 启动，长首轮也能立即获得可识别导航标题。 */
    @Test
    void publishesProvisionalTitleBeforeModelStarts() throws Exception {
        AtomicBoolean metadataPublished = new AtomicBoolean();
        AtomicReference<ThreadMetadataEvent> metadata = new AtomicReference<>();
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger metadataOrder = new AtomicInteger();
        AtomicInteger modelOrder = new AtomicInteger();
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) -> {
            modelOrder.set(sequence.incrementAndGet());
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        TurnEventSink events = new TurnEventSink() {
            /** 普通 Turn 事件不施加背压，本用例只观察元数据顺序。 */
            @Override public CompletionStage<Void> publish(
                    io.github.kongweiguang.ja.conversation.port.in.TurnEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 验证临时标题仍由 PLACEHOLDER 持有且使用 admission 的唯一 revision。 */
            @Override public CompletionStage<Void> publish(ThreadMetadataEvent event) {
                metadata.set(event);
                metadataPublished.set(true);
                metadataOrder.set(sequence.incrementAndGet());
                return CompletableFuture.completedFuture(null);
            }
        };
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "first request"), events);
            assertEquals(TurnState.COMPLETED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals("测试首问", metadata.get().title());
            assertEquals(1, metadata.get().revision());
            assertEquals(ThreadPreferences.TitleSource.PLACEHOLDER, metadata.get().titleSource());
            assertTrue(metadataPublished.get());
            assertTrue(metadataOrder.get() < modelOrder.get());
        }
    }

    /** 锁定运行中取消先确认再清理，并且最终只提交一个终态。 */
    @Test
    void runningCancelAcknowledgesBeforeCleanupAndCommitsOneTerminal() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        AtomicInteger propagatedCancellations = new AtomicInteger();
            // TurnService 是准入 Owner，其 Solon 依赖由外层生命周期关闭。
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            service.bindCancellationListener((threadId, turnId) -> {
                assertEquals("thr_test", threadId);
                assertEquals("turn_test", turnId);
                propagatedCancellations.incrementAndGet();
            });
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "cancel"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));
            long expectedRevision = store.revision();
            long before = System.nanoTime();
            TurnUseCase.CancelResult result = service.cancel("turn_test", expectedRevision);
            service.cancel("turn_test", expectedRevision);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue(elapsedMillis < 100, "cancel ACK took " + elapsedMillis + "ms");
            assertEquals(TurnState.RUNNING, result.status());
            assertEquals(1, propagatedCancellations.get());
            assertTrue(model.cleanupStarted.await(1, TimeUnit.SECONDS));
            assertEquals(0, store.terminals.get());
            model.modelRelease.countDown();
            // Provider 已观察到 Token，但终态持久化仍须等待同一清理屏障，
            // 否则迟到的清理失败会与终态事实竞争。
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> accepted.completion().toCompletableFuture().get(150, TimeUnit.MILLISECONDS));
            assertEquals(0, store.terminals.get());
            model.cleanupRelease.countDown();
            assertEquals(TurnState.CANCELLED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(1, store.terminals.get());
        }
    }

    /** 监听器瞬时失败后只保留一条退避重试链，重复取消不能并行派发同一持久欠账。 */
    @Test
    void retriesCancellationPropagationOnceWithoutDuplicateDispatch() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch propagated = new CountDownLatch(1);
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            service.bindCancellationListener((threadId, turnId) -> {
                assertEquals("thr_test", threadId);
                assertEquals("turn_test", turnId);
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient task listener failure");
                }
                propagated.countDown();
            });
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "cancel retry"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));
            long expectedRevision = store.revision();

            service.cancel("turn_test", expectedRevision);
            service.cancel("turn_test", expectedRevision);

            assertTrue(propagated.await(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            model.modelRelease.countDown();
            model.cleanupRelease.countDown();
            assertEquals(TurnState.CANCELLED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    /** 默认入队后通过 identity 提升，不能再从旧双入口直接创建 Steering。 */
    @Test
    void enqueuesFollowUpsAndPrioritizesByIdentity() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "queue"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));

            service.enqueueInput("turn_test", content("follow"));
            TurnUseCase.InputMutation second = service.enqueueInput("turn_test", content("steer"));
            service.prioritizeInput("turn_test", second.inputId(), 1);

            assertEquals(List.of(ConversationRepository.InputKind.FOLLOW_UP,
                            ConversationRepository.InputKind.STEERING),
                    store.queuedInputs().stream().map(ConversationRepository.PendingInput::kind).toList());
            assertEquals(List.of("follow", "steer"),
                    store.queuedInputs().stream().map(input -> input.content().text()).toList());
            assertTrue(store.queuedInputs().stream().allMatch(input ->
                    input.threadId().equals("thr_test") && input.turnId().equals("turn_test")));

            service.cancel("turn_test", store.revision());
            assertTrue(model.cleanupStarted.await(1, TimeUnit.SECONDS));
            model.modelRelease.countDown();
            model.cleanupRelease.countDown();
            assertEquals(TurnState.CANCELLED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    /**
     * 运行 owner 释放后的 SUSPENDED Turn 仍允许按 item revision 修复和删除问题队首；
     * mutation 只返回 SQLite 权威 ACK，不复用已结束 sink，也不得隐式恢复或接收新输入。
     */
    @Test
    void repairsAndDeletesSuspendedInputWithoutActiveOwner() {
        RecordingStore store = new RecordingStore();
        store.prepareSuspendedAttachmentInput();
        AtomicInteger releases = new AtomicInteger();
        ModelPort model = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("model must not run"));
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(releases), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.InputMutation repaired = service.updateInput(
                    "turn_resume", "input_suspended", 2, content("repaired"));

            InputQueue.QueuedInput repairedHead = repaired.inputQueue().items().getFirst();
            assertEquals(InputQueue.Status.PENDING, repairedHead.status());
            assertEquals(null, repairedHead.issue());
            assertEquals(3, repairedHead.inputRevision());
            assertEquals("repaired", repairedHead.content().text());
            assertTrue(repairedHead.attachments().isEmpty());
            assertEquals(TurnState.SUSPENDED, store.state);
            assertEquals(0, releases.get());

            TurnUseCase.InputMutationException enqueueFailure = assertThrows(
                    TurnUseCase.InputMutationException.class,
                    () -> service.enqueueInput("turn_resume", content("must wait for resume")));
            assertEquals(TurnUseCase.InputMutationFailure.TURN_NOT_FOUND, enqueueFailure.failure());

            TurnUseCase.InputMutation deleted = service.deleteInput(
                    "turn_resume", "input_suspended", 3);
            assertTrue(deleted.inputQueue().items().isEmpty());
            assertEquals(TurnState.SUSPENDED, store.state);
        }
    }

    /** 复现取消唤醒模型等待的真实顺序，并锁定终态发布屏障与复用线程中断隔离。 */
    @Test
    void cancellationPublishesTerminalBeforeCompletionAndDoesNotInterruptNextTurn() throws Exception {
        CancellationProbeModel model = new CancellationProbeModel();
        RecordingStore store = new RecordingStore();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        CompletableFuture<Void> terminalRelease = new CompletableFuture<>();
        CountDownLatch terminalPublishStarted = new CountDownLatch(1);
        AtomicInteger terminalPublications = new AtomicInteger();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations,
                        runtimeResolver(), CLOCK, AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted cancelled = service.start(request("turn_test", "cancel"), event -> {
                if (event instanceof io.github.kongweiguang.ja.conversation.port.in.TurnEvent.Terminal) {
                    terminalPublications.incrementAndGet();
                    terminalPublishStarted.countDown();
                    return terminalRelease;
                }
                return CompletableFuture.completedFuture(null);
            });
            assertTrue(model.firstStarted.await(1, TimeUnit.SECONDS));
            assertEquals(TurnState.RUNNING,
                    service.cancel("turn_test", store.revision()).status());
            assertTrue(terminalPublishStarted.await(1, TimeUnit.SECONDS));
            assertFalse(cancelled.completion().toCompletableFuture().isDone());
            assertEquals(1, terminalPublications.get());

            terminalRelease.complete(null);
            assertEquals(TurnState.CANCELLED,
                    cancelled.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state());

            AtomicBoolean nextTaskInterrupted = new AtomicBoolean();
            queue.submit("thr_after_cancel", "turn_after_cancel",
                    () -> nextTaskInterrupted.set(Thread.currentThread().isInterrupted()))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertFalse(nextTaskInterrupted.get());
            assertEquals(1, model.starts.get());
        }
    }

    /** 锁定取消持久化声明失败时不发布 token，避免未登记取消影响执行。 */
    @Test
    void failedCancellationClaimDoesNotPublishToken() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        store.rejectCancellationClaims = true;
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "cancel"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));
            TurnUseCase.TurnCancellationException failure = assertThrows(
                    TurnUseCase.TurnCancellationException.class,
                    () -> service.cancel("turn_test", store.revision()));
            assertEquals(TurnUseCase.CancelFailure.CONFLICT, failure.failure());
            assertFalse(model.cleanupStarted.await(150, TimeUnit.MILLISECONDS));
            model.modelRelease.countDown();
            assertEquals(TurnState.FAILED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    /** 生产 Repository 的稳定 CAS_CONFLICT 也必须映射为用例冲突，供 RPC 做有界单版重试。 */
    @Test
    void storageCancellationConflictMapsToUseCaseWithoutPublishingToken() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        store.cancellationStorageFailure = new StorageException(
                StorageException.Code.CAS_CONFLICT, "forced cancellation conflict");
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "cancel"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));

            TurnUseCase.TurnCancellationException failure = assertThrows(
                    TurnUseCase.TurnCancellationException.class,
                    () -> service.cancel("turn_test", store.revision()));

            assertEquals(TurnUseCase.CancelFailure.CONFLICT, failure.failure());
            assertFalse(model.cleanupStarted.await(150, TimeUnit.MILLISECONDS));
            model.modelRelease.countDown();
            assertEquals(TurnState.FAILED,
                    accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    /** 锁定协调器一次失败不会污染后续取消重试，保持可恢复边界。 */
    @Test
    void coordinatorFailureDoesNotPoisonCancellationRetry() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        FailOnceCancellationCoordinator cancellations = new FailOnceCancellationCoordinator();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "cancel"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS), () ->
                    "completion=" + accepted.completion().toCompletableFuture()
                            .handle((value, failure) -> failure == null ? "pending" : failure).join());
            long expectedRevision = store.revision();
            assertEquals(TurnState.RUNNING, service.cancel("turn_test", expectedRevision).status());
            assertTrue(model.cleanupStarted.await(1, TimeUnit.SECONDS));
            // 相同原始 expected revision 复用持久回执，并重试本地分派。
            assertEquals(TurnState.RUNNING, service.cancel("turn_test", expectedRevision).status());
            model.cleanupRelease.countDown();
            model.modelRelease.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(1, store.terminals.get());
        }
    }

    /** 锁定协调作用域丢失时先完成本地清理再失败关闭，避免资源残留。 */
    @Test
    void missingCoordinatorScopeFailsClosedAfterLocalCleanup() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        LostScopeCancellationCoordinator cancellations = new LostScopeCancellationCoordinator();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test"),
                    event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));
            assertEquals(TurnState.RUNNING, service.cancel("turn_test", store.revision()).status());
            assertTrue(model.cleanupStarted.await(1, TimeUnit.SECONDS));
            model.modelRelease.countDown();
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> accepted.completion().toCompletableFuture().get(150, TimeUnit.MILLISECONDS));
            assertEquals(0, store.terminals.get());
            model.cleanupRelease.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(TurnState.CANCELLED, store.state());
            assertEquals(1, store.terminals.get());
        }
    }

    /** 锁定紧急终态提交失败仍只结算 completion 一次，防止调用方收到冲突结果。 */
    @Test
    void emergencyTerminalFailureSettlesCompletionExactlyOnce() throws Exception {
        RecordingStore store = new RecordingStore();
        store.terminalCommitFailures = 2;
        ModelPort failingModel = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("provider failed"));
        AgentLoop loop = new AgentLoop(failingModel, new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test", "fail"),
                    event -> CompletableFuture.completedFuture(null));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TurnTerminalSettlement.TerminalSettlementFailure);
            assertFalse(failure.getCause().toString().contains("forced terminal"));
            assertEquals(TurnState.RUNNING, store.state());
            assertEquals(0, store.terminals.get());
        }
    }

    /** 锁定首次终态提交失败不盲目重试且只结算一次，避免重复副作用。 */
    @Test
    void firstTerminalCommitFailureDoesNotRetryAndSettlesOnce() throws Exception {
        RecordingStore store = new RecordingStore();
        store.terminalCommitFailures = 1;
        ModelPort failingModel = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("provider failed"));
        AgentLoop loop = new AgentLoop(failingModel, new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test"),
                    event -> CompletableFuture.completedFuture(null));
            AtomicInteger completions = new AtomicInteger();
            accepted.completion().whenComplete((value, failure) -> completions.incrementAndGet());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TurnTerminalSettlement.TerminalSettlementFailure);
            assertFalse(failure.getCause().toString().contains("forced terminal"));
            assertEquals(TurnState.RUNNING, store.state());
            assertEquals(0, store.terminals.get());
            assertEquals(1, completions.get());
        }
    }

    /** 应急失败也必须把安全回复、错误和 FAILED 状态原子提交，避免外层兜底重新制造空白终态。 */
    @Test
    void emergencyFailurePersistsAndPublishesSafeFinalReply() {
        RecordingStore store = new RecordingStore();
        store.state = TurnState.RUNNING;
        store.revision = 7;
        store.turnMutationVersion = 3;
        AtomicReference<TurnEvent.Terminal> published = new AtomicReference<>();
        Path workspace = Path.of("C:\\ja-test-workspace");
        TurnRuntimeRequest runtimeRequest = new TurnRuntimeRequest(
                "thr_test", "turn_test", workspace, "ws_turn_service", "provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                TurnOrigin.USER,
                TurnLimits.defaults().wallTimeout(), CLOCK.instant());
        try (DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
                RuntimeLease lease = runtimeResolver().resolve(runtimeRequest)) {
            CancellationCoordinator.CancellationScope cancellation =
                    cancellations.open("thr_test", "turn_emergency");
            try (cancellation) {
                TurnExecutionState.Common common = new TurnExecutionState.Common(
                        0, 0, 1, null, List.of(),
                        CLOCK.instant().plus(lease.limits().wallTimeout()),
                        io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER);
                TurnExecutionState.Ready execution = new TurnExecutionState.Ready(
                        common, TurnExecutionState.Next.ASSISTANT, null);
                TurnExecutionPlan plan = new TurnExecutionPlan(
                        "thr_test", "turn_emergency", workspace, content("test"), lease.model(),
                        lease.accessMode(), lease.limits(), CLOCK.instant(), "ws_turn_service", 7, 3, "",
                        lease.promptSession(), QueuedInputBoundary.plainTextOnly(),
                         lease.attachments(), lease.tools(), lease.generationId(),
                         lease.toolSessions(), lease.outputLimits(), lease.presentationSecrets(),
                         CLOCK.instant().plus(lease.limits().wallTimeout()),
                         (requestCommon, summary) -> {
                             throw new AssertionError("runtime refresh is outside emergency settlement test");
                         });
                TurnOwnership owner = new TurnOwnership(
                        plan, event -> {
                            if (event instanceof TurnEvent.Terminal terminal) published.set(terminal);
                            return CompletableFuture.completedFuture(null);
                        }, cancellation, new TerminalCoordinator(),
                        new CompletableFuture<>(), false,
                        CLOCK.instant().plus(lease.limits().wallTimeout()), execution);

                new TurnTerminalSettlement(store, CLOCK).settleEmergency(
                        owner, TurnState.FAILED, "INTERNAL_ERROR", "turn execution failed",
                        new AssertionError("internal provider detail"));
                var result = owner.completion.join();
                assertEquals(TurnState.FAILED, result.state());
                assertNotNull(result.terminal().finalMessage());
            } finally {
                cancellations.complete("thr_test", "turn_emergency");
            }
        }

        assertEquals(TurnState.FAILED, store.state());
        assertNotNull(store.lastTerminalCommit);
        assertNotNull(store.lastTerminalCommit.finalMessage());
        assertNotNull(published.get());
        assertNotNull(published.get().finalMessage());
        assertEquals(store.lastTerminalCommit.summary(), published.get().summary());
        assertEquals(store.lastTerminalCommit.finalMessageId(), published.get().finalMessage().messageId());
        assertEquals(store.lastTerminalCommit.summary(), published.get().finalMessage().text());
        assertFalse(published.get().finalMessage().text().contains("turn execution failed"));
    }

    /** 锁定不安全 generation 直接失败结算而不伪造紧急终态。 */
    @Test
    void unsafeGenerationSettlesWithoutEmergencyTerminal() throws Exception {
        RecordingStore store = new RecordingStore();
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("unsafe draft"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test"), event ->
                    event instanceof io.github.kongweiguang.ja.conversation.port.in.TurnEvent.TextDelta
                            ? CompletableFuture.failedFuture(new IllegalStateException("pipe failed"))
                            : CompletableFuture.completedFuture(null));
            AtomicInteger completions = new AtomicInteger();
            accepted.completion().whenComplete((value, failure) -> completions.incrementAndGet());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof AgentLoop.UnsafeGenerationException);
            assertEquals(TurnState.RUNNING, store.state());
            assertEquals(0, store.terminals.get());
            assertEquals(1, completions.get());
        }
    }

    /** 锁定排队取消被执行器拒绝后完成结算，且不会迟到提交终态。 */
    @Test
    void queuedCancellationExecutorRejectionSettlesWithoutLateTerminal() throws Exception {
        RecordingStore store = new RecordingStore();
        ModelPort unreachable = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("queued model ran"));
        AgentLoop loop = new AgentLoop(unreachable, new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        TurnQueue.Reservation blocker = queue.reserve("thr_block", "turn_block");
        blocker.submit(() -> {
            blockerStarted.countDown();
            try { blockerRelease.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        RejectingTerminalExecutor terminal = new RejectingTerminalExecutor();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(
                        store, loop, queue, cancellations, runtimeResolver(), CLOCK, terminal,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            TurnUseCase.Accepted accepted = service.start(request("turn_test"),
                    event -> CompletableFuture.completedFuture(null));
            TurnUseCase.CancelResult result = service.cancel("turn_test", store.revision());
            assertEquals(TurnState.QUEUED, result.status());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.util.concurrent.RejectedExecutionException);
            assertEquals(0, store.terminals.get());
            blockerRelease.countDown();
            assertTrue(queue.awaitQuiescence(Duration.ofSeconds(2)));
        } finally {
            blockerRelease.countDown();
        }
    }

    /** 锁定重复关闭共享一次完成过程并服从调用方截止时间。 */
    @Test
    void repeatedCloseUsesOneCompletionAndCallerDeadline() throws Exception {
        BlockingModel model = new BlockingModel();
        RecordingStore store = new RecordingStore();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            service.start(request("turn_test"), event -> CompletableFuture.completedFuture(null));
            assertTrue(model.started.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> service.closeAt(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            try {
                assertTrue(model.cleanupStarted.await(1, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> service.closeAt(
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)));
            } finally {
                model.cleanupRelease.countDown();
                model.modelRelease.countDown();
                first.get(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 正常关闭先执行临时 owner 的前置收口，再发布 Turn shutdown fence；前置 hook 内仍可完成一次
     * 真实准入，而 fence 发布后新的用户 Turn 必须立即拒绝，避免侧聊取消失去 Turn owner。
     */
    @Test
    void normalCloseRunsPreShutdownHookBeforeStoppingAdmission() throws Exception {
        RecordingStore store = new RecordingStore();
        CancellationProbeModel model = new CancellationProbeModel();
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
                TurnService service = new TurnService(store, loop, queue, cancellations, runtimeResolver(), CLOCK,
                        AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            AtomicReference<TurnUseCase.Accepted> hookAdmission = new AtomicReference<>();
            AtomicBoolean hookRan = new AtomicBoolean();
            service.bindPreShutdownHook(deadline -> {
                // 隐式 try-with-resources close 会再次调用 closeAt；只在首次 hook 中制造真实准入。
                if (hookRan.compareAndSet(false, true)) {
                    hookAdmission.set(service.start(request("turn_hook"),
                            event -> CompletableFuture.completedFuture(null)));
                    try {
                        if (!model.firstStarted.await(1, TimeUnit.SECONDS)) {
                            throw new AssertionError("pre-shutdown admission did not start");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("pre-shutdown test was interrupted", interrupted);
                    }
                }
            });

            service.closeAt(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

            assertTrue(hookRan.get());
            assertNotNull(hookAdmission.get());
            assertTrue(hookAdmission.get().completion().toCompletableFuture().isDone());
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> service.start(request("turn_after_shutdown"),
                            event -> CompletableFuture.completedFuture(null)));
        }
    }

    /** 创建 transport-free 启动意图，使测试也必须经过真实 RuntimeResolver 边界。 */
    private static TurnStartRequest request(String turnId) {
        return request(turnId, "test");
    }

    /** 允许场景固定用户输入，同时保留统一的身份和 Deadline 约束。 */
    private static TurnStartRequest request(String turnId, String input) {
        return request(turnId, input, List.of());
    }

    /** 允许附件场景复用同一运行时身份，同时由 TurnStartRequest 校验 text/attachment 非空组合。 */
    private static TurnStartRequest request(String turnId, String input, List<String> attachmentIds) {
        return new TurnStartRequest("thr_test", turnId, "ws_turn_service",
                Path.of("C:/workspace"), content(input, attachmentIds),
                "provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                TurnLimits.defaults().wallTimeout(),
                0, 0, CLOCK.instant());
    }

    /** 单文本测试也走 UserContent，防止测试继续依赖已删除的字符串双轨。 */
    private static UserContent content(String text) {
        return content(text, List.of());
    }

    /** 按生产规范构造附件后正文的输入；空正文不会生成非法 Text block。 */
    private static UserContent content(String text, List<String> attachmentIds) {
        List<UserContentBlock> blocks = new ArrayList<>();
        attachmentIds.stream().map(AttachmentContent::new).forEach(blocks::add);
        if (!text.isBlank()) blocks.add(new TextContent(text));
        return new UserContent(blocks);
    }

    /** 模拟 composition root 为每个安全点解析同一配置代际的命令、预算、Tool 和 MCP 工厂。 */
    private static TurnRuntimeResolver runtimeResolver() {
        return runtimeResolver(new AtomicInteger());
    }

    /** 注入释放计数器，验证所有权从准入到完成只归还一次。 */
    private static TurnRuntimeResolver runtimeResolver(AtomicInteger releases) {
        return runtimeResolver(releases,
                new FixedAgentPromptSession(ContextBudget.capabilities(1_000_000, 8_192, true)));
    }

    /** 注入 Prompt Session 以验证 Resume 恢复参数，同时保持配置与 Tool 指纹校验一致。 */
    private static TurnRuntimeResolver runtimeResolver(AtomicInteger releases, AgentPromptSession promptSession) {
        return runtimeResolver(releases, promptSession,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT);
    }

    /** 显式注入协作模式，覆盖 Resolver 发生模式漂移时的失败关闭边界。 */
    private static TurnRuntimeResolver runtimeResolver(AtomicInteger releases, AgentPromptSession promptSession,
                                                       io.github.kongweiguang.ja.conversation.domain.CollaborationMode
                                                               collaborationMode) {
        return new TurnRuntimeResolver() {
            /** 按入站意图创建不可变运行时租约，不依赖 Wire DTO 或配置 Adapter。 */
            @Override
            public RuntimeLease resolve(TurnRuntimeRequest start) {
                return new RuntimeLease("cfg_test", modelConfiguration(), AccessMode.APPROVAL_REQUIRED,
                        collaborationMode,
                        TurnLimits.defaults(), List.of(), new EmptyMcp(),
                        new ToolProjectionLimits(64_000, 16_000),
                        promptSession,
                        request -> { throw new AssertionError("text-only test must not read attachments"); },
                        List.of("test-secret"),
                        "0".repeat(64), "prompt_test", "medium",
                        releases::incrementAndGet);
            }

            /** 单元测试不执行工作区预热。 */
            @Override
            public void prepareWorkspace(Path workspaceRoot) {
            }
        };
    }

    /** 仅拒绝终态任务的执行器，用于稳定复现异步收口提交失败。 */
    private static final class RejectingTerminalExecutor
            extends java.util.concurrent.AbstractExecutorService {
        private final AtomicBoolean shutdown = new AtomicBoolean();

        /** 关闭操作无状态，避免拒绝夹具引入额外生命周期分支。 */
        @Override public void shutdown() { shutdown.set(true); }
        /** 立即关闭不返回任务，保持用例只观察 execute 拒绝。 */
        @Override public List<Runnable> shutdownNow() { shutdown.set(true); return List.of(); }
        /** 固定报告未关闭，避免服务提前绕过终态调度路径。 */
        @Override public boolean isShutdown() { return shutdown.get(); }
        /** 固定报告未终止，保持拒绝原因唯一来自任务提交。 */
        @Override public boolean isTerminated() { return shutdown.get(); }
        /** 不等待后台资源并返回未终止，使测试不会阻塞在夹具执行器。 */
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown.get(); }
        /** 对每个任务抛出拒绝异常，精确触发终态调度失败边界。 */
        @Override public void execute(Runnable command) {
            throw new java.util.concurrent.RejectedExecutionException("test terminal rejection");
        }
    }

    /** 构造与生产 JSON 形状一致的参数编解码器，避免 Tool 夹具绕过序列化。 */
    private static JsonValueCodec argumentsCodec() {
        return new TestJsonValueCodec();
    }

    /** 使用真实 Schema 适配器，确保服务测试与生产 Runner 的参数准入语义一致。 */
    private static ToolArgumentValidator argumentValidator() {
        return new NetworkntToolArgumentValidation(argumentsCodec());
    }

    /**
     * 为生命周期测试替身补上确定性的 Provider 精确计量接缝；测试仍由 delegate 控制发送，
     * 但不会因 ModelPort 的生产 fail-closed 默认实现提前终止 Turn。
     */
    private static ModelPort metered(ModelPort delegate) {
        return new ModelPort() {
            /** 返回与测试 envelope 绑定的稳定计量证据，不执行任何网络调用。 */
            @Override public InputTokenEstimate estimateInputTokens(
                    ModelRequest request, CancellationToken cancellationToken) {
                cancellationToken.throwIfCancellationRequested();
                return new InputTokenEstimate(1, "0".repeat(64));
            }

            /** 保留原测试替身的发送、阻塞与取消语义。 */
            @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                    CancellationToken cancellationToken) {
                return delegate.start(request, sink, cancellationToken);
            }
        };
    }

    /** 构造禁止意外压缩的上下文工厂，使服务测试只覆盖 Turn 生命周期。 */
    private static ContextOrchestratorFactory contextFactory(RecordingStore store) {
        CheckpointStore checkpoints = new CheckpointStore() {
            /** 意外读取检查点即失败，防止非压缩用例悄然进入上下文恢复。 */
            @Override public Snapshot read(String threadId) { return Snapshot.empty(threadId, store.revision()); }
            /** 意外提交检查点即失败，避免 Turn 服务测试隐藏额外持久化。 */
            @Override public CommittedCheckpoint commit(CheckpointStore.CommitRequest request) {
                throw new AssertionError("unexpected compaction");
            }
        };
        return new ContextOrchestratorFactory(checkpoints, CLOCK, binding ->
                prompt -> new SummaryGenerator.SummaryResult(SummaryDocument.empty(),
                        io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none()));
    }

    /** 提供无真实凭据的固定模型配置，确保服务回归不会访问外部 Provider。 */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, "test",
                URI.create("https://example.invalid/v1"), "test", Duration.ofSeconds(5),
                Duration.ofSeconds(30), java.util.Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
    }

    /** 在取消前保持请求未完成的模型假实现，用于控制运行中取消窗口。 */
    private static final class BlockingModel implements ModelPort {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cleanupStarted = new CountDownLatch(1);
        private final CountDownLatch cleanupRelease = new CountDownLatch(1);
        private final CountDownLatch modelRelease = new CountDownLatch(1);

        /** 等待测试释放或取消 token，精确复现模型仍在运行的 Turn。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                CancellationToken cancellationToken) {
            cancellationToken.onCancellation(() -> {
                cleanupStarted.countDown();
                try {
                    cleanupRelease.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            started.countDown();
            try {
                modelRelease.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            cancellationToken.throwIfCancellationRequested();
            return CompletableFuture.failedFuture(new AssertionError("cancellation was not registered"));
        }
    }

    /** 以取消完成模型 Future，稳定复现工作线程在流式轮次清理前收到 Token 的顺序。 */
    private static final class CancellationProbeModel implements ModelPort {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final AtomicInteger starts = new AtomicInteger();

        /** 通过取消回调完成待决阶段，使 Loop 必须先排空草稿，再提交并发布取消终态。 */
        @Override
        @SuppressWarnings("PMD.CloseResource")
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                   CancellationToken cancellationToken) {
            starts.incrementAndGet();
            CompletableFuture<ModelOutcome> pending = new CompletableFuture<>();
            cancellationToken.onCancellation(() -> pending.completeExceptionally(
                    new CancellationException("test cancellation")));
            firstStarted.countDown();
            return pending;
        }
    }

    /** 首次取消失败、随后委托真实实现的协调器，用于验证重试可恢复性。 */
    private static final class FailOnceCancellationCoordinator implements CancellationCoordinator {
        private final DefaultCancellationCoordinator delegate = new DefaultCancellationCoordinator();
        private final AtomicBoolean failNext = new AtomicBoolean(true);

        /** 委托创建真实作用域，确保只有取消入口注入一次性故障。 */
        @Override public CancellationScope open(String threadId, String turnId) {
            return delegate.open(threadId, turnId);
        }

        /** 首次返回失败，后续委托取消，用于证明失败不会污染作用域。 */
        @Override public CompletionStage<CancelOutcome> cancel(String threadId, String turnId,
                String reason) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("synthetic coordinator dispatch failure");
            }
            return delegate.cancel(threadId, turnId, reason);
        }

        /** 委托查询真实作用域，保持重试前后的 token 身份一致。 */
        @Override public Optional<CancellationToken> find(String threadId, String turnId) {
            return delegate.find(threadId, turnId);
        }

        /** 委托完成作用域，避免故障夹具改变正常资源释放。 */
        @Override public void complete(String threadId, String turnId) {
            delegate.complete(threadId, turnId);
        }

        /** 委托关闭底层协调器，确保测试后不存在残留作用域。 */
        @Override public void close() {
            delegate.close();
        }
    }

    /** 打开后隐藏作用域的协调器，用于稳定复现取消阶段身份丢失。 */
    private static final class LostScopeCancellationCoordinator implements CancellationCoordinator {
        private final DefaultCancellationCoordinator delegate = new DefaultCancellationCoordinator();

        /** 创建真实作用域后允许夹具在查询时模拟其丢失。 */
        @Override public CancellationScope open(String threadId, String turnId) {
            return delegate.open(threadId, turnId);
        }

        /** 保留真实取消行为，使失败只发生在后续作用域回读边界。 */
        @Override public CompletionStage<CancelOutcome> cancel(String threadId, String turnId,
                String reason) {
            return CompletableFuture.completedFuture(CancelOutcome.NOT_FOUND);
        }

        /** 固定返回空结果，模拟协调器内部作用域已不可恢复。 */
        @Override public Optional<CancellationToken> find(String threadId, String turnId) {
            return delegate.find(threadId, turnId);
        }

        /** 委托释放真实作用域，避免丢失模拟造成测试资源泄漏。 */
        @Override public void complete(String threadId, String turnId) {
            delegate.complete(threadId, turnId);
        }

        /** 关闭底层协调器并清理全部真实注册。 */
        @Override public void close() {
            delegate.close();
        }
    }

    /** 返回空 Tool 集的 MCP 会话工厂，使 Turn 服务测试隔离远程 Tool。 */
    private static final class EmptyMcp implements TurnToolSessionFactory {
        /** 为每轮返回独立空会话，避免会话状态跨测试共享。 */
        @Override public Session open(CancellationToken cancellationToken) {
            return new Session() {
                /** 固定返回空 Tool 列表，确保测试只执行模型与生命周期路径。 */
                @Override public List<io.github.kongweiguang.ja.conversation.port.out.AgentTool> tools() {
                    return List.of();
                }
                /** 空会话无需释放外部资源，关闭保持无副作用。 */
                @Override public void close() { }
            };
        }
    }

    /** 运行到 Repository.resume 并返回待验证异常，确保租约与队列清理走真实服务路径。 */
    private static RuntimeException resumeFailure(StorageException.Code code) {
        RecordingStore store = new RecordingStore();
        store.prepareResumeFailure(code);
        ModelPort model = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("model must not run"));
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations,
                     runtimeResolver(new AtomicInteger()), CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            return assertThrows(RuntimeException.class, () -> service.resume(
                    "turn_resume", 0, event -> CompletableFuture.completedFuture(null)));
        }
    }

    /** 注入解析阶段失败并运行真实 Resume 前半段，证明错误映射不会越过 Repository CAS。 */
    private static RuntimeException resumeResolutionFailure(RuntimeException injected) {
        RecordingStore store = new RecordingStore();
        store.prepareResumeCandidate();
        ModelPort model = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("model must not run"));
        AgentLoop loop = new AgentLoop(metered(model), new NoopApproval(), store, contextFactory(store),
                argumentsCodec(), argumentValidator(), List.of(), List.of(), CLOCK);
        TurnQueue queue = new TurnQueue(8, 4, 1);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        TurnRuntimeResolver resolver = new TurnRuntimeResolver() {
            /** 在返回任何租约前抛出指定稳定分类。 */
            @Override public RuntimeLease resolve(TurnRuntimeRequest request) { throw injected; }

            /** Resume 测试不执行工作区预热。 */
            @Override public void prepareWorkspace(Path workspaceRoot) { }
        };
        try (queue; loop; cancellations;
             TurnService service = new TurnService(store, loop, queue, cancellations, resolver, CLOCK,
                     AutomaticThreadTitleScheduler.disabled(), WORKSPACE_REFERENCES)) {
            return assertThrows(RuntimeException.class, () -> service.resume(
                    "turn_resume", 0, event -> CompletableFuture.completedFuture(null)));
        }
    }

    /** 记录 revision、Turn 状态与提交次数的存储假实现，用于断言生命周期顺序。 */
    private static final class RecordingStore implements ConversationRepository {
        /** 服务层夹具不注入 Child mailbox；执行器若误用必须显式失败。 */
        @Override public TaskMailboxConsumption consumeTaskMailbox(TaskMailboxCommit request) {
            throw new UnsupportedOperationException("task mailbox is not configured by this test");
        }
        private long revision;
        private long turnMutationVersion;
        private TurnState state = TurnState.QUEUED;
        private boolean cancellationClaimed;
        private boolean rejectCancellationClaims;
        private StorageException cancellationStorageFailure;
        private int terminalCommitFailures;
        private TerminalCommit lastTerminalCommit;
        private boolean provisionalTitleEnabled = true;
        private final AtomicInteger terminals = new AtomicInteger();
        private final List<StoredMessage> messages = new java.util.ArrayList<>();
        private final List<PendingInput> queuedInputs = new java.util.ArrayList<>();
        private final Map<String, Long> queuedInputRevisions = new HashMap<>();
        private final Map<String, InputQueue.Issue> queuedInputIssues = new HashMap<>();
        private long inputQueueRevision;
        private TurnAdmission lastAdmission;
        private ResumeCandidate resumeCandidate;
        private StorageException resumeFailure;

        /** 回读当前 Thread revision，供测试验证每次持久化推进。 */
        synchronized long revision() { return revision; }
        /** 回读当前 Turn 状态，证明失败路径未越过允许的状态边。 */
        synchronized TurnState state() {
            return state;
        }
        /** 返回最近一次准入事实，供附件测试断言消息与关系使用同一 ID 集合。 */
        synchronized TurnAdmission lastAdmission() {
            return lastAdmission;
        }
        /** 返回排队输入快照，避免并发 Turn owner 与断言共享可变集合。 */
        synchronized List<PendingInput> queuedInputs() {
            return List.copyOf(queuedInputs);
        }
        /** 模拟实际 Repository 已存在首条消息时的 admission 回执，后续 Turn 不再取得标题所有权。 */
        synchronized void disableProvisionalTitle() {
            provisionalTitleEnabled = false;
        }
        /** 配置完整 SUSPENDED 候选，并把指定存储分类注入原子 Resume 门。 */
        synchronized void prepareResumeFailure(StorageException.Code code) {
            prepareResumeCandidate();
            resumeFailure = new StorageException(code, "forced resume failure");
        }
        /** 配置完整 SUSPENDED 候选，使测试可在解析或 Repository CAS 边界注入失败。 */
        synchronized void prepareResumeCandidate() {
            prepareResumeCandidate("", "", List.of());
        }
        /** 构造真窗故障对应的挂起队首：附件仍被预留，条目已因消费失败推进到 revision 2。 */
        synchronized void prepareSuspendedAttachmentInput() {
            prepareResumeCandidate();
            PendingInput input = new PendingInput("input_suspended", "thr_test", "turn_resume",
                    InputKind.FOLLOW_UP, content("", List.of("att_missing")), CLOCK.instant());
            queuedInputs.add(input);
            queuedInputRevisions.put(input.inputId(), 2L);
            queuedInputIssues.put(input.inputId(), new InputQueue.Issue(
                    "ATTACHMENT_UNAVAILABLE", "排队附件已不可用，请移除后再继续。", true));
            inputQueueRevision = 2;
        }
        /** 配置摘要与 Active Skill 都非空的候选，证明服务不会混淆 Prompt 校验与下一轮上下文。 */
        synchronized void prepareResumeCandidate(String promptSummary, String latestSummary,
                                                 List<TurnExecutionState.ActiveSkill> activeSkills) {
            state = TurnState.SUSPENDED;
            TurnExecutionState.Common common = new TurnExecutionState.Common(
                    0, 0, 1, promptSummary.isEmpty() ? null : "cp_prompt",
                    activeSkills, CLOCK.instant().plus(TurnLimits.defaults().wallTimeout()),
                    io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER,
                    TurnLimits.defaults().wallTimeout());
            if (messages.isEmpty()) {
                messages.add(new StoredMessage("item_user_resume", "turn_resume", 1,
                        new ModelMessage(ModelRole.USER, List.of(new TextContent("resume"))), CLOCK.instant()));
            }
            resumeCandidate = new ResumeCandidate("thr_test", "turn_resume", "ws_test",
                    Path.of("C:\\ja-test-workspace"), 0, 0,
                    new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null),
                    promptSummary, latestSummary, content("resume"), null, false);
        }
        /** 将已准备候选标记为首轮标题 owner，用于验证跨进程恢复不丢失一次性标题责任。 */
        synchronized void prepareTitleEligibleResumeCandidate(String originalUserInput) {
            prepareResumeCandidate();
            resumeCandidate = new ResumeCandidate(resumeCandidate.threadId(), resumeCandidate.turnId(),
                    resumeCandidate.workspaceId(), resumeCandidate.workspaceRoot(),
                    resumeCandidate.threadRevision(), resumeCandidate.turnMutationVersion(),
                    resumeCandidate.execution(), resumeCandidate.promptSummary(),
                    resumeCandidate.latestCheckpointSummary(), content(originalUserInput), null, true);
        }
        /** 禁止用例创建 Thread，确保测试从已存在会话快照开始。 */
        @Override public ThreadSnapshot createThread(ThreadDefinition thread) { throw new UnsupportedOperationException(); }
        /** 记录用户消息并推进准入 revision，复现队列前的原子持久化。 */
        @Override public synchronized AdmissionReceipt admit(TurnAdmission admission) {
            lastAdmission = admission;
            messages.add(new StoredMessage(admission.messageId(), admission.turnId(), messages.size() + 1L,
                    admission.userMessage(), admission.requestedAt()));
            return new AdmissionReceipt(admission.threadId(), admission.turnId(), ++revision, 0,
                    provisionalTitleEnabled && messages.size() == 1 ? "测试首问" : null);
        }
        /** 返回预置恢复候选；普通服务测试没有配置时保持空。 */
        @Override public synchronized Optional<ResumeCandidate> findResumeCandidate(String turnId) {
            return Optional.ofNullable(resumeCandidate);
        }
        /** 注入精确存储失败，防止服务测试依赖异常文本映射。 */
        @Override public synchronized ResumeReceipt resume(String turnId, long expectedThreadRevision,
                                                            long expectedTurnMutationVersion,
                                                            Instant occurredAt) {
            if (resumeFailure != null) throw resumeFailure;
            return new ResumeReceipt("thr_test", turnId, ++revision, ++turnMutationVersion);
        }
        /** 校验 mutation version 后记录中间态，防止测试绕过 CAS 语义。 */
        @Override public synchronized CommitReceipt commit(CommitRequest request) {
            assertEquals(turnMutationVersion, request.expectedTurnMutationVersion());
            state = request.state();
            return new CommitReceipt(++revision, ++turnMutationVersion);
        }
        /** Service 夹具不执行 Agent STOP 结算，调用即表明测试越过服务职责边界。 */
        @Override public CommitReceipt commitAssistantSettlement(CommitRequest request) {
            throw new UnsupportedOperationException("Assistant settlement is outside TurnService tests");
        }
        /** 记录默认 FOLLOW_UP，并返回可用于 ACK/event 收敛的完整队列。 */
        @Override public synchronized QueueMutation enqueueInput(PendingInput input) {
            queuedInputs.add(input);
            queuedInputRevisions.put(input.inputId(), 1L);
            inputQueueRevision++;
            return new QueueMutation(input.inputId(), inputQueue(), revision, true);
        }
        /** Fake 按 identity 把普通输入提升为 Steering，并模拟首个正 item revision。 */
        @Override public synchronized QueueMutation prioritizeInput(String threadId, String turnId,
                                                                     String inputId, long expectedInputRevision,
                                                                     Instant occurredAt) {
            for (int index = 0; index < queuedInputs.size(); index++) {
                PendingInput input = queuedInputs.get(index);
                if (input.inputId().equals(inputId)) {
                    requireInputRevision(inputId, expectedInputRevision);
                    queuedInputs.set(index, new PendingInput(input.inputId(), input.threadId(), input.turnId(),
                            InputKind.STEERING, input.content(), input.createdAt()));
                    queuedInputRevisions.put(inputId, expectedInputRevision + 1);
                    inputQueueRevision++;
                    return new QueueMutation(inputId, inputQueue(), revision, true);
                }
            }
            throw InputQueueException.of(InputQueueFailure.NOT_FOUND);
        }
        /** 编辑用精确 item revision 替换内容并清除旧问题，模拟 Repository 的单事务修复语义。 */
        @Override public synchronized QueueMutation updateInput(
                String threadId, String turnId, String inputId, long expectedInputRevision,
                UserContent content, Instant occurredAt) {
            for (int index = 0; index < queuedInputs.size(); index++) {
                PendingInput input = queuedInputs.get(index);
                if (!input.inputId().equals(inputId)) continue;
                requireInputRevision(inputId, expectedInputRevision);
                queuedInputs.set(index, new PendingInput(input.inputId(), input.threadId(), input.turnId(),
                        input.kind(), content, input.createdAt()));
                queuedInputRevisions.put(inputId, expectedInputRevision + 1);
                queuedInputIssues.remove(inputId);
                inputQueueRevision++;
                return new QueueMutation(inputId, inputQueue(), revision, true);
            }
            throw InputQueueException.of(InputQueueFailure.NOT_FOUND);
        }
        /** 删除用精确 item revision 清理队列投影；附件丢弃由生产 Repository 的既有事务测试覆盖。 */
        @Override public synchronized QueueMutation deleteInput(
                String threadId, String turnId, String inputId, long expectedInputRevision,
                Instant occurredAt) {
            requireInputRevision(inputId, expectedInputRevision);
            boolean removed = queuedInputs.removeIf(input -> input.inputId().equals(inputId));
            if (!removed) throw InputQueueException.of(InputQueueFailure.NOT_FOUND);
            queuedInputRevisions.remove(inputId);
            queuedInputIssues.remove(inputId);
            inputQueueRevision++;
            return new QueueMutation(inputId, inputQueue(), revision, true);
        }
        /** Fake 与生产一样先区分缺失 identity 和过期 revision，不解析异常文本。 */
        private void requireInputRevision(String inputId, long expectedInputRevision) {
            Long actual = queuedInputRevisions.get(inputId);
            if (actual == null) throw InputQueueException.of(InputQueueFailure.NOT_FOUND);
            if (actual != expectedInputRevision) throw InputQueueException.of(InputQueueFailure.CONFLICT);
        }
        /** 服务层 fixture 不模拟运行期排队，STOP continuation 必须明确返回空而非继承生产语义。 */
        @Override public Optional<InputConsumption> commitWithNextInput(
                CommitRequest request, InputSelection selection) {
            return Optional.empty();
        }

        /** Fake 全量投影遵循 Steering 优先、普通 FIFO 的真实顺序。 */
        private InputQueue inputQueue() {
            List<InputQueue.QueuedInput> items = java.util.stream.Stream.concat(
                            queuedInputs.stream().filter(input -> input.kind() == InputKind.STEERING),
                            queuedInputs.stream().filter(input -> input.kind() == InputKind.FOLLOW_UP))
                    .map(input -> {
                        InputQueue.Issue issue = queuedInputIssues.get(input.inputId());
                        List<AttachmentSummary> attachments = input.content().attachmentIds().stream()
                                .map(attachmentId -> new AttachmentSummary(
                                        attachmentId, "missing.png", 16, "image", "image/png"))
                                .toList();
                        return new InputQueue.QueuedInput(input.inputId(), input.turnId(), input.content(),
                                InputQueue.Kind.valueOf(input.kind().name()), attachments,
                                issue == null ? InputQueue.Status.PENDING : InputQueue.Status.NEEDS_ATTENTION,
                                issue, queuedInputRevisions.getOrDefault(input.inputId(), 1L), input.createdAt());
                    })
                    .toList();
            String turnId = resumeCandidate == null ? "turn_test" : resumeCandidate.turnId();
            return new InputQueue(turnId, inputQueueRevision, true, items);
        }
        /** 服务层测试不执行 Tool；若误入取消 Tool 收敛边界应立即暴露错误。 */
        @Override public CommitReceipt commitCancellationToolBatch(CancellationToolBatchCommit request) {
            throw new UnsupportedOperationException("TurnService does not commit Tool batches");
        }
        /** 注入可控终态失败并记录唯一成功提交，用于断言不重复结算。 */
        @Override public synchronized CommitReceipt commitTerminal(TerminalCommit request) {
            if (terminalCommitFailures > 0) {
                terminalCommitFailures--;
                throw new StorageException(StorageException.Code.TRANSACTION,
                        "forced terminal commit failure");
            }
            long expected = request.expectedTurnMutationVersion();
            assertEquals(turnMutationVersion, expected);
            if (cancellationClaimed) assertEquals(TurnState.CANCELLED, request.state());
            lastTerminalCommit = request;
            state = request.state();
            terminals.incrementAndGet();
            return new CommitReceipt(++revision, ++turnMutationVersion);
        }
        /** 原子记录取消声明及新 revision，模拟生产存储的取消所有权边界。 */
        @Override public synchronized CancellationClaim claimCancellation(String threadId, String turnId,
                long expectedThreadRevision, String reason, Instant occurredAt) {
            if (cancellationStorageFailure != null) throw cancellationStorageFailure;
            if (rejectCancellationClaims) {
                throw ConversationRepository.CancellationClaimException.of(
                        ConversationRepository.CancellationFailure.CONFLICT);
            }
            if (state.terminal()) {
                throw ConversationRepository.CancellationClaimException.of(
                        ConversationRepository.CancellationFailure.CONFLICT);
            }
            if (cancellationClaimed) {
                return new CancellationClaim(true, state, revision, turnMutationVersion);
            }
            if (revision != expectedThreadRevision) {
                throw ConversationRepository.CancellationClaimException.of(
                        ConversationRepository.CancellationFailure.CONFLICT);
            }
            cancellationClaimed = true;
            return new CancellationClaim(true, state, ++revision, ++turnMutationVersion);
        }
        /** 返回当前 Turn 快照，使取消流程以持久状态而非内存猜测决策。 */
        @Override public synchronized Optional<TurnSnapshot> findTurn(String threadId, String turnId) {
            return Optional.of(new TurnSnapshot(threadId, turnId, state,
                    CLOCK.instant(), CLOCK.instant(), state.terminal() ? CLOCK.instant() : null, revision,
                    turnMutationVersion));
        }
        /** 返回包含当前准入 Turn 与消息的 Thread 快照，避免夹具硬编码身份把后续 Turn 误判为缺失。 */
        @Override public synchronized Optional<ThreadSnapshot> readThread(String threadId) {
            String admittedTurnId = lastAdmission != null ? lastAdmission.turnId()
                    : resumeCandidate == null ? "turn_test" : resumeCandidate.turnId();
            TurnSnapshot turn = new TurnSnapshot("thr_test", admittedTurnId, state,
                    CLOCK.instant(), CLOCK.instant(), state.terminal() ? CLOCK.instant() : null, revision,
                    turnMutationVersion);
            return Optional.of(new ThreadSnapshot("thr_test", "ws_test", "test", preferences(), revision,
                    List.of(turn), messages, CLOCK.instant(), CLOCK.instant()));
        }
        /** 夹具不持有外部资源，关闭保留记录供测试完成最终断言。 */
        @Override public void close() { }
    }

    /** 记录恢复与下一轮 prepare 的摘要边界，避免测试只检查最终状态而漏掉 Prompt 接线错误。 */
    private static final class TrackingPromptSession implements AgentPromptSession {
        private static final String REVISION = "prompt_fixture";
        private final List<TurnExecutionState.ActiveSkill> activeSkills;
        private final List<String> restoredSummaries = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<List<TurnExecutionState.ActiveSkill>> restoredSkills =
                new AtomicReference<>();
        private final List<String> preparedSummaries = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** 固定预期 Skill 引用，使 restore 与随后 Tool guard 使用同一快照。 */
        private TrackingPromptSession(List<TurnExecutionState.ActiveSkill> activeSkills) {
            this.activeSkills = List.copyOf(activeSkills);
        }

        /** 记录下一轮实际摘要，并返回与持久 revision 一致的固定 Prompt。 */
        @Override public PreparedPrompt prepare(String summary, List<ToolSpec> tools) {
            preparedSummaries.add(summary);
            return new PreparedPrompt(new AgentPromptSnapshot("fixture system", REVISION, 4),
                    ContextBudget.capabilities(1_000_000, 8_192, true));
        }
        /** 测试没有 Tool，门禁保持允许但仍满足完整端口。 */
        @Override public ToolGuard beforeTool(
                AgentTool.Invocation invocation, ToolSideEffect sideEffect, String batchRevision) {
            return ToolGuard.allow();
        }
        /** 测试没有 Tool settlement，不产生额外 Prompt 变化。 */
        @Override public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) { }
        /** 恢复测试不允许执行期新增 Skill。 */
        @Override public SkillActivation activateSkill(SkillCatalog.SkillDocument document) {
            throw new AssertionError("unexpected Skill activation");
        }
        /** 恢复夹具只允许当前 Skill 集对应的稳定 ID 被校验。 */
        @Override public void validateSkillReferences(List<String> skillIds) {
            assertEquals(activeSkills.stream().map(TurnExecutionState.ActiveSkill::skillId).toList(), skillIds);
        }
        /** 测试候选只携带稳定 ID，commit 不引入额外文件 IO。 */
        @Override public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
            validateSkillReferences(skillIds);
            return new SkillReplacement() {
                /** 返回持久 execution 引用的 revision。 */
                @Override public String promptRevision() { return REVISION; }
                /** 返回消息边界替换后的稳定引用。 */
                @Override public List<TurnExecutionState.ActiveSkill> activeSkillReferences() { return activeSkills; }
                /** 该夹具没有可变 Prompt 正文，提交只表示消费 CAS 已成功。 */
                @Override public void commit() { }
            };
        }
        /** 测试不读取 Skill 文件，仅以名称集合模拟一次消息边界的原子替换。 */
        @Override public void replaceActiveSkills(List<String> skillIds) {
            validateSkillReferences(skillIds);
        }
        /** 返回持久 execution 引用的 revision。 */
        @Override public String currentRevision() { return REVISION; }
        /** 返回恢复后按名称记录的 Active Skill 引用。 */
        @Override public List<TurnExecutionState.ActiveSkill> activeSkillReferences() { return activeSkills; }
        /** 精确记录旧 checkpoint summary 与 Skill 名称，不要求历史正文或 Prompt revision。 */
        @Override public void restoreActiveSkills(String summary,
                                                   List<TurnExecutionState.ActiveSkill> references) {
            assertEquals(activeSkills, references);
            restoredSummaries.add(summary);
            restoredSkills.set(List.copyOf(references));
        }
    }

    /** 禁止意外审批调用的假代理，使服务用例不会静默跨入人工确认路径。 */
    private static final class NoopApproval implements ApprovalBroker {
        /** 任何审批请求都立即失败，暴露测试编排中的非预期危险动作。 */
        @Override public CompletionStage<Resolution> request(ApprovalRequest request,
                CancellationToken cancellationToken) { return CompletableFuture.failedFuture(new AssertionError()); }
        /** 忽略外部响应，因为本夹具从不创建可解析的审批。 */
        @Override public boolean resolve(String approvalId, ApprovalDecision response, Instant resolvedAt) { return false; }
        /** 无待决审批可取消，保持取消测试只观察 Turn 协调器。 */
        @Override public void cancelTurn(String threadId, String turnId, String reason) { }
    }
}


