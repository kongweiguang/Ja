// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 准入后由 application 独占的 Turn Operation；请求环境由 runtimeFactory 在安全点短租。
 */
public record TurnExecutionPlan(TurnOperation operation, RequestView requestView,
                                RequestRuntimeFactory runtimeFactory, TurnChangeTracker changeTracker) {

    /**
     * 新 Turn 默认创建 complete tracker；稳定 Operation 与请求视图在构造边界即分离，
     * 后续热更新只能替换 RequestView，不能重置累计预算或绝对 Deadline。
     */
    public TurnExecutionPlan(String threadId, String turnId, Path workspaceRoot, UserContent content,
                             ModelPort.ModelConfiguration model, AccessMode accessMode, TurnLimits limits,
                             Instant requestedAt, String workspaceId, long initialThreadRevision,
                             long initialTurnMutationVersion, String initialSummary,
                             AgentPromptSession promptSession, QueuedInputBoundary queuedInputBoundary,
                             ManagedAttachmentReader attachments, List<AgentTool> tools, String configRevision,
                             TurnToolSessionFactory toolSessions, ToolProjectionLimits outputLimits,
                             List<String> presentationSecrets, Instant deadlineAt,
                             RequestRuntimeFactory runtimeFactory) {
        this(threadId, turnId, workspaceRoot, content, TurnOrigin.USER, model, accessMode, limits,
                requestedAt, workspaceId,
                initialThreadRevision, initialTurnMutationVersion, initialSummary, promptSession,
                queuedInputBoundary, attachments, tools, configRevision, toolSessions, outputLimits,
                presentationSecrets, deadlineAt, runtimeFactory, TurnChangeTracker.fresh(workspaceRoot));
    }

    /**
     * 内部 Turn 通过显式 origin 接受 null content；普通构造器始终固定 USER，避免生产调用方
     * 仅凭 null 猜测来源或绕过消息准入。
     */
    public TurnExecutionPlan(String threadId, String turnId, Path workspaceRoot, UserContent content,
                             TurnOrigin origin, ModelPort.ModelConfiguration model, AccessMode accessMode,
                             TurnLimits limits, Instant requestedAt, String workspaceId,
                             long initialThreadRevision, long initialTurnMutationVersion, String initialSummary,
                             AgentPromptSession promptSession, QueuedInputBoundary queuedInputBoundary,
                             ManagedAttachmentReader attachments, List<AgentTool> tools, String configRevision,
                             TurnToolSessionFactory toolSessions, ToolProjectionLimits outputLimits,
                             List<String> presentationSecrets, Instant deadlineAt,
                             RequestRuntimeFactory runtimeFactory, TurnChangeTracker changeTracker) {
        this(new TurnOperation(threadId, turnId, workspaceRoot, content, origin, limits, requestedAt, workspaceId,
                        initialThreadRevision, initialTurnMutationVersion, initialSummary,
                        queuedInputBoundary, deadlineAt),
                new RequestView(model, accessMode, promptSession, attachments, tools, configRevision,
                        toolSessions, outputLimits, presentationSecrets),
                runtimeFactory, changeTracker);
    }

    /** 请求计划必须总能在安全点解析环境；缺失 factory 不再退回准入时视图。 */
    public TurnExecutionPlan {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestView, "requestView");
        Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        Objects.requireNonNull(changeTracker, "changeTracker");
    }

    /**
     * 合并内建与 MCP Tool 时拒绝重名，执行阶段不能依赖集合插入顺序选实现。
     */
    public static Map<String, AgentTool> createToolCatalog(List<AgentTool> source) {
        Map<String, AgentTool> result = new HashMap<>();
        for (AgentTool tool : source) {
            Objects.requireNonNull(tool, "tool");
            if (result.putIfAbsent(tool.spec().name(), tool) != null) {
                throw new IllegalArgumentException("duplicate Tool name");
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 只替换数据库准入回执，保持 Operation 身份、累计预算与绝对 Deadline 不变。
     */
    public TurnExecutionPlan withAdmissionReceipt(long revision, long turnMutationVersion) {
        return new TurnExecutionPlan(operation.withAdmissionReceipt(revision, turnMutationVersion),
                requestView, runtimeFactory, changeTracker);
    }

    /**
     * Provider 或 Tool 安全点打开最新环境；返回视图只替换请求环境，稳定 Operation 始终沿用当前实例。
     * 工厂返回值的 release 所有权转移给新 RequestRuntime，由调用方 try-with-resources 关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public RequestRuntime openRequestRuntime(TurnExecutionState.Common common, String promptSummary) {
        RequestRuntime opened = Objects.requireNonNull(runtimeFactory.open(common, promptSummary), "requestRuntime");
        TurnExecutionPlan rebound = new TurnExecutionPlan(operation, opened.plan().requestView(),
                runtimeFactory, changeTracker);
        return new RequestRuntime(rebound, opened.profile(), opened.release());
    }

    /**
     * 每次返回独立能力视图但委托同一 Turn owner；调用方可以推进 Prompt 状态，
     * 却不能取得 record 内部保存的表示对象并依赖其身份。
     */
    public AgentPromptSession promptSession() {
        return new PromptSessionView(requestView.promptSession());
    }

    /** 稳定身份读取器避免调用方感知内部 Operation 分组。 */
    public String threadId() { return operation.threadId(); }
    /** 稳定身份读取器避免调用方感知内部 Operation 分组。 */
    public String turnId() { return operation.turnId(); }
    /** Workspace 归属在整个 Operation 内不允许热切换。 */
    public Path workspaceRoot() { return operation.workspaceRoot(); }
    /** 原始输入属于准入事实，环境刷新不得替换。 */
    public UserContent content() { return operation.content(); }
    /** Turn 来源决定 content 是否存在，内部来源不能产生 USER message。 */
    public TurnOrigin origin() { return operation.origin(); }
    /** 累计轮次和 Tool 数使用准入时硬上限，环境刷新不得重置预算。 */
    public TurnLimits limits() { return operation.limits(); }
    /** 原始请求时间用于审计和恢复，不随请求安全点变化。 */
    public Instant requestedAt() { return operation.requestedAt(); }
    /** Workspace identity 属于稳定 Operation。 */
    public String workspaceId() { return operation.workspaceId(); }
    /** 初始仓储游标只由 admission 回执替换一次。 */
    public long initialThreadRevision() { return operation.initialThreadRevision(); }
    /** 初始 Turn CAS 游标只由 admission 回执替换一次。 */
    public long initialTurnMutationVersion() { return operation.initialTurnMutationVersion(); }
    /** 恢复摘要是 Operation 起点，不代表后续请求 Prompt。 */
    public String initialSummary() { return operation.initialSummary(); }
    /** 输入队列属于 Operation owner，不能被请求环境刷新替换。 */
    public QueuedInputBoundary queuedInputBoundary() { return operation.queuedInputBoundary(); }
    /** 绝对 Deadline 属于 Operation，任何请求租约都只能收紧。 */
    public Instant deadlineAt() { return operation.deadlineAt(); }
    /** Provider 配置只从当前请求视图读取。 */
    public ModelPort.ModelConfiguration model() { return requestView.model(); }
    /** 权限只从当前请求视图读取。 */
    public AccessMode accessMode() { return requestView.accessMode(); }
    /** 附件读取能力只在当前请求租约内有效。 */
    public ManagedAttachmentReader attachments() { return requestView.attachments(); }
    /** Tool 目录只在当前请求租约内有效。 */
    public List<AgentTool> tools() { return requestView.tools(); }
    /** 配置代际只描述当前请求。 */
    public String configRevision() { return requestView.configRevision(); }
    /** MCP session factory 只在当前请求或 Tool batch 租约内使用。 */
    public TurnToolSessionFactory toolSessions() { return requestView.toolSessions(); }
    /** Tool 输出限制跟随当前请求环境。 */
    public ToolProjectionLimits outputLimits() { return requestView.outputLimits(); }
    /** 脱敏材料只在当前请求租约存活期间可用。 */
    public List<String> presentationSecrets() { return requestView.presentationSecrets(); }

    /** 请求级租约同时携带本次执行视图、Profile 与唯一释放动作。 */
    public record RequestRuntime(TurnExecutionPlan plan, ProviderRequestProfile profile,
                                 AutoCloseable release) implements AutoCloseable {
        /** 不允许创建缺失释放责任的请求环境。 */
        public RequestRuntime {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(release, "release");
        }

        /** 统一包装 checked close，避免资源释放异常逃逸成无法归类的类型。 */
        @Override public void close() {
            try {
                release.close();
            } catch (Exception failure) {
                throw new IllegalStateException("request runtime release failed", failure);
            }
        }
    }

    /**
     * Prompt Session 视图只转发类型化能力，不复制状态机；私有 delegate 永不作为字段访问器返回。
     */
    private static final class PromptSessionView implements AgentPromptSession {
        private final AgentPromptSession delegate;

        /** 所有权视图仅在 TurnExecutionPlan 内创建，外部不能注入或取回 delegate。 */
        private PromptSessionView(AgentPromptSession delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /** Provider 准备仍在唯一 Session 上串行推进。 */
        @Override
        public PreparedPrompt prepare(String summary, List<ToolSpec> tools) {
            return delegate.prepare(summary, tools);
        }

        /** Tool 前置规则与 revision 检查必须观察同一 Session。 */
        @Override
        public ToolGuard beforeTool(AgentTool.Invocation invocation, ToolSideEffect sideEffect,
                                    String batchRevision) {
            return delegate.beforeTool(invocation, sideEffect, batchRevision);
        }

        /** Tool 结果刷新继续发布到同一 Session owner。 */
        @Override
        public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
            delegate.afterTool(invocation, result);
        }

        /** Skill 激活不能因能力视图重建而分裂状态。 */
        @Override
        public SkillActivation activateSkill(io.github.kongweiguang.ja.conversation.port.out.SkillCatalog.SkillDocument document) {
            return delegate.activateSkill(document);
        }

        /** 引用校验沿用当前配置代际的唯一 Session。 */
        @Override
        public void validateSkillReferences(List<String> skillIds) {
            delegate.validateSkillReferences(skillIds);
        }

        /** 两阶段 Skill 替换必须返回底层 Session 生成的同一候选。 */
        @Override
        public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
            return delegate.prepareSkillReplacement(skillIds);
        }

        /** Revision 始终读取唯一 owner 的最新值。 */
        @Override
        public String currentRevision() {
            return delegate.currentRevision();
        }

        /** 恢复事实只导出稳定 Skill ID，不泄漏内部集合。 */
        @Override
        public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
            return delegate.activeSkillReferences();
        }

        /** Resume 继续在唯一 Session 上恢复 Skill 正文。 */
        @Override
        public void restoreActiveSkills(String summary, List<TurnExecutionState.ActiveSkill> references) {
            delegate.restoreActiveSkills(summary, references);
        }
    }

    /**
     * TurnOperation 只保存跨请求必须稳定的身份、累计预算、权威游标和绝对 Deadline。
     */
    public record TurnOperation(String threadId, String turnId, Path workspaceRoot, UserContent content,
                                TurnOrigin origin,
                                TurnLimits limits, Instant requestedAt, String workspaceId,
                                long initialThreadRevision, long initialTurnMutationVersion,
                                String initialSummary, QueuedInputBoundary queuedInputBoundary,
                                Instant deadlineAt) {
        /** 操作事实必须在准入时完整，后续请求不得用环境刷新覆盖。 */
        public TurnOperation {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            origin = Objects.requireNonNull(origin, "origin");
            if (origin.internal() != (content == null)) {
                throw new IllegalArgumentException("Turn content does not match origin");
            }
            limits = Objects.requireNonNull(limits, "limits");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            if (!workspaceId.startsWith("ws_")) throw new IllegalArgumentException("invalid workspaceId");
            if (initialThreadRevision < 0 || initialTurnMutationVersion < 0) {
                throw new IllegalArgumentException("turn revisions must be non-negative");
            }
            initialSummary = ContractChecks.text(initialSummary, "initialSummary", 4_000_000, true);
            queuedInputBoundary = Objects.requireNonNull(queuedInputBoundary, "queuedInputBoundary");
            deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        }

        /** Admission 只推进两个 CAS 游标，其余 Operation 事实保持原值。 */
        private TurnOperation withAdmissionReceipt(long revision, long turnMutationVersion) {
            return new TurnOperation(threadId, turnId, workspaceRoot, content, origin, limits, requestedAt, workspaceId,
                    revision, turnMutationVersion, initialSummary, queuedInputBoundary, deadlineAt);
        }
    }

    /**
     * RequestView 保存单次安全点解析出的 Provider、Prompt、Tool 与脱敏能力，不承载恢复游标。
     */
    public record RequestView(ModelPort.ModelConfiguration model, AccessMode accessMode,
                              AgentPromptSession promptSession, ManagedAttachmentReader attachments,
                              List<AgentTool> tools, String configRevision,
                              TurnToolSessionFactory toolSessions, ToolProjectionLimits outputLimits,
                              List<String> presentationSecrets) {
        /** 复制集合并校验能力完整性，防止租约外部修改已组装请求。 */
        public RequestView {
            model = Objects.requireNonNull(model, "model");
            accessMode = Objects.requireNonNull(accessMode, "accessMode");
            promptSession = new PromptSessionView(Objects.requireNonNull(promptSession, "promptSession"));
            attachments = Objects.requireNonNull(attachments, "attachments");
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            if (configRevision == null || !configRevision.startsWith("cfg_")) {
                throw new IllegalArgumentException("invalid configRevision");
            }
            toolSessions = Objects.requireNonNull(toolSessions, "toolSessions");
            outputLimits = Objects.requireNonNull(outputLimits, "outputLimits");
            presentationSecrets = List.copyOf(Objects.requireNonNull(presentationSecrets, "presentationSecrets"));
            if (presentationSecrets.stream().anyMatch(value -> value == null || value.isEmpty())) {
                throw new IllegalArgumentException("presentationSecrets must contain only non-empty values");
            }
            createToolCatalog(tools);
        }
    }

    /** 生产实现每次调用都必须重读 ThreadPreferences 并解析全新短租约。 */
    @FunctionalInterface
    public interface RequestRuntimeFactory {
        /** common 只提供 active Skill IDs 与 Deadline；不得用它恢复旧配置。 */
        RequestRuntime open(TurnExecutionState.Common common, String promptSummary);
    }

    /** 返回纯文本派生视图，模型历史仍使用完整结构化 content。 */
    public String userInput() {
        return content() == null ? "" : content().text();
    }

    /** 返回附件 ID 派生视图，避免执行器维护第二份可能漂移的列表。 */
    public List<String> attachmentIds() {
        return content() == null ? List.of() : content().attachmentIds();
    }

    /**
     * 日志只保留 Turn 身份和非敏感执行边界；默认 record 输出会泄漏用户输入及 presentationSecrets。
     */
    @Override
    public String toString() {
        return "TurnExecutionPlan[threadId=" + threadId() + ", turnId=" + turnId()
                + ", workspaceId=" + workspaceId() + ", model=" + model()
                + ", accessMode=" + accessMode() + ", limits=" + limits()
                + ", requestedAt=" + requestedAt() + ", attachmentCount=" + attachmentIds().size()
                + ", toolCount=" + tools().size() + ", configRevision=" + configRevision()
                + ", outputLimits=" + outputLimits() + ", userInput=<redacted>, presentationSecrets=<redacted>]";
    }
}
