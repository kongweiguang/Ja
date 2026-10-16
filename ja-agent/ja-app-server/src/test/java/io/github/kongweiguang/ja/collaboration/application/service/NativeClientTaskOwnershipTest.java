// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.service;

import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationBoundary;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionGateway;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.ConversationBoundaryAdapter;
import io.github.kongweiguang.ja.conversation.application.api.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicyRepository;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner scoped 临时任务只允许同一 NativeClientOwner 重放自己的持久关闭回执。 */
final class NativeClientTaskOwnershipTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);

    /** 关闭成功会清除活动 root，但同 owner 重试仍回放同一持久回执且不扩大权限。 */
    @Test
    void sameOwnerCanReplayClosedRootWithoutRetainingActiveWork() throws Exception {
        NativeExecutionContext context = NativeExecutionContext.shared();
        AtomicReference<TaskModels.Summary> current = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger deleteCalls = new AtomicInteger();
        TaskRepository repository = repository(current, closed, deleteCalls);
        ThreadUseCase threads = threads();
        Object turnOwner = turnOwner();

        try (NativeHostProcessFixture.Host host = NativeHostProcessFixture.start()) {
            NativeClientOwner owner = context.registerNativeClient(host.processId(), host.lifetimeId());
            NativeClientOwner otherOwner = context.registerNativeClient(host.processId(),
                    "native_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            try (TaskCoordinator coordinator = coordinator(repository, threads, turnOwner)) {
                coordinator.bindSideChatOwnerController((ignoredRoots, deadlineNanos) -> { });
                TaskModels.Summary created = coordinator.createSideTask(new TaskUseCase.CreateCommand(
                        "thr_parent", "turn_parent", 7, "temporary root", null), Optional.of(owner));
                assertTrue(current.get() != null);
                String rootThreadId = created.lineage().taskThreadId();

                TaskModels.SideChatCloseReceipt first = coordinator.closeSideChat(rootThreadId,
                        Optional.of(owner));
                TaskRepositoryException foreignOwner = assertThrows(TaskRepositoryException.class,
                        () -> coordinator.closeSideChat(rootThreadId, Optional.of(otherOwner)));
                TaskRepositoryException arbitraryId = assertThrows(TaskRepositoryException.class,
                        () -> coordinator.closeSideChat("thr_unowned_subagent", Optional.of(owner)));
                TaskModels.SideChatCloseReceipt replay = coordinator.closeSideChat(rootThreadId,
                        Optional.of(owner));

                assertEquals(first, replay);
                assertEquals(1, deleteCalls.get(), "closed retry must read the persistent receipt, not purge again");
                assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, foreignOwner.code());
                assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, arbitraryId.code());
                assertFalse(coordinator.hasLiveNativeClientWork(),
                        "closed-root replay identity must not keep the Native owner active");
                assertTrue(closed.get());
            }
        }
    }

    /** 所需路径真实提交 Activity 以外的 Task 事实；关闭回执记录删树前的 frozen root 集合。
     * @author kongweiguang
     */
    private static TaskRepository repository(AtomicReference<TaskModels.Summary> current,
                                             AtomicBoolean closed, AtomicInteger deleteCalls) {
        return proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> {
                String requested = (String) args[0];
                TaskModels.Summary summary = current.get();
                yield requested.equals("thr_parent") || summary == null || closed.get()
                        ? Optional.empty() : Optional.of(summary);
            }
            case "freezeEffectiveContext" -> new TaskModels.EffectiveContextSnapshot(
                    "thr_parent", 7, null, new JsonArray(List.of()),
                    JsonObjects.builder().putText("version", "task_access_v1")
                            .putText("accessMode", "approval_required").build());
            case "admitIdleChild" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                TaskModels.Summary summary = summary(child);
                current.set(summary);
                yield summary;
            }
            case "readTask" -> Optional.empty();
            case "beginSideChatClose" -> {
                String rootThreadId = (String) args[0];
                TaskModels.SideChatClosePhase phase = closed.get()
                        ? TaskModels.SideChatClosePhase.CLOSED : TaskModels.SideChatClosePhase.CLOSING;
                yield new TaskModels.SideChatCloseState(rootThreadId, "thr_parent", phase,
                        List.of(rootThreadId));
            }
            case "deleteClosedSideChat" -> {
                String rootThreadId = (String) args[0];
                String ownerRootThreadId = (String) args[1];
                @SuppressWarnings("unchecked")
                List<String> threadIds = (List<String>) args[2];
                deleteCalls.incrementAndGet();
                closed.set(true);
                current.set(null);
                yield new TaskModels.SideChatCloseReceipt(rootThreadId, ownerRootThreadId, threadIds);
            }
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** Host 只需真实父 Thread 元数据；关闭路径没有活动 Turn，因此读取不到子 Thread 可直接略过。
     * @author kongweiguang
     */
    private static ThreadUseCase threads() {
        ThreadSummary parent = new ThreadSummary("thr_parent", "ws_test", "Parent", "project", PREFERENCES,
                ThreadSummary.Status.ACTIVE, false, null, true, 7, NOW, NOW);
        ThreadSnapshot snapshot = new ThreadSnapshot(parent, List.of(), List.of(), null, null, null);
        return proxy(ThreadUseCase.class, (method, args) -> "readThread".equals(method.getName())
                ? Optional.of(snapshot) : throwUnsupported(method));
    }

    /** Create/close 不启动 Turn；同一双接口 proxy 满足 Coordinator 的共享 Turn owner 不变量。
     * @author kongweiguang
     */
    private static Object turnOwner() {
        return Proxy.newProxyInstance(NativeClientTaskOwnershipTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class},
                (proxy, method, args) -> throwUnsupported(method));
    }

    /** 构造真实 Coordinator，但让未触及的 admission、mailbox 和 policy 边界严格失败关闭。
     * @author kongweiguang
     */
    private static TaskCoordinator coordinator(TaskRepository repository, ThreadUseCase threads,
                                              Object turnOwner) {
        Workspace workspace = new Workspace("ws_test", Path.of("C:\\ja-owner-scope"), "Fixture",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, 1);
        WorkspaceUseCase workspaces = proxy(WorkspaceUseCase.class, (method, args) ->
                "requireOpenWorkspace".equals(method.getName()) ? workspace : throwUnsupported(method));
        TaskMailboxPort mailboxes = proxy(TaskMailboxPort.class, (method, args) -> throwUnsupported(method));
        TaskAdmissionGateway admissions = proxy(TaskAdmissionGateway.class,
                (method, args) -> throwUnsupported(method));
        SubagentPolicyRepository policies = proxy(SubagentPolicyRepository.class,
                (method, args) -> throwUnsupported(method));
        JsonValueCodec jsonValues = new TestJsonValueCodec();
        ConversationBoundary conversation = new ConversationBoundaryAdapter();
        return new TaskCoordinator(repository, mailboxes, threads,
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, workspaces,
                Clock.fixed(NOW, ZoneOffset.UTC), policies, admissions, jsonValues, conversation);
    }

    /** 只从本次真实 ChildAdmission 派生投影，保留 owner 绑定测试实际生成的 root ID。
     * @author kongweiguang
     */
    private static TaskModels.Summary summary(TaskModels.ChildAdmission child) {
        String threadId = child.childThread().threadId();
        TaskModels.Lineage lineage = new TaskModels.Lineage(threadId, child.parentThreadId(),
                child.parentThreadId(), child.originTurnId(), child.taskName(), 1, child.kind(),
                child.lifecycle(), child.contextSeed().contextSeedId(), NOW);
        TaskModels.Projection projection = new TaskModels.Projection(threadId, child.parentThreadId(),
                1, TaskModels.State.IDLE, 1, null, 1, 0, 0, 0, "idle", null, null, NOW);
        return new TaskModels.Summary(lineage, projection);
    }

    /** 代理只接受测试声明的调用，新增意外依赖会立即暴露。
     * @author kongweiguang
     */
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method, args)));
    }

    /** 提供动态代理的单一方法分发。
     * @author kongweiguang
     */
    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }

    /** 给未声明的端口调用保留方法名与失败关闭语义。
     * @author kongweiguang
     */
    private static Object throwUnsupported(java.lang.reflect.Method method) {
        throw new UnsupportedOperationException(method.getName());
    }
}
