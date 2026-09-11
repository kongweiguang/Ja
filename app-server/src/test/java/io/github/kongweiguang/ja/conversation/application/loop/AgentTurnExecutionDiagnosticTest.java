// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证 Agent 内部失败诊断只暴露可关联源码的字节码位置。 */
final class AgentTurnExecutionDiagnosticTest {

    /** origin 必须忽略异常 message 与文件名，只保留首个 Ja 代码帧的类、方法和非负行号。 */
    @Test
    void extractsProjectOriginWithoutSensitiveFailureData() {
        IllegalArgumentException failure = new IllegalArgumentException(
                "C:\\private-workspace\\secret.txt receipt-body");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.List", "copyOf", "List.java", 99),
                new StackTraceElement("io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker",
                        "apply", "TurnChangeTracker.java", 118),
        });

        String origin = AgentTurnExecution.internalFailureOrigin(failure);

        assertEquals("io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker#apply:118",
                origin);
        assertFalse(origin.contains("private-workspace"));
        assertFalse(origin.contains("secret.txt"));
        assertFalse(origin.contains("receipt-body"));
        assertFalse(origin.contains("TurnChangeTracker.java"));
    }

    /** Native Image 未保留项目帧时使用稳定占位，不回退到 JDK 文件名或异常正文。 */
    @Test
    void returnsUnknownWhenProjectFrameIsUnavailable() {
        IllegalArgumentException failure = new IllegalArgumentException("private-message");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", -1),
        });

        assertEquals("UNKNOWN", AgentTurnExecution.internalFailureOrigin(failure));
    }

    /** 普通摘要失败必须保留 SUMMARY_FAILURE，只有明确的 Provider 根因才映射外部不可用。 */
    @Test
    void preservesSummaryFailureAndProviderUnavailableSemantics() {
        ContextException summaryFailure = new ContextException(
                ContextException.Code.SUMMARY_FAILURE, "summary failed");
        ContextException wrappedUnavailable = new ContextException(
                ContextException.Code.SUMMARY_FAILURE, "summary failed",
                new ModelPort.ModelUnavailableException("provider unavailable", null));

        assertEquals("SUMMARY_FAILURE", AgentTurnExecution.contextFailureCode(summaryFailure));
        assertEquals("MODEL_UNAVAILABLE", AgentTurnExecution.contextFailureCode(wrappedUnavailable));
    }
}
