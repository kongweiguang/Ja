// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.execution;

/**
 * 与执行游标一同持久提交的事实；事实用于恢复状态，不表示再次执行其中记录的业务动作。
 */
public sealed interface ExecutionFact permits ExecutionFacts.AssistantMessage, ExecutionFacts.ReasoningSummary,
        ExecutionFacts.ToolMessage, ExecutionFacts.ToolPrepared, ExecutionFacts.ToolStarted,
        ExecutionFacts.ToolResult, ExecutionFacts.Approval, ExecutionFacts.Usage, ExecutionFacts.RecoveryEvidence,
        ExecutionFacts.ToolRetryIntent, ExecutionFacts.ToolRecoveryVerified {
}
