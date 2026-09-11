// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.filesystem.PathIdentities;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonBoolean;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 执行 Provider 选择的 Tool，并把外部副作用隔离在出站适配器。
 */
public interface AgentTool {
    /**
     * 返回权限、Schema 与并发调度共享的不可变领域描述。
     */
    ToolSpec spec();

    /** 未声明的 Tool 一律按外部副作用处理；只有实现能证明只读时才能收窄。 */
    default ToolSideEffect sideEffect() {
        return ToolSideEffect.EXTERNAL;
    }

    /**
     * 默认把 Tool 视为无法观察的工作区写入者；只有实现能够证明只读或返回精确文本收据时才能收窄。
     */
    default WorkspaceMutationMode workspaceMutationMode() {
        return WorkspaceMutationMode.UNOBSERVABLE;
    }

    /**
     * 规划阶段的准入证明；默认拒绝，只有明确受信的 Plan 内部持久化能力才能提升为内部写入。
     */
    default PlanAccess planAccess() {
        return PlanAccess.DISALLOWED;
    }

    /**
     * 返回审批边界；只有内核明确标记的内建操作才可跳过用户权限审批，不能由 Tool 名称推导。
     *
     * <p>该标记与 {@link #planAccess()} 分离：计划阶段的准入证明和执行阶段的权限确认是两条
     * 独立边界。调用方仍会校验绑定路由必须为 BUILTIN，防止外部 MCP 自述为可信内核操作。</p>
     */
    default ApprovalRequirement approvalRequirement() {
        return ApprovalRequirement.USER_REQUIRED;
    }

    /**
     * 返回持久 Tool batch 使用的不可变路由身份；Builtin 默认由规范化 schema 派生稳定哨兵，
     * MCP 适配器必须覆盖并提供真实服务与远端名称。
     */
    default ToolBindingDescriptor bindingDescriptor() {
        return builtinBindingDescriptor(spec(), sideEffect(), workspaceMutationMode());
    }

    /** 能力 prepare 与真实 Builtin Tool 共用身份算法，安全声明变化会使恢复绑定失效。 */
    static ToolBindingDescriptor builtinBindingDescriptor(
            ToolSpec spec, ToolSideEffect sideEffect, WorkspaceMutationMode workspaceMutationMode) {
        ToolSpec frozen = Objects.requireNonNull(spec, "spec");
        ToolSideEffect effect = Objects.requireNonNull(sideEffect, "sideEffect");
        WorkspaceMutationMode mutationMode = Objects.requireNonNull(workspaceMutationMode, "workspaceMutationMode");
        String localName = frozen.name();
        String schemaHash = sha256(canonicalSchema(frozen.inputSchema()));
        return new ToolBindingDescriptor(RouteKind.BUILTIN, localName, "builtin", localName,
                schemaHash, sha256("BUILTIN\0builtin\0" + localName + "\0" + schemaHash
                        + "\0" + effect.name() + "\0" + mutationMode.name()));
    }

    /**
     * 返回 Tool 身份与目录摘要共用的严格 schema 编码；对象键排序、数组保序和类型标记必须保持唯一，
     * 避免两个调用方各自复制规则后产生无法恢复的 route/catalog 指纹漂移。
     */
    static String canonicalSchema(JsonValue schema) {
        StringBuilder canonical = new StringBuilder();
        appendCanonicalJson(canonical, Objects.requireNonNull(schema, "schema"));
        return canonical.toString();
    }

    /**
     * 在给定取消作用域内执行一次调用，并返回可安全持久化的结果。
     */
    CompletionStage<ToolResult> execute(
            Invocation invocation,
            ExecutionContext context,
            CancellationToken cancellationToken);

    /**
     * 一次模型 Tool 调用的稳定身份、参数和顺序。
     */
    record Invocation(String callId, String toolName, JsonObject arguments, int ordinal) {
        /**
         * 冻结调用身份、参数与批次序号，避免 Adapter 在排队后观察到可变参数。
         */
        public Invocation {
            callId = ContractChecks.identifier(callId, "callId");
            toolName = ContractChecks.identifier(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("tool ordinal is outside turn limits");
            }
        }
    }

    /**
     * Tool 执行所需的最小 Turn、工作区、权限和 Deadline 上下文。
     */
    record ExecutionContext(
            String threadId,
            String turnId,
            Path workspaceRoot,
            AccessMode accessMode,
            String configGeneration,
            Instant deadline,
            String workspaceId,
            String planRevisionId,
            String runId,
            String goalId,
            TurnOrigin origin) {
        /** 保持普通 Tool 测试与非内部 Turn 的构造面；内部执行必须由 Runner 注入持久身份。 */
        public ExecutionContext(String threadId, String turnId, Path workspaceRoot,
                                AccessMode accessMode, String configGeneration, Instant deadline,
                                String workspaceId) {
            this(threadId, turnId, workspaceRoot, accessMode, configGeneration, deadline,
                    workspaceId, null, null, null, TurnOrigin.USER);
        }

        /**
         * 固化权限判断和路径约束所需的最小上下文，避免 Tool 反向读取全局运行时。
         */
        public ExecutionContext {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(accessMode, "accessMode");
            configGeneration = ContractChecks.configurationGeneration(configGeneration);
            Objects.requireNonNull(deadline, "deadline");
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            if (!workspaceId.startsWith("ws_")) throw new IllegalArgumentException("invalid workspaceId");
            if (runId == null && (planRevisionId != null || goalId != null)) {
                throw new IllegalArgumentException("invalid execution identity shape");
            }
            origin = Objects.requireNonNull(origin, "origin");
            if (origin == TurnOrigin.PLAN_EXECUTION && (runId == null || planRevisionId == null)) {
                throw new IllegalArgumentException("Plan execution identity is required");
            }
            if (origin == TurnOrigin.GOAL_CONTINUATION && (runId == null || goalId == null)) {
                throw new IllegalArgumentException("Goal execution identity is required");
            }
        }
    }

    /**
     * Tool Adapter 可提交给领域循环的安全结果，不携带异常或资源句柄。
     */
    record ToolResult(
            ToolOutcome outcome,
            String content,
            Optional<JsonValue> structuredContent,
            String errorCode,
            Optional<MutationReceipt> mutationReceipt,
            Optional<MutationObservationFailure> mutationObservationFailure) {
        /**
         * 将 Adapter 输出收敛为有界不可变值，异常与资源句柄不得越过端口。
         */
        public ToolResult {
            Objects.requireNonNull(outcome, "outcome");
            content = ContractChecks.text(content == null ? "" : content, "content", 4_000_000, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            mutationReceipt = Objects.requireNonNull(mutationReceipt, "mutationReceipt");
            mutationObservationFailure = Objects.requireNonNull(
                    mutationObservationFailure, "mutationObservationFailure");
            if (mutationReceipt.isPresent() && mutationObservationFailure.isPresent()) {
                throw new IllegalArgumentException("mutation result cannot contain receipt and failure");
            }
            if (errorCode != null) {
                errorCode = ContractChecks.identifier(errorCode, "errorCode");
            }
        }

        /** 普通 Tool 结果不携带内部修改收据，保留既有调用方的最小构造面。 */
        public ToolResult(ToolOutcome outcome, String content, Optional<JsonValue> structuredContent,
                          String errorCode) {
            this(outcome, content, structuredContent, errorCode, Optional.empty(), Optional.empty());
        }

        /** 精确写成功结果保留既有五参数构造面，失败观察只能通过专用六参数构造。 */
        public ToolResult(ToolOutcome outcome, String content, Optional<JsonValue> structuredContent,
                          String errorCode, Optional<MutationReceipt> mutationReceipt) {
            this(outcome, content, structuredContent, errorCode, mutationReceipt, Optional.empty());
        }

        /**
         * 构造没有额外元数据的成功结果，避免调用方重复拼装闭集字段。
         */
        public static ToolResult success(String content) {
            return new ToolResult(ToolOutcome.SUCCEEDED, content, Optional.empty(), null);
        }

        /**
         * 只输出长度与键集合，防止 Tool 正文或敏感元数据进入日志。
         */
        @Override
        public String toString() {
            return "ToolResult[outcome=" + outcome
                   + ", contentLength=" + content.length()
                   + ", structuredContent=" + structuredContent.isPresent()
                   + ", mutationReceipt=" + mutationReceipt.isPresent()
                   + ", mutationObservationFailure=" + mutationObservationFailure.isPresent()
                   + ", errorCode=" + errorCode + "]";
        }
    }

    /** 写入可能发生但无法形成可信收据时，仅向 Java tracker 传递的闭集观察结果。 */
    enum MutationObservationFailure {
        /** 写后物理路径不再属于受检 Workspace。 */
        OUTSIDE_WORKSPACE,
        /** 写后属性、身份或正文无法可靠确认。 */
        CAPTURE_FAILED
    }

    /** 工作区可观察性是 Tool 静态能力，不允许根据结果正文猜测。 */
    enum WorkspaceMutationMode {
        /** 已证明不会写工作区。 */
        NONE,
        /** 成功结果必须携带精确 UTF-8 写前/写后收据。 */
        EXACT_TEXT,
        /** 无法可靠观察，执行前永久降低本轮完整性。 */
        UNOBSERVABLE
    }

    /** 规划阶段允许的 Tool 来源；该标记不能由模型参数或 Tool 名称推导。 */
    enum PlanAccess {
        /** 不允许在只读规划上下文出现。 */
        DISALLOWED,
        /** 只读调研能力。 */
        READ_ONLY,
        /** 仅限服务端受信的计划草稿/提案持久化能力。 */
        INTERNAL_MUTATION
    }

    /** 内核 Tool 的用户审批要求；默认收紧为必须审批，避免新 Tool 意外扩大权限。 */
    enum ApprovalRequirement {
        /** 可能触及用户或外部系统，必须走当前 Turn 的权限审批。 */
        USER_REQUIRED,
        /** 仅限受信内建状态操作；不代表获得工作区或外部系统权限。 */
        TRUSTED_INTERNAL
    }

    /**
     * 仅在 Java 内存中流转的精确修改收据；正文与摘要禁止进入模型上下文、日志和持久化 Tool 事实。
     */
    record MutationReceipt(Path path, boolean beforeExists, boolean afterExists,
                           String beforeText, String afterText, long beforeBytes, long afterBytes,
                           String beforeSha256, String afterSha256,
                           Path confinedWorkspaceRoot, String confinedRelativePath) {
        /** 收据在创建点自校验 UTF-8 长度和摘要，避免 tracker 接受适配器伪造的内容边界。 */
        public MutationReceipt {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            beforeText = beforeText == null ? "" : beforeText;
            afterText = afterText == null ? "" : afterText;
            if (!beforeExists && (!beforeText.isEmpty() || beforeBytes != 0)) {
                throw new IllegalArgumentException("missing preimage must be empty");
            }
            if (!afterExists && (!afterText.isEmpty() || afterBytes != 0)) {
                throw new IllegalArgumentException("missing postimage must be empty");
            }
            if (beforeBytes != beforeText.getBytes(StandardCharsets.UTF_8).length
                    || afterBytes != afterText.getBytes(StandardCharsets.UTF_8).length
                    || !sha256(beforeText).equals(beforeSha256)
                    || !sha256(afterText).equals(afterSha256)) {
                throw new IllegalArgumentException("mutation receipt integrity mismatch");
            }
            if ((confinedWorkspaceRoot == null) != (confinedRelativePath == null)) {
                throw new IllegalArgumentException("mutation receipt confinement mismatch");
            }
            if (confinedWorkspaceRoot != null) {
                confinedWorkspaceRoot = PathIdentities.normalized(confinedWorkspaceRoot);
                confinedRelativePath = confinedRelative(confinedRelativePath);
                if (!PathIdentities.normalized(confinedWorkspaceRoot.resolve(confinedRelativePath)).equals(
                        PathIdentities.normalized(path))) {
                    throw new IllegalArgumentException("mutation receipt path identity mismatch");
                }
            }
        }

        /** 使用固定 UTF-8 与 SHA-256 创建收据，调用方不重复实现摘要规则。 */
        public static MutationReceipt of(Path path, boolean beforeExists, String beforeText,
                                         boolean afterExists, String afterText) {
            return create(path, beforeExists, beforeText, afterExists, afterText, null, null);
        }

        /**
         * 仅由已在 IO 前后完成物理 containment 检查的适配器创建 Workspace 证明；
         * root/relative/path 三者在收据边界再次做规范身份一致性校验。
         */
        public static MutationReceipt confined(Path workspaceRoot, String relativePath, Path path,
                                                boolean beforeExists, String beforeText,
                                                boolean afterExists, String afterText) {
            return create(path, beforeExists, beforeText, afterExists, afterText,
                    workspaceRoot, relativePath);
        }

        /** 两个公开工厂共享同一 UTF-8 长度与摘要构造，避免安全字段随重载漂移。 */
        private static MutationReceipt create(Path path, boolean beforeExists, String beforeText,
                                               boolean afterExists, String afterText,
                                               Path workspaceRoot, String relativePath) {
            String before = beforeText == null ? "" : beforeText;
            String after = afterText == null ? "" : afterText;
            return new MutationReceipt(path, beforeExists, afterExists, before, after,
                    before.getBytes(StandardCharsets.UTF_8).length,
                    after.getBytes(StandardCharsets.UTF_8).length,
                    sha256(before), sha256(after), workspaceRoot, relativePath);
        }

        /** 默认 record 输出不得泄漏路径、正文或 hash。 */
        @Override
        public String toString() {
            return "MutationReceipt[beforeExists=" + beforeExists + ", afterExists=" + afterExists
                    + ", beforeBytes=" + beforeBytes + ", afterBytes=" + afterBytes + "]";
        }

        /** Workspace 证明只使用规范斜杠相对路径，禁止父级逃逸或绝对路径。 */
        private static String confinedRelative(String value) {
            if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.startsWith("/")
                    || value.matches("(?i)^[a-z]:.*")
                    || java.util.Arrays.asList(value.split("/", -1)).contains("..")) {
                throw new IllegalArgumentException("invalid mutation receipt relative path");
            }
            return value;
        }
    }

    /** Tool 调用绑定的路由种类只区分内建实现与 MCP 远端实现。 */
    enum RouteKind {
        /** App Server 当前版本内置且名称稳定的 Tool。 */
        BUILTIN,
        /** 由一个精确 MCP 服务和远端名称提供的 Tool。 */
        MCP
    }

    /**
     * Provider 看到 Tool 后必须随 batch 持久化的路由身份；catalog revision 与 access mode
     * 属于请求级事实，刻意不混入这个适配器描述。
     */
    record ToolBindingDescriptor(RouteKind routeKind, String localName, String serverId,
                                 String remoteName, String schemaHash, String routeHash) {
        /** 所有名称和 hash 均严格校验，执行阶段才能用 record equality 做 fail-closed 比较。 */
        public ToolBindingDescriptor {
            Objects.requireNonNull(routeKind, "routeKind");
            localName = ContractChecks.identifier(localName, "localName");
            serverId = bounded(serverId, "serverId", 256);
            remoteName = bounded(remoteName, "remoteName", 512);
            schemaHash = hash(schemaHash, "schemaHash");
            routeHash = hash(routeHash, "routeHash");
            if (routeKind == RouteKind.BUILTIN && !"builtin".equals(serverId)) {
                throw new IllegalArgumentException("Builtin binding requires stable server sentinel");
            }
        }

        /** MCP 名称允许协议字符但不允许控制字符或无界内容。 */
        private static String bounded(String value, String field, int maximum) {
            if (value == null || value.isBlank() || value.length() > maximum
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        }

        /** 路由与 schema 均使用固定 SHA-256，禁止弱摘要或宽松归一化。 */
        private static String hash(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        }
    }

    /** Builtin fallback 只用于稳定本地路由；MCP 必须由 adapter 提供规范化 hash。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 递归排序对象键但保留数组顺序，使 Builtin schema hash 不依赖 Map 实现或 JVM 进程。
     */
    private static void appendCanonicalJson(StringBuilder target, JsonValue value) {
        switch (value) {
            case JsonObject object -> {
                target.append('o').append(object.members().size()).append('{');
                object.members().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            appendToken(target, 'k', entry.getKey());
                            appendCanonicalJson(target, entry.getValue());
                        });
                target.append('}');
            }
            case JsonArray array -> {
                target.append('a').append(array.values().size()).append('[');
                array.values().forEach(item -> appendCanonicalJson(target, item));
                target.append(']');
            }
            case JsonText text -> appendToken(target, 's', text.value());
            case JsonNumber number -> appendToken(target, 'm', number.value().toString());
            case JsonBoolean bool -> target.append(bool.value() ? "b1" : "b0");
            case JsonNull ignored -> target.append('z');
        }
    }

    /** 长度前缀消除相邻值拼接歧义，且无需依赖 JSON 库的序列化配置。 */
    private static void appendToken(StringBuilder target, char type, String value) {
        target.append(type).append(value.length()).append(':').append(value);
    }

}
