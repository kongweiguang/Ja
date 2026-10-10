// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjectionFailure;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 通过真实 RpcSession/ThreadHistoryHandler 验证 thread/read 的活动流基线投影。 */
final class ThreadHistoryLiveStreamTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String MODEL_REQUEST_ID = "mreq_live_stream_test";

    /** 新活动尚无 delta 时仍返回 seq=0 空基线，避免客户端把运行中的 Turn 当作无活动。 */
    @Test
    void readsEmptyLiveStreamBeforeFirstDelta() {
        try (Harness harness = new Harness(List.of(turn("turn_empty", "queued")))) {
            harness.session.registerTurnNotificationContext("turn_empty", "ws_live", "thr_live", 4);

            ObjectNode result = harness.read();

            assertEquals(4, result.path("revision").intValue());
            assertEquals("turn_empty", result.path("liveStream").path("turnId").textValue());
            assertEquals(0, result.path("liveStream").path("streamSeq").intValue());
            assertTrue(result.path("liveStream").path("segments").isEmpty());
        }
    }

    /** 崩溃后无本地 owner 的持久 RUNNING 只在 RPC 快照中显示为可继续，不改写历史事实。 */
    @Test
    void projectsOwnerlessRunningTurnAsSuspended() {
        try (Harness harness = new Harness(List.of(turn("turn_orphan", "running")))) {
            ObjectNode result = harness.read();

            assertEquals("suspended", result.path("turns").get(0).path("status").asText());
        }
    }

    /** 继承 Turn 只是只读展示元数据，即使损坏为 running 也不得绑定 child 的实时流或恢复状态。 @author kongweiguang */
    @Test
    void excludesInheritedTurnFromActiveStreamBaseline() {
        ThreadSnapshot.Turn inherited = new ThreadSnapshot.Turn("turn_inherited_live", "running", NOW, NOW,
                null, null, null, 1, 1, null, ThreadSnapshot.Origin.INHERITED);
        try (Harness harness = new Harness(List.of(inherited))) {
            harness.session.registerTurnNotificationContext("turn_inherited_live", "ws_live", "thr_live", 4);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_inherited_live", 1, "source-only draft", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.read();

            assertTrue(result.path("liveStream").isNull());
            assertFalse(harness.session.activeStreams().hasActive("thr_live"));
        }
    }

    /** Admission receipt 推进同 Workspace/Thread 的两路基线，且不改动其他 Workspace/Thread 或回拨水位。 */
    @Test
    void admissionRevisionFencesFirstDraftMonotonically() throws Exception {
        try (Harness harness = new Harness(List.of(turn("turn_admission", "running")))) {
            AgentIdentity identity = new AgentIdentity("ja.local", "thr_live", "turn_admission", "user_local");
            harness.session.registerTurnNotificationContext("turn_admission", "ws_live", "thr_live", 4);
            harness.session.registerTurnNotificationContext("turn_parallel", "ws_live", "thr_live", 4);
            harness.session.registerTurnNotificationContext("turn_other_workspace", "ws_other", "thr_live", 4);
            harness.session.registerTurnNotificationContext("turn_other_thread", "ws_live", "thr_other", 4);
            harness.session.eventSink().onThreadRevision(identity, 5);
            harness.session.eventSink().onThreadRevision(identity, 4);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_admission", 1, "首段输出", MODEL_REQUEST_ID)).join();
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_parallel", 1, "并行输出", MODEL_REQUEST_ID)).join();
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_other_workspace", 1, "隔离输出", MODEL_REQUEST_ID)).join();
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_other_thread", 1, "其他线程", MODEL_REQUEST_ID)).join();

            Map<String, Long> draftRevisions = new HashMap<>();
            for (String line : harness.output.toString(StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                ObjectNode frame = (ObjectNode) MAPPER.readTree(line);
                if (!"assistant/text-delta".equals(frame.path("method").asText())) {
                    continue;
                }
                ObjectNode params = (ObjectNode) frame.path("params");
                draftRevisions.put(params.path("turnId").asText(), params.path("threadRevision").longValue());
            }

            assertEquals(5L, draftRevisions.get("turn_admission"));
            assertEquals(5L, draftRevisions.get("turn_parallel"));
            assertEquals(4L, draftRevisions.get("turn_other_workspace"));
            assertEquals(4L, draftRevisions.get("turn_other_thread"));
            assertTrue(harness.session.activeStreams().snapshot("thr_live", 5, Set.of("turn_admission"),
                    Map.of("turn_admission", 0L), Map.of("turn_admission", 0)).isPresent());
            assertTrue(harness.session.activeStreams().snapshot("thr_live", 5, Set.of("turn_parallel"),
                    Map.of("turn_parallel", 0L), Map.of("turn_parallel", 0)).isPresent());
            assertTrue(harness.session.activeStreams().snapshot("thr_live", 4, Set.of("turn_other_workspace"),
                    Map.of("turn_other_workspace", 0L), Map.of("turn_other_workspace", 0)).isPresent());
            assertTrue(harness.session.activeStreams().snapshot("thr_other", 4, Set.of("turn_other_thread"),
                    Map.of("turn_other_thread", 0L), Map.of("turn_other_thread", 0)).isPresent());
        }
    }

    /** 当前进程注册的同一 owner 继续显示 running，避免把健康执行误导为恢复操作。 */
    @Test
    void keepsRunningStatusForCurrentLocalOwner() {
        try (Harness harness = new Harness(List.of(turn("turn_local", "running")), List.of(), "turn_local")) {
            ObjectNode result = harness.read();

            assertEquals("running", result.path("turns").get(0).path("status").asText());
        }
    }

    /** 多个本地 handle 按各自身份投影，较晚排队项不能因早先 Turn 正在运行而显示为失主。 */
    @Test
    void keepsQueuedTurnWhenEarlierRunningTurnIsAlsoLocallyOwned() {
        try (Harness harness = new Harness(List.of(turn("turn_a", "running"), turn("turn_b", "queued")),
                List.of(), Set.of("turn_a", "turn_b"))) {
            ObjectNode result = harness.read();

            assertEquals("running", result.path("turns").get(0).path("status").asText());
            assertEquals("queued", result.path("turns").get(1).path("status").asText());
        }
    }

    /** 后一排队项仍由本进程持有时，只把前一失主执行投影为可继续状态。 */
    @Test
    void suspendsOnlyOwnerlessEarlierTurnWhenLaterQueuedTurnIsLocallyOwned() {
        try (Harness harness = new Harness(List.of(turn("turn_a", "running"), turn("turn_b", "queued")),
                List.of(), Set.of("turn_b"))) {
            ObjectNode result = harness.read();

            assertEquals("suspended", result.path("turns").get(0).path("status").asText());
            assertEquals("queued", result.path("turns").get(1).path("status").asText());
        }
    }

    /**
     * Provider dispatch 的内部提交没有公开事件时，真实 RpcSession sink 仍须先建立 modelRound fence；
     * 首段 delta 到达后 thread/read 不得把健康活动流误判为断流。
     */
    @Test
    void readsFirstDeltaAfterInternalDispatchBoundary() {
        try (Harness harness = new Harness(List.of(turn("turn_dispatch", "running", 3, 0)))) {
            harness.session.registerTurnNotificationContext("turn_dispatch", "ws_live", "thr_live", 4, 1);
            harness.session.eventSink().observeCommittedTurn("thr_live", "turn_dispatch", 4,
                    3, 0, null, NOW);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_dispatch", 1, "首段输出", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.read();

            assertEquals("turn_dispatch", result.path("liveStream").path("turnId").textValue());
            assertEquals(1, result.path("liveStream").path("streamSeq").intValue());
            assertEquals("首段输出", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());
        }
    }

    /** 投影故障只发脱敏快照重读提示，不构造伪终态或新的事件游标。 */
    @Test
    void publishesProjectionResyncWithStableIdentityAndReasonOnly() throws Exception {
        try (Harness harness = new Harness(List.of(turn("turn_resync", "running")))) {
            harness.session.registerTurnNotificationContext("turn_resync", "ws_live", "thr_live", 4);

            harness.session.publish(new TurnProjectionFailure("thr_live", "turn_resync",
                    TurnProjectionFailure.Reason.PROJECTION_FAILED)).join();

            ObjectNode frame = java.util.Arrays.stream(new String(harness.output.toByteArray(),
                            StandardCharsets.UTF_8).split("\\R"))
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return (ObjectNode) MAPPER.readTree(line);
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .filter(value -> "turn/resync-required".equals(value.path("method").asText()))
                    .findFirst().orElseThrow();
            ObjectNode params = (ObjectNode) frame.path("params");
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            params.fieldNames().forEachRemaining(fieldNames::add);

            assertEquals("2.0", frame.path("jsonrpc").asText());
            assertEquals(java.util.Set.of("serverInstanceId", "eventId", "sequence", "occurredAt",
                    "generation", "workspaceId", "threadId", "turnId", "reasonCode"), fieldNames);
            assertTrue(params.path("sequence").asLong() > 0);
            assertEquals("ws_live", params.path("workspaceId").asText());
            assertEquals("thr_live", params.path("threadId").asText());
            assertEquals("turn_resync", params.path("turnId").asText());
            assertEquals("projection_failed", params.path("reasonCode").asText());
        }
    }

    /** 新 RPC 代际恢复已有模型轮次时，首个公开 delta 不得因默认零轮次而被误判为未清稿。 */
    @Test
    void restoresCompletedModelRoundForNewGeneration() {
        try (Harness harness = new Harness(List.of(turn("turn_resume", "running", 3, 2)))) {
            harness.session.registerTurnNotificationContext("turn_resume", "ws_live", "thr_live", 4,
                    3, 2);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_resume", 1, "恢复后的首段", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.read();

            assertEquals("turn_resume", result.path("liveStream").path("turnId").textValue());
            assertEquals("恢复后的首段", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());
        }
    }

    /** Plan 同身份恢复推进旧 mutation fence，沿用暂停前 streamSeq 与正文并在终态释放。 */
    @Test
    void resumesPlanTurnOnSameRpcRouteWithPersistedWatermarks() {
        try (Harness harness = new Harness(List.of(turn("turn_plan_resume", "running", 7, 2)))) {
            PlanExecutionEventSink events = harness.session.planExecutionEvents();
            harness.session.registerTurnNotificationContext(
                    "turn_plan_resume", "ws_live", "thr_live", 4, 6, 2);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_plan_resume", 1, "暂停前正文", MODEL_REQUEST_ID)).join();

            assertTrue(events.registerResumedTurn("turn_plan_resume", "ws_live", "thr_live", 4));
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_plan_resume", 2, "恢复后正文", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.read();
            assertEquals(2, result.path("liveStream").path("streamSeq").intValue());
            assertEquals("暂停前正文恢复后正文", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());

            harness.session.publish(new TurnEvent.Terminal(new TurnEvent.Context(
                    "evt_plan_resume_terminal", "thr_live", "turn_plan_resume", 4, 7, NOW),
                    TurnState.CANCELLED, "cancelled", null, null, null, null, null)).join();
            assertFalse(harness.session.activeStreams().hasActive("thr_live"));
        }
    }

    /** RPC 恢复路由只接受持久 Turn 所属的 Thread 与 Workspace，不能替换已有接收身份。 */
    @Test
    void rejectsPlanResumeRouteWithDifferentReceiverIdentity() {
        try (Harness harness = new Harness(List.of(turn("turn_plan_identity", "running", 3, 1)))) {
            PlanExecutionEventSink events = harness.session.planExecutionEvents();
            assertTrue(events.registerTurn("turn_plan_identity", "ws_live", "thr_live", 4));

            JaRpcException wrongThread = assertThrows(JaRpcException.class,
                    () -> events.registerResumedTurn("turn_plan_identity", "ws_live", "thr_other", 4));
            JaRpcException wrongWorkspace = assertThrows(JaRpcException.class,
                    () -> events.registerResumedTurn("turn_plan_identity", "ws_other", "thr_live", 4));

            assertEquals(JaErrorCatalog.TURN_NOT_RESUMABLE.name(), wrongThread.errorCode());
            assertEquals(JaErrorCatalog.TURN_NOT_RESUMABLE.name(), wrongWorkspace.errorCode());
        }
    }

    /** queued 与 running 共存时，真实出站 delta 绑定的 running Turn 必须成为 read 基线。 */
    @Test
    void readsRunningLiveStreamThroughRpcAfterQueuedTurn() {
        try (Harness harness = new Harness(List.of(turn("turn_queued", "queued"),
                turn("turn_running", "running")))) {
            harness.session.registerTurnNotificationContext("turn_queued", "ws_live", "thr_live", 4, 0);
            harness.session.registerTurnNotificationContext("turn_running", "ws_live", "thr_live", 4, 0);
            harness.session.publish(new TurnEvent.StateChanged(new TurnEvent.Context(
                    "evt_running", "thr_live", "turn_running", 4, 0, NOW),
                    TurnState.QUEUED, TurnState.RUNNING)).join();
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_running", 1, "正在生成", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.read();

            assertEquals("turn_running", result.path("liveStream").path("turnId").textValue());
            assertEquals(1, result.path("liveStream").path("streamSeq").intValue());
            assertEquals("assistant", result.path("liveStream").path("segments").get(0)
                    .path("kind").textValue());
            assertEquals("正在生成", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());
            assertEquals(NOW, Instant.parse(result.path("liveStream").path("segments").get(0)
                    .path("occurredAt").textValue()));
        }
    }

    /** 挂起等待用户输入仍是活动 Turn，thread/read 必须保留其合法恢复基线。 */
    @Test
    void readsLiveStreamForTurnWaitingForInput() {
        try (Harness harness = new Harness(List.of(turn("turn_waiting_input", "waiting_input", 1, 1)))) {
            harness.session.registerTurnNotificationContext(
                    "turn_waiting_input", "ws_live", "thr_live", 4, 1, 1);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_waiting_input", 1, "需要你补充信息", MODEL_REQUEST_ID)).join();
            harness.session.publish(new TurnEvent.StateChanged(new TurnEvent.Context(
                    "evt_waiting_input", "thr_live", "turn_waiting_input", 4, 1, NOW),
                    TurnState.RUNNING, TurnState.WAITING_INPUT)).join();

            ObjectNode result = harness.read();

            assertEquals("waiting_input", result.path("turns").get(0).path("status").asText());
            assertEquals("turn_waiting_input", result.path("liveStream").path("turnId").asText());
            assertEquals("需要你补充信息", result.path("liveStream").path("segments").get(0)
                    .path("text").asText());
        }
    }

    /** JSON 控制字符会把 1MiB 公开正文扩成超过 4MiB frame；只丢弃完整 liveStream，不截断片段。 */
    @Test
    void dropsLiveStreamWhenEscapedFrameWouldExceedBudget() {
        try (Harness harness = new Harness(List.of(turn("turn_budget", "running")))) {
            harness.session.registerTurnNotificationContext("turn_budget", "ws_live", "thr_live", 4);
            String escaped = String.valueOf((char) 1).repeat(60 * 1024);
            for (int sequence = 1; sequence <= 16; sequence++) {
                harness.session.publish(new TurnEvent.TextDelta(
                        "turn_budget", sequence, escaped, MODEL_REQUEST_ID)).join();
            }

            ObjectNode result = harness.read();

            assertTrue(result.has("liveStream"));
            assertTrue(result.get("liveStream").isNull());
            assertTrue(result.has("items"));
        }
    }

    /** 删除 Thread 清理全部实时草稿；进程会话资源由其 owner 管理，不由历史 Handler 重复关闭。 */
    @Test
    void deletesAllLiveStreamsForThread() {
        try (Harness harness = new Harness(List.of(turn("turn_delete_queued", "queued"),
                turn("turn_delete_running", "running")))) {
            harness.session.registerTurnNotificationContext("turn_delete_queued", "ws_live", "thr_live", 4);
            harness.session.registerTurnNotificationContext("turn_delete_running", "ws_live", "thr_live", 4);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_delete_running", 1, "draft", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.delete();

            assertTrue(result.path("accepted").booleanValue());
            assertTrue(harness.threads.deleted.get());
            assertTrue(!harness.session.activeStreams().hasActive("thr_live"));
            assertEquals(List.of("delete"), harness.lifecycle);
        }
    }

    /** 删除 CAS 失败只记录删除失败，不改变进程会话 owner 的启动状态。 */
    @Test
    void reopensProcessLaunchesWhenDeleteCasFails() {
        try (Harness harness = new Harness(List.of(turn("turn_delete_conflict", "completed")))) {
            harness.threads.failDelete.set(true);

            assertThrows(RuntimeException.class, harness::delete);

            assertEquals(List.of("delete_failed"), harness.lifecycle);
        }
    }

    /** 持久快照先进入终态时，即使终态通知迟到，thread/read 也必须回收孤儿临时流。 */
    @Test
    void clearsOrphanedStreamAfterTerminalSnapshot() {
        try (Harness harness = new Harness(List.of(turn("turn_terminal_snapshot", "completed")))) {
            harness.session.registerTurnNotificationContext(
                    "turn_terminal_snapshot", "ws_live", "thr_live", 4);
            harness.session.publish(new TurnEvent.TextDelta(
                    "turn_terminal_snapshot", 1, "draft", MODEL_REQUEST_ID)).join();

            ObjectNode result = harness.read();

            assertTrue(result.get("liveStream").isNull());
            assertFalse(harness.session.activeStreams().hasActive("thr_live"));
        }
    }

    /** 多个合法大条目不得合成超帧响应；缩页后的游标仍能无重无漏地读完剩余项。 */
    @Test
    void largeHistoryPageShrinksWithoutLosingItems() {
        ToolPresentation largePresentation = largePresentation();
        List<ThreadSnapshot.Item> items = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (ThreadSnapshot.Item) new ThreadSnapshot.ToolItem(
                        "item_large_" + index, NOW.plusSeconds(index), "turn_large",
                        ThreadSnapshot.ToolKind.TOOL_CALL, "call_large_" + index, "mcp/read",
                        largePresentation, index))
                .toList();
        try (Harness harness = new Harness(List.of(turn("turn_large", "completed")), items)) {
            ObjectNode first = harness.read();
            assertEquals(5, first.path("items").size());
            assertEquals("offset_5", first.path("nextCursor").textValue());
            int firstFrameBytes = harness.writeHistoryResponse(0, first);
            assertTrue(firstFrameBytes > 3 * 1024 * 1024);
            assertTrue(firstFrameBytes <= JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
            ObjectNode second = harness.read("offset_5");
            assertEquals(3, second.path("items").size());
            assertTrue(second.path("nextCursor").isNull());
            int secondFrameBytes = harness.writeHistoryResponse(1, second);
            assertTrue(secondFrameBytes > 2 * 1024 * 1024);
            assertTrue(secondFrameBytes <= JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
            List<String> returnedItemIds = new ArrayList<>();
            first.path("items").forEach(item -> returnedItemIds.add(item.path("itemId").textValue()));
            second.path("items").forEach(item -> returnedItemIds.add(item.path("itemId").textValue()));
            assertEquals(IntStream.range(0, 8).mapToObj(index -> "item_large_" + index).toList(),
                    returnedItemIds);
        }
    }

    /** 用领域允许的问答数量与标签长度形成真实大 Tool 展示，触发生产帧预算缩页。 */
    private static ToolPresentation largePresentation() {
        List<String> answers = IntStream.range(0, 64)
                .mapToObj(index -> "answer_" + index + "_"
                        + "x".repeat(4_088 - Integer.toString(index).length()))
                .toList();
        List<ToolPresentation.InteractionAnswerView> interactionAnswers = IntStream.range(0, 3)
                .mapToObj(index -> new ToolPresentation.InteractionAnswerView(
                        "question_" + index + "_" + "x".repeat(4_085), answers, false))
                .toList();
        return new ToolPresentation(ToolPresentation.Kind.MCP, "large history fixture",
                ToolPresentation.Status.SUCCESS, null, null, null, interactionAnswers, List.of(),
                null, null, null, null, null, null, false, null, null);
    }

    /** 构造真实连接级 Handler；除历史读取和 Task/Plan 空列表外的能力均明确拒绝。 */
    private static final class Harness implements AutoCloseable {
        private final RecordingThreads threads;
        private final List<String> lifecycle = new ArrayList<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final StdioWriter writer = new StdioWriter(output, MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final ThreadHistoryHandler handler;

        /** 将 ThreadUseCase 读取和真实 Event Sink 接入同一 RpcSession 生命周期。 */
        private Harness(List<ThreadSnapshot.Turn> turns) {
            this(turns, List.of());
        }

        /** 注入真实大小的历史页，保持其余连接与活动流装配路径一致。 */
        private Harness(List<ThreadSnapshot.Turn> turns, List<ThreadSnapshot.Item> items) {
            this(turns, items, Set.of());
        }

        /** 指定本地 owner 身份，验证 RPC 只将失主的持久运行态投影为可继续状态。 */
        private Harness(List<ThreadSnapshot.Turn> turns, List<ThreadSnapshot.Item> items,
                        String localActiveTurnId) {
            this(turns, items, localActiveTurnId == null ? Set.of() : Set.of(localActiveTurnId));
        }

        /** 允许多个 execution 同时持有本地句柄，验证每个历史 Turn 独立判定。 */
        private Harness(List<ThreadSnapshot.Turn> turns, List<ThreadSnapshot.Item> items,
                        Set<String> localTurnIds) {
            threads = new RecordingThreads(turns, items, lifecycle);
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class), threads.proxy,
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedThreadMcp(),
                    turnOwner(localTurnIds), unsupported(ContextCompactionUseCase.class),
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(AttachmentUseCase.class), unsupported(AttachmentPreviewUseCase.class),
                    passiveTasks(), passivePlans(), RpcTestBindings.passiveInteractions(),
                recordingProcesses(lifecycle),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), new NoopLifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-live-stream-test")
                    .toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            ConfigurationUseCase configuration = TestConfigurationPorts.unavailable();
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings, configuration);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadHistoryHandler(session);
        }

        /** 直接驱动 ThreadHistoryHandler，仍经过真实 RpcSession active stream owner。 */
        private ObjectNode read() {
            return read(null);
        }

        /** 指定服务端返回的 keyset 游标，验证缩页后下一页不会重读前段。 */
        private ObjectNode read(String cursor) {
            ObjectNode params = MAPPER.createObjectNode().put("threadId", "thr_live").put("limit", 20);
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            return handler.handle(new RpcCommand(RpcMethod.THREAD_READ,
                    params))
                    .toCompletableFuture().join();
        }

        /** 通过真实 Writer 写出结果 envelope，并返回本帧 UTF-8 JSONL 字节数。 */
        private int writeHistoryResponse(int page, ObjectNode result) {
            int previousBytes = output.size();
            writer.response("c:history-page-" + page, result).join();
            return output.size() - previousBytes;
        }

        /** 通过真实 Handler 走 Thread 删除分支，验证按 Thread 清理而非仅删一个 Turn。 */
        private ObjectNode delete() {
            return handler.handle(new RpcCommand(RpcMethod.THREAD_DELETE,
                    MAPPER.createObjectNode().put("threadId", "thr_live")
                            .put("expectedThreadRevision", 4)))
                    .toCompletableFuture().join();
        }

        /** 关闭真实 Handler、Session 与 Writer，避免线程池或出站 executor 泄漏到其它测试。 */
        @Override
        public void close() {
            try {
                handler.close();
            } finally {
                try {
                    session.close();
                } finally {
                    writer.close();
                }
            }
        }
    }

    /** 只响应会话 owner 观察，其他 Turn 控制调用仍保持显式未配置失败。 */
    private static TurnUseCase turnOwner(Set<String> localTurnIds) {
        return (TurnUseCase) Proxy.newProxyInstance(TurnUseCase.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if ("locallyActiveTurnId".equals(method.getName())) {
                        return localTurnIds.stream().findFirst();
                    }
                    if ("locallyOwnsTurn".equals(method.getName())) {
                        return "thr_live".equals(arguments[0]) && localTurnIds.contains(arguments[1]);
                    }
                    throw unsupported();
                });
    }

    /** 只记录 Thread 历史测试的进程生命周期观察点，意外的进程操作保持 fail closed。 */
    private static ProcessSessionUseCase recordingProcesses(List<String> lifecycle) {
        return (ProcessSessionUseCase) Proxy.newProxyInstance(ProcessSessionUseCase.class.getClassLoader(),
                new Class<?>[]{ProcessSessionUseCase.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "generation" -> { return 1L; }
                        case "closeThread" -> { lifecycle.add("close"); return null; }
                        case "reopenThread" -> { lifecycle.add("reopen"); return null; }
                        case "closeAt" -> { return null; }
                        case "toString" -> { return "RecordingProcessSessions"; }
                        default -> throw unsupported();
                    }
                });
    }

    /** 返回固定 revision 的历史快照；运行事件仍通过 RpcSession registry 进入 read。 */
    private static final class RecordingThreads implements ThreadUseCase {
        private final List<ThreadSnapshot.Turn> turns;
        private final List<ThreadSnapshot.Item> items;
        private final AtomicLong revision = new AtomicLong(4);
        private final AtomicBoolean deleted = new AtomicBoolean();
        private final AtomicBoolean failDelete = new AtomicBoolean();
        private final List<String> lifecycle;
        private final ThreadUseCase proxy;

        /** 固定 Thread/Turn 权威快照，令恢复测试从同一持久投影读取 revision 与 mutation 水位。 */
        private RecordingThreads(List<ThreadSnapshot.Turn> turns, List<ThreadSnapshot.Item> items,
                                 List<String> lifecycle) {
            this.turns = List.copyOf(turns);
            this.items = List.copyOf(items);
            this.lifecycle = lifecycle;
            this.proxy = (ThreadUseCase) Proxy.newProxyInstance(
                    ThreadUseCase.class.getClassLoader(), new Class<?>[]{ThreadUseCase.class},
                    (ignored, method, args) -> {
                        if ("readThread".equals(method.getName())) {
                            int start = args[1] == null ? 0
                                    : Integer.parseInt(((String) args[1]).substring("offset_".length()));
                            int end = Math.min(this.items.size(), start + (int) args[2]);
                            String next = end < this.items.size() ? "offset_" + end : null;
                            return Optional.of(snapshot(revision.get(), this.turns,
                                    this.items.subList(start, end), next));
                        }
                        if ("findTurn".equals(method.getName())) {
                            String turnId = (String) args[0];
                            return this.turns.stream().filter(turn -> turn.turnId().equals(turnId))
                                    .map(turn -> new TurnSummary("thr_live", turn.turnId(), turn.status(),
                                            revision.get(), turn.mutationVersion(), false))
                                    .findFirst();
                        }
                        if ("deleteThread".equals(method.getName())) {
                            if (failDelete.get()) {
                                lifecycle.add("delete_failed");
                                throw new IllegalStateException("delete CAS conflict");
                            }
                            lifecycle.add("delete");
                            deleted.set(true);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        /** 以同一 Thread revision 返回真实 reader 会看到的 metadata 与 turns。 */
        private static ThreadSnapshot snapshot(long revision, List<ThreadSnapshot.Turn> turns,
                                               List<ThreadSnapshot.Item> items, String nextCursor) {
            TurnState latest = turns.stream().anyMatch(turn -> "running".equals(turn.status()))
                    ? TurnState.RUNNING : TurnState.QUEUED;
            ThreadSummary thread = new ThreadSummary("thr_live", "ws_live", "Live stream", "project", preferences(),
                    ThreadSummary.Status.ACTIVE, false, latest, false, revision, NOW, NOW);
            return new ThreadSnapshot(thread, turns, items, null, null, nextCursor);
        }

        /** 该回归只读取固定快照，不允许测试意外创建 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 该回归不覆盖列表分页，未声明能力必须显式失败。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 该回归不覆盖搜索分页，避免用搜索结果伪造历史快照。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 代理只响应内部 readThread 分支，直接调用端口表示测试接线错误。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) { throw unsupported(); }
        /** 该回归不修改标题，保持 Thread revision 固定以专测活动流。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 该回归不修改偏好，避免 metadata 变化掩盖活动流 fence。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 该回归不生成自动标题，固定目录事实以隔离 liveStream 读取。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 活动流回归不修改置顶状态。 */
        @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw unsupported(); }
        /** 活动流回归不确认 Thread 已读。 */
        @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw unsupported(); }
        /** 该回归不归档 Thread，归档副作用应由真实仓储测试覆盖。 */
        @Override public ThreadSummary archiveThread(String threadId, long revision) { throw unsupported(); }
        /** 活动流回归不恢复已归档 Thread。 */
        @Override public ThreadSummary restoreThread(String threadId, long revision) { throw unsupported(); }
        /** 删除只通过代理识别并记录，不让未声明调用改变快照。 */
        @Override public void deleteThread(String threadId, long revision) { throw unsupported(); }
        /** 该回归不按 Turn 查询，防止单 Turn 查询替代 thread/read 合同。 */
        @Override public Optional<io.github.kongweiguang.ja.conversation.domain.model.TurnSummary> findTurn(String turnId) { throw unsupported(); }
        /** 活动历史回归不读取冻结差异，意外调用必须保持显式失败。 */
        @Override public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) { throw unsupported(); }
        /** 活动历史只分页读取快照 item，不额外读取正文 artifact。 */
        @Override public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) { throw unsupported(); }
        /** 活动流快照不额外读取 Tool 输出正文。 */
        @Override public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                                     String artifactId, int offsetCharacters,
                                                                     int limitCharacters) { throw unsupported(); }
        /** 活动流回归不读取跨 Session Thread 列表。 */
        @Override
        public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
        /** 活动流回归不执行 Thread 发现。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) { throw unsupported(); }
        /** 活动流回归不搜索用户输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
        /** 活动流回归不搜索 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
        /** 活动流回归不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
        /** 活动流回归不读取用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }
    }

    /** 构造历史 Turn 元数据，状态与 registry 生命周期选择逻辑保持同一 wire 小写值。 */
    private static ThreadSnapshot.Turn turn(String turnId, String status) {
        return turn(turnId, status, 0, 0);
    }

    /** 允许恢复回归固定持久 Turn mutation 与已完成模型轮次，避免从旧 usage 推断 fence。 */
    private static ThreadSnapshot.Turn turn(String turnId, String status,
                                             long mutationVersion, int modelRound) {
        return new ThreadSnapshot.Turn(turnId, status, NOW, NOW, null, null, null,
                mutationVersion, modelRound, null);
    }

    /** Task/Plan 只允许连接订阅与 thread/read 所需的空活动列表。 */
    private static TaskUseCase passiveTasks() {
        return (TaskUseCase) Proxy.newProxyInstance(TaskUseCase.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (ignored, method, args) -> {
                    if ("subscribe".equals(method.getName())) {
                        return (AutoCloseable) () -> { };
                    }
                    if ("closeTemporarySideChats".equals(method.getName())) {
                        return null;
                    }
                    if ("listRootActivities".equals(method.getName())) {
                        return List.of();
                    }
                    throw unsupported();
                });
    }

    /** Plan 只允许连接订阅和 thread/read 所需的空终态活动列表。 */
    private static PlanUseCase passivePlans() {
        return (PlanUseCase) Proxy.newProxyInstance(PlanUseCase.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (ignored, method, args) -> {
                    if ("subscribe".equals(method.getName()) || "subscribePlan".equals(method.getName())) {
                        return (AutoCloseable) () -> { };
                    }
                    throw unsupported();
                });
    }

    /** 未声明的边界能力显式失败，避免 test harness 吞掉真实接线缺口。 */
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> { throw unsupported(); });
    }

    /** 统一未声明能力失败类型。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not used by live stream test");
    }

    /** 不产生外部资源的最小 lifecycle owner。 */
    private static final class NoopLifecycle implements DeadlineCloseable {
        /** 测试无需关闭外部资源。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试无无需关闭外部资源。 */
        @Override public void close() { }
    }
}
