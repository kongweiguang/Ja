// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kongweiguang.ja.collaboration.application.service.NativeHostProcessFixture;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证 Windows Native Host 只以最小权限持有精确进程句柄并观察真实创建与退出状态。 */
final class WindowsNativeProcessObservationTest {
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int REQUIRED_ACCESS = 0x00101000;

    /**
     * 本地集成断言依赖 Windows Kernel32；非 Windows 平台明确跳过而不伪报本地路径通过。
     * @author kongweiguang
     */
    @BeforeEach
    void requireWindows() {
        assumeTrue(WindowsJobObject.isSupported(), "Native process observation requires Windows 11");
    }

    /**
     * 假系统边界验证精确 PID、0x00101000 权限掩码、真实创建时间及句柄关闭幂等性。
     * @author kongweiguang
     */
    @Test
    void opensWithLimitedQueryAndSynchronizeRightsAndClosesOnce() {
        Instant creationTime = Instant.parse("2026-10-09T02:03:04.123456700Z");
        FakeProcessApi api = new FakeProcessApi(creationTime, WAIT_TIMEOUT, WAIT_TIMEOUT, WAIT_OBJECT_0);
        WindowsNativeProcessObservation observation = WindowsNativeProcessObservation.open(1234, api);

        assertEquals(1234, api.processId);
        assertEquals(REQUIRED_ACCESS, api.desiredAccess);
        assertSame(api.handle, api.waitedHandle);
        assertEquals(creationTime, observation.creationTime());
        assertTrue(observation.isAlive());
        assertTrue(observation.awaitExit(250));

        observation.close();
        observation.close();
        assertSame(api.handle, api.closedHandle);
        assertEquals(1, api.closeHandleCalls);
        assertTrue(api.apiClosed);
        assertFalse(observation.isAlive());
    }

    /**
     * 已退出的 PID 不得登记成新的生命周期，打开失败路径仍关闭取得的句柄和 API 会话。
     * @author kongweiguang
     */
    @Test
    void closesHandleWhenProcessAlreadyExitedAtRegistration() {
        FakeProcessApi api = new FakeProcessApi(Instant.now(), WAIT_OBJECT_0);

        assertThrows(IllegalArgumentException.class, () -> WindowsNativeProcessObservation.open(4321, api));
        assertEquals(1, api.closeHandleCalls);
        assertSame(api.handle, api.closedHandle);
        assertTrue(api.apiClosed);
    }

    /**
     * 真实子进程验证 GetProcessTimes 和 WaitForSingleObject 指向同一 PID，退出后由观察器释放 HANDLE。
     * @author kongweiguang
     */
    @Test
    void readsRealProcessCreationTimeAndObservesExitOnHeldHandle() throws Exception {
        try (NativeHostProcessFixture.Host host = NativeHostProcessFixture.start()) {
            long handlesBefore = HandleCounter.current();
            WindowsNativeProcessObservation observation = WindowsNativeProcessObservation.open(host.processId());
            try {
                long handlesOpened = HandleCounter.current();
                Instant jdkStart = ProcessHandle.of(host.processId()).orElseThrow().info().startInstant().orElseThrow();

                assertTrue(handlesOpened > handlesBefore);
                assertTrue(Math.abs(observation.creationTime().toEpochMilli() - jdkStart.toEpochMilli()) <= 1);
                assertTrue(observation.isAlive());
                assertFalse(observation.awaitExit(0));

                host.close();
                assertTrue(observation.awaitExit(1_000));
                assertFalse(observation.isAlive());
            } finally {
                observation.close();
            }
            assertTrue(HandleCounter.current() <= handlesBefore,
                    "closing observation must return its native process handle");
        }
    }

    /**
     * 共享模式关闭必须释放组内句柄并停掉 watcher，即使被观察的 Native Host 仍在正常运行。
     * @author kongweiguang
     */
    @Test
    void disablingSharedModeClosesObservationWhileNativeHostRemainsAlive() throws Exception {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        bridge.enableSharedMode();
        try (NativeHostProcessFixture.Host host = NativeHostProcessFixture.start()) {
            long handlesBefore = HandleCounter.current();
            NativeClientOwner owner = bridge.registerNativeClient(host.processId(), host.lifetimeId());
            long handlesOpened = HandleCounter.current();
            assertTrue(handlesOpened > handlesBefore);
            assertTrue(bridge.nativeClientAcceptsRequests(owner));

            bridge.disableSharedMode();

            assertTrue(ProcessHandle.of(host.processId()).orElseThrow().isAlive());
            assertFalse(bridge.nativeClientAcceptsRequests(owner));
            assertTrue(HandleCounter.current() < handlesOpened,
                    "shared mode shutdown must close the observation handle");
        } finally {
            if (bridge.sharedMode()) {
                bridge.disableSharedMode();
            }
        }
    }

    /**
     * 使用 Kernel32 的进程句柄计数核验本地 HANDLE 已释放，而不是只检查 Java closed 标志。
     * @author kongweiguang
     */
    private static final class HandleCounter {
        /** 仅在本地测试调用一次无副作用查询，不保留 FFM Arena 或符号引用。 */
        private static long current() {
            try (Arena arena = Arena.ofConfined()) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
                MethodHandle currentProcess = linker.downcallHandle(
                        kernel32.findOrThrow("GetCurrentProcess"), FunctionDescriptor.of(ValueLayout.ADDRESS));
                MethodHandle getHandleCount = linker.downcallHandle(kernel32.findOrThrow("GetProcessHandleCount"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MemorySegment process = (MemorySegment) currentProcess.invokeExact();
                MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
                int result = (int) getHandleCount.invokeExact(process, count);
                if (result == 0) {
                    throw new IllegalStateException("GetProcessHandleCount failed");
                }
                return Integer.toUnsignedLong(count.get(ValueLayout.JAVA_INT, 0));
            } catch (Throwable failure) {
                throw new AssertionError("cannot query Windows handle count", failure);
            }
        }
    }

    /**
     * 测试观察器状态机时记录原生边界参数，避免以管理员权限改变机器安全配置。
     * @author kongweiguang
     */
    private static final class FakeProcessApi implements WindowsNativeProcessObservation.ProcessApi {
        private final Object handle = new Object();
        private final Instant creationTime;
        private final Queue<Integer> waitResults = new ArrayDeque<>();
        private long processId;
        private int desiredAccess;
        private Object waitedHandle;
        private Object closedHandle;
        private int closeHandleCalls;
        private boolean apiClosed;

        /**
         * 预置每次内核等待结果，首个值用于打开时确认 PID 仍存活。
         * @author kongweiguang
         */
        private FakeProcessApi(Instant creationTime, int... waitResults) {
            this.creationTime = creationTime;
            for (int waitResult : waitResults) {
                this.waitResults.add(waitResult);
            }
        }

        /** 记录原生打开参数，便于验证没有额外请求查询或终止权限。 */
        @Override
        public Object openProcess(long processId, int desiredAccess) {
            this.processId = processId;
            this.desiredAccess = desiredAccess;
            return handle;
        }

        /** 返回测试指定的进程创建时刻。 */
        @Override
        public Instant creationTime(Object handle) {
            assertSame(this.handle, handle);
            return creationTime;
        }

        /** 按调用次序返回存活或已退出状态，并记录始终使用同一个句柄。 */
        @Override
        public int waitForSingleObject(Object handle, int timeoutMillis) {
            waitedHandle = handle;
            return waitResults.remove();
        }

        /** 记录精确句柄，重复 close 不应再次到达此 API。 */
        @Override
        public void closeHandle(Object handle) {
            closedHandle = handle;
            closeHandleCalls++;
        }

        /** 记录该组的 FFM/API 生命周期结束。 */
        @Override
        public void close() {
            apiClosed = true;
        }
    }
}
