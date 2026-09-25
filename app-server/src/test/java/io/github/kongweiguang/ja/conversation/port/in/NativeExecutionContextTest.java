// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.NativeExecutionSnapshot;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原生上下文只用于同步准入，连接断开后的快照由请求或 Turn 自行持有。 */
final class NativeExecutionContextTest {
    /**
     * 两个并行 RPC Scope 各自看到完整原环境；连接释放后已绑定请求仍有效，
     * Scope 结束则不得把另一客户端的环境遗留在复用线程上。
     */
    @Test
    void parallelClientsRemainIsolatedAfterConnectionRelease() throws Exception {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        String first = bridge.register(Map.of("PATH", "first-path", "JA_CLIENT_MARKER", "first-secret"), null);
        String second = bridge.register(Map.of("PATH", "second-path", "JA_CLIENT_MARKER", "second-secret"), null);
        String firstRequest = "test_" + UUID.randomUUID();
        String secondRequest = "test_" + UUID.randomUUID();
        bridge.bindRequest(firstRequest, first);
        bridge.bindRequest(secondRequest, second);
        bridge.release(first);
        bridge.release(second);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch continueBoth = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = workers.submit(() -> scopedMarker(bridge, firstRequest, entered, continueBoth));
            var secondResult = workers.submit(() -> scopedMarker(bridge, secondRequest, entered, continueBoth));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            continueBoth.countDown();
            assertEquals("first-secret", firstResult.get(2, TimeUnit.SECONDS));
            assertEquals("second-secret", secondResult.get(2, TimeUnit.SECONDS));
        } finally {
            continueBoth.countDown();
            bridge.unbindRequest(firstRequest);
            bridge.unbindRequest(secondRequest);
        }
        assertFalse(bridge.current().isPresent());
    }

    /**
     * Run 能跨连接断开保留环境，暂停/终态按精确 run 释放；后台退出后重新启用
     * 共享模式时不得从上一个进程代际读出旧客户端的 PATH。
     */
    @Test
    void runSnapshotSurvivesDisconnectButNotDaemonRestart() {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        bridge.enableSharedMode();
        String contextId = bridge.register(Map.of("PATH", "client-only", "JA_SECRET", "private"), null);
        String requestId = "test_" + UUID.randomUUID();
        bridge.bindRequest(requestId, contextId);
        try {
            try (var ignored = bridge.enterRequest(requestId)) {
                bridge.bindRun("plan", "plan_one", "run_one", bridge.current().orElseThrow());
            }
            bridge.release(contextId);
            assertEquals("client-only", bridge.findRun("plan", "plan_one", "run_one")
                    .orElseThrow().environment().get("PATH"));
            assertTrue(bridge.findRun("plan", "plan_one", "run_other").isEmpty());
            bridge.releaseRun("plan", "plan_one", "run_other");
            assertTrue(bridge.findRun("plan", "plan_one", "run_one").isPresent());
        } finally {
            bridge.unbindRequest(requestId);
            bridge.release(contextId);
            bridge.disableSharedMode();
        }
        bridge.enableSharedMode();
        try {
            assertTrue(bridge.findRun("plan", "plan_one", "run_one").isEmpty());
        } finally {
            bridge.disableSharedMode();
        }
    }

    /** Scope 故意跨越同步屏障，确保另一个请求同时读取时没有进程级环境切换。 */
    private static String scopedMarker(NativeExecutionContext bridge, String requestId,
                                       CountDownLatch entered, CountDownLatch continueBoth) throws Exception {
        try (var ignored = bridge.enterRequest(requestId)) {
            entered.countDown();
            assertTrue(continueBoth.await(2, TimeUnit.SECONDS));
            NativeExecutionSnapshot snapshot = bridge.current().orElseThrow();
            assertFalse(snapshot.toString().contains("secret"));
            return snapshot.environment().get("JA_CLIENT_MARKER");
        }
    }
}
