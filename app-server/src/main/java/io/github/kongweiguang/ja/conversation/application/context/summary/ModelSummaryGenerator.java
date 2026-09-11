// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将结构化摘要端口绑定到当前 Turn，并在模型调用前后执行取消、Deadline 与容量门禁。
 */
public final class ModelSummaryGenerator implements SummaryGenerator {
    public static final String PROMPT_VERSION = "ja-context-summary-v1";
    private static final Limits DEFAULT_LIMITS = new Limits(2_000, 4_000_000, 16_384);

    private final SummaryModel.Factory models;
    private final RequestRuntimeFactory runtimes;
    private final Clock clock;
    private final Limits limits;
    private final SummaryOperation operation;

    /**
     * 使用统一生产上限构造生成器，避免不同调用链产生不一致的摘要容量。
     */
    public ModelSummaryGenerator(SummaryModel model, SummaryModel.TurnBinding binding, Clock clock) {
        this(model, binding, clock, DEFAULT_LIMITS, SummaryOperation.none());
    }

    /**
     * 固定模型、Turn 绑定、时钟和限制，使一次慢摘要调用只能服务其原始 Thread。
     */
    public ModelSummaryGenerator(SummaryModel model, SummaryModel.TurnBinding binding, Clock clock, Limits limits) {
        this(model, binding, clock, limits, SummaryOperation.none());
    }

    /**
     * 注入 Turn Operation 持久边界；手动压缩使用 no-op，自动压缩必须由 Agent Loop 提供真实实现。
     */
    public ModelSummaryGenerator(SummaryModel model, SummaryModel.TurnBinding binding, Clock clock,
                                 Limits limits, SummaryOperation operation) {
        this(ignored -> Objects.requireNonNull(model, "model"),
                () -> RequestRuntime.unprofiled(binding), clock, limits, operation);
    }

    /**
     * 每次本地计量或 Provider 调用都从 factory 打开独立请求环境；自动摘要由此在每个
     * candidate、repair 和 chunk 安全点读取最新模型与完整 Profile。
     */
    public ModelSummaryGenerator(SummaryModel.Factory models, RequestRuntimeFactory runtimes, Clock clock,
                                 Limits limits, SummaryOperation operation) {
        this.models = Objects.requireNonNull(models, "models");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /**
     * 按完整 Turn 组成 Provider 精确计量可容纳的最大块，并滚动生成全量替换摘要。
     */
    @Override
    public SummaryResult generate(SummaryRequest request) {
        Objects.requireNonNull(request, "request");
        validateInput(request);
        int outputBudget = outputBudget(request);
        long inputCeiling = request.budget().contextWindowTokens() - outputBudget;
        List<List<ContextMessage>> turns = completeTurns(request.evictedMessages());
        String planFingerprint = fingerprint(request);
        SummaryDocument initial = request.previousSummary().orElseGet(SummaryDocument::empty);
        SummaryOperation.Progress progress = operation.start(planFingerprint, initial);
        SummaryDocument rolling = progress.document();
        CheckpointUsage usage = progress.usage();
        int cursor = progress.nextTurn();
        if (cursor < 0 || cursor > turns.size()) {
            throw failure("persisted summary cursor exceeded the frozen plan", null);
        }
        while (cursor < turns.size()) {
            List<ContextMessage> chunk = new java.util.ArrayList<>();
            int accepted = cursor;
            while (accepted < turns.size()) {
                List<ContextMessage> candidate = new java.util.ArrayList<>(chunk);
                candidate.addAll(turns.get(accepted));
                SummaryRequest candidateRequest = chunkRequest(request, rolling, candidate);
                if (measure(candidateRequest, outputBudget) > inputCeiling) break;
                chunk = candidate;
                accepted++;
            }
            if (chunk.isEmpty()) {
                throw failure("a complete summary Turn exceeded the provider input window", null);
            }
            SummaryResult generated = generateChunk(chunkRequest(request, rolling, chunk),
                    outputBudget, inputCeiling, planFingerprint, accepted,
                    chunk.getLast().ordinal(), progress);
            rolling = generated.document();
            usage = generated.usage();
            cursor = accepted;
            progress = SummaryOperation.Progress.candidate(
                    rolling, usage, cursor, chunk.getLast().ordinal(), planFingerprint);
        }
        return new SummaryResult(rolling, usage);
    }

    /** 自动 Summary 步骤提交后，checkpoint 使用 Operation 返回的最新 Thread revision。 */
    @Override
    public long checkpointSourceRevision(long originalRevision) {
        return operation.checkpointSourceRevision(originalRevision);
    }

    /** 只有自动 Turn Summary 返回 Turn-aware checkpoint CAS 参数。 */
    @Override
    public Optional<CheckpointStore.TurnOperation> checkpointTurnOperation() {
        return operation.checkpointTurnOperation();
    }

    /**
     * 按持久子阶段执行候选、唯一修复或确定性回退；每次 Provider settlement 同时保存下一阶段，
     * 消除“用量已结算但阶段未推进”的强杀窗口。
     */
    private SummaryResult generateChunk(SummaryRequest request, int outputBudget, long inputCeiling,
                                        String planFingerprint, int nextTurn, long throughOrdinal,
                                        SummaryOperation.Progress persisted) {
        SummaryOperation.Progress current = persisted;
        if (current.stage() != SummaryOperation.Stage.CANDIDATE) {
            requirePendingChunk(current, nextTurn, throughOrdinal, planFingerprint);
        }
        if (current.stage() == SummaryOperation.Stage.CANDIDATE) {
            SummaryResult first = summarize(request, outputBudget, List.of(), inputCeiling, null);
            CheckpointUsage usage = addUsage(current.usage(), first.usage());
            List<String> violations = validateResult(request, first, outputBudget);
            if (violations.isEmpty()) {
                SummaryOperation.Progress accepted = SummaryOperation.Progress.candidate(
                        first.document(), usage, nextTurn, throughOrdinal, planFingerprint);
                operation.settle(first.usage(), accepted);
                return new SummaryResult(first.document(), usage);
            }
            SummaryModel.SummaryPrompt repairPrompt = prompt(request, outputBudget, violations);
            current = current.pending(SummaryOperation.Stage.REPAIR, usage, nextTurn,
                    throughOrdinal, violations, fingerprint(repairPrompt));
            operation.settle(first.usage(), current);
        }
        if (current.stage() == SummaryOperation.Stage.REPAIR) {
            requirePendingChunk(current, nextTurn, throughOrdinal, planFingerprint);
            SummaryResult repaired = summarize(request, outputBudget, current.violations(),
                    inputCeiling, current.promptFingerprint());
            CheckpointUsage usage = addUsage(current.usage(), repaired.usage());
            List<String> repairViolations = validateResult(request, repaired, outputBudget);
            if (repairViolations.isEmpty()) {
                SummaryOperation.Progress accepted = SummaryOperation.Progress.candidate(
                        repaired.document(), usage, nextTurn, throughOrdinal, planFingerprint);
                operation.settle(repaired.usage(), accepted);
                return new SummaryResult(repaired.document(), usage);
            }
            current = current.pending(SummaryOperation.Stage.FALLBACK_PENDING, usage, nextTurn,
                    throughOrdinal, repairViolations, current.promptFingerprint());
            operation.settle(repaired.usage(), current);
        }
        requirePendingChunk(current, nextTurn, throughOrdinal, planFingerprint);
        SummaryDocument fallback = SummaryDocument.evidenceLedger(
                request.previousSummary().orElseGet(SummaryDocument::empty), request.evictedMessages());
        SummaryResult deterministic = new SummaryResult(fallback, CheckpointUsage.none());
        if (!validateResult(request, deterministic, outputBudget).isEmpty()) {
            throw failure("summary repair and deterministic evidence ledger were invalid", null);
        }
        SummaryOperation.Progress accepted = SummaryOperation.Progress.candidate(
                fallback, current.usage(), nextTurn, throughOrdinal, planFingerprint);
        operation.advance(accepted);
        return new SummaryResult(fallback, current.usage());
    }

    /**
     * 执行一次已精确计量的摘要调用；恢复 REPAIR 时必须匹配持久 prompt 指纹，禁止重构不同请求。
     */
    private SummaryResult summarize(SummaryRequest request, int outputBudget,
                                    java.util.List<String> violations, long inputCeiling,
                                    String expectedPromptFingerprint) {
        SummaryModel.SummaryPrompt prompt = prompt(request, outputBudget, violations);
        String promptFingerprint = fingerprint(prompt);
        if (expectedPromptFingerprint != null && !expectedPromptFingerprint.equals(promptFingerprint)) {
            throw failure("persisted summary prompt no longer matches the recorded substep", null);
        }
        try (RequestRuntime runtime = openRuntime(prompt.threadId())) {
            SummaryModel current = bind(runtime);
            if (measure(current, runtime.binding(), prompt) > inputCeiling) {
                throw failure("summary repair prompt exceeded the provider input window", null);
            }
            operation.begin(promptFingerprint, runtime.profile());
            SummaryResult result = current.summarize(prompt);
            requireActiveBinding(prompt.threadId(), runtime.binding());
            if (result == null) throw failure("summary model returned no structured result", null);
            return result;
        }
    }

    /**
     * 恢复中的修复或回退必须仍指向本轮重新推导出的同一块，否则保持 SUSPENDED 而不发送 Provider 请求。
     */
    private static void requirePendingChunk(SummaryOperation.Progress progress, int nextTurn,
                                            long throughOrdinal, String planFingerprint) {
        if (progress.targetNextTurn() != nextTurn
            || progress.targetThroughOrdinal() != throughOrdinal
            || !progress.planFingerprint().equals(planFingerprint)) {
            throw failure("persisted summary substep no longer matches the frozen chunk", null);
        }
    }

    /** 创建版本化 Provider 提示，所有计量与发送都使用同一不可变值对象。 */
    private static SummaryModel.SummaryPrompt prompt(
            SummaryRequest request, int outputBudget, List<String> violations) {
        return new SummaryModel.SummaryPrompt(PROMPT_VERSION,
                request.strategyVersion(), request.threadId(), request.previousSummary(),
                request.evictedMessages(), request.splitTurn(), outputBudget, violations);
    }

    /** 使用纯本地保守估算返回完整摘要 envelope 输入 Token，任何失败都禁止摘要发送。 */
    private long measure(SummaryRequest request, int outputBudget) {
        return measure(prompt(request, outputBudget, List.of()));
    }

    /** 在估算前后复核取消与绝对 Deadline，并把本地编码失败稳定收敛为摘要失败。 */
    private long measure(SummaryModel.SummaryPrompt prompt) {
        try (RequestRuntime runtime = openRuntime(prompt.threadId())) {
            return measure(bind(runtime), runtime.binding(), prompt);
        }
    }

    /** 同一短租约完成精确计量；正式发送路径会继续复用该模型实例保存的不可变 envelope。 */
    private long measure(SummaryModel current, SummaryModel.TurnBinding binding,
                         SummaryModel.SummaryPrompt prompt) {
        requireActiveBinding(prompt.threadId(), binding);
        final io.github.kongweiguang.ja.conversation.port.out.ModelPort.InputTokenEstimate estimate;
        try {
            estimate = current.estimateInputTokens(prompt);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailable) {
            throw failure("summary input token count is unavailable", unavailable);
        }
        requireActiveBinding(prompt.threadId(), binding);
        if (estimate == null) throw failure("summary input token estimate is unavailable", null);
        return estimate.conservativeUpperBound();
    }

    /** 请求环境必须先完成 Thread、取消和 Deadline 校验，再绑定可能持有 Provider 资源的模型。 */
    private RequestRuntime openRuntime(String threadId) {
        RequestRuntime runtime = Objects.requireNonNull(runtimes.open(), "summary request runtime");
        try {
            requireActiveBinding(threadId, runtime.binding());
            return runtime;
        } catch (RuntimeException failure) {
            runtime.close();
            throw failure;
        }
    }

    /** Adapter 只绑定当前短租约携带的配置，禁止复用上一摘要请求的凭据或 API。 */
    private SummaryModel bind(RequestRuntime runtime) {
        return Objects.requireNonNull(models.bind(runtime.binding()), "summary model factory returned null");
    }

    /**
     * 校验来源、关键证据覆盖和旧事实退休；返回稳定违规码供唯一一次修复调用使用。
     */
    private List<String> validateResult(SummaryRequest request, SummaryResult result, int outputBudget) {
        List<String> violations = new java.util.ArrayList<>();
        if (!request.evictedMessages().isEmpty() && result.document().hasNoFacts()) {
            violations.add("EMPTY_DOCUMENT");
        }
        if (result.usage().outputTokens() > outputBudget) {
            violations.add("OUTPUT_BUDGET_EXCEEDED");
        }
        java.util.Set<Long> allowedSources = new java.util.HashSet<>();
        request.evictedMessages().forEach(message -> allowedSources.add(message.ordinal()));
        request.previousSummary().ifPresent(summary ->
                summary.allFacts().forEach(fact -> allowedSources.add(fact.sourceOrdinal())));
        for (SummaryDocument.Fact fact : result.document().allFacts()) {
            if (!allowedSources.contains(fact.sourceOrdinal())) violations.add("ILLEGAL_SOURCE_ORDINAL");
        }
        request.previousSummary().ifPresent(previous -> {
            java.util.Set<SummaryDocument.Fact> retained = new java.util.HashSet<>(result.document().allFacts());
            java.util.Set<String> retired = result.document().retirements().stream()
                    .map(value -> value.sourceOrdinal() + "\n" + value.text())
                    .collect(java.util.stream.Collectors.toSet());
            for (SummaryDocument.Fact fact : previous.allFacts()) {
                if (!retained.contains(fact)
                    && !retired.contains(fact.sourceOrdinal() + "\n" + fact.text())) {
                    violations.add("MISSING_FACT_RETIREMENT");
                }
            }
        });
        java.util.Set<Long> covered = new java.util.HashSet<>();
        result.document().allFacts().forEach(fact -> covered.add(fact.sourceOrdinal()));
        result.document().retirements().forEach(value -> covered.add(value.sourceOrdinal()));
        for (ContextMessage message : request.evictedMessages()) {
            boolean required = message.role() == ContextMessage.Role.USER
                    || message.blocks().stream().anyMatch(block -> block instanceof ContextMessage.ToolResultBlock tool
                            && tool.output().error() != null);
            if (required && !covered.contains(message.ordinal())) violations.add("UNCOVERED_CRITICAL_EVIDENCE");
        }
        return List.copyOf(new java.util.LinkedHashSet<>(violations));
    }

    /** 由窗口 4% 派生 4K～16K 输出预算，并同时服从模型能力与进程安全上限。 */
    private int outputBudget(SummaryRequest request) {
        long dynamic = Math.min(16_384L, Math.max(4_096L,
                request.budget().contextWindowTokens() / 25L));
        long bounded = Math.min(dynamic, request.budget().maxOutputTokens());
        bounded = Math.min(bounded, limits.maxOutputTokens());
        if (bounded < 1) throw failure("summary output budget is unavailable", null);
        return Math.toIntExact(bounded);
    }

    /** 按连续 Turn 身份分组，使 Tool call/result 永远不会跨摘要块边界拆开。 */
    private static List<List<ContextMessage>> completeTurns(List<ContextMessage> messages) {
        List<List<ContextMessage>> turns = new java.util.ArrayList<>();
        String activeTurn = null;
        java.util.Set<String> closedTurns = new java.util.HashSet<>();
        for (ContextMessage message : messages) {
            if (!message.turnId().equals(activeTurn)) {
                if (activeTurn != null) closedTurns.add(activeTurn);
                if (closedTurns.contains(message.turnId())) {
                    throw failure("summary input contained a non-contiguous Turn", null);
                }
                turns.add(new java.util.ArrayList<>());
                activeTurn = message.turnId();
            }
            turns.getLast().add(message);
        }
        return turns.stream().map(List::copyOf).toList();
    }

    /** 为滚动块替换 previousSummary，并只在块包含拆分 Turn 时携带 split 证据。 */
    private static SummaryRequest chunkRequest(SummaryRequest source, SummaryDocument previous,
                                               List<ContextMessage> messages) {
        Optional<ContextPolicy.TurnSplit> split = source.splitTurn().filter(value -> messages.stream()
                .anyMatch(message -> message.turnId().equals(value.turnId())));
        return new SummaryRequest(source.threadId(), previous.hasNoFacts() ? Optional.empty() : Optional.of(previous),
                messages, split, source.strategyVersion(), source.budget());
    }

    /** 精确累计多块及修复调用用量，溢出代表审计事实损坏而不是可饱和指标。 */
    private static CheckpointUsage addUsage(CheckpointUsage left, CheckpointUsage right) {
        try {
            return new CheckpointUsage(
                    Math.addExact(left.inputTokens(), right.inputTokens()),
                    Math.addExact(left.outputTokens(), right.outputTokens()),
                    Math.addExact(left.totalTokens(), right.totalTokens()),
                    Math.addExact(left.cacheReadTokens(), right.cacheReadTokens()),
                    Math.addExact(left.cacheWriteTokens(), right.cacheWriteTokens()));
        } catch (ArithmeticException overflow) {
            throw failure("summary usage accounting overflowed", overflow);
        }
    }

    /**
     * 对冻结计划或单次 Provider prompt 生成稳定 SHA-256；恢复只接受完全相同的输入与分块基线。
     */
    private static String fingerprint(Object value) {
        try {
            byte[] bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 自动 Summary 的持久 Operation 端口；Provider 调用前后与进度接受必须由实现原子推进 Turn 游标。
     */
    public interface SummaryOperation {
        /** 初始化或恢复同一计划，返回已接纳到 SQLite 的滚动进度。 */
        Progress start(String planFingerprint, SummaryDocument initial);

        /** 在每次可能计费的 Summary 请求前提交 PROVIDER_PENDING intent。 */
        void begin(String promptFingerprint, Optional<ProviderRequestProfile> profile);

        /** 结算 Provider 用量并原子保存其结果对应的下一子阶段或已接纳游标。 */
        void settle(CheckpointUsage callUsage, Progress accepted);

        /** deterministic fallback 不产生 Provider 用量，只推进已接纳进度。 */
        void advance(Progress accepted);

        /** 返回 Summary settlement 后的权威 Thread revision。 */
        default long checkpointSourceRevision(long originalRevision) { return originalRevision; }

        /** 返回 checkpoint 事务需要完成的 Turn execution CAS。 */
        default Optional<CheckpointStore.TurnOperation> checkpointTurnOperation() {
            return Optional.empty();
        }

        /** 手动压缩不属于 Turn Operation，保持原有 Thread-only checkpoint 语义。 */
        static SummaryOperation none() {
            return new SummaryOperation() {
                private Progress progress;

                /** 手动压缩没有持久游标，但仍复用同一阶段状态机以保持行为一致。 */
                @Override public Progress start(String fingerprint, SummaryDocument initial) {
                    progress = Progress.candidate(initial, CheckpointUsage.none(), 0, 0, fingerprint);
                    return progress;
                }

                /** 手动压缩由调用栈直接拥有 Provider intent，不额外建立 Turn execution。 */
                @Override public void begin(String promptFingerprint, Optional<ProviderRequestProfile> profile) {
                    Objects.requireNonNull(profile, "profile");
                }

                /** 手动压缩在内存中推进阶段，使 repair/fallback 与自动路径共享同一控制流。 */
                @Override public void settle(CheckpointUsage usage, Progress accepted) {
                    progress = Objects.requireNonNull(accepted, "accepted");
                }

                /** deterministic fallback 没有用量行，只替换当前内存进度。 */
                @Override public void advance(Progress accepted) { progress = accepted; }
            };
        }

        /** Summary 的显式 Provider 子阶段；已结算调用只能向后推进，不能回到 CANDIDATE 重发。 */
        enum Stage {
            /** 当前块尚未结算 candidate Provider 请求。 */
            CANDIDATE,
            /** candidate 已结算且非法，下一步只允许发送冻结的 repair prompt。 */
            REPAIR,
            /** repair 已结算且非法，下一步只允许执行 deterministic fallback。 */
            FALLBACK_PENDING
        }

        /**
         * 滚动摘要、已知用量、已接纳游标与待处理子阶段共同构成完整恢复状态；REPAIR/FALLBACK
         * 额外冻结目标块、违规码和 prompt 身份，避免 Resume 重新解释已结算调用。
         */
        record Progress(SummaryDocument document, CheckpointUsage usage, int nextTurn,
                        long throughOrdinal, String planFingerprint, Stage stage,
                        int targetNextTurn, long targetThroughOrdinal,
                        List<String> violations, String promptFingerprint) {
            /** 阶段字段按闭集交叉校验，损坏或不完整状态不得降级到 candidate。 */
            public Progress {
                Objects.requireNonNull(document, "document");
                Objects.requireNonNull(usage, "usage");
                Objects.requireNonNull(planFingerprint, "planFingerprint");
                Objects.requireNonNull(stage, "stage");
                violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
                if (nextTurn < 0 || throughOrdinal < 0 || !planFingerprint.matches("[0-9a-f]{64}")
                    || targetNextTurn < nextTurn || targetThroughOrdinal < throughOrdinal) {
                    throw new IllegalArgumentException("invalid summary Operation progress");
                }
                boolean candidate = stage == Stage.CANDIDATE;
                if (candidate != (targetNextTurn == nextTurn && targetThroughOrdinal == throughOrdinal
                    && violations.isEmpty() && promptFingerprint == null)) {
                    throw new IllegalArgumentException("invalid summary candidate stage");
                }
                if (!candidate && (targetNextTurn == nextTurn || violations.isEmpty()
                    || promptFingerprint == null || !promptFingerprint.matches("[0-9a-f]{64}"))) {
                    throw new IllegalArgumentException("invalid summary pending stage");
                }
            }

            /** 构造没有未结算子阶段的已接纳游标，作为首次进入或完成当前块后的唯一 CANDIDATE。 */
            public static Progress candidate(SummaryDocument document, CheckpointUsage usage, int nextTurn,
                                             long throughOrdinal, String planFingerprint) {
                return new Progress(document, usage, nextTurn, throughOrdinal, planFingerprint,
                        Stage.CANDIDATE, nextTurn, throughOrdinal, List.of(), null);
            }

            /** 保留已接纳正文与游标，只推进已知用量和待处理子阶段。 */
            Progress pending(Stage nextStage, CheckpointUsage settledUsage, int targetTurn,
                             long targetOrdinal, List<String> frozenViolations, String fingerprint) {
                if (nextStage == Stage.CANDIDATE) {
                    throw new IllegalArgumentException("pending summary stage cannot be candidate");
                }
                return new Progress(document, settledUsage, nextTurn, throughOrdinal, planFingerprint,
                        nextStage, targetTurn, targetOrdinal, frozenViolations, fingerprint);
            }
        }
    }

    /**
     * 为一次摘要计量或发送提供当前配置绑定、可选审计 Profile 与唯一释放动作。
     * 手动摘要不写请求级 Usage，因此显式使用空 Profile；自动摘要必须提供完整值。
     */
    public record RequestRuntime(SummaryModel.TurnBinding binding,
                                 Optional<ProviderRequestProfile> profile,
                                 AutoCloseable release) implements AutoCloseable {
        /** 请求资源必须完整，Optional 用于区分不产生 Usage 的手动压缩。 */
        public RequestRuntime {
            binding = Objects.requireNonNull(binding, "binding");
            profile = Objects.requireNonNull(profile, "profile");
            release = Objects.requireNonNull(release, "release");
        }

        /** 固定绑定只用于不持久化 Provider Profile 的手动压缩和纯规则测试。 */
        public static RequestRuntime unprofiled(SummaryModel.TurnBinding binding) {
            return new RequestRuntime(binding, Optional.empty(), () -> { });
        }

        /** checked close 统一收敛为运行时故障，避免资源泄漏被静默忽略。 */
        @Override
        public void close() {
            try {
                release.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("summary request runtime release failed", failure);
            }
        }
    }

    /** 自动摘要每次调用都必须重新打开当前请求环境；实现不得缓存上一次返回值。 */
    @FunctionalInterface
    public interface RequestRuntimeFactory {
        /** 打开只覆盖一次本地计量或一次 Provider 调用的短租约。 */
        RequestRuntime open();
    }

    /**
     * 在模型 IO 前后复核 Thread、取消和 Deadline，避免迟到结果进入 Checkpoint CAS。
     */
    private void requireActiveBinding(String threadId, SummaryModel.TurnBinding binding) {
        if (!binding.threadId().equals(threadId)) {
            throw failure("summary request did not match its Turn binding", null);
        }
        binding.cancellationToken().throwIfCancellationRequested();
        if (!clock.instant().isBefore(binding.deadline())) {
            throw failure("summary model deadline expired", null);
        }
    }

    /**
     * 以精确溢出检查累计旧摘要与淘汰消息，阻断超界输入到达付费 Provider。
     */
    private void validateInput(SummaryRequest request) {
        if (request.evictedMessages().size() > limits.maxMessages()) {
            throw failure("summary input exceeded its message bound", null);
        }
        long characters = request.previousSummary().map(summary -> (long) summary.toPromptText().length()).orElse(0L);
        for (ContextMessage message : request.evictedMessages()) {
            try {
                characters = Math.addExact(characters, serializedCharacters(message));
            } catch (ArithmeticException overflow) {
                throw failure("summary input size accounting overflowed", overflow);
            }
            if (characters > limits.maxInputCharacters()) {
                throw failure("summary input exceeded its storage safety bound", null);
            }
        }
    }

    /** 仅用于内存滥用防护累计字符，不参与任何 Token 准入或压缩决策。 */
    private static long serializedCharacters(ContextMessage message) {
        long characters = 0;
        for (ContextMessage.Block block : message.blocks()) {
            if (block instanceof ContextMessage.TextBlock text) characters += text.value().length();
            else if (block instanceof ContextMessage.AttachmentBlock attachment) {
                characters += attachment.attachmentId().length() + 64L;
            }
            else if (block instanceof ContextMessage.ToolCallBlock call) characters += call.arguments().length();
            else if (block instanceof ContextMessage.ToolResultBlock result) {
                characters += result.output().content().length();
            }
            else if (block instanceof ContextMessage.ReasoningBlock reasoning) {
                characters += reasoning.content().nativeJson().length();
            }
        }
        return characters;
    }

    /**
     * 统一把摘要边界故障映射为稳定分类，同时在存在时保留根因用于诊断。
     */
    private static ContextException failure(String message, Throwable cause) {
        return cause == null
                ? new ContextException(ContextException.Code.SUMMARY_FAILURE, message)
                : new ContextException(ContextException.Code.SUMMARY_FAILURE, message, cause);
    }

    /**
     * 为摘要输入消息数、输入 Token 和输出 Token 设置独立硬上限。
     */
    public record Limits(int maxMessages, long maxInputCharacters, int maxOutputTokens) {
        /**
         * 要求所有容量为正，避免零上限产生无法满足且难以诊断的模型请求。
         */
        public Limits {
            if (maxMessages <= 0 || maxInputCharacters <= 0 || maxOutputTokens <= 0) {
                throw new IllegalArgumentException("summary model limits must be positive");
            }
        }

        /**
         * 返回全局一致的生产默认值，使 Factory 与直接构造路径共享同一安全基线。
         */
        public static Limits defaults() {
            return DEFAULT_LIMITS;
        }
    }
}
