// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Plan 消费方定义的 Turn 生命周期端口；隐藏 Conversation 的具体协调器与准入实现。
 */
public interface PlanTurnPort {
    /** Plan adapter 需要原始终态区分自动推进与取消确认，ACL 不得提前把 CANCELLED 转成失败。 */
    void registerResumeContinuation(String turnId, Consumer<CompletionStage<TurnResult>> continuation);

    /** 清除尚未消费的 continuation。 */
    void clearResumeContinuation(String turnId);

    /** 以冻结请求启动一个隐藏 Plan Turn。 */
    Accepted startContinuation(StartRequest request, String instruction,
                               ConversationTurnEventSink sink,
                               NativeExecutionSnapshot nativeContext);

    /** 暂停当前 Plan Turn 并保留游标。 */
    CompletionStage<Void> suspendPlanRun(String turnId);

    /** 恢复同一 Turn 执行标识。 */
    Accepted resume(String turnId, long expectedThreadRevision,
                                ConversationTurnEventSink sink,
                                NativeExecutionSnapshot nativeContext);

    /** 取消当前 Turn 并等待 Conversation 返回权威终态。 */
    CompletionStage<TurnResult> cancel(String turnId);

    /** 读取当前 Plan/run 绑定的宿主快照，迟到 Turn 不得继承其它执行。 */
    NativeExecutionSnapshot runContext(String planId, String runId);

    /**
     * Plan 应用层使用的最小隐藏 Turn 请求；Conversation 的内部请求对象只在 ACL 内构造。
     */
    /**
     * @param threadId Thread 会话标识
     * @param turnId Turn 执行标识
     * @param workspaceId Workspace 工作区标识
     * @param workspaceRoot 工作区根目录
     * @param providerId Provider 标识
     * @param modelId 模型标识
     * @param reasoningLevel 推理级别
     * @param accessMode 访问模式
     * @param collaborationMode 协作模式
     * @param expectedThreadRevision 期望 Thread 版本
     * @param requestedAt 请求时间
     * @param planRevisionId Plan 版本 identity
     * @param runId Plan 执行标识
     */
    record StartRequest(String threadId,
                        String turnId,
                        String workspaceId,
                        Path workspaceRoot,
                        String providerId,
                        String modelId,
                        String reasoningLevel,
                        String accessMode,
                        String collaborationMode,
                        long expectedThreadRevision,
                        Instant requestedAt,
                        String planRevisionId,
                        String runId) {
        /** 冻结 Plan 执行身份；reasoningLevel 可为空以沿用模型默认，并由 Conversation 唯一准入入口解释。 */
        public StartRequest {
            require(threadId, "threadId");
            require(turnId, "turnId");
            require(workspaceId, "workspaceId");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            require(providerId, "providerId");
            require(modelId, "modelId");
            require(accessMode, "accessMode");
            require(collaborationMode, "collaborationMode");
            Objects.requireNonNull(requestedAt, "requestedAt");
            require(planRevisionId, "planRevisionId");
            require(runId, "runId");
            if (expectedThreadRevision < 0) {
                throw new IllegalArgumentException("invalid thread revision");
            }
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        }

        /** 统一隐藏 Plan 执行的身份构造，调用方不复制 Conversation enum 规则。 */
        public static StartRequest planExecution(PlanConversationPort.ThreadBinding thread,
                                                  PlanWorkspacePort.Binding workspace,
                                                  String turnId, Instant requestedAt,
                                                  String planRevisionId, String runId) {
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(workspace, "workspace");
            return new StartRequest(thread.threadId(), turnId, workspace.workspaceId(), workspace.root(),
                    thread.providerId(), thread.modelId(), thread.reasoningLevel(), thread.accessMode(),
                    thread.collaborationMode(), thread.revision(), requestedAt, planRevisionId, runId);
        }

        /** 校验跨域请求中的非空文本字段。 */
        private static void require(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }
    }

    /**
     * 保留 Conversation 权威终态，使 adapter 可在结算后分别决定推进或确认停止。
     * @param completion Conversation 返回的原始 Turn 终态
     */
    record Accepted(CompletionStage<TurnResult> completion) {
        /** accepted 必须保留真实 Turn 结果，不能让取消与执行失败共用一个成功投影。 */
        public Accepted {
            Objects.requireNonNull(completion, "completion");
        }
    }
}

