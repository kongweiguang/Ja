// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证公共 Tool 边界只传播稳定错误码与脱敏操作指引。 */
class ToolSupportTest {
    @TempDir Path temporary;

    /** 平台访问拒绝必须与普通 IO 区分，使模型知道继续换路径或申请权限而不是盲重试。 */
    @Test
    void classifiesAccessDeniedWithoutLeakingNativeMessage() {
        AgentTool.ToolResult result = execute(new AccessDeniedException("C:\\Users\\secret\\denied.txt"));

        assertSafeFailure(result, "tool_access_denied", "secret");
    }

    /** 未细分的 IO 故障使用稳定通用码，原始驱动器、路径和平台 reason 不得进入上下文。 */
    @Test
    void classifiesIoFailureWithoutLeakingNativeMessage() {
        AgentTool.ToolResult result = execute(new IOException("C:\\private\\locked.txt: sharing violation"));

        assertSafeFailure(result, "tool_io_failed", "sharing violation");
    }

    /** 未知异常保持最后一道失败兜底，并明确提示不要用相同参数无限重试。 */
    @Test
    void classifiesUnknownFailureWithoutLeakingExceptionMessage() {
        AgentTool.ToolResult result = execute(new IllegalStateException("provider secret state"));

        assertSafeFailure(result, "tool_execution_failed", "provider secret state");
    }

    /** 通过真实公共 execute 边界触发异常映射，避免只测试内部枚举或文案常量。 */
    private AgentTool.ToolResult execute(Exception failure) {
        AgentTool tool = new FaultTool(failure);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_fixture", "fault", JsonObject.empty(), 0);
        AgentTool.ExecutionContext context = new AgentTool.ExecutionContext(
                "thr_fixture", "turn_fixture", temporary.toAbsolutePath(), AccessMode.FULL_ACCESS,
                "cfg_fixture", Instant.now().plusSeconds(30), "ws_fixture");
        return tool.execute(invocation, context, CancellationToken.none()).toCompletableFuture().join();
    }

    /** 每类失败必须终态失败、正文非空、机器码稳定，且不能回显原始异常中的敏感片段。 */
    private static void assertSafeFailure(AgentTool.ToolResult result, String code, String sensitiveText) {
        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals(code, result.errorCode());
        assertFalse(result.content().isBlank());
        assertFalse(result.content().contains(sensitiveText));
        assertTrue(result.content().matches("(?s).*Diagnostic ID: diag_[0-9a-f]{32}\\.$"));
    }

    /** 使用可控异常模拟 OS/适配器失败，不依赖 Windows ACL 或文件锁时序。 */
    private static final class FaultTool extends ToolSupport {
        private final Exception failure;

        /** 构造最小合法 Tool 规格，测试只关注公共异常分类。 */
        private FaultTool(Exception failure) {
            super(new ToolSpec("fault", "Inject a controlled failure",
                    objectSchema(Map.of(), List.of())));
            this.failure = failure;
        }

        /** 抛出夹具异常，让父类成为唯一分类与脱敏位置。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws Exception {
            throw failure;
        }
    }
}
