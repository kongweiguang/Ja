// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 将 `workspace/path/search` 严格映射到 Java owner，并由服务端注入可信运行代际。
 */
public final class WorkspacePathSearchHandler implements RpcHandler {
    private final Runnable requireReady;
    private final ObjectMapper mapper;
    private final Supplier<ThreadUseCase> threads;
    private final Supplier<WorkspacePathSearchUseCase> searches;
    private final LongSupplier runtimeGeneration;

    /** Handler 只保存连接会话，不缓存 Thread、Workspace 或目录扫描结果。 */
    public WorkspacePathSearchHandler(RpcSession session) {
        this(Objects.requireNonNull(session, "session")::requireReady, session.mapper(),
                session::threads, session::workspacePathSearch, session::runtimeGeneration);
    }

    /**
     * 包级 seam 让严格参数与跨 Workspace 准入无需启动 stdio runtime 即可验证，
     * 生产构造仍只接受唯一 RpcSession。
     */
    WorkspacePathSearchHandler(Runnable requireReady, ObjectMapper mapper,
                               Supplier<ThreadUseCase> threads,
                               Supplier<WorkspacePathSearchUseCase> searches,
                               LongSupplier runtimeGeneration) {
        this.requireReady = Objects.requireNonNull(requireReady, "requireReady");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.searches = Objects.requireNonNull(searches, "searches");
        this.runtimeGeneration = Objects.requireNonNull(runtimeGeneration, "runtimeGeneration");
    }

    /** 独占路径搜索方法，避免扩展原 Workspace 注册与信任 Handler 的责任。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.WORKSPACE_PATH_SEARCH);
    }

    /**
     * 先读取 Thread 权威 Workspace，再开始目录 IO；跨 Workspace 参数不能用于存在性探测。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        requireReady.run();
        if (command.method() != RpcMethod.WORKSPACE_PATH_SEARCH) {
            throw JaRpcException.methodNotFound();
        }
        try {
            return CompletableFuture.completedFuture(search(command.params()));
        } catch (WorkspacePathFailure failure) {
            throw mapPathFailure(failure);
        } catch (WorkspaceFailure failure) {
            throw WorkspaceHandler.mapFailure(failure);
        }
    }

    /**
     * runtime generation 始终来自当前 RpcSession，Wire 不允许传入并伪造关联栅栏。
     */
    private ObjectNode search(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "workspaceId", "query", "limit");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 128);
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        ThreadSnapshot thread = threads.get().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(
                        JaErrorCatalog.THREAD_NOT_FOUND, "thread was not found"));
        if (!thread.thread().workspaceId().equals(workspaceId)) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.WORKSPACE_MISMATCH,
                    "workspace search does not belong to thread");
        }
        int limit = Math.min(RpcParams.pageLimit(params), 50);
        WorkspacePathSearchUseCase.SearchResult result = searches.get().search(
                new WorkspacePathSearchUseCase.SearchRequest(
                        threadId, workspaceId, runtimeGeneration.getAsLong(),
                        RpcParams.text(params, "query", 1_024, true), limit));
        return result(result);
    }

    /** Wire 只投影关联字段、相对路径、闭集类型和截断事实，不公开扫描计数或绝对路径。 */
    private ObjectNode result(WorkspacePathSearchUseCase.SearchResult value) {
        ObjectNode result = mapper.createObjectNode()
                .put("threadId", value.threadId())
                .put("workspaceId", value.workspaceId())
                .put("generation", value.runtimeGeneration())
                .put("query", value.query())
                .put("truncated", value.truncated());
        ArrayNode items = result.putArray("items");
        value.items().forEach(item -> items.add(mapper.createObjectNode()
                .put("relativePath", item.relativePath())
                .put("kind", wireKind(item.kind()))));
        return result;
    }

    /** 条目类型使用冻结小写 Wire 词汇，禁止依赖 enum 默认序列化。 */
    private static String wireKind(WorkspaceEntryKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** 路径失败映射到现有冻结 catalog，不把本地路径或 IOException message 带入响应。 */
    private static JaRpcException mapPathFailure(WorkspacePathFailure failure) {
        JaErrorCatalog error = switch (failure.code()) {
            case INVALID_PATH -> JaErrorCatalog.INVALID_PARAMS;
            case WORKSPACE_MISMATCH, CONFINEMENT -> JaErrorCatalog.WORKSPACE_CONFINEMENT;
            case PATH_UNAVAILABLE, TYPE_MISMATCH -> JaErrorCatalog.WORKSPACE_NOT_FOUND;
            case IO_UNAVAILABLE -> JaErrorCatalog.STORAGE_UNAVAILABLE;
        };
        return JaRpcException.of(error, "workspace path search failed");
    }
}
