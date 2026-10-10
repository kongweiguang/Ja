// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Page;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Snapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionSummary;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 将进程用例投影为严格校验且受代际围栏约束的 JA-RPC 方法。 */
public final class ProcessHandler implements RpcHandler {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_OUTPUT_BYTES = 65_536;
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final String CALL_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final String SESSION_ID_PATTERN = "proc_[0-9a-f]{32}";
    private static final String OPERATION_KEY_PATTERN = "op_[0-9a-f]{32}";
    private static final String CURSOR_PATTERN = "[A-Za-z0-9._~-]{1,256}";
    private final RpcSession session;

    /**
     * 只绑定 RPC 会话门面，进程句柄与持久事实继续由应用服务图唯一持有。
     *
     * @param session 已完成连接握手的 RPC 会话
     */
    public ProcessHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** 注册四个进程方法为单一所有者，避免第二个处理器绕过同一代际围栏。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.PROCESS_LIST, RpcMethod.PROCESS_READ,
                RpcMethod.PROCESS_WRITE, RpcMethod.PROCESS_STOP);
    }

    /** 校验请求后再进入用例；穷尽 switch 的每个分支均保留原 cause，固定 Wire 文案不暴露内部诊断。
     *
     * @param command 已通过路由的请求命令
     * @return 单次完成的 JSON-RPC 结果
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            ObjectNode result = switch (command.method()) {
                case PROCESS_LIST -> list(command.params());
                case PROCESS_READ -> read(command.params());
                case PROCESS_WRITE -> write(command.params());
                case PROCESS_STOP -> stop(command.params());
                default -> throw JaRpcException.methodNotFound();
            };
            return CompletableFuture.completedFuture(result);
        } catch (ProcessSessionFailure failure) {
            throw switch (failure.code()) {
                case SESSION_UNAVAILABLE -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND,
                        "process session is unavailable", failure);
                case WRITE_OUTCOME_UNKNOWN -> JaRpcException.of(JaErrorCatalog.TOOL_OUTCOME_UNKNOWN,
                        "process input outcome is unknown; do not retry the input", failure);
                case LAUNCH_FAILED -> new IllegalStateException(
                        "launch failure cannot cross the process RPC boundary", failure);
            };
        }
    }

    /** 校验显式游标字段，并逐行核对返回摘要的身份与请求范围；进程用例由运行时 owner 关闭。
     *
     * @param params 已由 JSON Schema 校验的请求参数
     * @return 严格符合进程列表合同的结果对象
     */
    @SuppressWarnings("PMD.CloseResource") // 这里只借用 RpcSession 的共享进程用例，不能关闭运行时 owner。
    private ObjectNode list(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "threadId", "limit", "cursor", "startedCallId");
        requireFields(params, "workspaceId", "threadId", "limit", "cursor");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 99);
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        int limit = RpcParams.integer(params, "limit");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw JaRpcException.invalidParams();
        }
        String cursor = nullableText(params, "cursor", 256, CURSOR_PATTERN);
        String startedCallId = params.has("startedCallId")
                ? textMatching(params, "startedCallId", 128, CALL_ID_PATTERN) : null;
        ProcessSessionUseCase processes = session.processes();
        long generation = processes.generation();
        Page page = processes.list(workspaceId, threadId, limit, cursor, startedCallId);
        if (page.sessions().size() > limit) {
            throw new IllegalStateException("process session page exceeded its requested limit");
        }
        ObjectNode result = session.mapper().createObjectNode()
                .put("workspaceId", workspaceId)
                .put("threadId", threadId)
                .put("generation", generation);
        ArrayNode sessions = result.putArray("sessions");
        Set<String> identities = new HashSet<>();
        for (ProcessSessionSummary summary : page.sessions()) {
            requireSummaryOwner(summary, workspaceId, threadId, generation);
            if (!identities.add(summary.sessionId())) {
                throw new IllegalStateException("process session page contains duplicate identities");
            }
            if (summary.state().equals("failed")) {
                continue;
            }
            sessions.add(summary(summary, generation));
        }
        putNullableCursor(result, "nextCursor", page.nextCursor());
        return result;
    }

    /** 按绝对游标读取输出，并允许历史代际以 interrupted 状态只读回放。
     *
     * @param params 已由 JSON Schema 校验的请求参数
     * @return 当前游标之后的有界输出快照
     */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireExact(params, "workspaceId", "threadId", "generation", "sessionId",
                "afterSequence", "maxBytes");
        ProcessSessionScope scope = scope(params);
        String sessionId = sessionId(params);
        long afterSequence = RpcParams.wholeNumber(params, "afterSequence");
        int maxBytes = RpcParams.integer(params, "maxBytes");
        if (maxBytes < 1 || maxBytes > MAX_OUTPUT_BYTES) {
            throw JaRpcException.invalidParams();
        }
        Snapshot snapshot = session.processes().read(scope, sessionId, afterSequence, maxBytes);
        requireSnapshotOwner(snapshot, scope, sessionId, afterSequence, maxBytes, false);
        if (snapshot.state().equals("exited") && snapshot.exitCode() == null) {
            throw new ProcessSessionFailure(ProcessSessionFailure.Code.SESSION_UNAVAILABLE);
        }
        return snapshot(snapshot, scope.generation());
    }

    /** 仅向调用代际写入幂等输入；副作用结果未知时阻止自动重放。
     *
     * @param params 已由 JSON Schema 校验的请求参数
     * @return 新游标位置及输出窗口
     */
    private ObjectNode write(ObjectNode params) {
        RpcParams.requireExact(params, "workspaceId", "threadId", "generation", "sessionId",
                "afterSequence", "input", "closeInput", "operationKey");
        ProcessSessionScope scope = scope(params);
        String sessionId = sessionId(params);
        long afterSequence = RpcParams.wholeNumber(params, "afterSequence");
        String input = RpcParams.text(params, "input", MAX_INPUT_BYTES, true);
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw JaRpcException.invalidParams();
        }
        boolean closeInput = booleanValue(params, "closeInput");
        String operationKey = textMatching(params, "operationKey", 35, OPERATION_KEY_PATTERN);
        Snapshot snapshot = session.processes().write(scope, sessionId, operationKey, input, closeInput,
                afterSequence, MAX_OUTPUT_BYTES);
        requireSnapshotOwner(snapshot, scope, sessionId, afterSequence, MAX_OUTPUT_BYTES, true);
        return snapshot(snapshot, scope.generation());
    }

    /** 停止当前代际进程，并返回空输出确认以避免重复发送已读取正文。
     *
     * @param params 已由 JSON Schema 校验的请求参数
     * @return 进程停止状态确认
     */
    private ObjectNode stop(ObjectNode params) {
        RpcParams.requireExact(params, "workspaceId", "threadId", "generation", "sessionId");
        ProcessSessionScope scope = scope(params);
        String sessionId = sessionId(params);
        Snapshot snapshot = session.processes().stop(scope, sessionId);
        requireSnapshotOwner(snapshot, scope, sessionId, snapshot.outputSequence(), 0, true);
        if (snapshot.state().equals("exited") && snapshot.exitCode() == null) {
            throw new ProcessSessionFailure(ProcessSessionFailure.Code.SESSION_UNAVAILABLE);
        }
        if ((!snapshot.state().equals("stopping") && !snapshot.state().equals("exited"))
                || !snapshot.output().isEmpty() || snapshot.outputTruncated()) {
            throw new IllegalStateException("process stop result violated its wire contract");
        }
        return snapshot(snapshot, scope.generation());
    }

    /** 只投影合同允许的进程状态，并显式处理活动启动态与旧代际事实。
     *
     * @param value 已验证的会话摘要
     * @param currentGeneration 当前服务代际
     * @return 符合 JA-RPC 合同的摘要对象
     */
    private ObjectNode summary(ProcessSessionSummary value, long currentGeneration) {
        String state = wireState(value.state(), value.generation(), currentGeneration);
        requireSessionIdentity(value.sessionId(), value.startedCallId(), value.startedTurnId(), value.createdAt());
        requireSafeSequence(value.outputSequence());
        if (state.equals("exited") && value.exitCode() == null) {
            throw new IllegalStateException("exited process session has no exit code");
        }
        ObjectNode result = commonSessionFields(value, state);
        putNullableInteger(result, "exitCode", value.exitCode());
        return result;
    }

    /**
     * 列表摘要与单次快照共用稳定字段；输出正文、下一游标和退出码仍由各自响应按合同添加。
     */
    private ObjectNode commonSessionFields(ProcessSessionSummary value, String state) {
        return session.mapper().createObjectNode()
                .put("workspaceId", value.workspaceId())
                .put("threadId", value.threadId())
                .put("generation", value.generation())
                .put("sessionId", value.sessionId())
                .put("startedTurnId", value.startedTurnId())
                .put("startedCallId", value.startedCallId())
                .put("createdAt", value.createdAt().toString())
                .put("state", state)
                .put("outputSequence", value.outputSequence())
                .put("outputTruncated", value.outputTruncated());
    }

    /** 序列化前复核有界输出快照，拒绝身份或游标漂移。
     *
     * @param value 已验证的进程快照
     * @param currentGeneration 当前服务代际
     * @return 符合 JA-RPC 合同的快照对象
     */
    private ObjectNode snapshot(Snapshot value, long currentGeneration) {
        ProcessSessionSummary summary = value.summary();
        String state = wireState(summary.state(), summary.generation(), currentGeneration);
        requireSessionIdentity(summary.sessionId(), summary.startedCallId(), summary.startedTurnId(),
                summary.createdAt());
        requireSafeSequence(summary.outputSequence());
        requireSafeSequence(value.nextSequence());
        if (value.nextSequence() > summary.outputSequence()
                || value.output().getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES
                || (state.equals("exited") && summary.exitCode() == null)) {
            throw new IllegalStateException("process snapshot violated its wire contract");
        }
        ObjectNode result = commonSessionFields(summary, state)
                .put("output", value.output())
                .put("nextSequence", value.nextSequence());
        putNullableInteger(result, "exitCode", summary.exitCode());
        return result;
    }

    /** 列表项必须保持请求的工作区与线程，并且不可来自未来代际。
     *
     * @param value 待校验的会话摘要
     * @param workspaceId 请求的工作区标识
     * @param threadId 请求的线程标识
     * @param currentGeneration 当前服务代际
     */
    private static void requireSummaryOwner(ProcessSessionSummary value, String workspaceId, String threadId,
                                            long currentGeneration) {
        Objects.requireNonNull(value, "process session summary");
        if (!workspaceId.equals(value.workspaceId()) || !threadId.equals(value.threadId())
                || value.generation() > currentGeneration) {
            throw new IllegalStateException("process session page crossed its owner or generation");
        }
    }

    /** 结果离开 Java 前检查共享身份和游标约束，避免 read/write/stop 使用不同边界。
     *
     * @param value 待校验的进程快照
     * @param scope 请求所绑定的权限范围
     * @param sessionId 请求的会话标识
     * @param afterSequence 请求的输出游标
     * @param maxBytes 请求的输出字节预算
     * @param currentGenerationOnly 是否要求会话属于当前代际
     */
    private static void requireSnapshotOwner(Snapshot value, ProcessSessionScope scope, String sessionId,
                                             long afterSequence, int maxBytes, boolean currentGenerationOnly) {
        Objects.requireNonNull(value, "process session snapshot");
        if (!scope.workspaceId().equals(value.workspaceId()) || !scope.threadId().equals(value.threadId())
                || !sessionId.equals(value.sessionId()) || value.generation() > scope.generation()
                || (currentGenerationOnly && value.generation() != scope.generation())
                || value.nextSequence() < afterSequence || value.nextSequence() > value.outputSequence()
                || (maxBytes > 0 && value.output().getBytes(StandardCharsets.UTF_8).length > maxBytes)) {
            throw new IllegalStateException("process snapshot crossed its owner, generation, or cursor");
        }
    }

    /** 从精确的工作区、线程与运行时代际字段构造已验证权限范围。
     *
     * @param params 已由 JSON Schema 校验的请求参数
     * @return 进程操作的权限范围
     */
    private static ProcessSessionScope scope(ObjectNode params) {
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 99);
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        long generation = RpcParams.wholeNumber(params, "generation");
        if (generation < 1) {
            throw JaRpcException.invalidParams();
        }
        return new ProcessSessionScope(workspaceId, threadId, generation);
    }

    /** 在会话标识进入进程 owner 前检查其随机小写编码格式。
     *
     * @param params 已由 JSON Schema 校验的请求参数
     * @return 合法进程会话标识
     */
    private static String sessionId(ObjectNode params) {
        return textMatching(params, "sessionId", 37, SESSION_ID_PATTERN);
    }

    /** 保证必须显式出现的字段不会把 JSON null 与字段省略混为一谈。
     *
     * @param params 请求参数对象
     * @param fields 必须存在的字段名
     */
    private static void requireFields(ObjectNode params, String... fields) {
        for (String field : fields) {
            if (!params.has(field)) {
                throw JaRpcException.invalidParams();
            }
        }
    }

    /** 读取可空文本字段，保留首屏显式 null 与续页字段值的协议差异。
     *
     * @param params 请求参数对象
     * @param field 字段名
     * @param maximum 最大字符数
     * @param pattern 允许的完整匹配格式
     * @return 文本值，显式 null 时为空
     */
    private static String nullableText(ObjectNode params, String field, int maximum, String pattern) {
        if (!params.has(field)) {
            throw JaRpcException.invalidParams();
        }
        if (params.get(field).isNull()) {
            return null;
        }
        return textMatching(params, field, maximum, pattern);
    }

    /** 拒绝类型强转和格式错误的不透明 ID，同时保留已验证的线上文本。
     *
     * @param params 请求参数对象
     * @param field 字段名
     * @param maximum 最大字符数
     * @param pattern 允许的完整匹配格式
     * @return 合法字段文本
     */
    private static String textMatching(ObjectNode params, String field, int maximum, String pattern) {
        String value = RpcParams.text(params, field, maximum, false);
        if (!value.matches(pattern)) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /** 要求真实 JSON 布尔值，避免标量强转意外触发终端 EOF。
     *
     * @param params 请求参数对象
     * @param field 布尔字段名
     * @return 字段布尔值
     */
    private static boolean booleanValue(ObjectNode params, String field) {
        if (!params.has(field) || !params.get(field).isBoolean()) {
            throw JaRpcException.invalidParams();
        }
        return params.get(field).booleanValue();
    }

    /** 映射到合同允许的状态闭集，且不把失败启动伪装成正常退出。
     *
     * @param state 持久化进程状态
     * @param sessionGeneration 会话创建代际
     * @param currentGeneration 当前服务代际
     * @return 可对外发布的进程状态
     */
    private static String wireState(String state, long sessionGeneration, long currentGeneration) {
        if (sessionGeneration < currentGeneration
                && (state.equals("starting") || state.equals("running") || state.equals("stopping"))) {
            return "interrupted";
        }
        return switch (state) {
            case "starting", "running" -> "running";
            case "stopping", "exited", "interrupted" -> state;
            case "failed" -> throw new IllegalStateException("failed process sessions are not wire-visible");
            default -> throw new IllegalStateException("unknown process session state");
        };
    }

    /** 构造摘要或快照前检查持久身份及时间值，避免错误账本行成为协议事实。
     *
     * @param sessionId 会话标识
     * @param callId 启动工具调用标识
     * @param turnId 启动 Turn 标识
     * @param createdAt 会话创建时间
     */
    private static void requireSessionIdentity(String sessionId, String callId, String turnId,
                                               java.time.Instant createdAt) {
        if (sessionId == null || !sessionId.matches(SESSION_ID_PATTERN)
                || callId == null || !callId.matches(CALL_ID_PATTERN)
                || turnId == null || !turnId.matches("turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                || createdAt == null || createdAt.toString().length() > 64) {
            throw new IllegalStateException("invalid process session identity");
        }
    }

    /** 将 JSON 数值游标约束在 Java、Rust 与 TypeScript 共享的精确整数范围内。
     *
     * @param value 待校验的序号
     */
    private static void requireSafeSequence(long value) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            throw new IllegalStateException("process session sequence exceeds the wire range");
        }
    }

    /** 显式写出可空游标，避免 Rust 或 TypeScript 将省略误判为完成。
     *
     * @param result 待写入的 JSON 结果
     * @param field 游标字段名
     * @param value 可空游标文本
     */
    private static void putNullableCursor(ObjectNode result, String field, String value) {
        if (value == null) {
            result.putNull(field);
            return;
        }
        if (!value.matches(CURSOR_PATTERN)) {
            throw new IllegalStateException("invalid process page cursor");
        }
        result.put(field, value);
    }

    /** 在摘要和快照中显式保留可空退出码，包括仍活动的进程。
     *
     * @param result 待写入的 JSON 结果
     * @param field 退出码字段名
     * @param value 可空退出码
     */
    private static void putNullableInteger(ObjectNode result, String field, Integer value) {
        if (value == null) {
            result.putNull(field);
        } else {
            result.put(field, value);
        }
    }
}
