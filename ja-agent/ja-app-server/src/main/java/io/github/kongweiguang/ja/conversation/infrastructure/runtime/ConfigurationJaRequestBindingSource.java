// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.ModelSelection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.codec.ReviewContextCodec;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.application.gateway.JaRequestBindingSource;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.WorkspaceBindingResolver;

import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 从 Ja 当前业务锚点重建 Core 请求运行时，不把路径、环境或 Ja 查询模型放进 Core。
 */
public final class ConfigurationJaRequestBindingSource implements JaRequestBindingSource {
    private static final String JA_TENANT = "ja.local";
    private static final String JA_USER = "user_local";

    private final ConversationProjectionRepository conversations;
    private final WorkspaceBindingResolver workspaces;
    private final Function<AgentIdentity, NativeExecutionSnapshot> nativeContexts;
    private final JaAdmissionParticipants pendingAdmissions;
    private final AdmissionParticipantFactory participants;
    private final AdmissionParticipantResolver admissionParticipants;

    /**
     * 依赖都来自 Ja composition；本地环境只通过显式查找，不从持久属性重建或 fallback。
     */
    public ConfigurationJaRequestBindingSource(
            ConversationProjectionRepository conversations,
            WorkspaceBindingResolver workspaces,
            Function<AgentIdentity, NativeExecutionSnapshot> nativeContexts,
            JaAdmissionParticipants pendingAdmissions,
            AdmissionParticipantFactory participants,
            AdmissionParticipantResolver admissionParticipants) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.nativeContexts = Objects.requireNonNull(nativeContexts, "nativeContexts");
        this.pendingAdmissions = Objects.requireNonNull(pendingAdmissions, "pendingAdmissions");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.admissionParticipants = Objects.requireNonNull(admissionParticipants, "admissionParticipants");
    }

    /**
     * 将同一组冻结 Ja 属性映射到 Core 请求；显式 turnId 区分普通执行与不伪造 Turn 的压缩身份。
     */
    private static TurnRuntimeRequest createRuntimeRequest(
            AgentIdentity identity, RequestAttributes selected, WorkspaceBindingResolver.WorkspaceBinding workspace,
            String turnId, NativeExecutionSnapshot nativeContext, Map<String, String> attributes) {
        return new TurnRuntimeRequest(identity.threadId(), turnId, workspace.root(), selected.workspaceId(),
                selected.providerId(), selected.modelId(), selected.reasoningLevel(), selected.accessMode(),
                selected.collaborationMode(), selected.origin(), selected.planRevisionId(), selected.runId(),
                selected.acceptedAt(), nativeContext, ReviewContextCodec.decode(attributes));
    }

    /**
     * 转成参与者契约并对照 Core 请求属性，防止临时 Task 元数据覆盖已持久化的执行身份。
     */
    private static AdmissionFacts validatePreparedFacts(RequestAttributes request, AgentIdentity identity,
                                                        JaAdmissionParticipants.PreparedAdmission prepared) {
        AdmissionFacts facts = Objects.requireNonNull(prepared, "prepared").facts();
        AdmissionFacts expected = request.admissionFacts(identity, facts.newThread());
        if (!sameRequestFacts(expected, facts)) {
            throw new IllegalStateException("prepared Ja admission facts do not match the Core request");
        }
        return facts;
    }

    /**
     * Task 注册项与 durable request attributes 必须逐字段一致；可选幂等身份成对为空时也必须安全比较。
     */
    static boolean sameRequestFacts(AdmissionFacts expected, AdmissionFacts actual) {
        return expected.identity().equals(actual.identity())
               && expected.workspaceId().equals(actual.workspaceId())
               && expected.origin() == actual.origin()
               && expected.method().equals(actual.method())
               && expected.acceptedAt().equals(actual.acceptedAt())
               && Objects.equals(expected.sourceMessageId(), actual.sourceMessageId())
               && Objects.equals(expected.parentTurnId(), actual.parentTurnId())
               && Objects.equals(expected.rootTurnId(), actual.rootTurnId())
               && Objects.equals(expected.planRevisionId(), actual.planRevisionId())
               && Objects.equals(expected.runId(), actual.runId())
               && Objects.equals(expected.operationKey(), actual.operationKey())
               && Objects.equals(expected.requestFingerprint(), actual.requestFingerprint())
               && expected.expectedThreadRevision() == actual.expectedThreadRevision();
    }

    /**
     * 只解析未恢复且无预注册 Child participant 的新准入；业务 resolver 决定 Task 参与哪些来源。
     */
    static Optional<JaAdmissionTransactionParticipant> resolveAdmissionParticipant(
            AdmissionFacts facts, AdmissionParticipantResolver resolver, boolean newAdmission) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(resolver, "resolver");
        if (!newAdmission) {
            return Optional.empty();
        }
        return Objects.requireNonNull(resolver.resolve(facts), "resolved admission participant");
    }

    /**
     * 准入前版本变化属于尚未执行的 CAS 冲突，让调用方重读快照；恢复仍保留原准入版本。
     */
    private static void validateAdmissionRevision(ThreadProjection thread, long expectedRevision) {
        if (thread.revision() != expectedRevision) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "Ja Thread revision changed before request admission");
        }
    }

    /**
     * Workspace 身份必须同时匹配只读投影与 workspace owner，不能由调用方传入路径替代。
     */
    private static void validateThreadIdentity(ThreadProjection thread, String workspaceId) {
        if (!thread.workspaceId().equals(workspaceId)) {
            throw new IllegalStateException("Ja Thread belongs to a different Workspace");
        }
    }

    /**
     * 首次 admission 的偏好必须是提交时 Thread 的明示偏好；恢复请求不因后来改偏好而换配置。
     */
    private static void validatePreferences(ThreadPreferences stored, ThreadPreferences selected) {
        if (!samePreferences(stored, selected)) {
            throw new IllegalStateException("Ja request preferences do not match the Thread snapshot");
        }
    }

    /** 显式执行覆盖是可信准入标记；恢复读取原快照，未知值不能放宽 Thread 默认校验。 */
    private static boolean runtimeOverridden(Map<String, String> attributes) {
        String value = attributes.getOrDefault("ja.runtimeOverridden", "false");
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalStateException("invalid runtime override admission marker");
        }
        return "true".equals(value);
    }

    /**
     * 比较会影响当前 Provider 与工具授权的偏好，标题来源不参与运行时选择。
     */
    private static boolean samePreferences(ThreadPreferences left, ThreadPreferences right) {
        return left.providerId().equals(right.providerId())
               && left.modelId().equals(right.modelId())
               && Objects.equals(left.reasoningLevel(), right.reasoningLevel())
               && left.accessMode() == right.accessMode()
               && left.collaborationMode() == right.collaborationMode();
    }

    /**
     * 新 Child 的显式 preference seed 必须等于本次请求绑定；不使用父 Thread 或默认配置回退。
     */
    private static void validateThreadInitialization(AdmissionFacts.ThreadInitialization initialization,
                                                     String workspaceId, ThreadPreferences selected) {
        if (!initialization.workspaceId().equals(workspaceId)
            || !samePreferences(initialization.preferences(), selected)) {
            throw new IllegalStateException("new Ja Thread metadata does not match its admitted request");
        }
    }

    /**
     * 新执行身份不能已经出现在同一个 Thread 的持久投影里。
     */
    private static void validateTurnNotAlreadyAdmitted(ThreadProjection thread, String turnId) {
        if (thread.turns().stream().anyMatch(turn -> turn.turnId().equals(turnId))) {
            throw new IllegalStateException("Ja Turn is already present without a recovered execution");
        }
    }

    /**
     * 新 Child 的 projection 尚未生成，Task prepared facts 本身仍须绑定唯一 turn identity。
     */
    private static void validateTurnNotAlreadyAdmitted(String turnId, AdmissionFacts facts) {
        if (!facts.identity().turnId().equals(turnId)) {
            throw new IllegalStateException("new Ja Thread facts do not match the requested Turn");
        }
    }

    /**
     * 恢复读模型必须与 AgentStore 的通用状态和 CAS 版本完全一致。
     */
    private static void validateRecoveredTurn(ThreadProjection thread, String turnId,
                                              AgentStore.ExecutionSnapshot recovered) {
        TurnProjection turn = thread.turns().stream().filter(value -> value.turnId().equals(turnId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("recovered Ja Turn is missing from its projection"));
        if (!turn.state().name().equals(recovered.state())
            || turn.mutationVersion() != recovered.mutationVersion()) {
            throw new IllegalStateException("Ja Turn projection differs from its AgentStore execution");
        }
    }

    /**
     * 恢复只允许显式保存的 Provider/model 选择，杜绝当前 Thread 偏好隐式替代原始请求。
     */
    private static void validateModelSelection(AgentRuntimeRequest request, RequestAttributes selected,
                                               Optional<AgentStore.ExecutionSnapshot> recovered) {
        Optional<ModelSelection> required = request.requiredModelSelection();
        if (recovered.isPresent() && required.isEmpty()) {
            throw new IllegalStateException("recovered Ja request has no persisted model selection");
        }
        required.ifPresent(selection -> {
            if (!selection.providerId().equals(selected.providerId())
                || !selection.modelId().equals(selected.modelId())) {
                throw new IllegalStateException("persisted model selection does not match Ja request attributes");
            }
        });
        request.modelOverride().ifPresent(override -> {
            if (!override.providerId().equals(selected.providerId())
                || !override.modelId().equals(selected.modelId())) {
                throw new IllegalStateException("model override does not match Ja request attributes");
            }
        });
    }

    /**
     * Ja 普通执行必须使用 Turn 身份，独立压缩则使用 Core 派生的专用身份，二者不互相映射。
     */
    static void validateJaIdentity(AgentIdentity identity, boolean compaction) {
        if (!JA_TENANT.equals(identity.tenantId()) || !JA_USER.equals(identity.userId())
            || !identity.threadId().startsWith("thr_")
            || compaction != identity.turnId().matches("compaction_[0-9a-f]{64}")
            || (!compaction && !identity.turnId().startsWith("turn_"))) {
            throw new IllegalArgumentException("Ja Agent identity is invalid");
        }
    }

    /**
     * 压缩是独立 Core execution，只有显式 compaction kind 才能使用专用身份和无 Turn 准入。
     */
    static void validateRequestKind(AgentRuntimeRequest.Kind kind, String method) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(method, "method");
        boolean compactionMethod = method.equals("thread/compact");
        if ((kind == AgentRuntimeRequest.Kind.COMPACTION) != compactionMethod) {
            throw new IllegalArgumentException("Ja request kind and method do not match");
        }
    }

    /**
     * 普通用户续写与 reask 复用显式历史项；Task 输入由 Core admission 同事务生成来源消息。
     */
    static void requireSourceMessage(TurnOrigin origin, String method, String sourceMessageId) {
        boolean required = origin == TurnOrigin.USER_CONTINUATION || method.equals("turn/reask");
        if (required && sourceMessageId == null) {
            throw new IllegalArgumentException("Ja request sourceMessageId is required");
        }
    }

    /**
     * 读取必需的有界 Ja 属性，避免异常把本地内容值回显给调用方。
     */
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank())
            {
                throw new IllegalArgumentException("missing Ja request attribute: " + key);
            }
        return value;
    }

    /**
     * 必需身份复用 core 稳定标识语法，而具体前缀由目标字段的值对象继续校验。
     */
    private static String identifier(Map<String, String> values, String key) {
        return ContractChecks.identifier(required(values, key), key);
    }

    /**
     * 可选属性只接受缺失或有效非空标识，不把空字符串视为未指定。
     */
    private static String optionalIdentifier(Map<String, String> values, String key) {
        return values.containsKey(key) ? ContractChecks.identifier(required(values, key), key) : null;
    }

    /**
     * 客户端幂等指纹仅随显式 operation ID 出现；内部调用不得伪造客户端指纹。
     */
    private static String optionalHash(Map<String, String> values, String key) {
        if (!values.containsKey(key)) {
            return null;
        }
        String value = required(values, key);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid Ja request fingerprint");
        }
        return value;
    }

    /**
     * 枚举只接受持久化名称，不通过大小写转换或别名恢复旧配置。
     */
    private static <T extends Enum<T>> T enumValue(Map<String, String> values, String key, Class<T> type) {
        String value = required(values, key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid Ja request attribute: " + key, invalid);
        }
    }

    /**
     * acceptedAt 必须是 Ja admission 原始 UTC/offset 时间值，不用当前时钟补缺。
     */
    private static Instant instant(Map<String, String> values, String key) {
        try {
            return Instant.parse(required(values, key));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("invalid Ja request attribute: " + key, invalid);
        }
    }

    /**
     * Thread revision 使用明确十进制非负整数，避免 NumberFormatException 回显属性值。
     */
    private static long revision(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("invalid Ja Thread revision");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid Ja Thread revision", invalid);
        }
    }

    /**
     * 普通执行绑定完整 Ja admission；独立压缩只校验 Thread 运行快照且绝不写入 Ja Turn 行。
     */
    @Override
    public Binding bind(AgentRuntimeRequest request, Optional<AgentStore.ExecutionSnapshot> recoveredExecution) {
        AgentRuntimeRequest current = Objects.requireNonNull(request, "request");
        Optional<AgentStore.ExecutionSnapshot> recovered =
                Objects.requireNonNull(recoveredExecution, "recoveredExecution");
        AgentIdentity identity = current.identity();
        Map<String, String> attributes = current.contextAttributes();
        RequestAttributes selected = RequestAttributes.read(attributes);
        boolean compaction = current.kind() == AgentRuntimeRequest.Kind.COMPACTION;
        validateJaIdentity(identity, compaction);
        validateRequestKind(current.kind(), selected.method());
        validateModelSelection(current, selected, recovered);

        Optional<ThreadProjection> projection = conversations.readThread(identity.threadId());
        JaAdmissionParticipants.PreparedAdmission prepared = compaction ? null
                : findPreparedAdmission(identity, selected, recovered);
        ThreadPreferences preferences = selected.preferences();
        if (projection.isPresent()) {
            ThreadProjection thread = projection.orElseThrow();
            validateThreadIdentity(thread, selected.workspaceId());
            if (recovered.isEmpty()) {
                validateAdmissionRevision(thread, selected.expectedThreadRevision());
                if (!runtimeOverridden(attributes)) {
                    validatePreferences(thread.preferences(), preferences);
                }
                if (!compaction) {
                    validateTurnNotAlreadyAdmitted(thread, identity.turnId());
                }
            } else if (compaction) {
                validateAdmissionRevision(thread, selected.expectedThreadRevision());
                validatePreferences(thread.preferences(), preferences);
            } else {
                validateRecoveredTurn(thread, identity.turnId(), recovered.orElseThrow());
            }
            if (prepared != null && prepared.facts().newThread().isPresent()) {
                throw new IllegalStateException("existing Ja Thread cannot carry new Thread metadata");
            }
        } else {
            if (recovered.isPresent()) {
                throw new IllegalStateException("recovered Ja Thread is missing");
            }
            if (compaction) {
                throw new IllegalStateException("compaction Ja Thread is missing");
            }
            if (prepared == null || prepared.facts().newThread().isEmpty()) {
                throw new IllegalStateException("new Ja Thread admission metadata is unavailable");
            }
            if (selected.expectedThreadRevision() != 0) {
                throw new IllegalStateException("new Ja Thread must start at revision zero");
            }
            AdmissionFacts.ThreadInitialization initialization = prepared.facts().newThread().orElseThrow();
            validateThreadInitialization(initialization, selected.workspaceId(), preferences);
            validateTurnNotAlreadyAdmitted(identity.turnId(), prepared.facts());
        }

        WorkspaceBindingResolver.WorkspaceBinding workspace = workspaces.requireOpenWorkspace(selected.workspaceId());
        if (!workspace.workspaceId().equals(selected.workspaceId())) {
            throw new IllegalStateException("Ja Workspace lookup returned a different identity");
        }
        NativeExecutionSnapshot nativeContext = nativeContexts.apply(identity);
        if (compaction) {
            TurnRuntimeRequest runtimeRequest = createRuntimeRequest(identity, selected, workspace, null, nativeContext,
                    attributes);
            return new Binding(runtimeRequest, AgentStoreTransactionParticipant.NONE);
        }
        AdmissionFacts facts = prepared == null ? selected.admissionFacts(identity, Optional.empty())
                : validatePreparedFacts(selected, identity, prepared);
        AgentStoreTransactionParticipant transactionParticipant = transactionParticipant(
                identity, facts, prepared, recovered.isEmpty());
        TurnRuntimeRequest runtimeRequest = createRuntimeRequest(identity, selected, workspace,
                identity.turnId(), nativeContext, attributes);
        return new Binding(runtimeRequest, transactionParticipant);
    }

    /**
     * 新 Child 的业务事务只能由 TaskCoordinator 在明确身份下预注册，解析过程可重复读取注册值。
     */
    private JaAdmissionParticipants.PreparedAdmission findPreparedAdmission(
            AgentIdentity identity, RequestAttributes request, Optional<AgentStore.ExecutionSnapshot> recovered) {
        if (request.origin() != TurnOrigin.CHILD_TASK || recovered.isPresent()) {
            return null;
        }
        JaAdmissionParticipants.PreparedAdmission prepared = pendingAdmissions.find(identity).orElseThrow(
                () -> new IllegalStateException("Child admission participant is unavailable"));
        validatePreparedFacts(request, identity, prepared);
        return prepared;
    }

    /**
     * 保留 admission 两阶段类型，让 Store 在业务锚点、通用投影和 Task 事实间建立真实事务顺序。
     */
    private JaAdmissionTransactionParticipant transactionParticipant(
            AgentIdentity identity, AdmissionFacts facts, JaAdmissionParticipants.PreparedAdmission prepared,
            boolean newAdmission) {
        AgentStoreTransactionParticipant host = Objects.requireNonNull(participants.create(facts), "participant");
        JaAdmissionTransactionParticipant business = prepared != null
                ? prepared.transactionParticipant()
                : resolveAdmissionParticipant(facts, admissionParticipants, newAdmission).orElse(null);
        return new JaAdmissionTransactionParticipant() {
            /** 只在 ADMISSION 建立 Ja 锚点；SQL 故障交由外层 Ja adapter 包装，运行时失败保持原样。 */
            @Override
            public void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException {
                validateIdentity(transaction);
                if (transaction.kind() == AgentStore.TransactionKind.ADMISSION) {
                    host.apply(transaction);
                    if (business != null) {
                        business.beforeProjection(transaction);
                    }
                }
            }

            /** Task 投影依赖已插入的 user message；只传递 SQL 受检故障，不扩大内部事务异常契约。 */
            @Override
            public void afterProjection(AgentStoreTransactionContext transaction) throws SQLException {
                validateIdentity(transaction);
                if (transaction.kind() == AgentStore.TransactionKind.ADMISSION && business != null) {
                    business.afterProjection(transaction);
                }
            }

            /** JaAgentStore 负责把两个阶段放在 Core 投影两侧；独立调用会破坏原子顺序。 */
            @Override
            public void apply(AgentStoreTransactionContext transaction) {
                validateIdentity(transaction);
                if (transaction.kind() == AgentStore.TransactionKind.ADMISSION) {
                    throw new IllegalStateException("Ja admission requires staged transaction ordering");
                }
            }

            /** 每个阶段都校验冻结身份，避免把同一个参与者误挂到其他执行提交。 */
            private void validateIdentity(AgentStoreTransactionContext transaction) {
                Objects.requireNonNull(transaction, "transaction");
                AgentStore.ExecutionKey key = transaction.execution();
                if (!identity.tenantId().equals(key.conversation().tenantId())
                    || !identity.threadId().equals(key.conversation().threadId())
                    || !identity.turnId().equals(key.turnId())) {
                    throw new IllegalArgumentException("Ja transaction identity does not match its runtime binding");
                }
            }
        };
    }

    /**
     * 请求属性严格按当前 Ja schema 读取；缺失字段失败关闭，不猜模型、权限或执行来源。
     *
     * @param workspaceId 工作区稳定标识
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param reasoningLevel 模型推理等级
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     * @param origin 来源分类
     * @param method 方法
     * @param acceptedAt 受理时间
     * @param sourceMessageId 来源消息标识
     * @param parentTurnId 父执行标识
     * @param rootTurnId 根任务执行标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param operationKey 客户端操作标识
     * @param requestFingerprint 请求指纹
     * @param expectedThreadRevision 预期对话修订版本
     */
    private record RequestAttributes(String workspaceId,
                                     String providerId,
                                     String modelId,
                                     String reasoningLevel,
                                     AccessMode accessMode,
                                     CollaborationMode collaborationMode,
                                     TurnOrigin origin,
                                     String method,
                                     Instant acceptedAt,
                                     String sourceMessageId,
                                     String parentTurnId,
                                     String rootTurnId,
                                     String planRevisionId,
                                     String runId,
                                     String operationKey,
                                     String requestFingerprint,
                                     long expectedThreadRevision) {
        /**
         * 读取 request snapshot 中 Ja 负责的稳定元数据，不把其它扩展命名空间转成 Ja 身份。
         */
        private static RequestAttributes read(Map<String, String> values) {
            Objects.requireNonNull(values, "contextAttributes");
            String workspaceId = identifier(values, "ja.workspaceId");
            String providerId = identifier(values, "ja.providerId");
            String modelId = identifier(values, "ja.modelId");
            String reasoning = optionalIdentifier(values, "ja.reasoningLevel");
            AccessMode access = enumValue(values, "ja.accessMode", AccessMode.class);
            CollaborationMode collaboration = enumValue(values, "ja.collaborationMode", CollaborationMode.class);
            TurnOrigin origin = enumValue(values, "ja.origin", TurnOrigin.class);
            String method = required(values, "ja.method");
            Instant acceptedAt = instant(values, "ja.acceptedAt");
            String sourceMessageId = optionalIdentifier(values, "ja.sourceMessageId");
            String parentTurnId = optionalIdentifier(values, "ja.parentTurnId");
            String rootTurnId = optionalIdentifier(values, "ja.rootTurnId");
            String planRevisionId = optionalIdentifier(values, "ja.planRevisionId");
            String runId = optionalIdentifier(values, "ja.runId");
            String operationKey = optionalIdentifier(values, "ja.operationKey");
            String requestFingerprint = optionalHash(values, "ja.requestFingerprint");
            long expectedThreadRevision = revision(values, "ja.expectedThreadRevision");
            requireSourceMessage(origin, method, sourceMessageId);
            return new RequestAttributes(workspaceId, providerId, modelId, reasoning, access, collaboration,
                    origin, method, acceptedAt, sourceMessageId, parentTurnId, rootTurnId,
                    planRevisionId, runId, operationKey, requestFingerprint,
                    expectedThreadRevision);
        }

        /**
         * 只从 Ja 值构造新 Thread 初始化偏好，seed 标题及创建时间由 Task admission 显式提供。
         */
        private ThreadPreferences preferences() {
            return new ThreadPreferences(providerId, modelId, reasoningLevel, accessMode, collaborationMode,
                    ThreadPreferences.TitleSource.PLACEHOLDER);
        }

        /**
         * 构造完全由持久 request attributes 声明的事务身份；Thread seed 只能来自临时 Task snapshot。
         */
        private AdmissionFacts admissionFacts(AgentIdentity identity,
                                              Optional<AdmissionFacts.ThreadInitialization> newThread) {
            return new AdmissionFacts(identity, workspaceId, origin, method, acceptedAt, sourceMessageId,
                    parentTurnId, rootTurnId, planRevisionId, runId, operationKey,
                    requestFingerprint, expectedThreadRevision, newThread);
        }

    }
}
