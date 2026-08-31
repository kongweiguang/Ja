// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.middleware;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

/**
 * 仅由 Java Composition Root 静态注册的窄 Middleware SPI，不提供外部发现或动态加载入口。
 */
public interface AgentMiddleware {
    /** 在一次 Provider 调用前观察冻结请求；异常会按正常模型失败处理。 */
    default void beforeModel(ModelPort.ModelRequest request) { }

    /** 在一次 Provider 调用成功后逆序观察结果。 */
    default void afterModel(ModelPort.ModelRequest request, ModelPort.ModelOutcome outcome) { }

    /** 在 Tool 执行前正序决定继续或返回结构化拒绝。 */
    default ToolDecision beforeTool(ToolContext context) { return ToolDecision.allow(); }

    /** 在 Tool 得到结构化结果后逆序观察，不允许改写结果。 */
    default void afterTool(ToolContext context, AgentTool.ToolResult result) { }

    /** 只观察已持久化并重绑定 revision 的不可变事件；异常由链记录后忽略。 */
    default void onEventCommitted(TurnEvent event) { }

    /** beforeTool/afterTool 使用的稳定调用身份与审批模式。 */
    record ToolContext(AgentTool.Invocation invocation, AgentTool.ExecutionContext execution,
                       ApprovalGate approvals) { }

    /** 由 Loop 绑定内部 ApprovalBroker 和持久化回调，Middleware 不拥有外部注册能力。 */
    @FunctionalInterface
    interface ApprovalGate {
        /** 对当前 Tool 发起一次独立审批，并返回是否获准。 */
        boolean request();
    }

    /** beforeTool 唯一允许的控制结果：继续或结构化拒绝。 */
    record ToolDecision(boolean proceed, String code, String message) {
        /** 创建继续决策，避免 Middleware 自行拼装无意义字段。 */
        public static ToolDecision allow() { return new ToolDecision(true, null, null); }

        /** 创建拒绝决策；code/message 将作为普通 Tool Result 返回模型。 */
        public static ToolDecision deny(String code, String message) {
            return new ToolDecision(false, code, message);
        }
    }
}
