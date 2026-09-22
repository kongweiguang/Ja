// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.RecoveryMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.TaskRecoveryPersistence;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 在 RPC admission 开放前，把崩溃遗留 Operation 调和为可验证 SUSPENDED 或稳定终态。
 */
public final class StartupRecoveryService {
    private final MybatisUnitOfWork transactions;
    private final Clock clock;
    private final TurnExecutionStateCodec executions;
    private final ToolPresentationCodec presentations;
    private final ObjectMapper objectMapper;
    private volatile List<QueuedTurn> queuedTurns = List.of();

    /** 生产构造复用官方 MyBatis-Solon Factory，执行状态使用显式无反射 codec。 */
    public StartupRecoveryService(SqlSessionFactory sessions, Clock clock) {
        transactions = new MybatisUnitOfWork(sessions);
        this.clock = Objects.requireNonNull(clock, "clock");
        objectMapper = new ObjectMapper();
        executions = new TurnExecutionStateCodec(objectMapper);
        presentations = new ToolPresentationCodec(objectMapper);
    }

    /** focused test 可注入真实 SQLite session owner，同时保持与生产完全相同的恢复编排。 */
    public StartupRecoveryService(SqlSessionFactory sessions, Clock clock,
                                  MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
        this.clock = Objects.requireNonNull(clock, "clock");
        objectMapper = new ObjectMapper();
        executions = new TurnExecutionStateCodec(objectMapper);
        presentations = new ToolPresentationCodec(objectMapper);
    }

    /**
     * 每个 Thread 在一个事务中逐 Turn 恢复，只有整批调和成功才推进一次 revision；
     * 任一 CAS 丢失都会回滚，禁止发布半恢复状态。
     */
    public RecoveryResult recover() {
        queuedTurns = List.of();
        Instant occurredAt = clock.instant();
        RecoveryBatch batch = transactions.required(mapper -> {
            int turns = 0;
            int tools = 0;
            int changeSets = 0;
            List<QueuedTurn> queued = new ArrayList<>();
            List<String> threads = mapper.recovery().selectRecoveryThreadIds();
            for (String threadId : threads) {
                ThreadRecovery recovered = recoverThread(mapper, threadId, occurredAt);
                turns += recovered.turns();
                tools += recovered.tools();
                queued.addAll(recovered.queuedTurns());
                PersistenceRecords.RecoveryCommand command = new PersistenceRecords.RecoveryCommand(
                        threadId, occurredAt.toString());
                changeSets += mapper.recovery().insertRecoveredChangeSets(command);
                requireChanged(mapper.recovery().advanceRecoveredThread(command),
                        "startup recovery lost its thread owner");
            }
            return new RecoveryBatch(new RecoveryResult(
                    threads.size(), turns, tools, changeSets, occurredAt), List.copyOf(queued));
        });
        queuedTurns = batch.queuedTurns();
        return batch.result();
    }

    /**
     * 返回本次已提交恢复中原本从未开始的 QUEUED Turn；运行时只能重接纳这些身份，不能把曾经
     * RUNNING 或 WAITING_APPROVAL 的 Operation 自动重放；每次返回防御性快照，避免调用方
     * 持有 volatile 发布列表的内部表示。
     */
    public List<QueuedTurn> queuedTurns() {
        return List.copyOf(queuedTurns);
    }

    /** 依 admission 顺序恢复同 Thread 的 Turn，取消意图先于游标损坏判断。 */
    private ThreadRecovery recoverThread(PersistenceMappers mappers, String threadId, Instant occurredAt) {
        RecoveryMapper mapper = mappers.recovery();
        int turns = 0;
        int tools = 0;
        List<QueuedTurn> queued = new ArrayList<>();
        for (PersistenceRecords.RecoveryTurnRow row : mapper.selectRecoveryTurns(threadId)) {
            if (!threadId.equals(row.threadId())) {
                throw corrupt("startup recovery returned a cross-thread Turn");
            }
            PersistenceRecords.RecoveryTurnCas cas = new PersistenceRecords.RecoveryTurnCas(
                    row.turnId(), threadId, row.mutationVersion(), occurredAt.toString());
            if (row.cancelRequestedAt() != null) {
                terminalCancellation(mappers, row.turnId(), cas, occurredAt);
                turns++;
                continue;
            }
            TurnExecutionState execution = decodeExecution(row);
            if (execution == null) {
                terminalCorruption(mappers, row.turnId(), cas, occurredAt);
                turns++;
                continue;
            }
            try {
                ExecutionRecovery reconciled = reconcileExecution(
                        mappers, row, execution, occurredAt);
                requireChanged(mapper.replaceExecution(new PersistenceRecords.TurnExecutionWrite(
                        row.turnId(), TurnExecutionState.SCHEMA_VERSION,
                        executions.write(reconciled.execution()))),
                        "startup recovery lost execution state");
                mapper.denyExpiredApprovals(row.turnId(), occurredAt.toString());
                requireChanged(mapper.suspendTurn(cas), "startup recovery lost active Turn");
                TaskRecoveryPersistence.reconcileSuspended(mappers, objectMapper, row.turnId(), occurredAt);
                if (neverStarted(row, execution)) queued.add(new QueuedTurn(row.threadId(), row.turnId()));
                tools += reconciled.tools();
            } catch (StorageException failure) {
                if (failure.code() != StorageException.Code.INVALID_STATE) throw failure;
                terminalCorruption(mappers, row.turnId(), cas, occurredAt);
            } catch (IllegalArgumentException failure) {
                terminalCorruption(mappers, row.turnId(), cas, occurredAt);
            }
            turns++;
        }
        return new ThreadRecovery(turns, tools, List.copyOf(queued));
    }

    /** Provider UNKNOWN 已在 dispatch 前与 intent 原子提交；恢复只推进原 READY，绝不能重复插入。 */
    private ExecutionRecovery reconcileExecution(PersistenceMappers mappers,
                                                  PersistenceRecords.RecoveryTurnRow row,
                                                  TurnExecutionState execution,
                                                  Instant occurredAt) {
        if (execution instanceof TurnExecutionState.ProviderPending pending) {
            return new ExecutionRecovery(pending.resume().advanceProviderOrdinal(), 0);
        }
        if (execution instanceof TurnExecutionState.Tools tools) {
            return reconcileTools(mappers, row, tools, occurredAt);
        }
        return new ExecutionRecovery(execution, 0);
    }

    /**
     * 启动后只把已结算调用向前推进。PREPARED 从未越过副作用边界，可在用户显式继续后通过原 binding
     * 执行；RUNNING 没有回执时保留未知并暂停，绝不能按名称或日志猜测成功、失败或可安全重放。
     */
    private ExecutionRecovery reconcileTools(PersistenceMappers mappers,
                                              PersistenceRecords.RecoveryTurnRow row,
                                              TurnExecutionState.Tools tools,
                                              Instant occurredAt) {
        RecoveryMapper mapper = mappers.recovery();
        int next = tools.firstOrdinal();
        int changed = 0;
        while (next <= tools.lastOrdinal()) {
            PersistenceRecords.RecoveryToolRow tool = mapper.selectRecoveryTool(row.turnId(), next);
            if (tool == null || tool.ordinal() != next) {
                throw corrupt("Tool recovery cursor is unavailable");
            }
            switch (tool.state()) {
                case "SUCCEEDED", "FAILED", "CANCELLED" -> next++;
                case "PREPARED" -> {
                    /* PREPARED 尚未穿越 ToolStarted 边界。恢复只保留精确 binding/cursor，用户点击继续后
                     * 会在当前权限下重新校验 binding 并执行，不能在应用启动时自动启动外部副作用。 */
                    return new ExecutionRecovery(tools, changed);
                }
                case "RUNNING" -> {
                    /* request_user_input 已在同一事务写入 Interaction 后才可保留；其待决请求就是唯一
                     * 权威裁决来源。其他 RUNNING Tool 只允许创建未知记录并等待 resume 的确定性核验或用户选择。 */
                    if ("request_user_input".equals(tool.toolName())
                            && mapper.hasPendingInteraction(row.turnId(), tool.callId())
                            && next == tools.nextOrdinal()) {
                        return new ExecutionRecovery(tools, changed);
                    }
                    markPendingRecovery(mappers, row, tool, occurredAt);
                    return new ExecutionRecovery(tools, changed + 1);
                }
                default -> throw corrupt("Tool recovery state is invalid");
            }
        }
        return new ExecutionRecovery(new TurnExecutionState.Ready(
                tools.common(), TurnExecutionState.Next.ASSISTANT, null), changed);
    }

    /**
     * 同一事务创建或重新打开未知记录，并把原 Tool 详情替换为简短、可操作的公开说明。文件证据只留在
     * 恢复表，Renderer 获得的只有 revision CAS；重复启动不追加第二条记录，也不会丢失原始未知事实。
     */
    private void markPendingRecovery(PersistenceMappers mappers, PersistenceRecords.RecoveryTurnRow turn,
                                     PersistenceRecords.RecoveryToolRow tool, Instant occurredAt) {
        RecoveryMapper mapper = mappers.recovery();
        mapper.insertUnknownToolRecovery(new PersistenceRecords.ToolRecoveryUnknownInsert(
                "recovery_" + tool.callId().substring("call_".length()), turn.threadId(), turn.turnId(),
                tool.callId(), occurredAt.toString()));
        requireChanged(mapper.markToolRecoveryPending(new PersistenceRecords.ToolRecoveryPending(
                        turn.turnId(), tool.callId(), occurredAt.toString())),
                "startup recovery lost pending Tool record");
        PersistenceRecords.ToolRecoveryRow recovery = mapper.selectToolRecovery(tool.callId());
        if (recovery == null || !turn.turnId().equals(recovery.turnId()) || !"PENDING".equals(recovery.state())) {
            throw corrupt("startup recovery Tool record is unavailable");
        }
        recordStartupObservation(mapper, recovery, occurredAt);
        io.github.kongweiguang.ja.conversation.domain.ToolPresentation current =
                presentations.read(tool.presentationJson());
        io.github.kongweiguang.ja.conversation.domain.ToolPresentation pending =
                new io.github.kongweiguang.ja.conversation.domain.ToolPresentation(
                        current.kind(), current.title(),
                        io.github.kongweiguang.ja.conversation.domain.ToolPresentation.Status.ERROR,
                        current.inputPreview(), current.outputPreview(),
                        "这条命令已启动，但未保存结果。再次执行可能重复操作。",
                        current.interactionAnswers(), current.relativePaths(), current.command(),
                        current.relativeCwd(), current.stdout(), current.stderr(), current.exitCode(),
                        current.durationMs(), current.truncated(), current.artifactId(),
                        new io.github.kongweiguang.ja.conversation.domain.ToolPresentation.Recovery(
                                recovery.recoveryRevision()));
        requireChanged(mappers.agent().updateToolPresentation(
                        new PersistenceRecords.ToolPresentationUpdate(turn.turnId(), tool.callId(),
                                presentations.write(pending), occurredAt.toString())),
                "startup recovery lost Tool presentation");
    }

    /**
     * STARTUP 观察以 recovery revision 派生稳定幂等键。崩溃可能发生在记录与 Tool 详情之间，
     * 因此允许重试到达，但绝不为同一未知边界追加第二次“启动观察”。
     */
    private static void recordStartupObservation(RecoveryMapper mapper,
                                                 PersistenceRecords.ToolRecoveryRow recovery,
                                                 Instant occurredAt) {
        String idempotencyKey = "startup-" + recovery.recoveryId() + "-" + recovery.recoveryRevision();
        mapper.insertToolRecoveryAttemptIgnore(new PersistenceRecords.ToolRecoveryAttemptInsert(
                "recovery_attempt_startup_" + recovery.recoveryId(), recovery.recoveryId(),
                mapper.countToolRecoveryAttempts(recovery.recoveryId()) + 1, "STARTUP", idempotencyKey,
                occurredAt.toString()));
    }

    /** 取消优先终结并清除所有可继续执行的附属状态，重复启动不会再次观察该 Turn。 */
    private void terminalCancellation(PersistenceMappers mappers, String turnId,
                                      PersistenceRecords.RecoveryTurnCas cas,
                                      Instant occurredAt) {
        RecoveryMapper mapper = mappers.recovery();
        requireChanged(mapper.cancelTurn(cas), "startup recovery lost cancellation intent");
        closeTerminalResources(mapper, turnId, occurredAt);
        TaskRecoveryPersistence.reconcileTerminal(
                mappers, objectMapper, turnId,
                io.github.kongweiguang.ja.conversation.domain.turn.TurnState.CANCELLED, occurredAt);
    }

    /** 缺失、未来版本或不自洽游标稳定 FAILED，不从消息、Tool 或日志猜测恢复点。 */
    private void terminalCorruption(PersistenceMappers mappers, String turnId,
                                    PersistenceRecords.RecoveryTurnCas cas,
                                    Instant occurredAt) {
        RecoveryMapper mapper = mappers.recovery();
        requireChanged(mapper.failCorruptTurn(cas), "startup recovery lost corrupt Turn");
        closeTerminalResources(mapper, turnId, occurredAt);
        TaskRecoveryPersistence.reconcileTerminal(
                mappers, objectMapper, turnId,
                io.github.kongweiguang.ja.conversation.domain.turn.TurnState.FAILED, occurredAt);
    }

    /**
     * 终态关闭审批/输入并尽力移除 execution；缺失 execution 正是 corruption 的合法输入，
     * 因而删除零行不能反向回滚已经提交的 FAILED 收敛。
     */
    private static void closeTerminalResources(RecoveryMapper mapper, String turnId, Instant occurredAt) {
        String timestamp = occurredAt.toString();
        mapper.closePendingApprovals(turnId, timestamp);
        mapper.cancelPendingInputs(turnId, timestamp);
        mapper.deleteExecution(turnId);
    }

    /** 严格版本与 JSON 解码失败返回 null，让调用方在同一事务执行稳定失败关闭。 */
    private TurnExecutionState decodeExecution(PersistenceRecords.RecoveryTurnRow row) {
        if (row.schemaVersion() == null || row.schemaVersion() != TurnExecutionState.SCHEMA_VERSION
            || row.stateJson() == null) return null;
        try {
            return executions.read(row.stateJson());
        } catch (StorageException | IllegalArgumentException failure) {
            return null;
        }
    }

    /**
     * QUEUED 标签必须同时具备初始 READY 游标才证明 Provider/Tool 从未派发；任一累计计数或其它
     * 子状态都按曾执行处理，只保持 SUSPENDED，避免仅凭公开状态自动重放潜在副作用。
     */
    private static boolean neverStarted(PersistenceRecords.RecoveryTurnRow row, TurnExecutionState execution) {
        if (!"QUEUED".equals(row.state()) || !(execution instanceof TurnExecutionState.Ready ready)) return false;
        TurnExecutionState.Common common = ready.common();
        return common.modelRound() == 0 && common.usedToolCalls() == 0 && common.nextProviderOrdinal() == 1
                && ready.next() == TurnExecutionState.Next.ASSISTANT && ready.summary() == null;
    }

    /** 所有恢复状态门都必须精确改变一行，否则整批回滚而不是掩盖竞态。 */
    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw new StorageException(StorageException.Code.INVALID_STATE, message);
    }

    /** 恢复损坏统一使用无用户内容的存储分类，不把 JSON、Tool 或 identity 带入错误。 */
    private static StorageException corrupt(String message) {
        return new StorageException(StorageException.Code.INVALID_STATE, message);
    }

    /** 单 Thread 汇总只统计真实状态变化，不把读取或已结算游标推进计为 Tool 恢复。 */
    private record ThreadRecovery(int turns, int tools, List<QueuedTurn> queuedTurns) { }

    /** 事务结果与待重接纳身份一起离开 UnitOfWork，防止回滚批次泄露给运行时。 */
    private record RecoveryBatch(RecoveryResult result, List<QueuedTurn> queuedTurns) { }

    /** 执行调和结果携带完整替换快照及实际改写的 Tool 数量。 */
    private record ExecutionRecovery(TurnExecutionState execution, int tools) { }

    /** 汇总一次启动恢复的稳定数量与统一时间，供启动诊断和幂等测试使用。 */
    public record RecoveryResult(int threads, int turns, int tools, int changeSets, Instant occurredAt) { }

    /** 原 QUEUED Turn 的稳定身份；顺序沿用恢复查询的 Thread 与 turn_sequence 排序。 */
    public record QueuedTurn(String threadId, String turnId) {
        /** 拒绝空 identity，避免启动接线把损坏行交给普通 resume 路径。 */
        public QueuedTurn {
            if (threadId == null || threadId.isBlank() || turnId == null || turnId.isBlank()) {
                throw new IllegalArgumentException("invalid queued Turn identity");
            }
        }
    }
}
