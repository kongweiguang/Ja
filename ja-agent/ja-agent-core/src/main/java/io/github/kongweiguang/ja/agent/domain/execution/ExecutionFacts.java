// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.execution;

import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 与框架无关的执行证据；展示投影和工具专属路由由应用层负责，不纳入事实规则。
 */
public final class ExecutionFacts {
    /**
     * 仅作为事实类型的命名空间；这里不承载服务状态，也不创建第二个执行所有者。
     */
    private ExecutionFacts() {
    }

    /**
     * 校验跨存储可往返的标识，不强制 Ja 专有的消息、调用或供应商前缀。
     */
    private static String identity(String value) {
        return ContractChecks.identifier(value, "fact identity");
    }

    /**
     * 校验从零开始的 attempt 游标，避免负数代次混淆首次派发和恢复重试。
     */
    private static void nonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException("negative " + field);
        }
    }

    /**
     * 核验摘要必须为稳定 SHA-256，避免空证明被当作安全重试许可。
     */
    private static void fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    /**
     * 正文长度由传输和上下文策略限制，此处不另设执行预算并允许按字段声明空值。
     */
    private static String text(String value, boolean emptyAllowed) {
        return ContractChecks.text(value, "fact text", Integer.MAX_VALUE, emptyAllowed);
    }

    /**
     * 拒绝零或负数，避免轮次、请求序号、版本和窗口参数落入未初始化状态。
     */
    private static void positive(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("positive value required");
        }
    }

    /**
     * 区分正常对话生成和上下文摘要生成，便于按真实请求用途核对用量。
     */
    public enum UsagePurpose {
        /**
         * 为助手回复发起的模型请求。
         */
        ASSISTANT,
        /**
         * 为压缩上下文发起的摘要请求。
         */
        SUMMARY
    }

    /**
     * 明确区分已确认用量与无法确认的用量，避免把缺失数据折算为零。
     */
    public enum UsageCertainty {
        /**
         * 供应商返回了可保存的用量数据。
         */
        KNOWN,
        /**
         * 请求可能已计费，但没有可信的用量响应。
         */
        UNKNOWN
    }

    /**
     * 完整的助手消息，按原顺序保留供应商原生工具调用和不透明推理内容。
     *
     * @param messageId 消息标识
     * @param message 消息或诊断说明
     * @param publicText 公开文本
     * @param reasoningSummary 推理摘要
     * @param modelRound 模型调用轮次
     */
    public record AssistantMessage(String messageId,
                                   ModelMessage message,
                                   String publicText,
                                   String reasoningSummary,
                                   int modelRound) implements ExecutionFact {
        /**
         * 只接受助手角色的完整消息，避免把未完成的供应商响应记成历史答复。
         */
        public AssistantMessage {
            messageId = identity(messageId);
            Objects.requireNonNull(message, "message");
            if (message.role() != ModelRole.ASSISTANT) {
                throw new IllegalArgumentException("assistant role required");
            }
            publicText = text(publicText, true);
            if (reasoningSummary != null) {
                reasoningSummary = text(reasoningSummary, false);
            }
            positive(modelRound);
        }
    }

    /**
     * 单独记录可展示的推理摘要，使请求失败时仍可保留过程信息而不伪造最终答复。
     *
     * @param messageId 消息标识
     * @param text 文本内容
     * @param modelRound 模型调用轮次
     */
    public record ReasoningSummary(String messageId,
                                   String text,
                                   int modelRound) implements ExecutionFact {
        /**
         * 摘要拥有独立消息身份；即使取消导致没有最终答复，也不会丢失已观察到的信息。
         */
        public ReasoningSummary {
            messageId = identity(messageId);
            text = ExecutionFacts.text(text, false);
            positive(modelRound);
        }
    }

    /**
     * 保存供模型读取的工具结果消息，并与真实执行结果事实保持区分。
     *
     * @param messageId 消息标识
     * @param message 消息或诊断说明
     */
    public record ToolMessage(String messageId,
                              ModelMessage message) implements ExecutionFact {
        /**
         * 仅接受 TOOL 角色及工具结果内容，防止历史投影借此插入新的工具调用。
         */
        public ToolMessage {
            messageId = identity(messageId);
            Objects.requireNonNull(message, "message");
            if (message.role() != ModelRole.TOOL || message.content().stream()
                    .anyMatch(block -> !(block instanceof ToolResultContent))) {
                throw new IllegalArgumentException("tool result message required");
            }
        }
    }

    /**
     * 工具准备事实冻结校验后的参数、顺序、影响类型和执行绑定，再允许进入外部执行阶段。
     *
     * @param batchId 批次标识
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param arguments 工具调用参数
     * @param ordinal 当前项的顺序位置
     * @param sideEffect 工具副作用分类
     * @param binding 绑定
     * @param approvalRequired 是否需要审批
     */
    public record ToolPrepared(String batchId,
                               String callId,
                               String toolName,
                               JsonObject arguments,
                               int ordinal,
                               ToolSideEffect sideEffect,
                               ToolExecutionBinding binding,
                               boolean approvalRequired) implements ExecutionFact {
        /**
         * 绑定可为空以记录未知工具的可纠正失败；授权要求仍显式保存，恢复不得推测默认权限。
         */
        public ToolPrepared {
            batchId = identity(batchId);
            callId = identity(callId);
            toolName = text(toolName, false);
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(sideEffect, "sideEffect");
            if (ordinal < 0) {
                throw new IllegalArgumentException("negative tool ordinal");
            }
            if (binding != null && !binding.toolName().equals(toolName)) {
                throw new IllegalArgumentException("tool binding name mismatch");
            }
        }
    }

    /**
     * 记录调用已越过派发边界；此后缺少结果时必须视为状态未知，不能安全地盲目重放。
     *
     * @param callId 模型生成的工具调用标识
     * @param attempt 尝试次数
     */
    public record ToolStarted(String callId,
                              int attempt) implements ExecutionFact {
        /**
         * attempt 从零开始递增；每次 retry 都重新持久 Started，崩溃后可核验精确派发代次。
         */
        public ToolStarted {
            callId = identity(callId);
            nonNegative(attempt, "attempt");
        }
    }

    /**
     * 保存真实工具结果，与中间件生成的模型可见投影及应用展示信息分开。
     *
     * @param callId 模型生成的工具调用标识
     * @param state 当前状态
     * @param content 内容
     * @param error 是否为错误结果
     * @param artifactContent 产物内容
     */
    public record ToolResult(String callId,
                             ToolState state,
                             String content,
                             boolean error,
                             String artifactContent) implements ExecutionFact {
        /**
         * 只允许终态，并校验状态与错误标记一致，避免投影把失败改成成功或抹去结果内容。
         */
        public ToolResult {
            callId = identity(callId);
            Objects.requireNonNull(state, "state");
            if (state == ToolState.PREPARED || state == ToolState.RUNNING) {
                throw new IllegalArgumentException("tool result must be terminal");
            }
            if ((state == ToolState.SUCCEEDED) == error) {
                throw new IllegalArgumentException("tool outcome and error disagree");
            }
            content = text(content, true);
            artifactContent = text(artifactContent, true);
        }
    }

    /**
     * 仅保存授权决定和有效期；宿主界面的呈现由独立投影负责。
     *
     * @param approvalId 审批标识
     * @param callId 模型生成的工具调用标识
     * @param decision 决策
     * @param expiresAt 过期时间
     */
    public record Approval(String approvalId,
                           String callId,
                           ApprovalDecision decision,
                           Instant expiresAt) implements ExecutionFact {
        /**
         * 决定为空表示仍待审批，不会被解释成默认许可。
         */
        public Approval {
            approvalId = identity(approvalId);
            callId = identity(callId);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 保存不含密钥的模型请求身份供审计使用，不依赖 Ja 的配置格式或约定。
     *
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param api 模型 API 协议
     * @param upstreamModel 上游模型
     * @param requestedReasoning 调用方请求的推理等级
     * @param effectiveReasoning 实际生效的推理等级
     * @param contextWindowTokens 模型上下文窗口的 Token 容量
     * @param maxOutputTokens 单次模型输出的 Token 上限
     * @param attributes 属性集合
     */
    public record ModelRequestIdentity(String providerId,
                                       String modelId,
                                       String api,
                                       String upstreamModel,
                                       String requestedReasoning,
                                       String effectiveReasoning,
                                       int contextWindowTokens,
                                       int maxOutputTokens,
                                       JsonObject attributes) {
        /**
         * 允许自定义供应商属性，同时不把凭据或服务端点写入可持久化事实。
         */
        public ModelRequestIdentity {
            providerId = identity(providerId);
            modelId = identity(modelId);
            api = identity(api);
            upstreamModel = text(upstreamModel, false);
            if (requestedReasoning != null) {
                requestedReasoning = text(requestedReasoning, false);
            }
            if (effectiveReasoning != null) {
                effectiveReasoning = text(effectiveReasoning, false);
            }
            positive(contextWindowTokens);
            nonNegative(maxOutputTokens, "maxOutputTokens");
            if (maxOutputTokens > contextWindowTokens) {
                throw new IllegalArgumentException("invalid output window");
            }
            Objects.requireNonNull(attributes, "attributes");
        }
    }

    /**
     * 记录模型请求用量；响应丢失且无法确认计费时保留 UNKNOWN，而不是伪记为零。
     *
     * @param modelRequestId 一次真实模型请求的标识
     * @param usage 用量
     * @param modelRound 模型调用轮次
     * @param modelRequestOrdinal 模型请求顺序位置
     * @param purpose 用途
     * @param certainty 确定性
     * @param profile 配置档案
     * @param estimatedContextTokens 估算的上下文 Token 数
     */
    public record Usage(String modelRequestId,
                        ModelUsage usage,
                        int modelRound,
                        int modelRequestOrdinal,
                        UsagePurpose purpose,
                        UsageCertainty certainty,
                        ModelRequestIdentity profile,
                        Long estimatedContextTokens) implements ExecutionFact {
        /**
         * 本地上下文估算不能替代供应商计数，也不能把未知计费结算成零用量。
         */
        public Usage {
            modelRequestId = identity(modelRequestId);
            positive(modelRound);
            positive(modelRequestOrdinal);
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(certainty, "certainty");
            Objects.requireNonNull(profile, "profile");
            if ((certainty == UsageCertainty.KNOWN) == (usage == null)) {
                throw new IllegalArgumentException("usage certainty mismatch");
            }
            if (estimatedContextTokens != null && (estimatedContextTokens < 0
                                                   || certainty != UsageCertainty.UNKNOWN || purpose != UsagePurpose.ASSISTANT)) {
                throw new IllegalArgumentException("invalid context estimate");
            }
        }
    }

    /**
     * 带类型的恢复证据保持不透明，只交由注册该证据类型的工具恢复实现解释。
     *
     * @param callId 模型生成的工具调用标识
     * @param attempt 尝试次数
     * @param typeId 类型标识
     * @param schemaVersion 数据结构版本
     * @param payload 负载
     * @param fingerprint 当前内容或配置的校验指纹
     */
    public record RecoveryEvidence(String callId,
                                   int attempt,
                                   String typeId,
                                   int schemaVersion,
                                   JsonObject payload,
                                   String fingerprint) implements ExecutionFact {
        /**
         * 证据和派发尝试一一绑定；Core 不解释宿主路径或业务结果，也不复用旧 attempt 的证据。
         */
        public RecoveryEvidence {
            callId = identity(callId);
            nonNegative(attempt, "attempt");
            typeId = identity(typeId);
            positive(schemaVersion);
            Objects.requireNonNull(payload, "payload");
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid recovery fingerprint");
            }
        }
    }

    /**
     * 只在核验上一次动作确定未发生后写入；该事实同时授权下一 attempt 并保存核验摘要。
     *
     * @param callId 模型生成的工具调用标识
     * @param verifiedAttempt 已核验尝试
     * @param nextAttempt 下一次尝试的序号
     * @param verificationFingerprint 副作用核验证据的指纹
     */
    public record ToolRetryIntent(String callId,
                                  int verifiedAttempt,
                                  int nextAttempt,
                                  String verificationFingerprint) implements ExecutionFact {
        /**
         * 重试次数严格连续且必须由宿主核验指纹证明，Store 不接受跳号或未证明的 retry。
         */
        public ToolRetryIntent {
            callId = identity(callId);
            nonNegative(verifiedAttempt, "verifiedAttempt");
            if (nextAttempt != Math.addExact(verifiedAttempt, 1)) {
                throw new IllegalArgumentException("Tool retry attempt is not contiguous");
            }
            fingerprint(verificationFingerprint, "verificationFingerprint");
        }
    }

    /**
     * 恢复核验发现动作已完成；真实 ToolResult 与这条 verification 必须同事务提交。
     *
     * @param callId 模型生成的工具调用标识
     * @param attempt 尝试次数
     * @param verificationFingerprint 副作用核验证据的指纹
     */
    public record ToolRecoveryVerified(String callId,
                                       int attempt,
                                       String verificationFingerprint) implements ExecutionFact {
        /**
         * 标记唯一已恢复成功的派发代次，禁止只凭 ToolResult 反推出外部系统核验成功。
         */
        public ToolRecoveryVerified {
            callId = identity(callId);
            nonNegative(attempt, "attempt");
            fingerprint(verificationFingerprint, "verificationFingerprint");
        }
    }
}
