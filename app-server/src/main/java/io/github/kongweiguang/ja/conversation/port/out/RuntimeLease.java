// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持有一次 Provider 请求或一次 Tool batch 解析出的配置代际与资源，并拥有其释放动作。
 */
public final class RuntimeLease implements AutoCloseable {
    private final String generationId;
    private final ModelPort.ModelConfiguration model;
    private final AccessMode accessMode;
    private final CollaborationMode collaborationMode;
    private final TurnLimits limits;
    private final List<AgentTool> tools;
    private final TurnToolSessionFactory toolSessions;
    private final ToolProjectionLimits outputLimits;
    private final AgentPromptSession promptSession;
    private final ManagedAttachmentReader attachments;
    private final List<String> presentationSecrets;
    private final String toolCatalogDigest;
    private final String promptRevision;
    private final String requestedReasoning;
    private final AutoCloseable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 复制 Tool 快照，并把附件读取端口绑定到同一配置租约；这样请求组装不会跨 Turn 借用漂移的能力或授权。
     */
    public RuntimeLease(String generationId, ModelPort.ModelConfiguration model,
                        AccessMode accessMode, CollaborationMode collaborationMode,
                        TurnLimits limits, List<AgentTool> tools,
                        TurnToolSessionFactory toolSessions, ToolProjectionLimits outputLimits,
                        AgentPromptSession promptSession,
                        ManagedAttachmentReader attachments,
                        List<String> presentationSecrets,
                        String toolCatalogDigest,
                        String promptRevision,
                        String requestedReasoning,
                        AutoCloseable release) {
        if (generationId == null || !generationId.startsWith("cfg_")) {
            throw new IllegalArgumentException("invalid generationId");
        }
        this.generationId = generationId;
        this.model = Objects.requireNonNull(model, "model");
        this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
        this.collaborationMode = Objects.requireNonNull(collaborationMode, "collaborationMode");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        this.toolSessions = Objects.requireNonNull(toolSessions, "toolSessions");
        this.outputLimits = Objects.requireNonNull(outputLimits, "outputLimits");
        this.promptSession = Objects.requireNonNull(promptSession, "promptSession");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.presentationSecrets = List.copyOf(Objects.requireNonNull(presentationSecrets, "presentationSecrets"));
        if (this.presentationSecrets.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("presentationSecrets must contain only non-empty values");
        }
        this.toolCatalogDigest = requiredDigest(toolCatalogDigest);
        this.promptRevision = requiredRevision(promptRevision, "promptRevision");
        if (requestedReasoning != null
                && !requestedReasoning.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid requestedReasoning");
        }
        this.requestedReasoning = requestedReasoning;
        this.release = Objects.requireNonNull(release, "release");
    }

    /**
     * 返回不透明配置代际标识，禁止 conversation 推导配置文档内容。
     */
    public String generationId() {
        return generationId;
    }

    /**
     * 返回与租约同代际的模型配置，凭据只在租约关闭前有效。
     */
    public ModelPort.ModelConfiguration model() {
        return model;
    }

    /**
     * 返回配置与用户请求共同收紧后的最高访问能力。
     */
    public AccessMode accessMode() {
        return accessMode;
    }

    /** 返回本次 Provider 请求解析出的协作模式；该值不改变 Tool 权限策略。 */
    public CollaborationMode collaborationMode() {
        return collaborationMode;
    }

    /**
     * 返回本次请求解析出的预算；Operation 累计上限和绝对 Deadline 由调用方单独持有。
     */
    public TurnLimits limits() {
        return limits;
    }

    /**
     * 返回当前短租约的 Tool 集合，租约存活期间配置刷新不得改变它。
     */
    public List<AgentTool> tools() {
        return tools;
    }

    /**
     * 返回与本租约同代际的 MCP 会话工厂。
     */
    public TurnToolSessionFactory toolSessions() {
        return toolSessions;
    }

    /**
     * 返回与本次模型配置同代际的 Tool 输出投影上限。
     */
    public ToolProjectionLimits outputLimits() {
        return outputLimits;
    }

    /**
     * 返回当前 Turn 独占 Session 的独立能力视图；委托状态仍唯一，调用方不能依赖内部表示身份。
     */
    public AgentPromptSession promptSession() {
        return new PromptSessionView(promptSession);
    }

    /**
     * 返回与 Turn 身份配合使用的受管读取端口；调用方仍必须提交 threadId，不能仅凭附件 ID 读取。
     */
    public ManagedAttachmentReader attachments() {
        return attachments;
    }

    /**
     * 返回本 Turn 已知的敏感值快照，只允许安全投影器执行字面量替换，不能写入事件、日志或普通持久化。
     */
    public List<String> presentationSecrets() {
        return presentationSecrets;
    }

    /** 返回模型可见 Tool schema 的确定性摘要，防止 Resume 在能力漂移后继续执行。 */
    public String toolCatalogDigest() {
        return toolCatalogDigest;
    }

    /** 返回初始动态 Prompt 修订；规则或环境变化时 Resume 必须保持 SUSPENDED。 */
    public String promptRevision() {
        return promptRevision;
    }

    /**
     * 以真正发送的 Prompt revision 构造请求级审计 Profile；调用方必须在请求发送前持久化该值。
     */
    public ProviderRequestProfile requestProfile(String effectivePromptRevision) {
        String api = model.api().name().toLowerCase(java.util.Locale.ROOT);
        int contextWindow = Math.addExact(limits.maxInputTokens(), limits.maxOutputTokens());
        return new ProviderRequestProfile(model.providerId(), model.modelId(), api, model.model(),
                requestedReasoning, model.generation().reasoningLevel(), accessMode, collaborationMode,
                generationId,
                requiredRevision(effectivePromptRevision, "effectivePromptRevision"), toolCatalogDigest,
                contextWindow, limits.maxOutputTokens());
    }

    /** Prompt 修订只允许有界非空标识，避免恢复状态成为任意文本通道。 */
    private static String requiredRevision(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** Tool 摘要固定为 SHA-256 小写十六进制，禁止宽松归一化掩盖目录漂移。 */
    private static String requiredDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid toolCatalogDigest");
        }
        return value;
    }

    /** Prompt 能力视图不复制状态，只阻止租约内部字段引用直接逃逸。 */
    private static final class PromptSessionView implements AgentPromptSession {
        private final AgentPromptSession delegate;

        /** 视图只在租约内部创建，delegate 不参与公开对象身份。 */
        private PromptSessionView(AgentPromptSession delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /** Provider 准备仍由本租约唯一 Session 完成。 */
        @Override
        public PreparedPrompt prepare(String summary,
                                      List<io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec> tools) {
            return delegate.prepare(summary, tools);
        }

        /** Tool 前置规则保持当前租约的 revision 语义。 */
        @Override
        public ToolGuard beforeTool(AgentTool.Invocation invocation,
                                    io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect sideEffect,
                                    String batchRevision) {
            return delegate.beforeTool(invocation, sideEffect, batchRevision);
        }

        /** Tool 后刷新不能越过租约内的 Session owner。 */
        @Override
        public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
            delegate.afterTool(invocation, result);
        }

        /** Skill 激活继续作用于唯一 Session。 */
        @Override
        public SkillActivation activateSkill(SkillCatalog.SkillDocument document) {
            return delegate.activateSkill(document);
        }

        /** 稳定 Skill ID 校验不在能力视图中缓存。 */
        @Override
        public void validateSkillReferences(List<String> skillIds) {
            delegate.validateSkillReferences(skillIds);
        }

        /** 两阶段 Skill 替换必须由底层 Session 生成和提交。 */
        @Override
        public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
            return delegate.prepareSkillReplacement(skillIds);
        }

        /** Revision 始终反映底层 Session 的当前状态。 */
        @Override
        public String currentRevision() {
            return delegate.currentRevision();
        }

        /** 恢复快照只读取底层 Session 导出的稳定引用。 */
        @Override
        public List<io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.ActiveSkill>
        activeSkillReferences() {
            return delegate.activeSkillReferences();
        }

        /** Resume 恢复仍写入本租约唯一 Session。 */
        @Override
        public void restoreActiveSkills(String summary,
                List<io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.ActiveSkill> references) {
            delegate.restoreActiveSkills(summary, references);
        }
    }

    /**
     * 幂等释放底层代际，使失败和完成回调可以竞争关闭而不重复归还资源。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            release.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("runtime lease release failed", failure);
        }
    }
}
