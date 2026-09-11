// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 thread/read 将持久 Task Activity 与 Goal 终态投影到 Timeline，且不物化完整详情。 */
final class ThreadHistoryTaskActivityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ROOT = "thr_root";
    private static final String CHILD = "thr_child";

    /** 根读取返回精确 activity/task 对，固定使用 128 上限；Child 读取只返回空数组。 */
    @Test
    void projectsRootActivitiesAndKeepsChildReadEmpty() {
        try (Harness harness = new Harness()) {
            ObjectNode root = harness.read(ROOT);
            ObjectNode child = harness.read(CHILD);

            assertEquals(1, root.path("taskActivities").size());
            assertEquals("activity_completed",
                    root.path("taskActivities").path(0).path("activity").path("activityId").asText());
            assertEquals(CHILD,
                    root.path("taskActivities").path(0).path("task").path("taskThreadId").asText());
            assertTrue(child.path("taskActivities").isArray());
            assertTrue(child.path("taskActivities").isEmpty());
            assertEquals(1, root.path("goalActivities").size());
            assertEquals("goal_done", root.path("goalActivities").path(0).path("goalId").asText());
            assertEquals("achieved", root.path("goalActivities").path(0).path("status").asText());
            assertTrue(child.path("goalActivities").isArray());
            assertTrue(child.path("goalActivities").isEmpty());
            assertEquals(List.of(ROOT, CHILD), harness.tasks.rootThreadIds);
            assertEquals(List.of(128, 128), harness.tasks.limits);
            assertEquals(List.of(ROOT, CHILD), harness.goals.ownerThreadIds);
            assertEquals(List.of(128, 128), harness.goals.limits);
        }
    }

    /** 通过真实 Handler 和 ready RpcSession 驱动读取，其余端口一旦被触达即失败。 */
    private static final class Harness implements AutoCloseable {
        private final RecordingTasks tasks = new RecordingTasks();
        private final RecordingGoals goals = new RecordingGoals();
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final ThreadHistoryHandler handler;

        /** 组合只开放 thread/read 与根 Activity 投影，证明 Handler 不读取 Task 详情或完整树。 */
        private Harness() {
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class),
                    threads(), unsupported(TurnUseCase.class), unsupported(ContextCompactionUseCase.class),
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(AttachmentUseCase.class), unsupported(AttachmentPreviewUseCase.class),
                    tasks.proxy(), goals.proxy(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                    new NoopLifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-task-activity-test")
                    .toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            ConfigurationUseCase configuration = TestConfigurationPorts.unavailable();
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings, configuration);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadHistoryHandler(session);
        }

        /** 每次读取显式给出合法分页参数，结果直接来自 Handler Wire 投影。 */
        private ObjectNode read(String threadId) {
            return handler.handle(new RpcCommand(RpcMethod.THREAD_READ, MAPPER.createObjectNode()
                    .put("threadId", threadId).put("limit", 20))).toCompletableFuture().join();
        }

        /** 关闭顺序与生产连接一致，测试不持有外部进程或数据库。 */
        @Override public void close() {
            try { session.close(); } finally { writer.close(); }
        }
    }

    /** Goal 端口只开放连接订阅和有界终态查询，完整 plan/read 不属于 thread/read 路径。 */
    private static final class RecordingGoals {
        private final List<String> ownerThreadIds = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();

        /** 动态代理记录边界参数，并为根 Thread 返回一个已达成的最小审计事实。 */
        private GoalUseCase proxy() {
            return (GoalUseCase) Proxy.newProxyInstance(GoalUseCase.class.getClassLoader(),
                    new Class<?>[]{GoalUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "subscribe", "subscribePlan" -> (AutoCloseable) () -> { };
                        case "listTerminalActivities" -> {
                            String ownerThreadId = (String) arguments[0];
                            ownerThreadIds.add(ownerThreadId);
                            limits.add((int) arguments[1]);
                            yield ROOT.equals(ownerThreadId) ? List.of(new GoalModels.TerminalActivity(
                                    "goal_done", ROOT, "完成生产验收", GoalModels.GoalStatus.ACHIEVED,
                                    8, 21, NOW)) : List.of();
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** 记录唯一允许的根活动读取，任何详情或树扫描都会由代理默认分支立即暴露。 */
    private static final class RecordingTasks {
        private final List<String> rootThreadIds = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();

        /** 动态代理只接受连接订阅和本测试目标方法，避免复制完整 TaskUseCase。 */
        private TaskUseCase proxy() {
            return (TaskUseCase) Proxy.newProxyInstance(TaskUseCase.class.getClassLoader(),
                    new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "subscribe" -> (AutoCloseable) () -> { };
                        case "listRootActivities" -> {
                            String rootThreadId = (String) arguments[0];
                            rootThreadIds.add(rootThreadId);
                            limits.add((int) arguments[1]);
                            yield ROOT.equals(rootThreadId) ? List.of(projection()) : List.of();
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** Thread 端口只返回请求身份对应的空历史快照，Task 数据必须由独立有界端口提供。 */
    private static ThreadUseCase threads() {
        return (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> {
                    if (!"readThread".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String threadId = (String) arguments[0];
                    return Optional.of(new ThreadSnapshot(thread(threadId), List.of(), List.of(), null, null, null));
                });
    }

    /** 根和 Child Thread 使用相同稳定元数据，测试只关注 taskActivities 附加投影。 */
    private static ThreadSummary thread(String threadId) {
        return new ThreadSummary(threadId, "ws_test", threadId, ConversationTestFixtures.preferences(),
                ThreadSummary.Status.ACTIVE, false, TurnState.COMPLETED, false, null, 3, NOW, NOW);
    }

    /** 构造一条终态 Task Activity 和当前摘要，字段值覆盖两层 Wire DTO 的身份关联。 */
    private static TaskModels.ActivityProjection projection() {
        TaskModels.Lineage lineage = new TaskModels.Lineage(CHILD, ROOT, ROOT, "turn_parent", "research", 1,
                TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED, "seed_child", NOW);
        TaskModels.Projection task = new TaskModels.Projection(CHILD, ROOT, 4, TaskModels.State.COMPLETED,
                7, null, 2, 0, 0, 0, "完成", NOW.minusSeconds(5), NOW, NOW);
        TaskModels.Activity activity = new TaskModels.Activity(7, "activity_completed", ROOT, CHILD, CHILD,
                "turn_child", TaskModels.ActivityKind.COMPLETED,
                JsonObjects.builder().putText("text", "完成").build(), NOW);
        return new TaskModels.ActivityProjection(activity, new TaskModels.Summary(lineage, task));
    }

    /** 测试组合没有外部资源，连接关闭仍走完整 Deadline 生命周期。 */
    private static final class NoopLifecycle implements DeadlineCloseable {
        /** 无资源可关闭。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 无资源可关闭。 */
        @Override public void close() { }
    }

    /** 非目标端口一旦被调用就失败，保证本测试没有隐式副作用。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> { throw new UnsupportedOperationException(method.getName()); });
    }
}
