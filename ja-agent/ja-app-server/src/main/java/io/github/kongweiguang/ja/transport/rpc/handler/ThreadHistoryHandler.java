// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.protocol.ThreadReadContract;
import io.github.kongweiguang.ja.transport.rpc.protocol.TurnStateWire;
import io.github.kongweiguang.ja.transport.rpc.runtime.ActiveStreamRegistry;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    /**
     * 测试可收窄期限并观察容量，生产仍固定使用 2 active、3 admitted 与五秒上限。
     */
    ThreadHistoryHandler(RpcSession session, Duration readTimeout, BoundedVirtualExecutor changeSetReads,
                         ScheduledExecutorService readDeadlines) {
        this.session = java.util.Objects.requireNonNull(session, "session");
        this.readTimeout = positiveTimeout(readTimeout);
        this.changeSetReads = java.util.Objects.requireNonNull(changeSetReads, "changeSetReads");
        this.readDeadlines = java.util.Objects.requireNonNull(readDeadlines, "readDeadlines");
    }

    /**
     * Discovery 状态使用不同闭集，只有最新排队或运行项可能因进程退出而需要显式恢复。
     */
    private static boolean recoverable(ThreadDiscovery.Status state) {
        return state == ThreadDiscovery.Status.QUEUED || state == ThreadDiscovery.Status.RUNNING;
    }

    /**
     * Thread 列表保存领域 Turn 状态；只把失去进程 owner 的排队和运行轮次标为待恢复。
     */
    private static boolean recoverable(TurnState state) {
        return state == TurnState.QUEUED || state == TurnState.RUNNING;
    }

    /**
     * 历史快照的状态已按 wire 小写存储，不经枚举解析以免旧的未知行破坏整页回读。
     */
    private static boolean recoverable(String state) {
        return "queued".equalsIgnoreCase(state) || "running".equalsIgnoreCase(state);
    }

    /**
     * 共享创建和更新的封闭偏好解析，reasoning 的显式 null 与缺失均表示模型默认。
     */
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
     * 五秒到期后中断当前 Future，并用稳定 timeout 错误终结响应；不自动重试有状态存储读取。
     */
    private static void timeoutChangeSetRead(Future<?> read, CompletableFuture<ObjectNode> result) {
        if (result.completeExceptionally(JaRpcException.of(
                JaErrorCatalog.REQUEST_DEADLINE_EXCEEDED, "change set read timed out"))) {
            read.cancel(true);
        }
    }

    /**
     * 读取期限必须有限且为正，避免测试注入或未来配置把后台读取变成无界等待。
     */
    private static Duration positiveTimeout(Duration timeout) {
        Duration value = java.util.Objects.requireNonNull(timeout, "readTimeout");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("invalid change set read timeout");
        }
        return value;
    }

    /**
     * Thread 导航与 fork 共用此 Java 权威边界，方法闭集不通过动态注册扩展。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.THREAD_CREATE, RpcMethod.THREAD_LIST, RpcMethod.THREAD_SEARCH,
                RpcMethod.THREAD_FORK,
                RpcMethod.THREAD_READ, RpcMethod.THREAD_ITEM_CONTENT_READ,
                RpcMethod.HISTORY_INPUT_SEARCH,
                RpcMethod.THREAD_OBSERVE, RpcMethod.THREAD_UNOBSERVE,
                RpcMethod.THREAD_USAGE_READ, RpcMethod.THREAD_RENAME, RpcMethod.THREAD_PREFERENCES_UPDATE,
                RpcMethod.THREAD_PIN, RpcMethod.THREAD_SEEN, RpcMethod.THREAD_ARCHIVE, RpcMethod.THREAD_RESTORE,
                RpcMethod.THREAD_DELETE, RpcMethod.TURN_CHANGE_SET_READ, RpcMethod.TOOL_ARTIFACT_READ);
    }

    /**
     * runtime ready 后执行一个历史命令，并映射 workspace 与 fork 的稳定领域失败。
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
                case THREAD_FORK -> fork(command.params());
                case THREAD_LIST -> list(command.params());
                case THREAD_SEARCH -> search(command.params());
                case THREAD_READ -> read(command.params());
                case THREAD_ITEM_CONTENT_READ -> readItemContent(command.params());
                case HISTORY_INPUT_SEARCH -> searchUserInputs(command.params());
                case THREAD_OBSERVE -> observe(command.params(), true);
                case THREAD_UNOBSERVE -> observe(command.params(), false);
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
     * 省略 cwd 表示创建独立 SESSION，显式 cwd 表示打开 PROJECT；拒绝 null 别名以保持合同形状唯一。
     */
    private ObjectNode create(ObjectNode params) {
        RpcParams.requireOnly(params, "cwd", "title", "providerId", "modelId", "reasoningLevel", "accessMode",
                "collaborationMode");
        String cwd = params.has("cwd") ? RpcParams.text(params, "cwd", 4_096, false) : null;
        String title = RpcParams.text(params, "title", 512, false);
        ThreadPreferences preferences = preferences(params, ThreadPreferences.TitleSource.PLACEHOLDER);
        String threadId = "thr_" + UUID.randomUUID().toString().replace("-", "");
        Workspace workspace = ensureWorkspace(cwd, threadId);
        ThreadSummary thread;
        if (cwd == null) {
            try (UnlinkedSessionWorkspaceRollback rollback =
                         new UnlinkedSessionWorkspaceRollback(session, workspace)) {
                thread = Objects.requireNonNull(session.threads().createThread(new ThreadSummary.Creation(
                                threadId, workspace.workspaceId(), title, preferences, session.clock().instant())),
                        "created thread");
                rollback.commit();
            }
        } else {
            thread = session.threads().createThread(new ThreadSummary.Creation(
                    threadId, workspace.workspaceId(), title, preferences, session.clock().instant()));
        }
        return RpcResults.thread(session.mapper(), thread);
    }

    /**
     * 只接受完整冻结的答复身份；从真实 item 精确截断，来源其余活动状态仍由原 Thread 持有。
     */
    private ObjectNode fork(ObjectNode params) {
        RpcParams.requireExact(params, "sourceThreadId", "sourceItemId", "expectedThreadRevision", "operationKey");
        String operationKey = RpcParams.text(params, "operationKey", 64, false);
        if (!operationKey.matches("op_[0-9a-f]{32}")) {
            throw JaRpcException.invalidParams();
        }
        ThreadUseCase.ThreadForkRequest request = new ThreadUseCase.ThreadForkRequest(
                RpcParams.identifier(params, "sourceThreadId", "thr_", 100),
                RpcParams.identifier(params, "sourceItemId", "item_", 108),
                RpcParams.revision(params, "expectedThreadRevision"), operationKey);
        ThreadUseCase.ThreadForkResult fork;
        try {
            fork = session.threads().forkThread(request);
        } catch (TaskRepositoryException failure) {
            throw mapForkFailure(failure);
        } catch (StorageException failure) {
            throw mapForkFailure(failure);
        }
        ObjectNode result = session.mapper().createObjectNode();
        result.set("thread", RpcResults.thread(session.mapper(), fork.thread()));
        result.putObject("source").put("threadId", fork.source().threadId())
                .put("itemId", fork.source().itemId())
                .put("threadRevision", fork.source().threadRevision());
        return result;
    }

    /**
     * 按 repository 领域码映射 fork 失败；保持参数具体类型，避免通用 RuntimeException 映射引入不安全强转。
     */
    private static JaRpcException mapForkFailure(TaskRepositoryException failure) {
        JaErrorCatalog code = switch (failure.code()) {
            case NOT_FOUND -> JaErrorCatalog.THREAD_NOT_FOUND;
            case CONTEXT_REVISION_CONFLICT, CAS_CONFLICT -> JaErrorCatalog.CONFLICT;
            case INVALID_STATE -> JaErrorCatalog.INVALID_STATE;
            default -> JaErrorCatalog.CONFLICT;
        };
        return JaRpcException.of(code, "thread fork could not be completed");
    }

    /**
     * 按存储领域码映射 fork 失败；具体 overload 让其他运行时异常继续传播并保留原始根因。
     */
    private static JaRpcException mapForkFailure(StorageException failure) {
        JaErrorCatalog code = switch (failure.code()) {
            case NOT_FOUND -> JaErrorCatalog.THREAD_NOT_FOUND;
            case CAS_CONFLICT -> JaErrorCatalog.CONFLICT;
            case INVALID_STATE -> JaErrorCatalog.INVALID_STATE;
            default -> JaErrorCatalog.STORAGE_CONFLICT;
        };
        return JaRpcException.of(code, "thread fork could not be completed");
    }

    /**
     * 先冻结主 Thread ID 再建其专属空目录；非法 cwd 只映射参数错误，不回显用户路径。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private Workspace ensureWorkspace(String cwd, String threadId) {
        if (cwd == null) {
            return session.workspaces().createSessionWorkspace(threadId);
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
        if (params.has("scope")) {
            return discover(params);
        }
        if (params.has("workspaceKind")) {
            RpcParams.requireOnly(params, "workspaceKind", "cursor", "limit");
            if (!"session".equals(RpcParams.text(params, "workspaceKind", 16, false))) {
                throw JaRpcException.invalidParams();
            }
            return threadPage(session.threads().listSessionThreads(
                    RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params)));
        }
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

    /**
     * 空查询返回最近 Thread；非空查询仅做当前 Workspace 标题 contains。
     */
    private ObjectNode search(ObjectNode params) {
        if (params.has("workspaceKind")) {
            RpcParams.requireOnly(params, "workspaceKind", "query", "cursor", "limit");
            if (!"session".equals(RpcParams.text(params, "workspaceKind", 16, false))) {
                throw JaRpcException.invalidParams();
            }
            String sessionQuery = params.has("query") ? RpcParams.text(params, "query", 256, true) : "";
            return threadPage(session.threads().searchSessionThreads(sessionQuery,
                    RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params)));
        }
        RpcParams.requireOnly(params, "workspaceId", "query", "cursor", "limit");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        String query = params.has("query") ? RpcParams.text(params, "query", 256, true) : "";
        CursorPage<ThreadSummary> page = session.threads().searchThreads(workspaceId, query,
                RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params));
        return threadPage(page);
    }

    /**
     * 一页最多十条公开输入，防止长提示词搜索结果占满 JA-RPC 单帧。
     */
    private ObjectNode searchUserInputs(ObjectNode params) {
        RpcParams.requireOnly(params, "query", "cursor", "limit");
        String query = params.has("query") ? RpcParams.text(params, "query", 256, true) : "";
        int limit = Math.min(RpcParams.pageLimit(params), 10);
        CursorPage<UserInputHistory> page = session.threads().searchUserInputs(
                query, RpcParams.optionalText(params, "cursor", 512), limit);
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode items = result.putArray("items");
        page.items().forEach(item -> items.addObject().put("itemId", item.itemId())
                .put("threadId", item.threadId()).put("text", item.text())
                .put("createdAt", item.createdAt()).put("truncated", item.truncated()));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 列表只用返回快照中的 latestTurnId 检查精确 owner，避免逐 Thread 扫描完整历史。
     */
    private ObjectNode threadPage(CursorPage<ThreadSummary> page) {
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode threads = result.putArray("items");
        page.items().forEach(value -> {
            ObjectNode item = RpcResults.thread(session.mapper(), value);
            if (recoverable(value.latestTurnStatus())
                && !session.turns().locallyOwnsTurn(value.threadId(), value.latestTurnId())) {
                item.put("latestTurnStatus", TurnStateWire.value(
                        io.github.kongweiguang.ja.agent.domain.turn.TurnState.SUSPENDED));
            }
            threads.add(item);
        });
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * discovery 用列表快照的 latestTurnId 判断 owner；不因同 Thread 的较早执行仍在运行而保活。
     */
    private ObjectNode discoveryPage(CursorPage<ThreadDiscovery> page) {
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode threads = result.putArray("items");
        page.items().forEach(value -> {
            ObjectNode item = discovery(value);
            if (recoverable(value.status())
                && !session.turns().locallyOwnsTurn(value.threadId(), value.latestTurnId())) {
                item.put("status", ThreadDiscovery.Status.SUSPENDED.name().toLowerCase(Locale.ROOT));
            }
            threads.add(item);
        });
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 按每个精确 Turn 身份查本地句柄表；较早的本地 Turn 不会掩盖较新的失主项。
     */
    private ObjectNode snapshotTurn(String threadId, ThreadSnapshot.Turn turn) {
        ObjectNode item = RpcResults.snapshotTurn(session.mapper(), turn);
        if (recoverable(turn.status()) && !session.turns().locallyOwnsTurn(threadId, turn.turnId())) {
            item.put("status", TurnStateWire.value(
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.SUSPENDED));
        }
        return item;
    }

    /**
     * 枚举使用 JA-RPC 小写 wire 值，领域投影不携带更新时间或配置正文。
     */
    private ObjectNode discovery(ThreadDiscovery value) {
        return session.mapper().createObjectNode()
                .put("threadId", value.threadId())
                .put("title", value.title())
                .put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("workspaceId", value.workspaceId())
                .put("status", value.status().name().toLowerCase(Locale.ROOT));
    }

    /**
     * 逐步缩小权威 keyset 页面直至放入 RPC 单帧；只改变页大小，不丢失条目或伪造游标。
     */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "cursor", "limit", "tail");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        String cursor = RpcParams.optionalText(params, "cursor", 512);
        int limit = RpcParams.pageLimit(params);
        boolean tail = params.has("tail");
        if (tail && (!params.get("tail").isBoolean() || !params.get("tail").booleanValue())) {
            throw JaRpcException.invalidParams();
        }
        for (int pageLimit = limit; ; pageLimit = Math.max(1, pageLimit / 2)) {
            ObjectNode result = readPage(threadId, cursor, pageLimit, tail);
            trimLiveStreamForFrameBudget(result);
            ThreadReadContract.requireValidLiveStream(result);
            if (serializedBytes(result) <= THREAD_READ_FRAME_BUDGET) {
                return result;
            }
            if (pageLimit == 1) {
                throw JaRpcException.of(JaErrorCatalog.FRAME_TOO_LARGE,
                        "one history item exceeds the RPC frame");
            }
        }
    }

    /**
     * 缩页重读时重新取得完整事务版本；不能直接截掉已投影数组尾部并沿用旧游标。
     */
    private ObjectNode readPage(String threadId, String cursor, int limit, boolean tail) {
        ConsistentRead consistent = readConsistent(threadId, cursor, limit, tail);
        ThreadSnapshot snapshot = consistent.snapshot();
        ObjectNode result = session.mapper().createObjectNode()
                .put("threadId", snapshot.thread().threadId())
                .put("revision", snapshot.thread().revision());
        /* nullable 是有意的：revision 窗口未收敛时不能把旧 draft 重置或伪装成完整基线。 */
        if (consistent.liveStream().isEmpty()) {
            result.putNull("liveStream");
        }
        else {
            ActiveStreamRegistry.Snapshot stream = consistent.liveStream().orElseThrow();
            result.set("liveStream", RpcResults.liveStream(session.mapper(), stream.turnId(),
                    stream.streamSeq(), stream.segments().stream()
                            .map(segment -> new RpcResults.LiveStreamSegment(segment.kind().wireName(),
                                    segment.segmentStartSeq(), segment.streamSeq(), segment.text(),
                                    segment.occurredAt()))
                            .toList()));
        }
        if (snapshot.contextUsage() == null) {
            result.putNull("contextUsage");
        }
        else {
            result.set("contextUsage", RpcResults.contextUsage(session.mapper(), snapshot.contextUsage()));
        }
        if (snapshot.inputQueue() == null) {
            result.putNull("inputQueue");
        }
        else {
            result.set("inputQueue", RpcResults.inputQueue(session.mapper(), snapshot.inputQueue()));
        }
        ArrayNode turns = result.putArray("turns");
        snapshot.turns().forEach(turn -> turns.add(snapshotTurn(threadId, turn)));
        ArrayNode items = result.putArray("items");
        snapshot.items().forEach(item -> items.add(RpcResults.snapshotItem(session.mapper(), item)));
        ArrayNode taskActivities = result.putArray("taskActivities");
        session.tasks().listRootActivities(threadId, 128).forEach(value -> {
            ObjectNode projected = taskActivities.addObject();
            projected.set("activity", RpcResults.taskActivity(session.mapper(), value.activity()));
            projected.set("task", RpcResults.task(session.mapper(), value.task()));
        });
        RpcResults.cursor(result, snapshot.nextCursor());
        return result;
    }

    /**
     * 只凭 Thread 内的 snapshot item 身份读取公开文本；owner、item kind 与 current path 由用例统一校验。
     */
    private ObjectNode readItemContent(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "itemId", "offsetCharacters", "limitCharacters");
        ThreadUseCase.ItemContentPage page = session.threads().readItemContent(
                        RpcParams.identifier(params, "threadId", "thr_", 128),
                        RpcParams.identifier(params, "itemId", "item_", 101),
                        RpcParams.integer(params, "offsetCharacters"), RpcParams.integer(params, "limitCharacters"))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND,
                        "item content is unavailable"));
        return characterPageResult("itemId", page.itemId(), page.offsetCharacters(),
                page.nextOffsetCharacters(), page.totalCharacters(), page.truncated(), page.content());
    }

    /**
     * 按最终 JSON 字节数而非 Java 字符数判断帧大小，覆盖正文控制字符转义的放大效应。
     */
    private int serializedBytes(ObjectNode result) {
        try {
            return session.mapper().writeValueAsBytes(result).length;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("thread history response could not be serialized", failure);
        }
    }

    /**
     * 观察确认与随后 thread/read 分开，使连接先收到事件，再从权威历史对账；
     * 多客户端 daemon 广播所有 Thread 事件，所以这里仅校验身份并返回稳定确认。
     */
    private ObjectNode observe(ObjectNode params, boolean subscribing) {
        RpcParams.requireExact(params, "threadId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        if (subscribing && session.threads().readThread(threadId, null, 1).isEmpty()) {
            throw JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable");
        }
        return session.mapper().createObjectNode().put("accepted", true).put("threadId", threadId);
    }

    /**
     * Jackson 对控制字符的 JSON 转义可能把公开正文放大到远超内存 UTF-8 预算；完整响应超出
     * JA-RPC 帧上限时只丢弃可选恢复基线，保留权威历史页，禁止截断 segment 伪造完整文本。
     */
    private void trimLiveStreamForFrameBudget(ObjectNode result) {
        if (result.path("liveStream").isNull() || !result.has("liveStream")) {
            return;
        }
        try {
            if (session.mapper().writeValueAsBytes(result).length > THREAD_READ_FRAME_BUDGET) {
                result.putNull("liveStream");
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("thread history response could not be serialized", failure);
        }
    }

    /**
     * 在活动原生 Turn 存在时最多重读三次，等待提交 revision 与出站 registry 收敛；继承 Turn 仅是只读
     * 历史投影，不能拥有活动流或触发恢复判定。超过预算返回无基线，不拼接跨 revision 的分页事实。
     *
     * @author kongweiguang
     */
    private ConsistentRead readConsistent(String threadId, String cursor, int limit, boolean tail) {
        for (int attempt = 0; attempt < LIVE_STREAM_READ_ATTEMPTS; attempt++) {
            ThreadSnapshot snapshot = (tail
                    ? session.threads().readThreadLatest(threadId, cursor, limit)
                    : session.threads().readThread(threadId, cursor, limit))
                    .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND,
                            "thread is unavailable"));
            ActiveStreamRegistry streams = session.activeStreams();
            Set<String> activeTurnIds = snapshot.turns().stream()
                    .filter(turn -> turn.origin() == ThreadSnapshot.Origin.NATIVE)
                    .filter(turn -> !io.github.kongweiguang.ja.agent.domain.turn.TurnState
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

    /**
     * 人工重命名通过 expected revision CAS，返回提交后的完整 Thread 元数据。
     */
    private ObjectNode rename(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "title", "expectedThreadRevision");
        ThreadSummary thread = session.threads().renameThread(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.text(params, "title", 512, false),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /**
     * 偏好 CAS 接受完整替换，缺失字段不能继承陈旧 renderer 状态。
     */
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

    /**
     * 置顶布尔值必须显式给出，返回完整 Thread 让调用方按服务端时间立即重排。
     */
    private ObjectNode pin(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "pinned", "expectedThreadRevision");
        JsonNode pinned = params.get("pinned");
        if (pinned == null || !pinned.isBoolean()) {
            throw JaRpcException.invalidParams();
        }
        ThreadSummary thread = session.threads().pinThread(
                RpcParams.identifier(params, "threadId", "thr_", 100), pinned.booleanValue(),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /**
     * 只接受 Thread 身份与 revision，待标记的最新 Turn 必须由 App Server 在事务内权威选取。
     */
    private ObjectNode seen(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        ThreadSummary thread = session.threads().markThreadSeen(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
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

    /**
     * restore 仅接受归档后的 revision，返回未置顶的完整 active Thread。
     */
    private ObjectNode restore(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        ThreadSummary thread = session.threads().restoreThread(threadId,
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), observeThreadMutation(thread));
    }

    /**
     * 删除仍返回 accepted envelope；本轮不将删除暴露为会话菜单动作。
     */
    private ObjectNode delete(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        session.threads().deleteThread(threadId, RpcParams.revision(params, "expectedThreadRevision"));
        session.activeStreams().abandonThread(threadId);
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /**
     * 同步 Thread metadata CAS 没有独立事件可发布，因此按返回记录的 Workspace/Thread
     * 在成功响应前推进活动流 revision，让下一次 thread/read 仍可精确复用公开 draft。
     */
    private ThreadSummary observeThreadMutation(ThreadSummary thread) {
        session.activeStreams().observeThreadRevision(thread.workspaceId(), thread.threadId(), thread.revision());
        return thread;
    }

    /**
     * Tool artifact 通过四元身份和 code point 页读取，找不到时不泄漏哪个身份不匹配。
     */
    private ObjectNode readToolArtifact(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "turnId", "callId", "artifactId",
                "offsetCharacters", "limitCharacters");
        ThreadUseCase.TextArtifactPage page = session.threads().readToolArtifact(
                        RpcParams.identifier(params, "threadId", "thr_", 128),
                        RpcParams.identifier(params, "turnId", "turn_", 101),
                        RpcParams.toolCallId(params, "callId"),
                        RpcParams.identifier(params, "artifactId", "artifact_", 128),
                        RpcParams.integer(params, "offsetCharacters"), RpcParams.integer(params, "limitCharacters"))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "artifact is unavailable"));
        return characterPageResult("artifactId", page.artifactId(), page.offsetCharacters(),
                page.nextOffsetCharacters(), page.totalCharacters(), page.truncated(), page.content());
    }

    /**
     * 两种字符页沿用同一 nullable 游标投影；仅来源身份和授权路径各自独立。
     */
    private ObjectNode characterPageResult(String identityField, String identity, int offset,
                                           Integer nextOffset, int total, boolean truncated, String content) {
        ObjectNode result = session.mapper().createObjectNode().put(identityField, identity)
                .put("offsetCharacters", offset).put("totalCharacters", total)
                .put("truncated", truncated).put("content", content);
        if (nextOffset == null) {
            result.putNull("nextOffsetCharacters");
        }
        else {
            result.put("nextOffsetCharacters", nextOffset);
        }
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

    /**
     * 只由后台读取任务访问 SQLite；完成竞争失败代表请求已超时，不再投影迟到结果。
     */
    private void completeChangeSetRead(ChangeSetReadRequest request, CompletableFuture<ObjectNode> result) {
        try {
            ThreadUseCase.ChangeSetArtifactFile file = session.threads().readChangeSetArtifact(
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

    /**
     * 普通关闭使用进程共享的默认期限；生产组合根应调用带期限重载。
     */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 停止新读取、取消 deadline 调度并在同一绝对期限内等待读取退出；只有本方法返回后才能关闭 Session。
     */
    public void close(ShutdownDeadline deadline) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
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

    /**
     * 仅在线程创建提交失败时回收新空 SESSION；try-with-resources 会把清理异常附加到原失败。
     */
    private static final class UnlinkedSessionWorkspaceRollback implements AutoCloseable {
        private final RpcSession session;
        private final Workspace workspace;
        private boolean committed;

        /**
         * 固定本次创建的工作区身份，禁止回滚逻辑重新解析调用方输入。
         */
        private UnlinkedSessionWorkspaceRollback(RpcSession session, Workspace workspace) {
            this.session = session;
            this.workspace = workspace;
        }

        /**
         * 创建成功后保留目录，由 Thread 持久记录接管其所有权。
         */
        private void commit() {
            committed = true;
        }

        /**
         * 失败时只清理未链接 SESSION，让 JVM 保留主异常并自动挂载清理异常。
         */
        @Override
        public void close() {
            if (!committed) {
                session.workspaces().discardUnlinkedSessionWorkspace(workspace.workspaceId(), workspace.revision());
            }
        }
    }

    /**
     * read 返回的快照与同 revision 活动基线，禁止调用方将两次独立查询重新组合。
     *
     * @param snapshot 快照
     * @param liveStream 实时流（可选）
     */
    private record ConsistentRead(ThreadSnapshot snapshot,
                                  Optional<ActiveStreamRegistry.Snapshot> liveStream) {
        /**
         * 统一非空约束，避免以后新增字段时绕开一致性边界。
         */
        private ConsistentRead {
            Objects.requireNonNull(snapshot, "snapshot");
            liveStream = Optional.ofNullable(liveStream).orElseThrow();
        }
    }

    /**
     * 冻结通过严格 Wire 校验的四元读取身份，后台任务不再接触调用方可变 JSON。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param artifactId 产物标识
     * @param filePath 文件路径
     */
    private record ChangeSetReadRequest(String threadId,
                                        String turnId,
                                        String artifactId,
                                        String filePath) {
    }

}
