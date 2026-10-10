// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证异常摘要在 cause 身份形成环时仍能及时结束。 */
final class FailureDiagnosticsTest {
    /** 间接 cause 环只允许遍历到首个重复身份，避免诊断阻塞承载失败的线程。 */
    @Test
    void stopsAtTheFirstRepeatedCauseIdentity() {
        RootFailure first = new RootFailure();
        NestedFailure second = new NestedFailure();
        first.link(second);
        second.link(first);

        FailureDiagnostics.Summary summary = FailureDiagnostics.summarize(first);

        assertEquals(RootFailure.class.getName(), summary.type());
    }

    /** 最深故障只保留八个本产品代码位置，不能把共享入口或凭据文件名扩展成完整异常文本。 */
    @Test
    void boundsRootCallPathWithoutPayload() {
        RuntimeException failure = new java.util.concurrent.CancellationException("api_key=private-secret");
        StackTraceElement[] frames = new StackTraceElement[10];
        frames[0] = new StackTraceElement("java.util.concurrent.CompletableFuture", "join",
                "C:/private/secret.txt", 42);
        for (int index = 1; index < frames.length; index++) {
            frames[index] = new StackTraceElement("io.github.kongweiguang.ja.fixture.Owner" + index,
                    "step" + index, "C:/private/auth.json", index);
        }
        failure.setStackTrace(frames);
        FailureDiagnostics.Summary diagnostic = FailureDiagnostics.summarize(
                new java.util.concurrent.CompletionException(failure));
        String path = diagnostic.origin();
        assertEquals(java.util.concurrent.CancellationException.class.getName(), diagnostic.type());
        assertEquals(8, path.split(" <- ").length);
        assertTrue(path.startsWith("io.github.kongweiguang.ja.fixture.Owner1#step1:1 <- "));
        assertTrue(path.endsWith("io.github.kongweiguang.ja.fixture.Owner8#step8:8"));
        assertFalse(path.contains("Owner9"));
        assertFalse(path.contains("CompletableFuture"));
        assertFalse(path.contains("private"));
        assertFalse(path.contains("api_key"));
        assertFalse(path.contains("auth.json"));
    }

    /** 为回归测试构造可计数的 cause 环，保证旧式无限遍历会快速失败而不挂住测试进程。 */
    private abstract static class BoundedFailure extends Throwable {
        private Throwable next;
        private int causeReads;

        /** 只链接测试图中的下一异常，不调用 Throwable.initCause 的单边写入约束。 */
        final void link(Throwable cause) {
            next = cause;
        }

        /** 超过有限读取预算即让测试失败，检测摘要器是否再次越过重复身份。 */
        @Override
        public synchronized Throwable getCause() {
            if (causeReads++ >= 4) {
                throw new AssertionError("cause traversal did not stop at a cycle");
            }
            return next;
        }
    }

    /** 外层故障类型用于确认摘要停在第一个重复节点。 */
    private static final class RootFailure extends BoundedFailure {
    }

    /** 内层故障提供环中的第二个、可区分身份节点。 */
    private static final class NestedFailure extends BoundedFailure {
    }
}
