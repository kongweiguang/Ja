// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.foundation.runtime.ProductVersion;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 负责 Java 所有的 v1 配置与运行时边界握手，不承载领域资源操作。
 */
public final class HandshakeHandler implements RpcHandler {
    static final List<String> METHODS = List.of(
            "runtime/initialize", "runtime/health", "runtime/shutdown",
            "workspace/open", "workspace/open-general", "workspace/list", "workspace/path/search",
            "workspace/set-trust", "workspace/unregister",
            "thread/create", "thread/list", "thread/search", "thread/read", "thread/rename", "thread/pin", "thread/seen",
            "thread/preferences/update", "thread/archive", "thread/restore", "thread/delete", "thread/compact",
            "interaction/read", "interaction/observe", "interaction/unobserve",
            "interaction/draft/save", "interaction/respond", "interaction/cancel",
            "goal/read", "goal/events/read", "goal/observe", "goal/unobserve", "plan/read", "plan/revisions/list",
            "plan/current/read", "plan/events/read", "plan/observe", "plan/unobserve", "plan/evidence/list",
            "goal/evidence/list", "goal/create", "goal/plan/attach", "goal/plan/detach",
            "goal/pause", "goal/resume", "goal/stop",
            "plan/create", "plan/draft/save", "plan/draft/discard", "plan/propose", "plan/execute", "plan/reject",
            "plan/pause", "plan/resume", "plan/stop",
            "task/create", "task/list", "task/read", "task/observe", "task/unobserve", "task/seen",
            "thread/message/send", "task/followup", "task/cancel", "task/tree/delete", "task/close",
            "attachment/import", "attachment/discard", "attachment/preview/open",
            "attachment/preview/read", "attachment/preview/close",
            "turn/start", "turn/resume", "turn/cancel", "turn/input/enqueue", "turn/input/prioritize",
            "turn/input/update", "turn/input/delete",
            "turn/change-set/read",
            "approval/respond", "configuration/read", "configuration/patch",
            "configuration/replace", "configuration/reset", "credential/set", "credential/delete",
            "skill/list", "mcp/list", "mcp/test", "model/test",
            "mcp/list-tools", "tool/artifact/read");
    static final List<String> EVENTS = List.of(
            "runtime/status-changed", "turn/state-changed", "turn/input-queue-changed", "turn/input-consumed",
            "turn/messages_received",
            "assistant/model-step-committed",
            "assistant/text-delta", "assistant/reasoning-summary-delta", "tool/started", "tool/batch-committed",
            "approval/requested", "approval/resolved", "context/compaction-started", "context/compacted",
            "context/compaction-failed",
            "workspace/dirty", "turn/terminal",
            "thread/metadata-changed", "configuration/changed",
            "task/activity", "task/progress", "task/mailbox-changed",
            "goal/changed", "goal/activity", "interaction/changed", "plan/changed");
    static final List<String> ACCESS_MODES = List.of("approval_required", "full_access");
    static final List<String> COLLABORATION_MODES = List.of("default", "plan");
    static final List<String> FEATURES = List.of("task_threads_v1", "plan_goal_v1", "interaction_v1");
    private final RpcSession session;

    /**
     * 只绑定当前连接代际状态，领域资源仍由 Solon Factory 隔离。
     */
    public HandshakeHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 仅声明 initialize 所有权，配置写入继续路由至 configuration handler。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.RUNTIME_INITIALIZE);
    }

    /**
     * 执行严格 v1 协商，不接受客户端持有的配置快照。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        return CompletableFuture.completedFuture(switch (command.method()) {
            case RUNTIME_INITIALIZE -> initialize(command.params());
            default -> throw JaRpcException.methodNotFound();
        });
    }

    /**
     * 在开启空白启动代际前校验协商能力与队列限制，防止双方资源边界静默漂移。
     */
    private ObjectNode initialize(ObjectNode params) {
        RpcParams.requireExact(params, "protocolMajor", "protocolMinor", "clientVersion",
                "capabilities", "limits");
        if (!integralEquals(params.get("protocolMajor"), 1)
            || !integralEquals(params.get("protocolMinor"), 0)) {
            throw JaRpcException.of(JaErrorCatalog.PROTOCOL_VERSION_UNSUPPORTED,
                    "protocol version is unsupported");
        }
        RpcParams.text(params, "clientVersion", 128, false);
        validateCapabilities(RpcParams.object(params, "capabilities"));
        validateLimits(RpcParams.object(params, "limits"));
        session.initialize();
        ObjectNode result = identity();
        result.set("capabilities", capabilities(session.mapper()));
        result.set("limits", limits(session.mapper()));
        ObjectNode runtime = result.putObject("runtime");
        // Runtime identity 是精确公共契约而非诊断面；工具链版本应进入有界诊断，
        // 写入此处会破坏严格的 Rust/TypeScript 客户端。
        runtime.put("engine", "ja-kernel").put("engineVersion", ProductVersion.current());
        return result;
    }

    /**
     * 返回所有运行代际共享的三个不可变协议身份字段。
     */
    private ObjectNode identity() {
        return session.mapper().createObjectNode().put("protocolMajor", 1).put("protocolMinor", 0)
                .put("serverInstanceId", session.serverInstanceId());
    }

    /**
     * 拒绝能力降级或扩展，并让协作模式与访问模式分别协商，避免 Plan 暗中改变权限边界。
     */
    private static void validateCapabilities(ObjectNode value) {
        RpcParams.requireExact(value, "methods", "events", "accessModes", "collaborationModes", "features");
        requireExactArray(value.get("methods"), METHODS);
        requireExactArray(value.get("events"), EVENTS);
        requireExactArray(value.get("accessModes"), ACCESS_MODES);
        requireExactArray(value.get("collaborationModes"), COLLABORATION_MODES);
        requireExactArray(value.get("features"), FEATURES);
    }

    /**
     * 要求完全匹配冻结限制，禁止任一端静默放宽资源准入。
     */
    private static void validateLimits(ObjectNode value) {
        RpcParams.requireExact(value, "maxFrameBytes", "maxInFlightRequests", "maxInboundQueueFrames",
                "maxControlOutboundQueueFrames", "maxDataOutboundQueueFrames", "maxConcurrentTurns",
                 "maxAdmittedTurns", "maxThreadQueuedTurns", "maxSnapshotPageItems",
                 "maxToolBatchConcurrency", "maxTurnQueuedInputs", "maxTurnQueuedInputBytes");
        ObjectNode expected = limits(new com.fasterxml.jackson.databind.ObjectMapper());
        if (!expected.equals(value)) throw JaRpcException.invalidParams();
    }

    /**
     * 比较 JSON 整数，不接受浮点数或文本强制转换。
     */
    private static boolean integralEquals(com.fasterxml.jackson.databind.JsonNode value, int expected) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
               && value.intValue() == expected;
    }

    /**
     * 要求数组顺序完全一致，因为 golden corpus 冻结了首发词汇表。
     */
    private static void requireExactArray(JsonNode value, List<String> expected) {
        if (!(value instanceof ArrayNode array) || array.size() != expected.size()) {
            throw JaRpcException.invalidParams();
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!array.get(index).isTextual() || !expected.get(index).equals(array.get(index).textValue())) {
                throw JaRpcException.invalidParams();
            }
        }
    }

    /**
     * 构造返回给 Rust 的精确能力词汇表；数组顺序与 golden corpus 一致，禁止端侧自行排序。
     */
    static ObjectNode capabilities(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode methods = result.putArray("methods");
        METHODS.forEach(methods::add);
        ArrayNode events = result.putArray("events");
        EVENTS.forEach(events::add);
        ArrayNode modes = result.putArray("accessModes");
        ACCESS_MODES.forEach(modes::add);
        ArrayNode collaborationModes = result.putArray("collaborationModes");
        COLLABORATION_MODES.forEach(collaborationModes::add);
        ArrayNode features = result.putArray("features");
        FEATURES.forEach(features::add);
        return result;
    }

    /**
     * 构造 Java、Rust 与 TypeScript 共同遵守的精确传输和队列限制。
     */
    static ObjectNode limits(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return mapper.createObjectNode().put("maxFrameBytes", 4 * 1024 * 1024)
                .put("maxInFlightRequests", 64).put("maxInboundQueueFrames", 256)
                .put("maxControlOutboundQueueFrames", 64).put("maxDataOutboundQueueFrames", 1_024)
                .put("maxConcurrentTurns", 8).put("maxAdmittedTurns", 64)
                 .put("maxThreadQueuedTurns", 8).put("maxSnapshotPageItems", 200)
                 .put("maxToolBatchConcurrency", 8).put("maxTurnQueuedInputs", 8)
                 .put("maxTurnQueuedInputBytes", 512 * 1024);
    }
}
