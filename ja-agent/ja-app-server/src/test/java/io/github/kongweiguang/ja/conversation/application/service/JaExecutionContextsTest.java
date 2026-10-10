// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 核对并发 Engine 提交只在最后一个请求释放后清除原生环境。 */
final class JaExecutionContextsTest {
    /** 同身份的幂等并发调用共享快照，但各自只释放自己的引用。 */
    @Test
    void keepsSnapshotUntilEverySubmissionReleasesItsBinding() {
        NativeExecutionContext.shared().enableSharedMode();
        JaExecutionContexts contexts = new JaExecutionContexts();
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_context", "turn_context_"
                + java.util.UUID.randomUUID().toString().replace("-", ""), "user_local");
        NativeExecutionSnapshot snapshot = new NativeExecutionSnapshot(Map.of("PATH", "client-path"), null);
        try {
            try (JaExecutionContexts.Binding first = contexts.bind(identity, snapshot);
                 JaExecutionContexts.Binding second = contexts.bind(identity, snapshot)) {
                assertSame(snapshot, contexts.find(identity));

                first.close();

                assertSame(snapshot, contexts.find(identity));
                assertEquals("client-path", contexts.find(identity).environment().get("PATH"));
            }
            assertThrows(IllegalStateException.class, () -> contexts.find(identity));
        } finally {
            NativeExecutionContext.shared().disableSharedMode();
        }
    }

    /** 不允许同一执行身份被并发请求切换到另一连接的 PATH 与 Shell 配置。 */
    @Test
    void rejectsDifferentSnapshotForAnActiveExecution() {
        JaExecutionContexts contexts = new JaExecutionContexts();
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_context", "turn_context", "user_local");
        NativeExecutionSnapshot first = new NativeExecutionSnapshot(Map.of("PATH", "first-path"), null);
        NativeExecutionSnapshot second = new NativeExecutionSnapshot(Map.of("PATH", "second-path"), null);
        try (JaExecutionContexts.Binding ignored = contexts.bind(identity, first)) {
            assertThrows(IllegalStateException.class, () -> contexts.bind(identity, second));
            assertSame(first, contexts.find(identity));
        }
    }
}
