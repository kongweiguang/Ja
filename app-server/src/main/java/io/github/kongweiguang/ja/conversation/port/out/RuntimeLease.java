// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 冻结一个 Turn 使用的配置代际、Tool 集合和 MCP 会话工厂，并拥有其释放动作。
 */
public final class RuntimeLease implements AutoCloseable {
    private final String generationId;
    private final ModelPort.ModelConfiguration model;
    private final AccessMode accessMode;
    private final TurnLimits limits;
    private final List<AgentTool> tools;
    private final TurnToolSessionFactory toolSessions;
    private final ToolProjectionLimits outputLimits;
    private final AgentPromptSession promptSession;
    private final ManagedAttachmentReader attachments;
    private final List<String> presentationSecrets;
    private final AutoCloseable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 复制 Tool 快照，并把附件读取端口绑定到同一配置租约；这样请求组装不会跨 Turn 借用漂移的能力或授权。
     */
    public RuntimeLease(String generationId, ModelPort.ModelConfiguration model,
                        AccessMode accessMode, TurnLimits limits, List<AgentTool> tools,
                        TurnToolSessionFactory toolSessions, ToolProjectionLimits outputLimits,
                        AgentPromptSession promptSession,
                        ManagedAttachmentReader attachments,
                        List<String> presentationSecrets,
                        AutoCloseable release) {
        if (generationId == null || !generationId.startsWith("cfg_")) {
            throw new IllegalArgumentException("invalid generationId");
        }
        this.generationId = generationId;
        this.model = Objects.requireNonNull(model, "model");
        this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
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

    /**
     * 返回配置解析阶段冻结且不得扩大的 Turn 预算。
     */
    public TurnLimits limits() {
        return limits;
    }

    /**
     * 返回准入时冻结的 Tool 集合，后续配置刷新不得改变它。
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
     * 返回与模型配置同代际冻结的 Tool 输出投影上限。
     */
    public ToolProjectionLimits outputLimits() {
        return outputLimits;
    }

    /** 返回当前 Turn 独占的 Prompt Session，环境、规则与 Skill 激活不得跨 Turn 共享。 */
    public AgentPromptSession promptSession() {
        return promptSession;
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
