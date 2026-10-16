// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.startup;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.collaboration.application.service.TaskCoordinator;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅为本次启动发现的审批代际重挂到期投影；它不恢复 Turn、不创建 Driver，也不推断用户决定。
 */
public final class ApprovalExpiryRecoveryListener implements AgentEventListener, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApprovalExpiryRecoveryListener.class);
    private static final int REPLAY_PAGE_SIZE = 256;
    private static final int MAX_REPLAY_PAGES = 4096;

    private final AgentStore store;
    private final ExecutionControlCodec controls;
    private final ExecutionEventCodec events;
    private final MybatisTaskRepository tasks;
    private final Clock clock;
    private final ConcurrentHashMap<AgentIdentity, ApprovalClaim> startupClaims = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile AgentEngine engine;
    private volatile TaskCoordinator taskCoordinator;

    /**
     * 审批只属于 Core typed wait，不能从普通交互投影推断；复用同一 Store 与 Task owner，不另开资源。
     */
    public ApprovalExpiryRecoveryListener(AgentStore store,
                                          ExecutionControlCodec controls,
                                          ExecutionEventCodec events,
                                          MybatisTaskRepository tasks,
                                          Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.events = Objects.requireNonNull(events, "events");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 订阅身份固定在扩展图中，避免启动重试注册第二个生命周期监听器。
     */
    @Override
    public String id() {
        return "ja.approval-expiry-recovery";
    }

    /**
     * 仅处理启动扫描注册的精确 interaction revision；热审批由 TurnService watcher 发布，避免双重 Task 投影。
     */
    @Override
    public void onEvent(AgentEvent event) {
        AgentEvent committed = Objects.requireNonNull(event, "event");
        if (closed.get() || !isLocalIdentity(committed.identity())
            || !"agent.execution.control".equals(committed.persistedEvent().eventType())) {
            return;
        }
        ApprovalClaim claim = startupClaims.get(committed.identity());
        if (claim == null) {
            return;
        }
        ExecutionEvent decoded = events.decode(committed.persistedEvent());
        if (!(decoded instanceof ExecutionEvent.ControlRecorded control)
            || control.action() != ExecutionEvent.ControlAction.WAIT_RESOLVED) {
            return;
        }
        Optional<InteractionWait> current = request(claim.identity());
        if (current.isEmpty() || !current.orElseThrow().interactionId().equals(claim.interactionId())) {
            startupClaims.remove(claim.identity(), claim);
            return;
        }
        if ("EXPIRED".equals(control.reasonCode())) {
            if (current.orElseThrow().status() != InteractionStatus.EXPIRED
                || current.orElseThrow().interactionRevision() != claim.interactionRevision() + 1) {
                if (current.orElseThrow().status() != InteractionStatus.PENDING) {
                    startupClaims.remove(claim.identity(), claim);
                }
                return;
            }
            reconcileAndPublish(claim);
            startupClaims.remove(claim.identity(), claim);
        } else if (current.isPresent() && current.orElseThrow().status() != InteractionStatus.PENDING) {
            startupClaims.remove(claim.identity(), claim);
        }
    }

    /**
     * 队列缺口按固定事件水位补读；超界或无进展时保留失败供下次启动扫描收敛。
     * Engine 由运行时组合根持有，本监听器只借用其事件读取能力；PMD 无法区分借用端口与待关闭资源。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void onReplayRequired(ReplayRequired replay) {
        ReplayRequired request = Objects.requireNonNull(replay, "replay");
        if (!isLocalIdentity(request.identity()) || closed.get()) {
            return;
        }
        AgentEngine source = Objects.requireNonNull(engine, "approval recovery Engine is not bound");
        long cursor = request.afterSequence();
        for (int pageNumber = 0; cursor < request.throughSequence(); pageNumber++) {
            if (pageNumber == MAX_REPLAY_PAGES) {
                throw new IllegalStateException("approval recovery replay exceeded its page limit");
            }
            ensureNotInterrupted();
            AgentEventPage page = source.readEvents(request.identity(), cursor, REPLAY_PAGE_SIZE);
            if (page.nextSequence() <= cursor) {
                throw new IllegalStateException("approval recovery replay cursor did not advance");
            }
            long previous = cursor;
            for (AgentStore.PersistedEvent persisted : page.events()) {
                if (persisted.sequence() <= previous || persisted.sequence() > request.throughSequence()) {
                    if (persisted.sequence() > request.throughSequence()) {
                        break;
                    }
                    throw new IllegalStateException("approval recovery replay page is out of order");
                }
                previous = persisted.sequence();
                onEvent(new AgentEvent(request.identity(), persisted));
            }
            long next = Math.min(page.nextSequence(), request.throughSequence());
            if (!page.hasMore() && next < request.throughSequence()) {
                throw new IllegalStateException("approval recovery replay range is incomplete");
            }
            cursor = next;
        }
    }

    /**
     * 在 Engine 与 TaskCoordinator 就绪且侧聊清理完成后扫描；处理逐页完成以免到期更新扰动后续 cursor。
     */
    public int start(AgentEngine engine, ThreadUseCase threads, TaskCoordinator taskCoordinator,
                     StartupMaintenanceService maintenance) {
        Objects.requireNonNull(maintenance, "maintenance").requireInitialized();
        AgentEngine selectedEngine = Objects.requireNonNull(engine, "engine");
        ThreadUseCase selectedThreads = Objects.requireNonNull(threads, "threads");
        TaskCoordinator selectedTasks = Objects.requireNonNull(taskCoordinator, "taskCoordinator");
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("approval expiry recovery cannot be started");
        }
        this.engine = selectedEngine;
        this.taskCoordinator = selectedTasks;
        return recoverPages(selectedThreads);
    }

    /**
     * 启动关闭先撤销仅属于此进程发现代际的 claim；Core Engine 再关闭它自己的 expiry scheduler。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            startupClaims.clear();
        }
    }

    /**
     * 只扫描固定大小的 Thread discovery 页，并拒绝重复 cursor 或超过最大页数的存储结果。
     */
    private int recoverPages(ThreadUseCase threads) {
        final int pageSize = 200;
        final int maxPages = 4096;
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        for (; pages < maxPages; pages++) {
            ensureNotInterrupted();
            CursorPage<ThreadDiscovery> page =
                    threads.discoverThreads(new ThreadDiscovery.Query("all", null, cursor, pageSize));
            if (page.items().size() > pageSize) {
                throw new IllegalStateException("approval recovery discovery exceeded its page bound");
            }
            for (ThreadDiscovery thread : page.items()) {
                if (candidate(thread)) {
                    recoverThread(thread);
                }
            }
            String next = page.nextCursor();
            if (next == null) {
                return pages + 1;
            }
            if (next.equals(cursor) || !seenCursors.add(next)) {
                throw new IllegalStateException("approval recovery discovery cursor did not advance");
            }
            cursor = next;
        }
        throw new IllegalStateException("approval recovery discovery exceeded its page limit");
    }

    /**
     * 主动过滤只物化可能含未决审批的最新执行；侧聊不会在启动清理后复活。
     */
    private static boolean candidate(ThreadDiscovery thread) {
        return thread.kind() != ThreadDiscovery.Kind.SIDE_CHAT
                && thread.latestTurnId() != null
                && (thread.status() == ThreadDiscovery.Status.WAITING_APPROVAL
                    || thread.status() == ThreadDiscovery.Status.SUSPENDED);
    }

    /**
     * 精确绑定最新 Turn 与 Ja approval 问题；只在 SUBAGENT 中保留 Task 恢复 claim。
     * 恢复操作借用组合根持有的 Engine，关闭权仍属于共享生命周期 owner。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void recoverThread(ThreadDiscovery thread) {
        AgentIdentity identity = AppServerIdentity.local().execution(thread.threadId(), thread.latestTurnId());
        Optional<InteractionWait> found = request(identity);
        if (found.isEmpty()) {
            return;
        }
        InteractionWait request = found.orElseThrow();
        if (!isApproval(request)) {
            return;
        }
        boolean childTask = thread.kind() == ThreadDiscovery.Kind.SUBAGENT;
        ApprovalClaim claim = new ApprovalClaim(identity, request.interactionId(), request.interactionRevision(), childTask);
        if (request.status() == InteractionStatus.PENDING && childTask) {
            startupClaims.put(identity, claim);
        }
        if (request.status() != InteractionStatus.PENDING && request.status() != InteractionStatus.EXPIRED) {
            return;
        }
        AgentEngine selectedEngine = Objects.requireNonNull(engine, "approval recovery Engine is not bound");
        selectedEngine.restoreApprovalExpiry(identity);
        Optional<InteractionWait> refreshed = request(identity);
        if (refreshed.isEmpty() || !refreshed.orElseThrow().interactionId().equals(request.interactionId())) {
            startupClaims.remove(identity, claim);
            return;
        }
        InteractionWait after = refreshed.orElseThrow();
        if (after.status() == InteractionStatus.EXPIRED && childTask
            && (request.status() == InteractionStatus.EXPIRED || startupClaims.get(identity) == claim)) {
            reconcileAndPublish(claim);
            startupClaims.remove(identity, claim);
        } else if (after.status() != InteractionStatus.PENDING) {
            startupClaims.remove(identity, claim);
        }
    }

    /**
     * 沿用 Core 的显式 kind 信封识别审批，不把外观相似的普通交互当作授权。
     */
    private static boolean isApproval(InteractionWait request) {
        return request.attributes().get("kind") instanceof JsonText(String kind) && "approval".equals(kind);
    }

    /**
     * 同一个事务校验 Core SUSPENDED/current-path 与 Task 投影；发布在提交后唤醒当前观察者。
     */
    private void reconcileAndPublish(ApprovalClaim claim) {
        if (closed.get() || !claim.childTask()) {
            return;
        }
        Optional<TaskRecords.TaskSummaryRow> reconciled = tasks.reconcileRecoveredSuspension(
                claim.identity().turnId(), clock.instant());
        reconciled.filter(row -> "SUSPENDED".equals(row.state())).ifPresent(row ->
                Objects.requireNonNull(taskCoordinator, "approval recovery TaskCoordinator is not bound")
                        .publishRecoveredSuspended(row.taskThreadId(), claim.identity().turnId()));
        if (reconciled.isPresent()) {
            LOGGER.info("event=approval_expiry_task_recovered suspended_count=1");
        }
    }

    /**
     * 固定 Core 执行水位后逐页归约最新 wait；不依赖缺席的普通交互投影，也不创建 Driver 或推进执行。
     */
    private Optional<InteractionWait> request(AgentIdentity identity) {
        Optional<AgentStore.ExecutionSnapshot> snapshot =
                store.loadExecution(AppServerIdentity.local().executionKey(identity));
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        AgentStore.ExecutionSnapshot execution = snapshot.orElseThrow();
        long through = execution.lastRecordSequence();
        long cursor = 0;
        InteractionWait latest = null;
        for (int pageNumber = 0; cursor < through; pageNumber++) {
            if (pageNumber == MAX_REPLAY_PAGES) {
                throw new IllegalStateException("approval recovery records exceeded their page limit");
            }
            ensureNotInterrupted();
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    execution.key(), cursor, through, REPLAY_PAGE_SIZE));
            long previous = cursor;
            for (AgentStore.StoredRecord record : page.records()) {
                if (record.sequence() <= cursor || record.sequence() > through) {
                    throw new IllegalStateException("approval recovery record page is out of order");
                }
                if (ExecutionControlCodec.WAIT_RECORD_TYPE.equals(record.recordType())) {
                    latest = controls.decodeWait(record);
                }
                cursor = record.sequence();
            }
            if (page.nextSequence() != cursor || cursor <= previous
                    || (!page.hasMore() && cursor < through)) {
                throw new IllegalStateException("approval recovery record range is incomplete");
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * 本 listener 仅处理本地持久 owner，防止共享 Engine 的其他 tenant 被 Ja Task projection 接管。
     */
    private static boolean isLocalIdentity(AgentIdentity identity) {
        return AppServerIdentity.local().tenantId().equals(identity.tenantId())
                && AppServerIdentity.local().userId().equals(identity.userId());
    }

    /**
     * startup/replay 被中断时保留线程取消语义，不继续扫描或提交 Task 投影。
     */
    private static void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("approval expiry recovery was interrupted");
        }
    }

    /**
     * 绑定本进程发现的审批代际，使恢复只对同一交互版本生效并可被后续用户决定撤销。
     *
     * @param identity 执行身份，用于限定租户与会话归属
     * @param interactionId 交互标识，用于识别本次审批等待
     * @param interactionRevision 交互版本，用于拒绝过期代际的恢复投影
     * @param childTask 是否属于子任务，用于选择对应的 Task 状态对账
     */
    private record ApprovalClaim(AgentIdentity identity,
                                 String interactionId,
                                 long interactionRevision,
                                 boolean childTask) {
        /** claim 不能以非正版本或空身份参与投影对账。 */
        private ApprovalClaim {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(interactionId, "interactionId");
            if (interactionRevision < 0) {
                throw new IllegalArgumentException("invalid approval recovery revision");
            }
        }
    }
}
