// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker;
import io.github.kongweiguang.ja.conversation.application.change.WorkspaceMutationFactCodec;
import io.github.kongweiguang.ja.conversation.domain.model.JaToolCallId;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnChangeSetSerialization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将已提交的 Workspace 收据累计为可恢复状态，并只在 Core 接受终态时生成冻结 ChangeSet 事实。
 */
final class JaTurnChangeSetMiddleware implements AgentMiddleware {
    private static final String ID = "ja.turn";
    private static final String CHANGE_SET_TYPE = "change-set";
    private static final String CHANGE_SET_ARTIFACT_TYPE = "change-set-artifact";
    private static final int FACT_SCHEMA_VERSION = 1;
    private static final String WORKSPACE_STATE_KEY = "workspaceId";
    private static final String REASONS_STATE_KEY = "incompleteReasons";
    private static final String FILE_STATE_PREFIX = "file_";
    private static final String PENDING_STATE_PREFIX = "pending_";
    private final AgentIdentity identity;
    private final Path workspaceRoot;
    private final String workspaceId;
    private final Map<String, JaTool> jaTools;
    private final Map<String, AgentTool> coreTools;
    private final TurnChangeSetSerialization changeSets;

    /**
     * 固定本次执行可观察的工具声明与工作区身份；恢复仅使用这些绑定，不从工具名称推断写入范围。
     */
    JaTurnChangeSetMiddleware(AgentIdentity identity, Path workspaceRoot, String workspaceId,
                              List<JaTool> jaTools, List<AgentTool> coreTools,
                              TurnChangeSetSerialization changeSets) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.jaTools = indexJaTools(jaTools);
        this.coreTools = indexCoreTools(coreTools);
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets");
    }

    /**
     * Core 只会在前一 Tool 已结算并推进 cursor 后进入下一次准入；遗留 marker 表示未知恢复被跳过。
     */
    private static boolean foldUnsettledCalls(TurnChangeTracker tracker,
                                              Map<String, JaTool.WorkspaceMutationMode> pending, String nextCallId) {
        if (pending.containsKey(nextCallId)) {
            throw new IllegalStateException("persisted Tool preparation re-entered its admission hook");
        }
        if (pending.isEmpty()) {
            return false;
        }
        tracker.markIncomplete(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY);
        if (pending.values().stream().anyMatch(mode -> mode == JaTool.WorkspaceMutationMode.UNOBSERVABLE)) {
            tracker.markIncomplete(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR);
        }
        pending.clear();
        return true;
    }

    /**
     * 从 Tracker 既有约束生成稳定 artifact 身份，重试同一终态提交不会改变事实载荷。
     */
    private static TurnChangeTracker.Frozen freeze(AgentIdentity identity, TurnChangeTracker tracker) {
        String identityFields = identity.tenantId() + "\u0000" + identity.threadId() + "\u0000"
                                + identity.turnId() + "\u0000" + identity.userId();
        String stableIdentity = sha256(identityFields.getBytes(StandardCharsets.UTF_8));
        return tracker.freeze(stableIdentity);
    }

    /**
     * 把冻结 diff 作为与 ChangeSet 同事务提交的 artifact 事实，字段继续受 participant 严格校验。
     */
    private static MiddlewareFact artifactFact(TurnChangeTracker.Frozen frozen, String diff) {
        byte[] bytes = diff.getBytes(StandardCharsets.UTF_8);
        if (frozen.byteLength() == null || frozen.sha256() == null
            || frozen.byteLength() != bytes.length || bytes.length > TurnChangeTracker.MAX_DIFF_BYTES
            || !sha256(bytes).equals(frozen.sha256())) {
            throw new IllegalStateException("frozen ChangeSet artifact failed integrity validation");
        }
        JsonObject payload = JsonObjects.builder().putText("artifactId", frozen.changeSet().artifactId())
                .putText("sha256", frozen.sha256()).putNumber("byteLength", frozen.byteLength())
                .putText("content", diff).build();
        return new MiddlewareFact(CHANGE_SET_ARTIFACT_TYPE, FACT_SCHEMA_VERSION, payload);
    }

    /**
     * 将不含物理路径的单文件 pre/postimage 状态写成严格 JSON 对象。
     */
    private static JsonObject filePayload(TurnChangeTracker.DurableFile file) {
        return JsonObjects.builder().putText("path", file.path())
                .putBoolean("beforeExists", file.beforeExists()).putText("beforeText", file.beforeText())
                .putText("beforeSha256", file.beforeSha256()).putBoolean("afterExists", file.afterExists())
                .putText("afterText", file.afterText()).putText("afterSha256", file.afterSha256()).build();
    }

    /**
     * 仅接受持久状态字段闭集，并让 DurableFile 再校验 pre/post 内容哈希。
     */
    private static TurnChangeTracker.DurableFile readFile(JsonValue value) {
        if (!(value instanceof JsonObject object) || !object.members().keySet().equals(Set.of("path",
                "beforeExists", "beforeText", "beforeSha256", "afterExists", "afterText", "afterSha256"))) {
            throw new IllegalStateException("Ja ChangeSet file state is invalid");
        }
        return new TurnChangeTracker.DurableFile(text(object, "path"), bool(object, "beforeExists"),
                text(object, "beforeText"), text(object, "beforeSha256"), bool(object, "afterExists"),
                text(object, "afterText"), text(object, "afterSha256"));
    }

    /**
     * 严格解析原因数组并拒绝未知或重复值，避免恢复时把新状态静默当作完整。
     */
    private static void readReasons(JsonValue value, Set<TurnChangeSet.IncompleteReason> target) {
        if (!(value instanceof JsonArray(List<JsonValue> values))) {
            throw new IllegalStateException("Ja ChangeSet reasons are invalid");
        }
        for (JsonValue item : values) {
            if (!(item instanceof JsonText(String value1))) {
                throw new IllegalStateException("Ja ChangeSet reason is invalid");
            }
            TurnChangeSet.IncompleteReason reason;
            try {
                reason = TurnChangeSet.IncompleteReason.valueOf(value1);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException("Ja ChangeSet reason is unknown", unknown);
            }
            if (!target.add(reason)) {
                throw new IllegalStateException("Ja ChangeSet reason is duplicated");
            }
        }
        if (target.isEmpty()) {
            throw new IllegalStateException("Ja ChangeSet reason list must not be empty");
        }
    }

    /**
     * 只从 JSON Text 解码必需字段，不执行字符串化或弱类型转换。
     */
    private static String text(JsonObject object, String name) {
        if (!(object.get(name) instanceof JsonText(String value1)))
            {
                throw new IllegalStateException("Ja ChangeSet text is invalid");
            }
        return value1;
    }

    /**
     * 只从 JSON Boolean 解码布尔字段，保留 false 与缺失字段的区别。
     */
    private static boolean bool(JsonObject object, String name) {
        if (!(object.get(name) instanceof JsonBoolean value))
            {
                throw new IllegalStateException("Ja ChangeSet boolean is invalid");
            }
        return value.value();
    }

    /**
     * 工作区状态字段按路径摘要寻址，避免长相对路径占用 middleware 顶层键预算。
     */
    private static String fileStateKey(String path) {
        return FILE_STATE_PREFIX + sha256(Objects.requireNonNull(path, "path")
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 预索引应用工具；重复名称与 Registry 同样直接失败，不覆盖既有路由。
     */
    private static Map<String, JaTool> indexJaTools(List<JaTool> tools) {
        Map<String, JaTool> indexed = new LinkedHashMap<>();
        for (JaTool tool : List.copyOf(Objects.requireNonNull(tools, "jaTools"))) {
            JaTool value = Objects.requireNonNull(tool, "Ja tool");
            String name = Objects.requireNonNull(value.spec(), "Ja Tool spec").name();
            if (indexed.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate Ja Tool name");
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * 预索引基础 Core 工具，以其显式副作用声明处理 Ja 之外的不可观察写入。
     */
    private static Map<String, AgentTool> indexCoreTools(List<AgentTool> tools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : List.copyOf(Objects.requireNonNull(tools, "coreTools"))) {
            AgentTool value = Objects.requireNonNull(tool, "Core tool");
            ToolSpec spec = Objects.requireNonNull(value.spec(), "Core Tool spec");
            if (indexed.putIfAbsent(spec.name(), value) != null) {
                throw new IllegalArgumentException("duplicate Core Tool name");
            }
        }
        return Map.copyOf(indexed);
    }

    /**
     * 从固定枚举恢复 workspace mode，拒绝任何未识别的状态值。
     */
    private static JaTool.WorkspaceMutationMode parseMutationMode(String value) {
        try {
            return JaTool.WorkspaceMutationMode.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("Ja ChangeSet mutation mode is unknown", unknown);
        }
    }

    /**
     * 以同一 SHA-256 字节语义校验 artifact，并为状态键提供确定路径摘要。
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 返回持久状态与 ChangeSet 事实共用的稳定 namespace。
     */
    @Override
    public String id() {
        return ID;
    }

    /**
     * 状态只由有界收据累计值组成；字段语义变更时必须显式升级 schema。
     */
    @Override
    public int stateSchemaVersion() {
        return FACT_SCHEMA_VERSION;
    }

    /**
     * 在最终工具参数已确定后记录可能写 Workspace 的调用，便于结果提交或恢复跳过时区分未知副作用。
     */
    @Override
    public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
        ToolContext call = Objects.requireNonNull(context, "context");
        requireIdentity(call.identity());
        State current = readState(call.extensionState());
        TurnChangeTracker tracker = restore(current.tracker());
        Map<String, JaTool.WorkspaceMutationMode> pending = new LinkedHashMap<>(current.pendingCalls());
        boolean foldedPreviousCall = foldUnsettledCalls(tracker, pending, call.invocation().callId());
        JaTool.WorkspaceMutationMode mode = mutationMode(call.invocation());
        if (mode == JaTool.WorkspaceMutationMode.NONE && !foldedPreviousCall) {
            return ExtensionDecision.unchanged(ToolDecision.proceed(call.invocation().arguments()),
                    call.extensionState());
        }
        if (mode != JaTool.WorkspaceMutationMode.NONE) {
            if (pending.putIfAbsent(call.invocation().callId(), mode) != null) {
                throw new IllegalStateException("Workspace mutation call identity is already pending");
            }
        }
        JsonObject replacement = writeState(tracker.durableSnapshot(), pending);
        return new ExtensionDecision<>(ToolDecision.proceed(call.invocation().arguments()),
                ExtensionStatePatch.replace(call.extensionState(), replacement), List.of());
    }

    /**
     * 只吸收当前 Tool 结果事务中的 Workspace 收据，并与结果一起保存净差异和未完成原因。
     */
    @Override
    public ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext context) {
        ToolResultContext result = Objects.requireNonNull(context, "context");
        requireIdentity(result.identity());
        State current = readState(result.extensionState());
        TurnChangeTracker tracker = restore(current.tracker());
        Map<String, JaTool.WorkspaceMutationMode> pending = new LinkedHashMap<>(current.pendingCalls());
        JaTool.WorkspaceMutationMode preparedMode = pending.remove(result.invocation().callId());
        JaTool.WorkspaceMutationMode actualMode = mutationMode(result.invocation());
        if (preparedMode != null && preparedMode != actualMode) {
            tracker.markIncomplete(TurnChangeSet.IncompleteReason.CAPTURE_FAILED);
        }
        boolean hasWorkspaceFact = applyWorkspaceFacts(tracker, result.extensionFacts());
        if (preparedMode == JaTool.WorkspaceMutationMode.UNOBSERVABLE && result.executionStarted()) {
            tracker.markIncomplete(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR);
        }
        if (preparedMode == null && hasWorkspaceFact) {
            tracker.markIncomplete(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY);
        }
        JsonObject replacement = writeState(tracker.durableSnapshot(), pending);
        return new ExtensionDecision<>(new ToolResultProjection(result.content(), result.structuredContent(),
                result.artifactContent()),
                ExtensionStatePatch.replace(result.extensionState(), replacement), List.of());
    }

    /**
     * 从持久累计状态重建终态快照；未结算或无法核验的调用只能生成 partial，不能伪造空 complete。
     */
    @Override
    public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
        CompletionContext completion = Objects.requireNonNull(context, "context");
        requireIdentity(completion.identity());
        State current = readState(completion.extensionState());
        TurnChangeTracker tracker = restore(current.tracker());
        if (!current.pendingCalls().isEmpty()) {
            tracker.markIncomplete(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY);
            if (current.pendingCalls().values().stream()
                    .anyMatch(mode -> mode == JaTool.WorkspaceMutationMode.UNOBSERVABLE)) {
                tracker.markIncomplete(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR);
            }
        }
        if (completion.hasUnverifiedToolEffects()) {
            tracker.markIncomplete(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY);
        }
        TurnChangeTracker.Frozen frozen = freeze(completion.identity(), tracker);
        List<MiddlewareFact> facts = new ArrayList<>(2);
        facts.add(new MiddlewareFact(CHANGE_SET_TYPE, FACT_SCHEMA_VERSION, changeSets.writePayload(frozen.changeSet())));
        if (frozen.unifiedDiff() != null) {
            facts.add(artifactFact(frozen, frozen.unifiedDiff()));
        }
        return new ExtensionDecision<>(CompletionDecision.complete(),
                ExtensionStatePatch.unchanged(completion.extensionState()), facts);
    }

    /**
     * 按当前声明识别是否可能写 Workspace；Core 外部副作用工具保守归为不可观察。
     */
    private JaTool.WorkspaceMutationMode mutationMode(AgentTool.Invocation invocation) {
        AgentTool.Invocation call = Objects.requireNonNull(invocation, "invocation");
        JaTool jaTool = jaTools.get(call.toolName());
        if (jaTool != null)
            {
                return Objects.requireNonNull(jaTool.workspaceMutationMode(call), "workspace mutation mode");
            }
        AgentTool coreTool = coreTools.get(call.toolName());
        if (coreTool != null && coreTool.sideEffect(call) == ToolSideEffect.EXTERNAL) {
            return JaTool.WorkspaceMutationMode.UNOBSERVABLE;
        }
        return JaTool.WorkspaceMutationMode.NONE;
    }

    /**
     * 按收据 schema 逐条校验事实；未知 Ja Workspace 事实只降级完整性，不丢掉已确认收据。
     */
    private boolean applyWorkspaceFacts(TurnChangeTracker tracker, List<AgentTool.ExtensionFact> facts) {
        boolean found = false;
        for (AgentTool.ExtensionFact fact : facts) {
            if (!WorkspaceMutationFactCodec.NAMESPACE.equals(fact.namespace())) {
                continue;
            }
            found = true;
            tracker.applyCommittedWorkspaceFact(fact.namespace(), fact.type(), fact.schemaVersion(), fact.payload());
        }
        return found;
    }

    /**
     * 用同一冻结 Workspace 身份恢复 Tracker；缺字段、错 hash 或越界路径均失败关闭。
     */
    private TurnChangeTracker restore(TurnChangeTracker.DurableSnapshot snapshot) {
        return TurnChangeTracker.restore(workspaceRoot, workspaceId, snapshot);
    }

    /**
     * 解码 namespace 状态；若状态为空表示尚无已提交的可观察工具事实，不能推断磁盘内容。
     */
    private State readState(ExtensionStateView current) {
        ExtensionStateView state = Objects.requireNonNull(current, "extensionState");
        if (!ID.equals(state.namespace()) || state.schemaVersion() != FACT_SCHEMA_VERSION) {
            throw new IllegalStateException("Ja ChangeSet middleware state identity mismatch");
        }
        JsonObject value = state.value();
        if (value.isEmpty()) {
            return emptyState();
        }
        if (!(value.get(WORKSPACE_STATE_KEY) instanceof JsonText(String value2))
            || !workspaceId.equals(value2)) {
            throw new IllegalStateException("Ja ChangeSet middleware state belongs to another workspace");
        }
        EnumSet<TurnChangeSet.IncompleteReason> reasons = EnumSet.noneOf(TurnChangeSet.IncompleteReason.class);
        List<TurnChangeTracker.DurableFile> files = new ArrayList<>();
        Map<String, JaTool.WorkspaceMutationMode> pending = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : value.members().entrySet()) {
            String key = entry.getKey();
            if (WORKSPACE_STATE_KEY.equals(key)) {
                continue;
            }
            if (REASONS_STATE_KEY.equals(key)) {
                readReasons(entry.getValue(), reasons);
            } else if (key.startsWith(FILE_STATE_PREFIX)) {
                TurnChangeTracker.DurableFile file = readFile(entry.getValue());
                if (!key.equals(fileStateKey(file.path()))) {
                    throw new IllegalStateException("Ja ChangeSet file state key does not match its path");
                }
                files.add(file);
            } else if (key.startsWith(PENDING_STATE_PREFIX)) {
                String callId = key.substring(PENDING_STATE_PREFIX.length());
                if (callId.isBlank() || !(entry.getValue() instanceof JsonText(String value1))) {
                    throw new IllegalStateException("Ja ChangeSet pending call state is invalid");
                }
                JaToolCallId.require(callId);
                JaTool.WorkspaceMutationMode mode = parseMutationMode(value1);
                if (mode == JaTool.WorkspaceMutationMode.NONE || pending.putIfAbsent(callId, mode) != null) {
                    throw new IllegalStateException("Ja ChangeSet pending call state is invalid");
                }
            } else {
                throw new IllegalStateException("Ja ChangeSet middleware state contains an unknown field");
            }
        }
        return new State(new TurnChangeTracker.DurableSnapshot(workspaceId, reasons, files), pending);
    }

    /**
     * 将空状态显式映射为本请求的 fresh Tracker，而不是读取 Workspace 推测历史修改。
     */
    private State emptyState() {
        return new State(TurnChangeTracker.fresh(workspaceRoot, workspaceId).durableSnapshot(), Map.of());
    }

    /**
     * 序列化累计 receipts、完整性原因与待结算调用；文件数和正文预算由 Tracker 统一限制。
     */
    private JsonObject writeState(TurnChangeTracker.DurableSnapshot snapshot,
                                  Map<String, JaTool.WorkspaceMutationMode> pending) {
        if (snapshot.files().isEmpty() && snapshot.incompleteReasons().isEmpty() && pending.isEmpty()) {
            return JsonObject.empty();
        }
        io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder state =
                JsonObjects.builder().putText(WORKSPACE_STATE_KEY, workspaceId);
        if (!snapshot.incompleteReasons().isEmpty()) {
            List<JsonValue> reasons = snapshot.incompleteReasons().stream().sorted().map(value ->
                    (JsonValue) new JsonText(value.name())).toList();
            state.put(REASONS_STATE_KEY, new JsonArray(reasons));
        }
        for (TurnChangeTracker.DurableFile file : snapshot.files()) {
            state.put(fileStateKey(file.path()), filePayload(file));
        }
        pending.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                state.put(PENDING_STATE_PREFIX + entry.getKey(), new JsonText(entry.getValue().name())));
        return state.build();
    }

    /**
     * 比较恢复时冻结的 Ja execution identity，防止状态跨会话或 Turn 被复用。
     */
    private void requireIdentity(AgentIdentity actual) {
        if (!identity.equals(Objects.requireNonNull(actual, "identity"))) {
            throw new IllegalArgumentException("Ja ChangeSet middleware identity mismatch");
        }
    }

    /**
     * 已由持久状态恢复的收据累计值与待结算工具调用。
     *
     * @param tracker 跟踪器
     * @param pendingCalls 待处理的调用
     */
    private record State(TurnChangeTracker.DurableSnapshot tracker,
                         Map<String, JaTool.WorkspaceMutationMode> pendingCalls) {
        /**
         * 固定映射并拒绝缺失子状态，保证状态变更由 ExtensionStatePatch 单次应用。
         */
        private State {
            Objects.requireNonNull(tracker, "tracker");
            pendingCalls = Map.copyOf(Objects.requireNonNull(pendingCalls, "pendingCalls"));
        }
    }
}
