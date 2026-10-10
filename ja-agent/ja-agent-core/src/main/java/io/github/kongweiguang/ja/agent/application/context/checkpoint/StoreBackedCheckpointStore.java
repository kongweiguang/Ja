// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.context.checkpoint;

import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecord;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoredRecord;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ContextCheckpoint;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ProjectionSnapshot;

/**
 * 只恢复或暂存上下文事实；真正提交必须由执行器连同游标、用量和事件一次完成。
 */
public final class StoreBackedCheckpointStore {
    private static final String CHECKPOINT = "agent.context.checkpoint";
    private static final String PROJECTION = "agent.context.projection";
    private static final int PAGE_SIZE = 256;
    private final AgentStore store;
    private final ExecutionRecordCodec codec;

    /**
     * 借用同一执行存储及严格编解码器，不创建本地文件、内存替代库或独立事务。
     */
    public StoreBackedCheckpointStore(AgentStore store, ExecutionRecordCodec codec) {
        this.store = Objects.requireNonNull(store, "store");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * 同阶段只允许追加选择；新阶段仍必须保持编号与来源版本单调。
     */
    private static void requireProjectionSuccessor(CheckpointStore.ProjectionSnapshot previous,
                                                   CheckpointStore.ProjectionSnapshot next) {
        if (!previous.threadId().equals(next.threadId()) || next.stageNumber() < previous.stageNumber()
            || next.sourceRevision() < previous.sourceRevision()) {
                throw corrupt("projection stage moved backwards");
            }
        if (previous.stageNumber() == next.stageNumber()) {
            if (!previous.stageId().equals(next.stageId()) || previous.sourceRevision() != next.sourceRevision()
                || previous.reason() != next.reason() || !previous.binding().equals(next.binding())
                || !next.selections().entrySet().containsAll(previous.selections().entrySet())) {
                throw corrupt("frozen projection changed");
            }
        } else if (previous.stageId().equals(next.stageId())) {
            throw corrupt("projection identity reused");
        }
    }

    /**
     * 存储读取允许旧源版本，但不允许跨租户会话或来自未来的事实。
     */
    private static void requireSource(AgentStore.ConversationSnapshot snapshot, String threadId, long sourceRevision) {
        if (!snapshot.key().threadId().equals(threadId) || sourceRevision > snapshot.revision()) {
            throw corrupt("context source does not match conversation snapshot");
        }
    }

    /**
     * 新事实必须携带本次快照 CAS，旧版本不能被自动提升为当前版本。
     */
    private static void requireCurrentSource(View view, String threadId, long sourceRevision) {
        requireSource(view.conversation(), threadId, sourceRevision);
        if (sourceRevision != view.conversation().revision())
            {
                throw new CheckpointStore.CommitConflict("context source changed");
            }
    }

    /**
     * 暂存记录也绑定完整租户会话，防止宿主把其他会话的摘要写入当前执行。
     */
    private static void requireExecution(AgentStore.ExecutionKey execution, View view) {
        if (!Objects.requireNonNull(execution, "execution").conversation().equals(view.conversation().key())) {
            throw new IllegalArgumentException("context execution belongs to another conversation");
        }
    }

    /**
     * 损坏事实明确失败，不将空白摘要或重新选择的投影视为恢复成功。
     */
    private static AgentStore.StoreIntegrityException corrupt(String message) {
        return new AgentStore.StoreIntegrityException(message, null);
    }

    /**
     * 将既有压缩策略绑定到唯一执行提交者；所有写入均由 Session 携带当前游标与 fencing 提交。
     */
    public CheckpointStore bind(AgentStore.ExecutionKey execution, Committer committer) {
        return new BoundStore(Objects.requireNonNull(execution, "execution"), Objects.requireNonNull(committer, "committer"));
    }

    /**
     * 全部分页固定在调用方捕获的记录水位；损坏、跨会话或倒退记录都不能当作无压缩历史。
     */
    public View read(AgentStore.ConversationSnapshot snapshot) {
        return scan(snapshot, Optional.empty()).view();
    }

    /**
     * 按模型请求冻结的 checkpoint ID 读取历史摘要；绝不以同一水位的 latest 替代缺失引用。
     */
    public CheckpointStore.ContextCheckpoint readCheckpoint(AgentStore.ConversationSnapshot snapshot,
                                                            String checkpointId) {
        if (checkpointId == null || checkpointId.isBlank() || checkpointId.length() > 256
            || checkpointId.indexOf('\0') >= 0 || checkpointId.indexOf('\n') >= 0
            || checkpointId.indexOf('\r') >= 0) {
            throw corrupt("invalid checkpoint reference");
        }
        return scan(snapshot, Optional.of(checkpointId)).selectedCheckpoint().orElseThrow(
                () -> corrupt("referenced checkpoint is missing from the restored record watermark"));
    }

    /**
     * 单次分页扫描同时校验 checkpoint 演进和精确引用，避免 latest 读取与历史查找各有一套规则。
     */
    private CheckpointScan scan(AgentStore.ConversationSnapshot snapshot, Optional<String> requestedCheckpointId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(requestedCheckpointId, "requestedCheckpointId");
        CheckpointStore.ContextCheckpoint checkpoint = null;
        CheckpointStore.ContextCheckpoint selectedCheckpoint = null;
        int selectedCount = 0;
        java.util.Set<String> checkpointIds = new java.util.HashSet<>();
        CheckpointStore.ProjectionSnapshot projection = null;
        long after = 0;
        while (after < snapshot.recordSequence()) {
            ConversationRecordPage page = store.readConversationRecords(
                    AgentStore.ConversationRecordQuery.withDefaultMaxBytes(
                            snapshot.key(), after, snapshot.recordSequence(), PAGE_SIZE));
            long previous = after;
            for (ConversationRecord record : page.records()) {
                if (!record.conversation().equals(snapshot.key()) || record.sequence() <= previous
                    || record.sequence() > snapshot.recordSequence()) {
                        throw corrupt("invalid context record order");
                    }
                previous = record.sequence();
                StoredRecord stored = new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                        record.payloadSchemaVersion(), record.sequence(), record.payload(), record.occurredAt());
                if (CHECKPOINT.equals(record.recordType())) {
                    ContextCheckpoint next = codec.decodeCheckpoint(stored);
                    requireSource(snapshot, next.threadId(), next.sourceRevision());
                    if (!checkpointIds.add(next.checkpointId())) {
                        throw corrupt("checkpoint identity was reused in the conversation");
                    }
                    if (checkpoint != null && next.sourceRevision() < checkpoint.sourceRevision()) {
                        throw corrupt("checkpoint source revision moved backwards");
                    }
                    if (requestedCheckpointId.isEmpty() || requestedCheckpointId.orElseThrow().equals(next.checkpointId())) {
                        if (requestedCheckpointId.isPresent() && ++selectedCount > 1) {
                            throw corrupt("checkpoint reference is ambiguous in the restored record watermark");
                        }
                        selectedCheckpoint = next;
                    }
                    checkpoint = next;
                } else if (PROJECTION.equals(record.recordType())) {
                    ProjectionSnapshot next = codec.decodeProjection(stored);
                    requireSource(snapshot, next.threadId(), next.sourceRevision());
                    if (projection != null) {
                        requireProjectionSuccessor(projection, next);
                    }
                    projection = next;
                }
            }
            if (previous <= after || page.nextSequence() != previous
                || !page.hasMore() && previous != snapshot.recordSequence()) {
                throw corrupt("context record watermark is incomplete");
            }
            after = previous;
        }
        if (requestedCheckpointId.isPresent() && selectedCheckpoint == null) {
            return new CheckpointScan(new View(snapshot, Optional.ofNullable(checkpoint), Optional.ofNullable(projection)),
                    Optional.empty());
        }
        return new CheckpointScan(new View(snapshot, Optional.ofNullable(checkpoint), Optional.ofNullable(projection)),
                Optional.ofNullable(selectedCheckpoint));
    }

    /**
     * 普通追加复用冻结阶段；仅明确阶段变化或模型、工具绑定变化才分配新的稳定阶段。
     */
    public CheckpointStore.ProjectionSnapshot prepareProjection(View view, CheckpointStore.ProjectionRequest request,
                                                                Optional<CheckpointStore.ProjectionReason> advanceReason) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(advanceReason, "advanceReason");
        requireCurrentSource(view, request.threadId(), request.sourceRevision());
        if (request.binding().equals(CheckpointStore.ProjectionBinding.unbound())) {
            throw new IllegalArgumentException("persistent projection requires explicit model and tool bindings");
        }
        ProjectionSnapshot current = view.projection().orElse(null);
        boolean sameBinding = current != null && current.binding().equals(request.binding());
        boolean checkpointAfterStage = current != null && view.checkpoint()
                .filter(value -> value.sourceRevision() >= current.sourceRevision()).isPresent();
        if (sameBinding && advanceReason.isEmpty() && !checkpointAfterStage) {
            return current;
        }
        boolean checkpointCovered = current != null && view.checkpoint()
                .filter(value -> value.sourceRevision() < current.sourceRevision()).isPresent();
        if (sameBinding && checkpointCovered && advanceReason.filter(reason ->
                reason == CheckpointStore.ProjectionReason.CHECKPOINT
                && (current.reason() == CheckpointStore.ProjectionReason.CHECKPOINT
                    || current.reason() == CheckpointStore.ProjectionReason.OVERFLOW)
                || reason == CheckpointStore.ProjectionReason.OVERFLOW
                   && current.reason() == CheckpointStore.ProjectionReason.OVERFLOW).isPresent()) {
            return current;
        }
        CheckpointStore.ProjectionReason reason = advanceReason.orElseGet(() -> current == null
                ? CheckpointStore.ProjectionReason.INITIAL
                : checkpointAfterStage && sameBinding ? CheckpointStore.ProjectionReason.CHECKPOINT
                  : !current.binding().modelBinding().equals(request.binding().modelBinding())
                    ? CheckpointStore.ProjectionReason.MODEL_BINDING : CheckpointStore.ProjectionReason.TOOL_BINDING);
        long stage = current == null ? 1 : Math.addExact(current.stageNumber(), 1);
        String material = view.conversation().key().tenantId() + "\0" + request.threadId() + "\0" + stage
                          + "\0" + request.sourceRevision() + "\0" + request.binding().modelBinding()
                          + "\0" + request.binding().toolBinding() + "\0" + reason.name();
        return new CheckpointStore.ProjectionSnapshot("projection_" + Hashing.sha256Hex(material), request.threadId(), stage,
                request.sourceRevision(), reason, request.binding(), Map.of());
    }

    /**
     * 选择只能追加不能改写；返回待提交记录，调用方须在发起 Provider 请求前原子提交。
     */
    public StagedProjection stageProjection(AgentStore.ExecutionKey execution, View view,
                                            CheckpointStore.ProjectionSnapshot projection, Map<String, ToolProjection> selections,
                                            Instant occurredAt) {
        requireExecution(execution, view);
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireSource(view.conversation(), projection.threadId(), projection.sourceRevision());
        ProjectionSnapshot complete = projection.withSelections(selections);
        requireProjectionSuccessor(projection, complete);
        view.projection().ifPresent(previous -> requireProjectionSuccessor(previous, complete));
        if (view.projection().filter(complete::equals).isPresent()) {
            return new StagedProjection(complete, List.of());
        }
        long previousStage = view.projection().map(CheckpointStore.ProjectionSnapshot::stageNumber).orElse(0L);
        if (complete.stageNumber() != previousStage) {
            if (complete.stageNumber() != Math.addExact(previousStage, 1)) {
                throw new IllegalArgumentException("projection must advance one stage at a time");
            }
            requireCurrentSource(view, complete.threadId(), complete.sourceRevision());
        }
        StringBuilder material = new StringBuilder(complete.stageId());
        complete.selections().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> material.append('\0').append(entry.getKey()).append('\0').append(entry.getValue().name()));
        String recordId = AgentRecordIds.derive(execution, PROJECTION, Hashing.sha256Hex(material.toString()));
        return new StagedProjection(complete, List.of(codec.encodeProjection(recordId, complete, occurredAt)));
    }

    /**
     * 摘要源必须仍对应当前 CAS 版本，慢摘要不能覆盖期间新增的消息或另一份摘要。
     */
    public AgentStore.RecordWrite stageCheckpoint(AgentStore.ExecutionKey execution, View view,
                                                  CheckpointStore.ContextCheckpoint checkpoint, Instant occurredAt) {
        requireExecution(execution, view);
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireCurrentSource(view, checkpoint.threadId(), checkpoint.sourceRevision());
        view.checkpoint().ifPresent(previous -> {
            if (checkpoint.checkpointId().equals(previous.checkpointId())) {
                throw new IllegalArgumentException("checkpoint must not reuse an existing identity");
            }
        });
        return codec.encodeCheckpoint(AgentRecordIds.derive(execution, CHECKPOINT, checkpoint.checkpointId()),
                checkpoint, occurredAt);
    }

    /**
     * 回调必须使用当前执行的 CAS 与游标，不能另开事务或返回尚未提交的成功回执。
     */
    public interface Committer {
        /**
         * 返回本 owner 最后一次确认提交的快照，不通过读取最新版本掩盖并发消息变化。
         */
        AgentStore.ConversationSnapshot snapshot();

        /**
         * 选择与当前执行游标一起提交，失败不得发起对应 Provider 请求。
         */
        AgentStore.CommitReceipt commitProjection(long expectedThreadRevision, List<AgentStore.RecordWrite> records);

        /**
         * 摘要、用量、恢复游标和压缩事件共用本次提交，返回实际已提交回执。
         */
        CheckpointStore.CommittedCheckpoint commitCheckpoint(CheckpointStore.CommitRequest request,
                                                             AgentStore.RecordWrite record);
    }

    /**
     * 空 Optional 只表示固定记录窗口没有该类事实，不代表默认或内存检查点。
     *
     * @param conversation 对话
     * @param checkpoint 检查点（可选）
     * @param projection 投影（可选）
     */
    public record View(AgentStore.ConversationSnapshot conversation,
                       Optional<CheckpointStore.ContextCheckpoint> checkpoint,
                       Optional<CheckpointStore.ProjectionSnapshot> projection) {
        /**
         * 保存来源水位供调用者加入同一次 ExecutionCommit 的版本校验。
         */
        public View {
            Objects.requireNonNull(conversation, "conversation");
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            projection = Objects.requireNonNull(projection, "projection");
            checkpoint.ifPresent(value -> requireSource(conversation, value.threadId(), value.sourceRevision()));
            projection.ifPresent(value -> requireSource(conversation, value.threadId(), value.sourceRevision()));
        }
    }

    /**
     * 暂存与提交有明确类型区别，调用方不能把这些记录直接作为已提交回执发布。
     *
     * @param projection 投影
     * @param records 记录列表
     */
    public record StagedProjection(CheckpointStore.ProjectionSnapshot projection,
                                   List<AgentStore.RecordWrite> records) {
        /**
         * 防御性复制让提交前的调用方修改不能改变冻结选择。
         */
        public StagedProjection {
            Objects.requireNonNull(projection, "projection");
            records = List.copyOf(records);
        }
    }

    /**
     * 内部扫描结果分别保留最新策略快照与按模型消息精确命中的历史检查点。
     *
     * @param view 只读视图
     * @param selectedCheckpoint 已选检查点（可选）
     */
    private record CheckpointScan(View view,
                                  Optional<CheckpointStore.ContextCheckpoint> selectedCheckpoint) {
    }

    /**
     * 一个请求仅由一个执行 owner 使用；源版本与自身控制事实引起的版本推进保持可区分。
     */
    private final class BoundStore implements CheckpointStore {
        private final AgentStore.ExecutionKey execution;
        private final Committer committer;
        private View view;
        private long sourceRevision;
        private Instant projectionTime;

        /**
         * 从 owner 已确认的持久快照开始，不生成 ephemeral projection 或伪摘要。
         */
        private BoundStore(AgentStore.ExecutionKey execution, Committer committer) {
            this.execution = execution;
            this.committer = committer;
            refresh();
            sourceRevision = view.conversation().revision();
        }

        /**
         * 策略读取建立本次不可变历史源，后续自身控制事实不冒充新的用户内容。
         */
        @Override
        public Snapshot read(String threadId) {
            requireThread(threadId);
            refresh();
            sourceRevision = view.conversation().revision();
            return new Snapshot(threadId, sourceRevision, view.checkpoint());
        }

        /**
         * 提交摘要时必须使用生成器给出的最新 owner CAS，而非最初请求的旧版本。
         */
        @Override
        public CommittedCheckpoint commit(CommitRequest request) {
            requireThread(request.threadId());
            refresh();
            AgentStore.RecordWrite record = stageCheckpoint(execution, view, request.checkpoint(), request.checkpoint().createdAt());
            CommittedCheckpoint committed = Objects.requireNonNull(committer.commitCheckpoint(request, record), "checkpoint receipt");
            refresh();
            if (!committed.checkpoint().equals(request.checkpoint())
                || committed.threadRevision() != view.conversation().revision()
                || view.checkpoint().filter(request.checkpoint()::equals).isEmpty()) {
                throw corrupt("checkpoint commit receipt does not match durable facts");
            }
            return committed;
        }

        /**
         * 已冻结阶段不因普通消息追加重选，Provider 前的持久选择仍由 persistProjection 完成。
         */
        @Override
        public ProjectionSnapshot prepareProjection(ProjectionRequest request) {
            projectionTime = request.occurredAt();
            return StoreBackedCheckpointStore.this.prepareProjection(viewFor(request), currentRequest(request), Optional.empty());
        }

        /**
         * 显式阶段切换依赖真实 owner 版本；不让 source/binding 重试生成随机身份。
         */
        @Override
        public ProjectionSnapshot advanceProjection(ProjectionRequest request, ProjectionReason reason) {
            projectionTime = request.occurredAt();
            return StoreBackedCheckpointStore.this.prepareProjection(viewFor(request), currentRequest(request), Optional.of(reason));
        }

        /**
         * 等待提交与回读成功后才返回选择，压缩策略无法越过未持久的 Provider 意图。
         */
        @Override
        public ProjectionSnapshot persistProjection(ProjectionSnapshot projection,
                                                    Map<String, ToolProjection> selections) {
            refresh();
            StagedProjection staged = stageProjection(execution, view, projection, selections,
                    Objects.requireNonNull(projectionTime, "projection must be prepared before persistence"));
            if (staged.records().isEmpty()) {
                return staged.projection();
            }
            long expected = view.conversation().revision();
            AgentStore.CommitReceipt receipt = Objects.requireNonNull(
                    committer.commitProjection(expected, staged.records()), "projection receipt");
            refresh();
            if (receipt.threadRevision() != view.conversation().revision()
                || view.projection().filter(staged.projection()::equals).isEmpty()) {
                throw corrupt("projection commit receipt does not match durable facts");
            }
            return staged.projection();
        }

        /**
         * 只接受本次冻结源或 owner 的已确认版本；外部变化由提交回调的 CAS 拒绝。
         */
        private View viewFor(ProjectionRequest request) {
            requireThread(request.threadId());
            refresh();
            if (request.sourceRevision() != sourceRevision && request.sourceRevision() != view.conversation().revision()) {
                throw new CommitConflict("projection source changed");
            }
            return view;
        }

        /**
         * Projection 的来源记录当前阶段落库版本，旧请求源只用于校验本次规划身份。
         */
        private ProjectionRequest currentRequest(ProjectionRequest request) {
            return new ProjectionRequest(request.threadId(), view.conversation().revision(), request.binding(), request.occurredAt());
        }

        /**
         * 用提交者持有的已确认水位回读，禁止数据库最新版本被当作隐式重试。
         */
        private void refresh() {
            ConversationSnapshot snapshot = Objects.requireNonNull(committer.snapshot(), "owner snapshot");
            if (!snapshot.key().equals(execution.conversation()))
                {
                    throw corrupt("checkpoint owner changed conversation");
                }
            if (view != null && snapshot.revision() < view.conversation().revision())
                {
                    throw corrupt("checkpoint owner revision moved backwards");
                }
            view = StoreBackedCheckpointStore.this.read(snapshot);
        }

        /**
         * 旧策略端口仅接收会话 ID，但绑定处仍保留完整租户边界。
         */
        private void requireThread(String threadId) {
            if (!execution.conversation().threadId().equals(threadId))
                {
                    throw new IllegalArgumentException("checkpoint conversation mismatch");
                }
        }
    }
}
