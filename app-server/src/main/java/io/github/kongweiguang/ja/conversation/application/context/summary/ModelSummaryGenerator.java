// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将结构化摘要端口绑定到当前 Turn，并在模型调用前后执行取消、Deadline 与容量门禁。
 */
public final class ModelSummaryGenerator implements SummaryGenerator {
    public static final String PROMPT_VERSION = "ja-context-summary-v2";
    private static final Limits DEFAULT_LIMITS = new Limits(2_000, 4_000_000, 16_384);

    private final SummaryModel model;
    private final SummaryModel.TurnBinding binding;
    private final Clock clock;
    private final Limits limits;

    /**
     * 使用统一生产上限构造生成器，避免不同调用链产生不一致的摘要容量。
     */
    public ModelSummaryGenerator(SummaryModel model, SummaryModel.TurnBinding binding, Clock clock) {
        this(model, binding, clock, DEFAULT_LIMITS);
    }

    /**
     * 固定模型、Turn 绑定、时钟和限制，使一次慢摘要调用只能服务其原始 Thread。
     */
    public ModelSummaryGenerator(SummaryModel model, SummaryModel.TurnBinding binding, Clock clock, Limits limits) {
        this.model = Objects.requireNonNull(model, "model");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * 按完整 Turn 组成 Provider 精确计量可容纳的最大块，并滚动生成全量替换摘要。
     */
    @Override
    public SummaryResult generate(SummaryRequest request) {
        Objects.requireNonNull(request, "request");
        requireActiveBinding(request.threadId());
        validateInput(request);
        int outputBudget = outputBudget(request);
        long inputCeiling = request.budget().contextWindowTokens() - outputBudget;
        List<List<ContextMessage>> turns = completeTurns(request.evictedMessages());
        SummaryDocument rolling = request.previousSummary().orElseGet(SummaryDocument::empty);
        CheckpointUsage usage = CheckpointUsage.none();
        int cursor = 0;
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
                    outputBudget, inputCeiling);
            rolling = generated.document();
            usage = addUsage(usage, generated.usage());
            cursor = accepted;
        }
        return new SummaryResult(rolling, usage);
    }

    /** 对单块执行候选、唯一一次修复和确定性回退，并累计所有真实 Provider 用量。 */
    private SummaryResult generateChunk(SummaryRequest request, int outputBudget, long inputCeiling) {
        SummaryResult first = summarize(request, outputBudget, List.of(), inputCeiling);
        CheckpointUsage usage = first.usage();
        List<String> violations = validateResult(request, first, outputBudget);
        if (violations.isEmpty()) return first;
        SummaryResult repaired = summarize(request, outputBudget, violations, inputCeiling);
        usage = addUsage(usage, repaired.usage());
        List<String> repairViolations = validateResult(request, repaired, outputBudget);
        if (repairViolations.isEmpty()) return new SummaryResult(repaired.document(), usage);
        SummaryDocument fallback = SummaryDocument.evidenceLedger(
                request.previousSummary().orElseGet(SummaryDocument::empty), request.evictedMessages());
        SummaryResult deterministic = new SummaryResult(fallback, CheckpointUsage.none());
        if (!validateResult(request, deterministic, outputBudget).isEmpty()) {
            throw failure("summary repair and deterministic evidence ledger were invalid", null);
        }
        return new SummaryResult(fallback, usage);
    }

    /** 执行一次已精确计量的摘要调用，并在返回后复核同一 Turn 的取消与绝对 Deadline。 */
    private SummaryResult summarize(SummaryRequest request, int outputBudget,
                                    java.util.List<String> violations, long inputCeiling) {
        SummaryModel.SummaryPrompt prompt = prompt(request, outputBudget, violations);
        if (measure(prompt) > inputCeiling) {
            throw failure("summary repair prompt exceeded the provider input window", null);
        }
        SummaryResult result = model.summarize(prompt);
        requireActiveBinding(prompt.threadId());
        if (result == null) throw failure("summary model returned no structured result", null);
        return result;
    }

    /** 创建版本化 Provider 提示，所有计量与发送都使用同一不可变值对象。 */
    private static SummaryModel.SummaryPrompt prompt(
            SummaryRequest request, int outputBudget, List<String> violations) {
        return new SummaryModel.SummaryPrompt(PROMPT_VERSION,
                request.strategyVersion(), request.threadId(), request.previousSummary(),
                request.evictedMessages(), request.splitTurn(), outputBudget, violations);
    }

    /** 使用 Provider 官方计量接口返回完整摘要 envelope 输入 Token，任何失败都禁止摘要发送。 */
    private long measure(SummaryRequest request, int outputBudget) {
        return measure(prompt(request, outputBudget, List.of()));
    }

    /** 在计量前后复核取消与绝对 Deadline，并把缺失计量稳定收敛为摘要失败。 */
    private long measure(SummaryModel.SummaryPrompt prompt) {
        requireActiveBinding(prompt.threadId());
        final io.github.kongweiguang.ja.conversation.port.out.ModelPort.InputTokenCount count;
        try {
            count = model.countInputTokens(prompt);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailable) {
            throw failure("summary input token count is unavailable", unavailable);
        }
        requireActiveBinding(prompt.threadId());
        if (count == null) throw failure("summary input token count is unavailable", null);
        return count.tokens();
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
     * 在模型 IO 前后复核 Thread、取消和 Deadline，避免迟到结果进入 Checkpoint CAS。
     */
    private void requireActiveBinding(String threadId) {
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
