// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.kongweiguang.ja.foundation.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.GoalWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.ThreadReadContract;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.transport.rpc.runtime.ActiveStreamRegistry;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 映射 Thread 创建、全局导航、权威快照、归档与删除 Wire DTO。
 */
public final class ThreadHistoryHandler implements RpcHandler, AutoCloseable {
    private static final Duration CHANGE_SET_READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int LIVE_STREAM_READ_ATTEMPTS = 3;
    /* StdioWriter 还会补齐外层 envelope 与连接级 sequence；预留固定元数据预算，避免最终 JSONL 超帧。 */
    private static final int THREAD_READ_FRAME_BUDGET = JaRpcCodec.DEFAULT_MAX_FRAME_BYTES - 64 * 1024;
    private final RpcSession session;
    private final Duration readTimeout;
    private final BoundedVirtualExecutor changeSetReads;
    private final ScheduledExecutorService readDeadlines;
    private final Set<CompletableFuture<ObjectNode>> pendingReads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 只绑定连接会话，不保留 event journal 或 active workspace 状态。
     */
    public ThreadHistoryHandler(RpcSession session) {
        this(session, CHANGE_SET_READ_TIMEOUT,
                new BoundedVirtualExecutor("ja-change-set-read-", 2, 3),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("ja-change-set-deadline-", 0).factory()));
    }

    /** 测试可收窄期限并观察容量，生产仍固定使用 2 active、3 admitted 与五秒上限。 */
    ThreadHistoryHandler(RpcSession session, Duration readTimeout, BoundedVirtualExecutor changeSetReads,
                         ScheduledExecutorService readDeadlines) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.readTimeout = positiveTimeout(readTimeout);
        this.changeSetReads = java.util.Objects.requireNonNull(changeSetReads, "changeSetReads");
        this.readDeadlines = java.util.Objects.requireNonNull(readDeadlines, "readDeadlines");
    }

    /**
     * 返回 Thread 历史、搜索和元数据 CAS 方法闭集。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.THREAD_CREATE, RpcMethod.THREAD_LIST, RpcMethod.THREAD_SEARCH,
                RpcMethod.THREAD_READ, RpcMethod.THREAD_USAGE_READ, RpcMethod.THREAD_RENAME, RpcMethod.THREAD_PREFERENCES_UPDATE,
                RpcMethod.THREAD_PIN, RpcMethod.THREAD_SEEN, RpcMethod.THREAD_ARCHIVE, RpcMethod.THREAD_RESTORE,
                RpcMethod.THREAD_DELETE, RpcMethod.TURN_CHANGE_SET_READ, RpcMethod.TOOL_ARTIFACT_READ);
    }

    /**
     * runtime ready 后执行一个历史命令，并统一映射 workspace 领域失败。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            if (command.method() == RpcMethod.TURN_CHANGE_SET_READ) {
                return readChangeSet(command.params());
            }
            return CompletableFuture.completedFuture(switch (command.method()) {
                case THREAD_CREATE -> create(command.params());
                case THREAD_LIST -> list(command.params());
                case THREAD_SEARCH -> search(command.params());
                case THREAD_READ -> read(command.params());
                case THREAD_USAGE_READ -> readUsageSummary(command.params());
                case THREAD_RENAME -> rename(command.params());
                case THREAD_PIN -> pin(command.params());
                case THREAD_SEEN -> seen(command.params());
                case THREAD_PREFERENCES_UPDATE -> updatePreferences(command.params());
                case THREAD_ARCHIVE -> archive(command.params());
                case THREAD_RESTORE -> restore(command.params());
                case THREAD_DELETE -> delete(command.params());
                case TOOL_ARTIFACT_READ -> readToolArtifact(command.params());
                default -> throw JaRpcException.methodNotFound();
            });
        } catch (WorkspaceFailure failure) {
            throw WorkspaceHandler.mapFailure(failure);
        }
    }

    /**
     * 从 cwd/title/v3 偏好创建 Thread，workspace 用例负责解析并持久化目录身份。
     */
    private ObjectNode create(ObjectNode params) {
        RpcParams.requireOnly(params, "cwd", "title", "providerId", "modelId", "reasoningLevel", "accessMode",
                "collaborationMode");
        String cwd = RpcParams.optionalText(params, "cwd", 4_096);
        Workspace workspace = ensureWorkspace(cwd);
        ThreadPreferences preferences = preferences(params, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = session.threads().createThread(
                new ThreadSummary.Creation(
                        "thr_" + UUID.randomUUID().toString().replace("-", ""), workspace.workspaceId(),
                        RpcParams.text(params, "title", 512, false), preferences,
                        session.clock().instant()));
        return RpcResults.thread(session.mapper(), thread);
    }

    /**
     * 缺失 cwd 使用通用工作区，否则显式打开项目且不在 transport 派生身份。
     */
    private Workspace ensureWorkspace(String cwd) {
        if (cwd == null) {
            return session.workspaces().openGeneralWorkspace();
        }
        try {
            return session.workspaces().openWorkspace(
                    new WorkspaceUseCase.OpenWorkspace(Path.of(cwd), null));
        } catch (InvalidPathException invalid) {
            throw JaRpcException.invalidParams();
        }
    }

    /**
     * 按请求形状选择旧的 Workspace 导航或全局 discovery；缺少 scope 时保留旧导航合同，
     * 带 scope 时只允许显式 all，避免同一 RPC 方法在 transport 层悄然产生第三种语义。
     */
    private ObjectNode list(ObjectNode params) {
        if (params.has("scope")) return discover(params);
        RpcParams.requireOnly(params, "workspaceId", "cursor", "limit");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        CursorPage<ThreadSummary> page = session.threads()
                .listThreads(workspaceId, RpcParams.optionalText(params, "cursor", 512),
                        RpcParams.pageLimit(params));
        return threadPage(page);
    }

    /**
     * 全局 discovery 只投影最小 Thread 目录；Workspace 过滤可选，cursor 与 limit 仍由 Java owner
     * 校验并交给同一 SQL keyset 查询，避免 Handler 读取完整 Thread 或 Task transcript。
     */
    private ObjectNode discover(ObjectNode params) {
        RpcParams.requireOnly(params, "scope", "query", "cursor", "limit", "workspaceId");
        String scope = RpcParams.text(params, "scope", 16, false);
        String query = params.has("query") ? RpcParams.text(params, "query", 256, true) : null;
        String cursor = RpcParams.optionalText(params, "cursor", 512);
        String workspaceId = params.has("workspaceId") && !params.get("workspaceId").isNull()
                ? RpcParams.identifier(params, "workspaceId", "ws_", 100) : null;
        CursorPage<ThreadDiscovery> page = session.threads().discoverThreads(
                new ThreadDiscovery.Query(scope, query, cursor, RpcParams.pageLimit(params), workspaceId));
        return discoveryPage(page);
    }

    /** 空查询返回最近 Thread；非空查询仅做当前 Workspace 标题 contains。 */
    private ObjectNode search(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "query", "cursor", "limit");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        String query = params.has("query") ? RpcParams.text(params, "query", 256, true) : "";
        CursorPage<ThreadSummary> page = session.threads().searchThreads(workspaceId, query,
                RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params));
        return threadPage(page);
    }

    /** Thread 列表与搜索共用相同分页 envelope，避免两个入口出现字段或 cursor 漂移。 */
    private ObjectNode threadPage(CursorPage<ThreadSummary> page) {
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode threads = result.putArray("items");
        page.items().forEach(value -> threads.add(RpcResults.thread(session.mapper(), value)));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /** discovery 与旧 Thread page 共用 envelope，但条目固定为五个安全字段。 */
    private ObjectNode discoveryPage(CursorPage<ThreadDiscovery> page) {
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode threads = result.putArray("items");
        page.items().forEach(value -> threads.add(discovery(value)));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /** 枚举使用 JA-RPC 小写 wire 值，领域投影不携带更新时间或配置正文。 */
    private ObjectNode discovery(ThreadDiscovery value) {
        return session.mapper().createObjectNode()
                .put("threadId", value.threadId())
                .put("title", value.title())
                .put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("workspaceId", value.workspaceId())
                .put("status", value.status().name().toLowerCase(Locale.ROOT));
    }

    /**
     * 返回一个 keyset 快照页，不保留旧 journal/delta replay 概念。
     */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "cursor", "limit");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        String cursor = RpcParams.optionalText(params, "cursor", 512);
        int limit = RpcParams.pageLimit(params);
        ConsistentRead consistent = readConsistent(threadId, cursor, limit);
        ThreadSnapshot snapshot = consistent.snapshot();
        ObjectNode result = session.mapper().createObjectNode()
                .put("threadId", snapshot.thread().threadId())
                .put("revision", snapshot.thread().revision());
        /* nullable 是有意的：revision 窗口未收敛时不能把旧 draft 重置或伪装成完整基线。 */
        if (consistent.liveStream().isEmpty()) result.putNull("liveStream");
        else {
            ActiveStreamRegistry.Snapshot stream = consistent.liveStream().orElseThrow();
            result.set("liveStream", RpcResults.liveStream(session.mapper(), stream.turnId(),
                    stream.streamSeq(), stream.segments().stream()
                            .map(segment -> new RpcResults.LiveStreamSegment(segment.kind().wireName(),
                                    segment.segmentStartSeq(), segment.streamSeq(), segment.text(),
                                    segment.occurredAt()))
                            .toList()));
        }
        if (snapshot.contextUsage() == null) result.putNull("contextUsage");
        else result.set("contextUsage", RpcResults.contextUsage(session.mapper(), snapshot.contextUsage()));
        if (snapshot.inputQueue() == null) result.putNull("inputQueue");
        else result.set("inputQueue", RpcResults.inputQueue(session.mapper(), snapshot.inputQueue()));
        ArrayNode turns = result.putArray("turns");
        snapshot.turns().forEach(turn -> turns.add(RpcResults.snapshotTurn(session.mapper(), turn)));
        ArrayNode items = result.putArray("items");
        snapshot.items().forEach(item -> items.add(RpcResults.snapshotItem(session.mapper(), item)));
        ArrayNode taskActivities = result.putArray("taskActivities");
        session.tasks().listRootActivities(threadId, 128).forEach(value -> {
            ObjectNode projected = taskActivities.addObject();
            projected.set("activity", RpcResults.taskActivity(session.mapper(), value.activity()));
            projected.set("task", RpcResults.task(session.mapper(), value.task()));
        });
        ArrayNode goalActivities = result.putArray("goalActivities");
        GoalWireMapper goalWire = new GoalWireMapper(session.mapper());
        session.goals().listTerminalActivities(threadId, 128)
                .forEach(value -> goalActivities.add(goalWire.terminalActivity(value)));
        RpcResults.cursor(result, snapshot.nextCursor());
        trimLiveStreamForFrameBudget(result);
        ThreadReadContract.requireValidLiveStream(result);
        return result;
    }

    /**
     * Jackson 对控制字符的 JSON 转义可能把公开正文放大到远超内存 UTF-8 预算；完整响应超出
     * JA-RPC 帧上限时只丢弃可选恢复基线，保留权威历史页，禁止截断 segment 伪造完整文本。
     */
    private void trimLiveStreamForFrameBudget(ObjectNode result) {
        if (result.path("liveStream").isNull() || !result.has("liveStream")) return;
        try {
            if (session.mapper().writeValueAsBytes(result).length > THREAD_READ_FRAME_BUDGET) {
                result.putNull("liveStream");
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("thread history response could not be serialized", failure);
        }
    }

    /**
     * 在活动 Turn 存在时最多重读三次，等待提交 revision 与出站 registry 收敛；超过预算返回无基线，
     * 不阻塞 RPC worker，也不把跨 revision 的分页事实拼成一份假的恢复文本。
     */
    private ConsistentRead readConsistent(String threadId, String cursor, int limit) {
        for (int attempt = 0; attempt < LIVE_STREAM_READ_ATTEMPTS; attempt++) {
            ThreadSnapshot snapshot = session.threads().readThread(threadId, cursor, limit)
                    .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND,
                            "thread is unavailable"));
            ActiveStreamRegistry streams = session.activeStreams();
            Set<String> activeTurnIds = snapshot.turns().stream()
                    .filter(turn -> !io.github.kongweiguang.ja.conversation.domain.turn.TurnState
                            .valueOf(turn.status().toUpperCase(Locale.ROOT)).terminal())
                    .map(ThreadSnapshot.Turn::turnId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (activeTurnIds.isEmpty()) {
                /* 持久快照已确认没有活动 Turn；即使终态通知丢失，也必须释放 registry 临时草稿。 */
                streams.abandonThread(threadId);
                return new ConsistentRead(snapshot, Optional.empty());
            }
            Map<String, Long> persistedMutationVersions = snapshot.turns().stream()
                    .filter(turn -> activeTurnIds.contains(turn.turnId()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            ThreadSnapshot.Turn::turnId, ThreadSnapshot.Turn::mutationVersion));
            Map<String, Integer> persistedModelRounds = snapshot.turns().stream()
                    .filter(turn -> activeTurnIds.contains(turn.turnId()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            ThreadSnapshot.Turn::turnId, ThreadSnapshot.Turn::modelRound));
            Optional<ActiveStreamRegistry.Snapshot> live = streams.snapshot(
                    threadId, snapshot.thread().revision(), activeTurnIds, persistedMutationVersions,
                    persistedModelRounds);
            if (!streams.hasActive(threadId) || live.isPresent() || attempt + 1 == LIVE_STREAM_READ_ATTEMPTS) {
                return new ConsistentRead(snapshot, live);
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("thread history consistency retry exhausted");
    }

    /** read 返回的快照与同 revision 活动基线，禁止调用方将两次独立查询重新组合。 */
    private record ConsistentRead(ThreadSnapshot snapshot,
                                  Optional<ActiveStreamRegistry.Snapshot> liveStream) {
        /** 统一非空约束，避免以后新增字段时绕开一致性边界。 */
        private ConsistentRead {
            Objects.requireNonNull(snapshot, "snapshot");
            liveStream = Optional.ofNullable(liveStream).orElseThrow();
        }
    }

    /**
     * 累计用量独立于 paginated thread/read，防止只为浮层加载正文或被历史页大小截断；不存在的
     * Thread 保持与 read 相同的公开错误，不泄漏删除状态。
     */
    private ObjectNode readUsageSummary(ObjectNode params) {
        RpcParams.requireExact(params, "threadId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        return session.threads().readThreadUsageSummary(threadId)
                .map(value -> RpcResults.threadUsageSummary(session.mapper(), threadId, value))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND,
                        "thread is unavailable"));
    }

    /** 人工重命名通过 expected revision CAS，返回提交后的完整 Thread 元数据。 */
    private ObjectNode rename(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "title", "expectedThreadRevision");
        ThreadSummary thread = session.threads().renameThread(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.text(params, "title", 512, false),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /** 偏好 CAS 接受完整替换，缺失字段不能继承陈旧 renderer 状态。 */
    private ObjectNode updatePreferences(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "providerId", "modelId", "reasoningLevel",
                "accessMode", "collaborationMode", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        ThreadSnapshot snapshot = session.threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
        ThreadPreferences current = snapshot.thread().preferences();
        ThreadPreferences.TitleSource titleSource = current == null
                ? ThreadPreferences.TitleSource.MANUAL
                : current.titleSource();
        ThreadPreferences preferences = preferences(params, titleSource);
        ThreadSummary thread = session.threads().updatePreferences(threadId, preferences,
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /** 置顶布尔值必须显式给出，返回完整 Thread 让调用方按服务端时间立即重排。 */
    private ObjectNode pin(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "pinned", "expectedThreadRevision");
        JsonNode pinned = params.get("pinned");
        if (pinned == null || !pinned.isBoolean()) throw JaRpcException.invalidParams();
        ThreadSummary thread = session.threads().pinThread(
                RpcParams.identifier(params, "threadId", "thr_", 100), pinned.booleanValue(),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /** 只接受 Thread 身份与 revision，待标记的最新 Turn 必须由 App Server 在事务内权威选取。 */
    private ObjectNode seen(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        ThreadSummary thread = session.threads().markThreadSeen(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /** 共享创建和更新的封闭偏好解析，reasoning 的显式 null 与缺失均表示模型默认。 */
    private static ThreadPreferences preferences(ObjectNode params, ThreadPreferences.TitleSource titleSource) {
        String reasoning = RpcParams.optionalText(params, "reasoningLevel", 16);
        if (reasoning != null && !reasoning.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw JaRpcException.invalidParams();
        }
        String access = RpcParams.text(params, "accessMode", 32, false);
        AccessMode accessMode = switch (access) {
            case "approval_required" -> AccessMode.APPROVAL_REQUIRED;
            case "full_access" -> AccessMode.FULL_ACCESS;
            default -> throw JaRpcException.invalidParams();
        };
        CollaborationMode collaborationMode = switch (RpcParams.text(params, "collaborationMode", 16, false)) {
            case "default" -> CollaborationMode.DEFAULT;
            case "plan" -> CollaborationMode.PLAN;
            default -> throw JaRpcException.invalidParams();
        };
        return new ThreadPreferences(RpcParams.identifier(params, "providerId", "provider_", 128),
                RpcParams.identifier(params, "modelId", "model_", 128), reasoning, accessMode,
                collaborationMode, titleSource);
    }

    /**
     * 应用归档或删除 CAS，不在 transport 保留生命周期状态。
     */
    private ObjectNode archive(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        long revision = RpcParams.revision(params, "expectedThreadRevision");
        ThreadSummary thread = session.threads().archiveThread(threadId, revision);
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /** restore 仅接受归档后的 revision，返回未置顶的完整 active Thread。 */
    private ObjectNode restore(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        ThreadSummary thread = session.threads().restoreThread(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /** 删除仍返回 accepted envelope；本轮不将删除暴露为会话菜单动作。 */
    private ObjectNode delete(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        session.threads().deleteThread(threadId, RpcParams.revision(params, "expectedThreadRevision"));
        session.activeStreams().abandonThread(threadId);
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /**
     * 同步 Thread metadata CAS 没有独立事件可发布，因此在返回成功响应前推进活动流 revision，
     * 让下一次 thread/read 仍可精确复用公开 draft，而不会将旧版本与新目录混合。
     */
    private ThreadSummary observeThreadMutation(ThreadSummary thread) {
        session.activeStreams().observeThreadRevision(thread.threadId(), thread.revision());
        return thread;
    }

    /** Tool artifact 通过四元身份和 code point 页读取，找不到时不泄漏哪个身份不匹配。 */
    private ObjectNode readToolArtifact(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "turnId", "callId", "artifactId",
                "offsetCharacters", "limitCharacters");
        var page = session.threads().readToolArtifact(
                RpcParams.identifier(params, "threadId", "thr_", 128),
                RpcParams.identifier(params, "turnId", "turn_", 101),
                RpcParams.identifier(params, "callId", "call_", 128),
                RpcParams.identifier(params, "artifactId", "artifact_", 128),
                RpcParams.integer(params, "offsetCharacters"), RpcParams.integer(params, "limitCharacters"))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "artifact is unavailable"));
        ObjectNode result = session.mapper().createObjectNode().put("artifactId", page.artifactId())
                .put("offsetCharacters", page.offsetCharacters()).put("totalCharacters", page.totalCharacters())
                .put("truncated", page.truncated()).put("content", page.content());
        if (page.nextOffsetCharacters() == null) result.putNull("nextOffsetCharacters");
        else result.put("nextOffsetCharacters", page.nextOffsetCharacters());
        return result;
    }

    /**
     * 参数在请求线程上严格冻结，SQL 与 artifact 解析进入独立 2+1 有界执行器；五秒期限覆盖排队与执行，
     * 超时只取消当前选择，不重试读取，也不占用共享 JA-RPC 请求执行器等待区。
     */
    private CompletionStage<ObjectNode> readChangeSet(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "turnId", "artifactId", "filePath");
        ChangeSetReadRequest request = new ChangeSetReadRequest(
                RpcParams.identifier(params, "threadId", "thr_", 128),
                RpcParams.identifier(params, "turnId", "turn_", 101),
                RpcParams.identifier(params, "artifactId", "artifact_", 128),
                RpcParams.text(params, "filePath", 4_096, false));
        if (closed.get()) {
            return CompletableFuture.failedFuture(JaRpcException.of(
                    JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down"));
        }
        CompletableFuture<ObjectNode> result = new CompletableFuture<>();
        pendingReads.add(result);
        Future<?> read;
        try {
            read = changeSetReads.submit(() -> completeChangeSetRead(request, result));
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(JaRpcException.of(
                    closed.get() ? JaErrorCatalog.SHUTTING_DOWN : JaErrorCatalog.QUEUE_FULL,
                    closed.get() ? "runtime is shutting down" : "change set read capacity is exhausted"));
            pendingReads.remove(result);
            return result;
        }
        ScheduledFuture<?> timeout;
        try {
            timeout = readDeadlines.schedule(
                    () -> timeoutChangeSetRead(read, result), readTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException rejected) {
            read.cancel(true);
            result.completeExceptionally(JaRpcException.of(
                    JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down"));
            pendingReads.remove(result);
            return result;
        }
        result.whenComplete((ignored, failure) -> {
            timeout.cancel(false);
            pendingReads.remove(result);
        });
        return result;
    }

    /** 只由后台读取任务访问 SQLite；完成竞争失败代表请求已超时，不再投影迟到结果。 */
    private void completeChangeSetRead(ChangeSetReadRequest request, CompletableFuture<ObjectNode> result) {
        try {
            var file = session.threads().readChangeSetArtifact(
                    request.threadId(), request.turnId(), request.artifactId(), request.filePath())
                    .orElseThrow(() -> JaRpcException.of(
                            JaErrorCatalog.THREAD_NOT_FOUND, "artifact is unavailable"));
            result.complete(session.mapper().createObjectNode().put("artifactId", file.artifactId())
                    .put("filePath", file.filePath()).put("byteLength", file.byteLength())
                    .put("sha256", file.sha256()).put("contentBase64", file.contentBase64()));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    /** 五秒到期后中断当前 Future，并用稳定 timeout 错误终结响应；不自动重试有状态存储读取。 */
    private static void timeoutChangeSetRead(Future<?> read, CompletableFuture<ObjectNode> result) {
        if (result.completeExceptionally(JaRpcException.of(
                JaErrorCatalog.REQUEST_DEADLINE_EXCEEDED, "change set read timed out"))) {
            read.cancel(true);
        }
    }

    /** 读取期限必须有限且为正，避免测试注入或未来配置把后台读取变成无界等待。 */
    private static Duration positiveTimeout(Duration timeout) {
        Duration value = java.util.Objects.requireNonNull(timeout, "readTimeout");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("invalid change set read timeout");
        }
        return value;
    }

    /** 普通关闭使用进程共享的默认期限；生产组合根应调用带期限重载。 */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 停止新读取、取消 deadline 调度并在同一绝对期限内等待读取退出；只有本方法返回后才能关闭 Session。
     */
    public void close(ShutdownDeadline deadline) {
        if (!closed.compareAndSet(false, true)) return;
        pendingReads.forEach(result -> result.completeExceptionally(JaRpcException.of(
                JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down")));
        readDeadlines.shutdownNow();
        changeSetReads.shutdownNow();
        try {
            if (!changeSetReads.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw ShutdownDeadline.forced("change set read executor timed out", null);
            }
            if (!readDeadlines.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw ShutdownDeadline.forced("change set deadline scheduler timed out", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ShutdownDeadline.forced("change set read shutdown interrupted", interrupted);
        }
    }

    /** 冻结通过严格 Wire 校验的四元读取身份，后台任务不再接触调用方可变 JSON。 */
    private record ChangeSetReadRequest(String threadId, String turnId, String artifactId, String filePath) { }

}
