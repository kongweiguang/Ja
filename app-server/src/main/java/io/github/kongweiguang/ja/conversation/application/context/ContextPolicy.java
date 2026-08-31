// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.compaction.ToolOutputProjector;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 纯上下文选择策略：负责投影、预算核算、Turn 切分和 Tool 配对。
 */
public final class ContextPolicy {
    private static final ToolProjectionLimits SUMMARY_LIMITS =
            new ToolProjectionLimits(1_000, 1_000);

    /** 创建无状态确定性策略；Token 计量必须由调用方显式提供。 */
    public ContextPolicy() {
    }

    /**
     * 计算单个不可变压缩计划，不读取存储也不调用模型。
     */
    public Plan plan(PlanningInput input, ContextTokenMeter meter) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(meter, "meter");
        validateOrder(input.messages());
        validateToolPairs(input.messages());
        List<ContextMessage> normalizedSource = applyRetainedSplit(input.messages(), input.previousRetainedSplit());
        List<ContextMessage> activeSource = normalizedSource.stream()
                .filter(message -> message.ordinal() >= input.baseRetainedFromOrdinal()).toList();
        ToolOutputProjector summaryProjector = new ToolOutputProjector(SUMMARY_LIMITS);
        LayeredProjection promptProjection = layeredProjection(activeSource, input, meter);
        List<ContextMessage> fullPrompt = promptProjection.messages();
        ContextTokenMeter.Measurement fullMeasurement = meter.measure(
                fullPrompt, input.previousSummary(), input.continuation(), false);
        long fullTokens = fullMeasurement.inputTokens();
        boolean fullFits = fullTokens <= input.budget().sendCeilingTokens();
        long baselineTokens = meter.measure(List.of(), input.previousSummary(), Optional.empty(), true)
                .inputTokens();
        long retainedTarget = Math.min(input.budget().compactedTargetTokens(),
                saturatingAdd(baselineTokens, input.budget().recentTailTokens()));
        Selection selection = selectTail(activeSource, promptProjection::project, meter, input.previousSummary(),
                retainedTarget, input.previousRetainedSplit());
        List<ContextMessage> retained = selection.retained();
        List<ContextMessage> retainedPrompt = retained.stream().map(promptProjection::project).toList();
        List<ContextMessage> evicted = selection.evicted();
        List<ContextMessage> summaryInput = evicted.stream()
                .filter(message -> message.ordinal() > input.baseThroughOrdinal()
                                   || input.previousRetainedSplit().isPresent()
                                      && message.ordinal() == input.baseThroughOrdinal())
                .map(message -> message.project(summaryProjector)).toList();
        long compactedTokens = meter.measure(
                retainedPrompt, input.previousSummary(), Optional.empty(), true).inputTokens();
        boolean fullPromptFits = fullFits;
        boolean requiresCompaction = input.forceCompaction()
                || input.budget().autoCompact()
                   && fullTokens >= input.budget().automaticCompactionThreshold();
        return new Plan(input.threadId(), input.sourceRevision(), input.baseThroughOrdinal(),
                input.previousSummary(), fullPrompt, retained, retainedPrompt, evicted, summaryInput,
                selection.splitTurn(), selection.retainedSplit(), fullPromptFits, requiresCompaction,
                (int) Math.min(Integer.MAX_VALUE,
                        fullTokens), (int) Math.min(Integer.MAX_VALUE, compactedTokens), input.budget().recentTailTokens(),
                input.continuation());
    }

    /**
     * 最近 Tool 结果在动态 Token 预算内保留全文，更旧结果逐个采用头尾；若完整 envelope
     * 仍超过准入目标，再按最旧优先逐个退化为 artifact-only，并在每个候选后重新精确计量。
     */
    private static LayeredProjection layeredProjection(
            List<ContextMessage> source, PlanningInput input, ContextTokenMeter meter) {
        ToolOutputProjector full = ToolOutputProjector.full();
        ToolOutputProjector headTail = new ToolOutputProjector(input.outputLimits());
        ToolOutputProjector artifact = ToolOutputProjector.artifactOnly();
        Map<String, ToolOutputProjector> choices = new HashMap<>();
        source.stream().filter(ContextMessage::hasToolResult)
                .forEach(message -> choices.put(message.messageId(), artifact));
        if (choices.isEmpty()) return new LayeredProjection(source, Map.of());
        if (input.outputLimits().artifactOnly()) {
            return new LayeredProjection(projectAll(source, choices), Map.copyOf(choices));
        }

        List<ContextMessage> artifactBaseline = projectAll(source, choices);
        long baselineTokens = meter.measure(artifactBaseline, input.previousSummary(),
                input.continuation(), false).inputTokens();
        long recentBudget = Math.min(input.budget().sendCeilingTokens(),
                Math.min(40_000L, Math.max(8_000L, input.budget().sendCeilingTokens() / 4L)));
        Set<String> protectedMessages = new HashSet<>();
        for (int index = source.size() - 1; index >= 0; index--) {
            ContextMessage message = source.get(index);
            if (!message.hasToolResult()) continue;
            choices.put(message.messageId(), full);
            long candidateTokens = meter.measure(projectAll(source, choices), input.previousSummary(),
                    input.continuation(), false).inputTokens();
            if (candidateTokens > saturatingAdd(baselineTokens, recentBudget)) {
                choices.put(message.messageId(), artifact);
                break;
            }
            protectedMessages.add(message.messageId());
        }

        for (int index = source.size() - 1; index >= 0; index--) {
            ContextMessage message = source.get(index);
            if (!message.hasToolResult() || protectedMessages.contains(message.messageId())) continue;
            choices.put(message.messageId(), headTail);
            meter.measure(projectAll(source, choices), input.previousSummary(),
                    input.continuation(), false);
        }

        long target = input.budget().autoCompact()
                ? input.budget().automaticCompactionThreshold() : input.budget().sendCeilingTokens();
        long currentTokens = meter.measure(projectAll(source, choices), input.previousSummary(),
                input.continuation(), false).inputTokens();
        for (ContextMessage message : source) {
            if (currentTokens <= target) break;
            if (!message.hasToolResult() || protectedMessages.contains(message.messageId())) continue;
            choices.put(message.messageId(), artifact);
            currentTokens = meter.measure(projectAll(source, choices), input.previousSummary(),
                    input.continuation(), false).inputTokens();
        }
        return new LayeredProjection(projectAll(source, choices), Map.copyOf(choices));
    }

    /** 使用当前阶段映射投影完整候选，禁止只计量孤立 Tool block。 */
    private static List<ContextMessage> projectAll(
            List<ContextMessage> source, Map<String, ToolOutputProjector> choices) {
        return source.stream().map(message -> {
            ToolOutputProjector projector = choices.get(message.messageId());
            return projector == null ? message : message.project(projector);
        }).toList();
    }

    /**
     * 使用最近一次切分 Checkpoint 保留的精确后缀替换不可变源消息。
     */
    private static List<ContextMessage> applyRetainedSplit(List<ContextMessage> messages,
                                                           Optional<RetainedSplit> retainedSplit) {
        if (retainedSplit.isEmpty()) {
            return messages;
        }
        RetainedSplit split = retainedSplit.orElseThrow();
        List<ContextMessage> normalized = new ArrayList<>(messages.size());
        boolean matched = false;
        for (ContextMessage message : messages) {
            if (!message.messageId().equals(split.sourceMessageId())) {
                normalized.add(message);
                continue;
            }
            if (matched || message.ordinal() != split.retainedMessage().ordinal()
                || !message.turnId().equals(split.retainedMessage().turnId())
                || message.role() != split.retainedMessage().role()) {
                throw new ContextException(ContextException.Code.INVALID_STATE,
                        "retained split no longer matches immutable source history");
            }
            normalized.add(split.retainedMessage());
            matched = true;
        }
        if (!matched) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "retained split source message is unavailable");
        }
        return List.copyOf(normalized);
    }

    /**
     * 在边界持久化前校验单调 ordinal 和连续 Turn。
     */
    private static void validateOrder(List<ContextMessage> messages) {
        long previous = 0;
        String previousTurn = null;
        Set<String> closedTurns = new HashSet<>();
        for (ContextMessage message : messages) {
            if (message.ordinal() <= previous) {
                throw new ContextException(ContextException.Code.INVALID_STATE,
                        "context message ordinals must increase");
            }
            previous = message.ordinal();
            if (previousTurn != null && !previousTurn.equals(message.turnId())) {
                closedTurns.add(previousTurn);
            }
            if (closedTurns.contains(message.turnId())) {
                throw new ContextException(ContextException.Code.INVALID_STATE,
                        "context turn messages must be contiguous");
            }
            previousTurn = message.turnId();
        }
    }

    /**
     * 历史进入模型提示前，要求每个 Tool 调用与完成结果一一配对。
     */
    private static void validateToolPairs(List<ContextMessage> messages) {
        Map<String, Integer> calls = new HashMap<>();
        Map<String, Integer> results = new HashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            ContextMessage message = messages.get(index);
            for (String callId : message.toolCallIds()) {
                if (calls.putIfAbsent(callId, index) != null) {
                    throw new ContextException(ContextException.Code.INVALID_STATE,
                            "duplicate Tool call identifier");
                }
            }
            for (String callId : message.toolResultIds()) {
                if (results.putIfAbsent(callId, index) != null || !calls.containsKey(callId)) {
                    throw new ContextException(ContextException.Code.INVALID_STATE,
                            "Tool result is not paired with a call");
                }
            }
        }
        for (Map.Entry<String, Integer> result : results.entrySet()) {
            if (calls.get(result.getKey()) >= result.getValue()) {
                throw new ContextException(ContextException.Code.INVALID_STATE,
                        "Tool result must follow its call");
            }
        }
        if (!calls.keySet().equals(results.keySet())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "Tool call is missing its completed result");
        }
    }

    /**
     * 从尾部选择完整 Turn，仅在最新 Turn 超限时才执行切分。
     */
    private static Selection selectTail(List<ContextMessage> source,
                                        Function<ContextMessage, ContextMessage> projector,
                                        ContextTokenMeter meter, SummaryDocument previousSummary,
                                        long target,
                                        Optional<RetainedSplit> previousRetainedSplit) {
        if (source.isEmpty()) {
            return new Selection(List.of(), List.of(), Optional.empty(), Optional.empty());
        }
        List<TurnRange> turns = ranges(source);
        TurnRange newest = turns.getLast();
        long newestTokens = measureRange(source, newest.start(), newest.end(), projector, meter, previousSummary);
        int start;
        Optional<ContextMessage.MessageSplit> messageSplit = Optional.empty();
        if (newestTokens > target) {
            start = newest.end() - 1;
            ContextMessage newestMessage = source.get(start);
            if (newestMessage.blocks().size() == 1
                && newestMessage.blocks().getFirst() instanceof ContextMessage.TextBlock) {
                ContextMessage.MessageSplit split = selectMeasuredSuffix(
                        newestMessage, projector, meter, previousSummary, target);
                if (split.split()) {
                    messageSplit = Optional.of(split);
                }
            }
        } else {
            start = newest.start();
            for (int turn = turns.size() - 2; turn >= 0; turn--) {
                TurnRange candidate = turns.get(turn);
                long candidateTokens = measureRange(
                        source, candidate.start(), newest.end(), projector, meter, previousSummary);
                if (candidateTokens > target) {
                    break;
                }
                start = candidate.start();
            }
        }
        start = includePairedCalls(start, source);
        List<ContextMessage> evicted = new ArrayList<>(source.subList(0, start));
        List<ContextMessage> retained = new ArrayList<>(source.subList(start, source.size()));
        Optional<TurnSplit> split = Optional.empty();
        Optional<RetainedSplit> retainedSplit = Optional.empty();
        if (messageSplit.isPresent()) {
            ContextMessage.MessageSplit value = messageSplit.orElseThrow();
            ContextMessage splitSource = source.get(newest.end() - 1);
            evicted.add(value.prefix());
            retained.set(0, value.suffix());
            String sourceMessageId = previousRetainedSplit
                    .filter(previous -> previous.retainedMessage().messageId().equals(splitSource.messageId()))
                    .map(RetainedSplit::sourceMessageId).orElse(splitSource.messageId());
            retainedSplit = Optional.of(new RetainedSplit(sourceMessageId, value.suffix()));
        }
        if (start == 0 && messageSplit.isEmpty()) {
            retained = new ArrayList<>(source);
        }
        if (start > newest.start() || messageSplit.isPresent()) {
            List<ContextMessage> prefix = new ArrayList<>(source.subList(newest.start(), start));
            messageSplit.ifPresent(value -> prefix.add(value.prefix()));
            split = Optional.of(new TurnSplit(source.get(newest.start()).turnId(), prefix, retained));
        }
        if (retainedSplit.isEmpty() && previousRetainedSplit.isPresent()
            && retained.stream().anyMatch(message -> message.messageId()
                .equals(previousRetainedSplit.orElseThrow().retainedMessage().messageId()))) {
            retainedSplit = previousRetainedSplit;
        }
        return new Selection(List.copyOf(retained), List.copyOf(evicted), split, retainedSplit);
    }

    /**
     * 用二分搜索寻找 Provider 精确计量可容纳的最大 Unicode 后缀，不再从 Token 反推字符数。
     */
    private static ContextMessage.MessageSplit selectMeasuredSuffix(
            ContextMessage message, Function<ContextMessage, ContextMessage> projector,
            ContextTokenMeter meter, SummaryDocument previousSummary, long target) {
        ContextMessage.TextBlock text = (ContextMessage.TextBlock) message.blocks().getFirst();
        int codePoints = text.value().codePointCount(0, text.value().length());
        int low = 1;
        int high = codePoints - 1;
        ContextMessage.MessageSplit best = message.splitTextSuffix(1);
        while (low <= high) {
            int middle = low + (high - low) / 2;
            ContextMessage.MessageSplit candidate = message.splitTextSuffix(middle);
            long tokens = meter.measure(List.of(projector.apply(candidate.suffix())), previousSummary,
                    Optional.empty(), true).inputTokens();
            if (tokens <= target) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    /** 使用完整 envelope 计量一个连续候选范围，System 与 Tool Schema 始终参与比较。 */
    private static long measureRange(
            List<ContextMessage> source, int start, int end,
            Function<ContextMessage, ContextMessage> projector,
            ContextTokenMeter meter, SummaryDocument previousSummary) {
        List<ContextMessage> candidate = source.subList(start, end).stream()
                .map(projector).toList();
        return meter.measure(candidate, previousSummary, Optional.empty(), true).inputTokens();
    }

    /**
     * 向左移动切点，直到每个保留的 Tool 结果都包含其先前调用。
     */
    private static int includePairedCalls(int start, List<ContextMessage> source) {
        Map<String, Integer> calls = new HashMap<>();
        for (int index = 0; index < source.size(); index++) {
            for (String callId : source.get(index).toolCallIds()) {
                calls.put(callId, index);
            }
        }
        int boundary = start;
        boolean changed;
        do {
            changed = false;
            for (int index = boundary; index < source.size(); index++) {
                for (String callId : source.get(index).toolResultIds()) {
                    Integer callIndex = calls.get(callId);
                    if (callIndex == null) {
                        throw new ContextException(ContextException.Code.INVALID_STATE,
                                "Tool result is not paired with a call");
                    }
                    if (callIndex < boundary) {
                        boundary = callIndex;
                        changed = true;
                    }
                }
            }
        } while (changed);
        return boundary;
    }

    /**
     * 创建连续 Turn 范围，防止选择逻辑从普通 Turn 中间切断。
     */
    private static List<TurnRange> ranges(List<ContextMessage> messages) {
        List<TurnRange> ranges = new ArrayList<>();
        int start = 0;
        String turnId = messages.getFirst().turnId();
        for (int index = 1; index < messages.size(); index++) {
            if (!turnId.equals(messages.get(index).turnId())) {
                ranges.add(new TurnRange(start, index));
                start = index;
                turnId = messages.get(index).turnId();
            }
        }
        ranges.add(new TurnRange(start, messages.size()));
        return ranges;
    }

    /**
     * 对预算分量执行饱和加法，避免极端输入回绕后被误判为可容纳。
     */
    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * AgentLoop 在慢摘要生成开始前捕获的冻结输入。
     */
    public record PlanningInput(
            String threadId,
            List<ContextMessage> messages,
            SummaryDocument previousSummary,
            long sourceRevision,
            long baseThroughOrdinal,
            long baseRetainedFromOrdinal,
            Optional<RetainedSplit> previousRetainedSplit,
            ContextBudget budget,
            boolean forceCompaction,
            Optional<ModelContinuation> continuation,
            ToolProjectionLimits outputLimits) {
        /**
         * 复制源列表，并保留这些消息所代表的 Thread revision。
         */
        public PlanningInput {
            if (threadId == null || threadId.isBlank() || sourceRevision < 0
                || baseThroughOrdinal < 0 || baseRetainedFromOrdinal < baseThroughOrdinal) {
                throw new IllegalArgumentException("invalid context planning input");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            previousSummary = Objects.requireNonNull(previousSummary, "previousSummary");
            previousRetainedSplit = Objects.requireNonNull(previousRetainedSplit, "previousRetainedSplit");
            budget = Objects.requireNonNull(budget, "budget");
            continuation = Objects.requireNonNull(continuation, "continuation");
            outputLimits = Objects.requireNonNull(outputLimits, "outputLimits");
        }
    }

    /**
     * 供压缩服务消费的纯结果，此处不产生存储或模型副作用。
     */
    public record Plan(
            String threadId,
            long sourceRevision,
            long baseThroughOrdinal,
            SummaryDocument previousSummary,
            List<ContextMessage> fullPrompt,
            List<ContextMessage> retained,
            List<ContextMessage> retainedPrompt,
            List<ContextMessage> evicted,
            List<ContextMessage> summaryInput,
            Optional<TurnSplit> splitTurn,
            Optional<RetainedSplit> retainedSplit,
            boolean fullPromptFits,
            boolean requiresCompaction,
            int fullPromptTokens,
            int compactedPromptTokens,
            long recentTailTokens,
            Optional<ModelContinuation> continuation) {
        /**
         * 冻结全部策略输出，防止慢生成器改变 Thread CAS 证据。
         */
        public Plan {
            if (threadId == null || threadId.isBlank() || sourceRevision < 0
                || baseThroughOrdinal < 0 || fullPromptTokens < 0 || compactedPromptTokens < 0
                || recentTailTokens < 0) {
                throw new IllegalArgumentException("invalid context plan");
            }
            previousSummary = Objects.requireNonNull(previousSummary, "previousSummary");
            fullPrompt = List.copyOf(Objects.requireNonNull(fullPrompt, "fullPrompt"));
            retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
            retainedPrompt = List.copyOf(Objects.requireNonNull(retainedPrompt, "retainedPrompt"));
            evicted = List.copyOf(Objects.requireNonNull(evicted, "evicted"));
            summaryInput = List.copyOf(Objects.requireNonNull(summaryInput, "summaryInput"));
            splitTurn = Objects.requireNonNull(splitTurn, "splitTurn");
            retainedSplit = Objects.requireNonNull(retainedSplit, "retainedSplit");
            continuation = Objects.requireNonNull(continuation, "continuation");
        }

        /**
         * 返回不可变淘汰前缀所代表的最后一个源 ordinal。
         */
        public long throughOrdinal() {
            return evicted.isEmpty() ? baseThroughOrdinal : evicted.getLast().ordinal();
        }

        /**
         * 返回首个保留源 ordinal；无保留项时返回前缀后的边界。
         */
        public long retainedFromOrdinal() {
            return retained.isEmpty() ? throughOrdinal() : retained.getFirst().ordinal();
        }
    }

    /**
     * 单个 Turn 的内部连续源范围。
     */
    private record TurnRange(int start, int end) {
    }

    /**
     * 记录合法的最新 Turn 切分，使摘要模型能够解释被省略的前缀。
     */
    public record TurnSplit(String turnId, List<ContextMessage> prefix, List<ContextMessage> suffix) {
        /**
         * 冻结切分两侧，并在压缩过程中保留精确 Turn 身份。
         */
        public TurnSplit {
            if (turnId == null || turnId.isBlank()) {
                throw new IllegalArgumentException("invalid split turn");
            }
            prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
            suffix = List.copyOf(Objects.requireNonNull(suffix, "suffix"));
            if (prefix.isEmpty() || suffix.isEmpty()) {
                throw new IllegalArgumentException("split turn requires prefix and suffix");
            }
        }
    }

    /**
     * 恢复时以保留后缀替换不可变完整源消息所需的持久证据。
     */
    public record RetainedSplit(String sourceMessageId, ContextMessage retainedMessage) {
        /**
         * 要求文本后缀身份与完整源消息标识不同。
         */
        public RetainedSplit {
            if (sourceMessageId == null || sourceMessageId.isBlank() || sourceMessageId.length() > 256
                || sourceMessageId.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid retained split source");
            }
            retainedMessage = Objects.requireNonNull(retainedMessage, "retainedMessage");
            if (sourceMessageId.equals(retainedMessage.messageId())
                || retainedMessage.blocks().size() != 1
                || !(retainedMessage.blocks().getFirst() instanceof ContextMessage.TextBlock)) {
                throw new IllegalArgumentException("invalid retained split suffix");
            }
        }
    }

    /**
     * 与公开计划词汇分离的内部选择结果。
     */
    private record Selection(List<ContextMessage> retained, List<ContextMessage> evicted,
                             Optional<TurnSplit> splitTurn, Optional<RetainedSplit> retainedSplit) {
    }

    /** 冻结本轮各 Tool 消息的降载阶段，使尾部选择和最终发送复用同一投影。 */
    private record LayeredProjection(List<ContextMessage> messages,
                                     Map<String, ToolOutputProjector> choices) {
        /** 复制投影与映射，避免规划期间候选 Map 后续变化。 */
        private LayeredProjection {
            messages = List.copyOf(messages);
            choices = Map.copyOf(choices);
        }

        /** 按消息身份复用冻结阶段；非 Tool 消息保持原始事实。 */
        private ContextMessage project(ContextMessage message) {
            ToolOutputProjector projector = choices.get(message.messageId());
            return projector == null ? message : message.project(projector);
        }
    }
}
