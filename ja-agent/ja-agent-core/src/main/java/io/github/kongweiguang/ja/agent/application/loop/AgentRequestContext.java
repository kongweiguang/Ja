// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.context.ContextBaseline;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.agent.application.context.ContextOrchestratorFactory;
import io.github.kongweiguang.ja.agent.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.agent.application.context.ModelContinuation;
import io.github.kongweiguang.ja.agent.application.context.compaction.ContextCompactionService;
import io.github.kongweiguang.ja.agent.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBinding;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 为单次模型请求装配所选历史分支、精确摘要来源与唯一压缩策略。
 */
public final class AgentRequestContext {
    private static final String CHECKPOINT_RECORD_TYPE = "agent.context.checkpoint";
    private final AgentIdentity identity;
    private final AgentRequestRuntime runtime;
    private final AgentContextMapper mapper;
    private final CheckpointStore checkpoints;
    private final CancellationToken cancellation;
    private final ContextOrchestrator orchestrator;
    private CheckpointStore.ContextCheckpoint selectedCheckpoint;
    private ExecutionRecordCodec.SeedSummaryReference selectedSeedReference;
    private SummaryDocument selectedSummary = SummaryDocument.empty();
    private boolean summarySelectionRead;

    /**
     * 把请求级模型/Prompt 租约与执行器提供的真实 checkpoint 和 Summary 提交端口绑定；
     * 持久回执仍由 Session 的 checkpoint adapter 与本次 execution CAS 一起完成。
     */
    public AgentRequestContext(AgentIdentity identity, AgentRequestRuntime runtime,
                               AgentContextMapper mapper, CheckpointStore checkpoints,
                               CancellationToken cancellation, Clock clock,
                               ModelSummaryGenerator.RequestRuntimeFactory summaryRuntimes,
                               ModelSummaryGenerator.SummaryOperation summaryOperation) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(summaryRuntimes, "summaryRuntimes");
        Objects.requireNonNull(summaryOperation, "summaryOperation");
        this.orchestrator = new ContextOrchestratorFactory(checkpoints, clock, runtime.summaryModels())
                .create(identity.threadId(), summaryRuntimes, summaryOperation);
    }

    /**
     * 恢复能力事实中的 seed 引用，按不可变来源三元组与父 ordinal 精确核验内容存在性。
     */
    private static SelectedSummary seedSummary(Optional<InitialContextSeed> seed,
                                               ExecutionRecordCodec.SeedSummaryReference reference) {
        InitialContextSeed value = seed.orElseThrow(() -> integrity(
                "model seed summary reference is missing from the execution admission snapshot"));
        if (!value.sourceIdentity().equals(reference.sourceIdentity())
            || value.sourceOrdinal() != reference.sourceOrdinal()) {
            throw integrity("model seed summary reference does not match the frozen admission seed");
        }
        SummaryDocument summary = value.summary().orElseThrow(() -> integrity(
                "model seed summary reference has no summary document"));
        return SelectedSummary.seed(summary, reference);
    }

    /**
     * 用与 Context Mapper 相同的执行顺序计算可见路径，并要求当前执行确在该冻结水位中。
     */
    private static List<String> historyPath(AgentLoopPersistence.ConversationHistory history,
                                            AgentExecutionRequest.HistorySelection selection, String currentTurnId) {
        Set<String> selected = switch (selection.mode()) {
            case CURRENT_CONVERSATION -> history.executions().stream()
                    .map(execution -> execution.key().turnId()).collect(java.util.stream.Collectors.toSet());
            case EXPLICIT -> Set.copyOf(selection.visibleTurnIds());
            case EMPTY -> Set.of();
        };
        Set<String> available = history.executions().stream().map(execution -> execution.key().turnId())
                .collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(selected))
            {
                throw new IllegalArgumentException("history path references an unavailable Turn");
            }
        LinkedHashSet<String> throughCurrent = new LinkedHashSet<>();
        boolean found = false;
        for (AgentStore.ExecutionSnapshot execution : history.executions()) {
            String turnId = execution.key().turnId();
            if (selected.contains(turnId) || turnId.equals(currentTurnId))
                {
                    throughCurrent.add(turnId);
                }
            if (turnId.equals(currentTurnId)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("current Turn is absent from history");
        }
        return List.copyOf(throughCurrent);
    }

    /**
     * 明确验证宿主选择，不默默忽略不存在或跨 Thread 的 Turn 身份。
     */
    private static Set<String> visibleTurnIds(AgentLoopPersistence.ConversationHistory history,
                                              AgentExecutionRequest.HistorySelection selection) {
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        switch (selection.mode()) {
            case CURRENT_CONVERSATION -> history.executions().forEach(execution ->
                    visible.add(execution.key().turnId()));
            case EXPLICIT -> visible.addAll(selection.visibleTurnIds());
            case EMPTY -> {
            }
        }
        return visible;
    }

    /**
     * 转换已冻结会话记录为严格执行 codec 输入，不重新查数据库最新记录。
     */
    private static AgentStore.StoredRecord stored(AgentStore.ConversationRecord record) {
        return new AgentStore.StoredRecord(record.recordId(), record.recordType(), record.payloadSchemaVersion(),
                record.sequence(), record.payload(), record.occurredAt());
    }

    /**
     * 将损坏或缺失引用统一映射成 fail-closed 上下文状态。
     */
    private static AgentStore.StoreIntegrityException integrity(String message) {
        return new AgentStore.StoreIntegrityException(message, null);
    }

    /**
     * 从固定 history 水位恢复或选择分支匹配的摘要，再运行规划、压缩与唯一一次 overflow recovery；
     * Prompt Skill 已由 Engine 在执行权恢复边界恢复，此处校验其游标身份；摘要由冻结请求传给 Prompt，避免重复改写会话状态。
     * 新 checkpoint receipt 到达后立即更新本请求的权威摘要来源。
     */
    public <T> ContextOrchestrator.Execution<T> prepare(
            AgentLoopPersistence.ConversationHistory history,
            AgentExecutionRequest.HistorySelection selection,
            TurnExecutionState.Common common,
            Request request,
            ContextOrchestrator.CheckpointReceiptSink receiptSink,
            ContextOrchestrator.PromptSender<T> sender,
            ContextCompactionService.CompactionStartObserver compactionStarted) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(common, "common");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiptSink, "receiptSink");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(compactionStarted, "compactionStarted");
        requireConversation(history.conversation());
        cancellation.throwIfCancellationRequested();

        CheckpointStore.Snapshot checkpointSnapshot = checkpoints.read(identity.threadId());
        if (checkpointSnapshot.threadRevision() != history.conversation().revision()) {
            throw new ContextException(ContextException.Code.CAS_CONFLICT,
                    "conversation changed after the Turn history snapshot");
        }
        Optional<InitialContextSeed> seed = mapper.initialContextSeed(history, identity);
        SelectedSummary selected = selectSummary(history, selection, request, seed);
        selectedCheckpoint = selected.checkpoint().orElse(null);
        selectedSeedReference = selected.seedReference().orElse(null);
        selectedSummary = selected.summary();
        summarySelectionRead = true;

        List<TurnExecutionState.ActiveSkill> promptSkills = runtime.promptSession().activeSkillReferences();
        List<String> persistedSkillIds = common.activeSkills().stream()
                .map(TurnExecutionState.ActiveSkill::skillId).toList();
        List<String> promptSkillIds = promptSkills.stream().map(TurnExecutionState.ActiveSkill::skillId).toList();
        if (!persistedSkillIds.equals(promptSkillIds)) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "Prompt Skill identities differ from the Turn cursor");
        }
        Set<String> visibleTurns = visibleTurnIds(history, selection);
        visibleTurns.add(identity.turnId());
        List<ContextMessage> messages = mapper.fromSnapshot(history, visibleTurns, identity.turnId());
        ContextOrchestrator.Request contextRequest = new ContextOrchestrator.Request(
                identity.threadId(), history.conversation().revision(), messages,
                request.budget(), request.forceCompaction(), request.continuation(),
                request.outputLimits(), request.projectionBinding(), request.meter(), cancellation,
                Optional.of(new ContextBaseline(selected.checkpoint(), selectedSummary)));
        return orchestrator.execute(contextRequest, receipt -> {
            recordCommittedCheckpoint(receipt);
            receiptSink.publish(receipt);
        }, sender, compactionStarted);
    }

    /**
     * 将模型实际采用的摘要绑定到持久来源；不相符时失败，不以当前会话头替换旧请求摘要。
     */
    SummarySource summarySourceFor(SummaryDocument summary) {
        Objects.requireNonNull(summary, "summary");
        if (!summarySelectionRead) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "model prompt summary was not selected from a conversation snapshot");
        }
        if (selectedCheckpoint != null) {
            if (selectedCheckpoint.summary().equals(summary)) {
                return new SummarySource(Optional.of(selectedCheckpoint.checkpointId()), Optional.empty());
            }
        } else if (selectedSeedReference != null && selectedSummary.equals(summary)) {
            return new SummarySource(Optional.empty(), Optional.of(selectedSeedReference));
        } else if (selectedSummary.hasNoFacts() && summary.hasNoFacts()) {
            return new SummarySource(Optional.empty(), Optional.empty());
        }
        throw new ContextException(ContextException.Code.INVALID_STATE,
                "model prompt summary has no matching durable source");
    }

    /**
     * 已提交 checkpoint 取代 seed 摘要成为当前请求来源；seed 原始消息仍由 mapper 稳定保留。
     */
    private void recordCommittedCheckpoint(CheckpointStore.CommittedCheckpoint receipt) {
        CheckpointStore.ContextCheckpoint checkpoint = Objects.requireNonNull(receipt, "receipt").checkpoint();
        if (!identity.threadId().equals(checkpoint.threadId())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "committed prompt checkpoint belongs to another conversation");
        }
        selectedCheckpoint = checkpoint;
        selectedSeedReference = null;
        selectedSummary = checkpoint.summary();
    }

    /**
     * 按当前历史路径选择压缩基线；续跑先服从原模型调用冻结引用，新执行只复用路径前缀 checkpoint。
     */
    private SelectedSummary selectSummary(AgentLoopPersistence.ConversationHistory history,
                                          AgentExecutionRequest.HistorySelection selection, Request request, Optional<InitialContextSeed> seed) {
        List<CheckpointCandidate> applicable = applicableCheckpoints(history, selection, request);
        if (request.frozenCapabilities().isPresent()) {
            ExecutionRecordCodec.ModelCapabilities capabilities = request.frozenCapabilities().orElseThrow();
            if (capabilities.checkpointId().isPresent()) {
                String checkpointId = capabilities.checkpointId().orElseThrow();
                CheckpointStore.ContextCheckpoint checkpoint = applicable.stream()
                        .map(CheckpointCandidate::checkpoint)
                        .filter(candidate -> candidate.checkpointId().equals(checkpointId))
                        .reduce((first, second) -> {
                            throw integrity("model checkpoint reference is ambiguous in the selected history");
                        }).orElseThrow(() -> integrity(
                                "model checkpoint reference is missing from the selected history watermark"));
                return SelectedSummary.checkpoint(checkpoint);
            }
            if (capabilities.seedSummaryRef().isPresent()) {
                return seedSummary(seed, capabilities.seedSummaryRef().orElseThrow());
            }
            return SelectedSummary.empty();
        }
        if (!applicable.isEmpty()) {
            return SelectedSummary.checkpoint(applicable.getLast().checkpoint());
        }
        return seed.map(value -> value.summary().map(summary -> SelectedSummary.seed(summary,
                        new ExecutionRecordCodec.SeedSummaryReference(value.sourceIdentity(), value.sourceOrdinal())))
                .orElseGet(SelectedSummary::empty)).orElseGet(SelectedSummary::empty);
    }

    /**
     * 只把写入执行的历史路径确为当前路径前缀的 checkpoint 用于新请求，避免隐藏分支摘要泄漏。
     */
    private List<CheckpointCandidate> applicableCheckpoints(AgentLoopPersistence.ConversationHistory history,
                                                            AgentExecutionRequest.HistorySelection selection, Request request) {
        List<String> currentPath = historyPath(history, selection, identity.turnId());
        Map<String, AgentStore.ConversationRecord> bindings = new HashMap<>();
        for (AgentStore.ConversationRecord record : history.records()) {
            if (!AgentRequestBindingCodec.RECORD_TYPE.equals(record.recordType()))
                {
                    continue;
                }
            String turnId = record.execution().turnId();
            if (bindings.putIfAbsent(turnId, record) != null) {
                throw integrity("history contains duplicate request bindings");
            }
        }
        AgentRequestBindingCodec bindingCodec = new AgentRequestBindingCodec();
        List<CheckpointCandidate> candidates = new ArrayList<>();
        long previousSequence = 0;
        for (AgentStore.ConversationRecord record : history.records()) {
            if (record.sequence() <= previousSequence) {
                throw integrity("history records are not in sequence order");
            }
            previousSequence = record.sequence();
            if (!CHECKPOINT_RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            AgentStore.ExecutionKey owner = record.execution();
            int ownerIndex = currentPath.indexOf(owner.turnId());
            if (ownerIndex < 0) {
                continue;
            }
            AgentStore.StoredRecord stored = stored(record);
            CheckpointStore.ContextCheckpoint checkpoint = request.records().decodeCheckpoint(stored);
            if (!checkpoint.threadId().equals(identity.threadId())
                || checkpoint.sourceRevision() > history.conversation().revision()
                || !record.recordId().equals(AgentRecordIds.derive(owner, CHECKPOINT_RECORD_TYPE,
                    checkpoint.checkpointId()))) {
                throw integrity("history checkpoint does not match its execution or conversation");
            }
            AgentStore.ConversationRecord bindingRecord = bindings.get(owner.turnId());
            if (bindingRecord == null) {
                continue;
            }
            AgentRequestBinding binding = bindingCodec.decode(stored(bindingRecord));
            if (!binding.identity().tenantId().equals(owner.conversation().tenantId())
                || !binding.identity().threadId().equals(owner.conversation().threadId())
                || !binding.identity().turnId().equals(owner.turnId())) {
                throw integrity("checkpoint owner does not match its frozen request binding");
            }
            List<String> checkpointPath;
            try {
                checkpointPath = historyPath(history, binding.history(), owner.turnId());
            } catch (IllegalArgumentException unavailablePath) {
                continue;
            }
            if (ownerIndex + 1 == checkpointPath.size()
                && currentPath.subList(0, ownerIndex + 1).equals(checkpointPath)) {
                candidates.add(new CheckpointCandidate(checkpoint, record.sequence()));
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 拒绝跨租户快照或身份不匹配的会话，避免 mapper 变成授权边界。
     */
    private void requireConversation(AgentStore.ConversationSnapshot conversation) {
        AgentStore.ConversationKey key = Objects.requireNonNull(conversation, "conversation").key();
        if (!identity.tenantId().equals(key.tenantId()) || !identity.threadId().equals(key.threadId())) {
            throw new IllegalArgumentException("execution context belongs to another conversation");
        }
    }

    /**
     * 当前已冻结的预算与摘要边界，Codec 必须与同一 admission snapshot 配套使用。
     *
     * @param budget 预算
     * @param forceCompaction 是否强制压缩上下文
     * @param continuation 续跑（可选）
     * @param outputLimits 工具结果投影的容量约束
     * @param projectionBinding 投影绑定
     * @param meter 计量器
     * @param records 记录列表
     * @param json JSON 编解码器
     * @param frozenCapabilities 创建时冻结的能力快照（可选）
     */
    public record Request(ContextBudget budget,
                          boolean forceCompaction,
                          Optional<ModelContinuation> continuation,
                          ToolProjectionLimits outputLimits,
                          CheckpointStore.ProjectionBinding projectionBinding,
                          ContextTokenMeter meter,
                          ExecutionRecordCodec records,
                          JsonValueCodec json,
                          Optional<ExecutionRecordCodec.ModelCapabilities> frozenCapabilities) {
        /**
         * 拒绝缺失预算、Codec 或恢复引用，避免边界层静默回到无摘要默认。
         */
        public Request {
            Objects.requireNonNull(budget, "budget");
            continuation = Objects.requireNonNull(continuation, "continuation");
            Objects.requireNonNull(outputLimits, "outputLimits");
            Objects.requireNonNull(projectionBinding, "projectionBinding");
            Objects.requireNonNull(meter, "meter");
            Objects.requireNonNull(records, "records");
            Objects.requireNonNull(json, "json");
            frozenCapabilities = Objects.requireNonNull(frozenCapabilities, "frozenCapabilities");
        }
    }

    /**
     * 模型请求冻结的唯一摘要来源；无摘要请求以两个空引用明确表示。
     *
     * @param checkpointId 检查点标识（可选）
     * @param seedSummaryRef 初始上下文摘要引用（可选）
     */
    record SummarySource(Optional<String> checkpointId,
                         Optional<ExecutionRecordCodec.SeedSummaryReference> seedSummaryRef) {
        /**
         * 只允许 checkpoint 或 seed 二选一，避免恢复时来源优先级含糊。
         */
        SummarySource {
            checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
            seedSummaryRef = Objects.requireNonNull(seedSummaryRef, "seedSummaryRef");
            if (checkpointId.isPresent() && seedSummaryRef.isPresent()) {
                throw new IllegalArgumentException("summary source references conflict");
            }
        }
    }

    /**
     * 已校验 checkpoint 与其生成执行的冻结历史选择。
     *
     * @param checkpoint 检查点
     * @param sequence 当前记录的顺序号
     */
    private record CheckpointCandidate(CheckpointStore.ContextCheckpoint checkpoint,
                                       long sequence) {
    }

    /**
     * 单次请求实际采用的摘要与其可持久来源。
     *
     * @param checkpoint 检查点（可选）
     * @param seedReference 初始上下文引用（可选）
     * @param summary 摘要
     */
    private record SelectedSummary(Optional<CheckpointStore.ContextCheckpoint> checkpoint,
                                   Optional<ExecutionRecordCodec.SeedSummaryReference> seedReference,
                                   SummaryDocument summary) {
        /**
         * 保证选中快照中 checkpoint、seed 与正文严格匹配且来源互斥。
         */
        SelectedSummary {
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            seedReference = Objects.requireNonNull(seedReference, "seedReference");
            summary = Objects.requireNonNull(summary, "summary");
            if (checkpoint.isPresent() && seedReference.isPresent()) {
                throw new IllegalArgumentException("selected summary sources conflict");
            }
            if (checkpoint.isPresent()) {
                CheckpointStore.ContextCheckpoint value = checkpoint.orElseThrow();
                if (!value.summary().equals(summary)) {
                    throw new IllegalArgumentException("checkpoint summary mismatch");
                }
            }
        }

        /**
         * 构造精确 checkpoint 来源，保留其本身的 retained/split 边界。
         */
        static SelectedSummary checkpoint(CheckpointStore.ContextCheckpoint checkpoint) {
            return new SelectedSummary(Optional.of(checkpoint), Optional.empty(), checkpoint.summary());
        }

        /**
         * 构造初始父摘要来源；checkpoint through 仍从 0 起算，父消息 ordinal 保持原水位。
         */
        static SelectedSummary seed(SummaryDocument summary, ExecutionRecordCodec.SeedSummaryReference reference) {
            return new SelectedSummary(Optional.empty(), Optional.of(reference), summary);
        }

        /**
         * 表示该模型请求实际没有摘要，而不是缺失引用或等待数据库补选。
         */
        static SelectedSummary empty() {
            return new SelectedSummary(Optional.empty(), Optional.empty(), SummaryDocument.empty());
        }
    }
}
