// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextCompactionLifecycle;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.conversation.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEventSink;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** 在空闲 Thread 上复用生产 ContextOrchestrator，且永不执行普通模型发送。 */
public final class ManualContextCompactionService implements ContextCompactionUseCase {
    private static final Duration MANUAL_DEADLINE = Duration.ofSeconds(290);

    private final ConversationRepository conversations;
    private final CheckpointStore checkpoints;
    private final WorkspaceUseCase workspaces;
    private final TurnRuntimeResolver runtimes;
    private final io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory contexts;
    private final ModelPort models;
    private final AgentContextMapper mapper;
    private final Clock clock;

    /** 固定全部权威 Owner，手动入口不通过 RPC 或配置文档旁路任何生产依赖。 */
    public ManualContextCompactionService(
            ConversationRepository conversations,
            CheckpointStore checkpoints,
            WorkspaceUseCase workspaces,
            TurnRuntimeResolver runtimes,
            io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory contexts,
            ModelPort models,
            JsonValueCodec argumentsCodec,
            Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.mapper = new AgentContextMapper(Objects.requireNonNull(argumentsCodec, "argumentsCodec"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 冻结 Thread、Provider/Model、Prompt、Tool schema 与配置代际后执行强制压缩；sender 为空操作，
     * 因此唯一付费副作用是必要的 Token 计量和 Summary，而不会生成普通 assistant 回复。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public Result compact(Command command, ContextCompactionEventSink events, CancellationToken cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) throw failure(Code.CANCELLED);
        ConversationRepository.ThreadSnapshot snapshot = conversations.readThread(command.threadId())
                .orElseThrow(() -> failure(Code.THREAD_NOT_FOUND));
        if (snapshot.revision() != command.expectedThreadRevision()) throw failure(Code.CONFLICT);
        if (snapshot.turns().stream().anyMatch(turn -> !turn.state().terminal())) {
            throw failure(Code.THREAD_BUSY);
        }
        CheckpointStore.Snapshot checkpointSnapshot = checkpoints.read(command.threadId());
        Optional<CheckpointStore.ContextCheckpoint> current = checkpointSnapshot.checkpoint();
        if (current.isPresent()
            && checkpointSnapshot.threadRevision() == snapshot.revision()
            && snapshot.revision() == current.orElseThrow().sourceRevision() + 1L) {
            long tokens = current.orElseThrow().estimatedTokens();
            return new Result(Outcome.UNCHANGED, null, null, snapshot.revision(), tokens, tokens);
        }
        List<ContextMessage> history = mapper.fromCompleteSnapshot(snapshot);
        if (history.isEmpty()) {
            return new Result(Outcome.UNCHANGED, null, null, snapshot.revision(), 0, 0);
        }
        Workspace workspace = workspaces.requireOpenWorkspace(snapshot.workspaceId());
        io.github.kongweiguang.ja.conversation.domain.ThreadPreferences preferences =
                snapshot.preferences();
        java.time.Instant requestedAt = clock.instant();
        String compactionId = "cmp_" + UUID.randomUUID().toString().replace("-", "");
        ContextCompactionLifecycle lifecycle = new ContextCompactionLifecycle(
                snapshot.workspaceId(), snapshot.threadId(), null, snapshot.revision(), compactionId,
                events, clock);
        TurnRuntimeRequest runtimeRequest = new TurnRuntimeRequest(
                snapshot.threadId(), workspace.root(), workspace.workspaceId(),
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), MANUAL_DEADLINE, requestedAt);
        try (RuntimeLease runtime = runtimes.resolve(runtimeRequest);
             TurnToolSessionFactory.Session mcp = runtime.toolSessions().open(cancellation)) {
            List<AgentTool> tools = new ArrayList<>(runtime.tools());
            tools.addAll(mcp.tools());
            List<ToolSpec> specs = tools.stream().map(AgentTool::spec).toList();
            AgentPromptSession.PreparedPrompt initial = runtime.promptSession().prepare("", specs);
            ContextTokenMeter meter = meter(runtime, specs, cancellation, snapshot.threadId());
            ContextOrchestrator orchestrator = contexts.create(new SummaryModel.TurnBinding(
                    snapshot.threadId(), runtime.model(), requestedAt.plus(runtime.limits().wallTimeout()),
                    cancellation));
            ContextOrchestrator.Execution<Void> execution = orchestrator.execute(
                    new ContextOrchestrator.Request(snapshot.threadId(), snapshot.revision(), history,
                            initial.budget(), true, Optional.empty(), runtime.outputLimits(), meter,
                            cancellation),
                    receipt -> { }, prompt -> null, lifecycle, ContextCompactionEvent.Trigger.MANUAL);
            CheckpointStore.ContextCheckpoint checkpoint = execution.checkpoint()
                    .orElseThrow(() -> failure(Code.INVALID_STATE));
            long committedRevision = execution.committedReceipt()
                    .map(CheckpointStore.CommittedCheckpoint::threadRevision)
                    .orElseGet(() -> conversations.readThread(snapshot.threadId())
                            .map(ConversationRepository.ThreadSnapshot::revision)
                            .orElseThrow(() -> failure(Code.INVALID_STATE)));
            return new Result(Outcome.COMPACTED, compactionId, checkpoint.checkpointId(),
                    committedRevision, lifecycle.inputTokensBefore(), execution.prompt().estimatedTokens());
        } catch (java.util.concurrent.CancellationException failure) {
            if (!lifecycle.terminated()) {
                lifecycle.cancelled(ContextCompactionEvent.Trigger.MANUAL);
            }
            throw failure(Code.CANCELLED);
        } catch (Failure failure) {
            throw failure;
        } catch (ContextException failure) {
            throw map(failure);
        } catch (RuntimeException failure) {
            if (!lifecycle.terminated()) {
                lifecycle.failBeforeStart(ContextCompactionEvent.Trigger.MANUAL,
                        ContextException.Code.INVALID_STATE);
            }
            throw failure(Code.INVALID_STATE);
        }
    }

    /**
     * 构造只做官方 Token 计量的完整 Provider envelope，并复用真实发送的附件双门，避免计量与实际请求分叉。
     */
    private ContextTokenMeter meter(RuntimeLease runtime, List<ToolSpec> tools,
                                    CancellationToken cancellation, String threadId) {
        return (messages, summary, continuation, localCompaction) -> {
            AgentPromptSession.PreparedPrompt prepared = runtime.promptSession()
                    .prepare(summary.toPromptText(), tools);
            ContextOrchestrator.PreparedPrompt context = new ContextOrchestrator.PreparedPrompt(
                    messages, summary, 0, continuation, localCompaction);
            ModelPort.ModelRequest request = mapper.toModelRequest(
                    context, runtime.model(), prepared.snapshot(), tools, null, 1,
                    threadId, runtime.attachments(),
                    models.nativeAttachmentSupport(runtime.model()));
            try {
                ModelPort.InputTokenCount count = models.countInputTokens(request, cancellation)
                        .toCompletableFuture().join();
                return new ContextTokenMeter.Measurement(count.tokens(), count.fingerprint());
            } catch (CompletionException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
                throw new ContextException(ContextException.Code.TOKEN_COUNT_UNAVAILABLE,
                        "manual compaction token count is unavailable", cause);
            } catch (ModelPort.TokenCountUnavailableException unavailable) {
                throw new ContextException(ContextException.Code.TOKEN_COUNT_UNAVAILABLE,
                        "manual compaction token count is unavailable", unavailable);
            }
        };
    }

    /** 将内部 Context 错误收敛为手动入口稳定闭集，不泄漏 Provider 或存储细节。 */
    private static Failure map(ContextException failure) {
        return switch (failure.code()) {
            case CAS_CONFLICT -> failure(Code.CONFLICT);
            case TOKEN_COUNT_UNAVAILABLE -> failure(Code.TOKEN_COUNT_UNAVAILABLE);
            case SUMMARY_FAILURE -> failure(Code.SUMMARY_FAILURE);
            case CONTEXT_LIMIT -> failure(Code.CONTEXT_LIMIT);
            case INVALID_STATE -> failure(Code.INVALID_STATE);
        };
    }

    /** 创建无堆栈稳定失败，具体技术异常不得越过应用边界。 */
    private static Failure failure(Code code) {
        return new Failure(code);
    }
}
