// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 映射 Thread 创建、全局导航、权威快照、归档与删除 Wire DTO。
 */
public final class ThreadHistoryHandler implements RpcHandler {
    private final RpcSession session;

    /**
     * 只绑定连接会话，不保留 event journal 或 active workspace 状态。
     */
    public ThreadHistoryHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 返回 Thread 历史、搜索和元数据 CAS 方法闭集。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.THREAD_CREATE, RpcMethod.THREAD_LIST, RpcMethod.THREAD_SEARCH,
                RpcMethod.THREAD_READ, RpcMethod.THREAD_RENAME, RpcMethod.THREAD_PREFERENCES_UPDATE,
                RpcMethod.THREAD_ARCHIVE, RpcMethod.THREAD_DELETE, RpcMethod.TURN_CHANGE_SET_COMMIT,
                RpcMethod.TURN_CHANGE_SET_READ, RpcMethod.TOOL_ARTIFACT_READ);
    }

    /**
     * runtime ready 后执行一个历史命令，并统一映射 workspace 领域失败。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            return CompletableFuture.completedFuture(switch (command.method()) {
                case THREAD_CREATE -> create(command.params());
                case THREAD_LIST -> list(command.params());
                case THREAD_SEARCH -> search(command.params());
                case THREAD_READ -> read(command.params());
                case THREAD_RENAME -> rename(command.params());
                case THREAD_PREFERENCES_UPDATE -> updatePreferences(command.params());
                case THREAD_ARCHIVE -> lifecycle(command.params(), true);
                case THREAD_DELETE -> lifecycle(command.params(), false);
                case TURN_CHANGE_SET_COMMIT -> commitChangeSet(command.params());
                case TURN_CHANGE_SET_READ -> readChangeSet(command.params());
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
        RpcParams.requireOnly(params, "cwd", "title", "providerId", "modelId", "reasoningLevel", "accessMode");
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
     * 按必需 Workspace 身份列出 Thread，分页边界不会混入其它项目的会话。
     */
    private ObjectNode list(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "cursor", "limit");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        CursorPage<ThreadSummary> page = session.threads()
                .listThreads(workspaceId, RpcParams.optionalText(params, "cursor", 512),
                        RpcParams.pageLimit(params));
        return threadPage(page);
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

    /**
     * 返回一个 keyset 快照页，不保留旧 journal/delta replay 概念。
     */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "cursor", "limit");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        ThreadSnapshot snapshot = session.threads()
                .readThread(threadId, RpcParams.optionalText(params, "cursor", 512),
                        RpcParams.pageLimit(params))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND,
                        "thread is unavailable"));
        ObjectNode result = session.mapper().createObjectNode()
                .put("threadId", snapshot.thread().threadId())
                .put("revision", snapshot.thread().revision());
        if (snapshot.contextUsage() == null) result.putNull("contextUsage");
        else result.set("contextUsage", RpcResults.contextUsage(session.mapper(), snapshot.contextUsage()));
        ArrayNode turns = result.putArray("turns");
        snapshot.turns().forEach(turn -> turns.add(RpcResults.snapshotTurn(session.mapper(), turn)));
        ArrayNode items = result.putArray("items");
        snapshot.items().forEach(item -> items.add(RpcResults.snapshotItem(session.mapper(), item)));
        RpcResults.cursor(result, snapshot.nextCursor());
        return result;
    }

    /** 人工重命名通过 expected revision CAS，返回提交后的完整 Thread 元数据。 */
    private ObjectNode rename(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "title", "expectedThreadRevision");
        ThreadSummary thread = session.threads().renameThread(
                RpcParams.identifier(params, "threadId", "thr_", 100),
                RpcParams.text(params, "title", 512, false),
                RpcParams.revision(params, "expectedThreadRevision"));
        return RpcResults.thread(session.mapper(), thread);
    }

    /** 偏好 CAS 接受完整替换，缺失字段不能继承陈旧 renderer 状态。 */
    private ObjectNode updatePreferences(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "providerId", "modelId", "reasoningLevel",
                "accessMode", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        ThreadSnapshot snapshot = session.threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
        ThreadPreferences current = snapshot.thread().preferences();
        ThreadPreferences.TitleSource titleSource = current == null
                ? ThreadPreferences.TitleSource.MANUAL
                : current.titleSource();
        ThreadPreferences preferences = preferences(params, titleSource);
        return RpcResults.thread(session.mapper(), session.threads().updatePreferences(threadId, preferences,
                RpcParams.revision(params, "expectedThreadRevision")));
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
        return new ThreadPreferences(RpcParams.identifier(params, "providerId", "provider_", 128),
                RpcParams.identifier(params, "modelId", "model_", 128), reasoning, accessMode, titleSource);
    }

    /**
     * 应用归档或删除 CAS，不在 transport 保留生命周期状态。
     */
    private ObjectNode lifecycle(ObjectNode params, boolean archive) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        long revision = RpcParams.revision(params, "expectedThreadRevision");
        if (archive) session.threads().archiveThread(threadId, revision);
        else session.threads().deleteThread(threadId, revision);
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /**
     * 接纳 Rust 前向提交的冻结差异；未知字段、绝对路径、统计漂移和 artifact 不一致均失败关闭。
     */
    private ObjectNode commitChangeSet(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "turnId", "workspaceId", "state", "reason",
                "files", "stats", "artifact");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 128);
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 128);
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 128);
        TurnChangeSet.State state = switch (RpcParams.text(params, "state", 32, false)) {
            case "available" -> TurnChangeSet.State.AVAILABLE;
            case "unavailable" -> TurnChangeSet.State.UNAVAILABLE;
            default -> throw JaRpcException.invalidParams();
        };
        String reason = RpcParams.optionalText(params, "reason", 64);
        JsonNode fileValues = params.get("files");
        if (fileValues == null || !fileValues.isArray() || fileValues.size() > 10_000) {
            throw JaRpcException.invalidParams();
        }
        List<TurnChangeSet.FileChange> files = new ArrayList<>(fileValues.size());
        for (JsonNode value : fileValues) {
            if (!(value instanceof ObjectNode file)) throw JaRpcException.invalidParams();
            RpcParams.requireOnly(file, "path", "oldPath", "status", "additions", "deletions", "binary", "truncated");
            TurnChangeSet.FileStatus status = switch (RpcParams.text(file, "status", 32, false)) {
                case "added" -> TurnChangeSet.FileStatus.ADDED;
                case "modified" -> TurnChangeSet.FileStatus.MODIFIED;
                case "deleted" -> TurnChangeSet.FileStatus.DELETED;
                case "renamed" -> TurnChangeSet.FileStatus.RENAMED;
                default -> throw JaRpcException.invalidParams();
            };
            files.add(new TurnChangeSet.FileChange(RpcParams.text(file, "path", 4_096, false),
                    RpcParams.optionalText(file, "oldPath", 4_096), status,
                    optionalWhole(file, "additions"), optionalWhole(file, "deletions"),
                    bool(file, "binary"), bool(file, "truncated")));
        }
        ObjectNode statsValue = RpcParams.object(params, "stats");
        RpcParams.requireExact(statsValue, "files", "additions", "deletions", "binaryFiles", "truncated");
        TurnChangeSet.Stats stats = new TurnChangeSet.Stats(RpcParams.wholeNumber(statsValue, "files"),
                RpcParams.wholeNumber(statsValue, "additions"), RpcParams.wholeNumber(statsValue, "deletions"),
                RpcParams.wholeNumber(statsValue, "binaryFiles"), bool(statsValue, "truncated"));
        String sha256 = null;
        Long byteLength = null;
        String unifiedDiff = null;
        if (params.has("artifact")) {
            ObjectNode artifact = RpcParams.object(params, "artifact");
            RpcParams.requireExact(artifact, "sha256", "byteLength", "unifiedDiff");
            sha256 = RpcParams.text(artifact, "sha256", 64, false);
            if (!sha256.matches("[0-9a-f]{64}")) throw JaRpcException.invalidParams();
            byteLength = RpcParams.wholeNumber(artifact, "byteLength");
            unifiedDiff = RpcParams.text(artifact, "unifiedDiff", 2_097_152, true);
        }
        TurnChangeSet committed = session.threads().commitChangeSet(new io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase.ChangeSetCommit(
                threadId, turnId, workspaceId, new TurnChangeSet(state, reason, files, stats, null),
                sha256, byteLength, unifiedDiff));
        return session.mapper().createObjectNode().put("accepted", true)
                .set("changeSet", RpcResults.changeSet(session.mapper(), committed));
    }

    /** Tool artifact 通过四元身份和 code point 页读取，找不到时不泄漏哪个身份不匹配。 */
    private ObjectNode readToolArtifact(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "turnId", "callId", "artifactId",
                "offsetCharacters", "limitCharacters");
        var page = session.threads().readToolArtifact(
                RpcParams.identifier(params, "threadId", "thr_", 128),
                RpcParams.identifier(params, "turnId", "turn_", 128),
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

    /** Change-set artifact 通过三元身份和 UTF-8 byte 页读取，EOF 使用显式 null。 */
    private ObjectNode readChangeSet(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "turnId", "artifactId", "offsetBytes", "limitBytes");
        var page = session.threads().readChangeSetArtifact(
                RpcParams.identifier(params, "threadId", "thr_", 128),
                RpcParams.identifier(params, "turnId", "turn_", 128),
                RpcParams.identifier(params, "artifactId", "artifact_", 128),
                RpcParams.integer(params, "offsetBytes"), RpcParams.integer(params, "limitBytes"))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "artifact is unavailable"));
        ObjectNode result = session.mapper().createObjectNode().put("artifactId", page.artifactId())
                .put("offsetBytes", page.offsetBytes()).put("byteLength", page.byteLength())
                .put("truncated", page.truncated()).put("content", page.content());
        if (page.nextOffsetBytes() == null) result.putNull("nextOffsetBytes");
        else result.put("nextOffsetBytes", page.nextOffsetBytes());
        return result;
    }

    /** 读取必需 JSON boolean，不接受字符串或数字 coercion。 */
    private static boolean bool(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isBoolean()) throw JaRpcException.invalidParams();
        return node.booleanValue();
    }

    /** 行统计允许缺失以表达二进制或无法证明，不允许显式 null 冒充零。 */
    private static Long optionalWhole(ObjectNode value, String field) {
        return value.has(field) ? RpcParams.wholeNumber(value, field) : null;
    }
}
