// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.api;

import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;

import io.github.kongweiguang.ja.collaboration.application.service.NativeHostProcessFixture;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext.NativeClientProcessExitListener;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 原生上下文只用于同步准入，连接断开后的快照由请求或 Turn 自行持有。 */
final class NativeExecutionContextTest {
    /**
     * 两个并行 RPC Scope 各自看到完整原环境；连接释放后已绑定请求仍有效，
     * Scope 结束则不得把另一客户端的环境遗留在复用线程上。
     */
    @Test
    void parallelClientsRemainIsolatedAfterConnectionRelease() throws Exception {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        String first = bridge.register(Map.of("PATH", "first-path", "JA_CLIENT_MARKER", "first-secret"), null,
                java.util.Optional.empty());
        String second = bridge.register(Map.of("PATH", "second-path", "JA_CLIENT_MARKER", "second-secret"), null,
                java.util.Optional.empty());
        String firstRequest = "test_" + UUID.randomUUID();
        String secondRequest = "test_" + UUID.randomUUID();
        bridge.bindRpcRequest(firstRequest, first);
        bridge.bindRpcRequest(secondRequest, second);
        bridge.release(first);
        bridge.release(second);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch continueBoth = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> firstResult = workers.submit(() -> scopedMarker(bridge, firstRequest, entered, continueBoth));
            Future<String> secondResult = workers.submit(() -> scopedMarker(bridge, secondRequest, entered, continueBoth));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            continueBoth.countDown();
            assertEquals("first-secret", firstResult.get(2, TimeUnit.SECONDS));
            assertEquals("second-secret", secondResult.get(2, TimeUnit.SECONDS));
        } finally {
            continueBoth.countDown();
            bridge.unbindRpcRequest(firstRequest);
            bridge.unbindRpcRequest(secondRequest);
        }
        assertFalse(bridge.current().isPresent());
    }

    /**
     * Plan Run 能跨连接断开保留环境，暂停/终态按精确 Run 释放；后台退出后重新启用
     * 共享模式时不得从上一个进程代际读出旧客户端的 PATH。
     */
    @Test
    void runSnapshotSurvivesDisconnectButNotDaemonRestart() {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        bridge.enableSharedMode();
        String contextId = bridge.register(Map.of("PATH", "client-only", "JA_SECRET", "private"), null,
                java.util.Optional.empty());
        String rpcRequestId = "test_" + UUID.randomUUID();
        bridge.bindRpcRequest(rpcRequestId, contextId);
        try {
            try (NativeExecutionContext.Scope ignored = bridge.enterRpcRequest(rpcRequestId)) {
                bridge.bindPlanRun("plan_one", "run_one", bridge.current().orElseThrow());
            }
            bridge.release(contextId);
            assertEquals("client-only", bridge.findPlanRun("plan_one", "run_one")
                    .orElseThrow().environment().get("PATH"));
            assertTrue(bridge.findPlanRun("plan_one", "run_other").isEmpty());
            bridge.releasePlanRun("plan_one", "run_other");
            assertTrue(bridge.findPlanRun("plan_one", "run_one").isPresent());
        } finally {
            bridge.unbindRpcRequest(rpcRequestId);
            bridge.release(contextId);
            bridge.disableSharedMode();
        }
        bridge.enableSharedMode();
        try {
            assertTrue(bridge.findPlanRun("plan_one", "run_one").isEmpty());
        } finally {
            bridge.disableSharedMode();
        }
    }

    /**
     * Windows Native Host 必须能以真实创建时间登记，并在同一受限句柄报告退出后触发 owner 清理。
     * @author kongweiguang
     */
    @Test
    void windowsNativeHostRegistrationUsesHeldProcessObservationUntilExit() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        bridge.enableSharedMode();
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<List<NativeClientOwner>> exitedOwners = new AtomicReference<>(List.of());
        NativeClientProcessExitListener listener = owners -> {
            exitedOwners.set(List.copyOf(owners));
            exited.countDown();
        };
        bridge.registerNativeClientProcessExitListener(listener);
        try (NativeHostProcessFixture.Host nativeHost = NativeHostProcessFixture.start()) {
            NativeClientOwner owner = bridge.registerNativeClient(nativeHost.processId(), nativeHost.lifetimeId());
            assertSame(owner, bridge.registerNativeClient(nativeHost.processId(), nativeHost.lifetimeId()));
            assertTrue(bridge.nativeClientAcceptsRequests(owner));

            nativeHost.close();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(owner), exitedOwners.get());
            assertFalse(bridge.nativeClientAcceptsRequests(owner));
            assertFalse(bridge.nativeClientProcessAlive(owner));
        } finally {
            bridge.releaseNativeClientProcessExitListener(listener);
            bridge.disableSharedMode();
        }
    }

    /**
     * Scope 故意跨越同步屏障，确保另一个请求同时读取时没有进程级环境切换。
     * @author kongweiguang
     */
    private static String scopedMarker(NativeExecutionContext bridge, String rpcRequestId,
                                       CountDownLatch entered, CountDownLatch continueBoth) throws Exception {
        try (NativeExecutionContext.Scope ignored = bridge.enterRpcRequest(rpcRequestId)) {
            entered.countDown();
            assertTrue(continueBoth.await(2, TimeUnit.SECONDS));
            NativeExecutionSnapshot snapshot = bridge.current().orElseThrow();
            assertFalse(snapshot.toString().contains("secret"));
            return snapshot.environment().get("JA_CLIENT_MARKER");
        }
    }
}
