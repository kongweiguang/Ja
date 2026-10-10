// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证共享 Kernel32 FFM 会话的调用状态和资源关闭边界。 */
final class WindowsKernel32LibraryTest {
    /**
     * 在测试触达 Kernel32 前显式拒绝其它平台，避免把未执行的本地断言误报为通过。
     */
    @BeforeEach
    void requireWindows() {
        assertTrue(WindowsJobObject.isSupported(), "Kernel32 FFM 测试要求 Windows 11");
    }

    /**
     * 在调用局部 Arena 释放前读取输出和 GetLastError，证明共享边界不会泄漏临时 MemorySegment。
     */
    @Test
    void capturesResultBeforeCallArenaCloses() throws Exception {
        try (WindowsKernel32Library library =
                     new WindowsKernel32Library("windows_test_symbols_unavailable");
             Arena outputArena = Arena.ofConfined()) {
            MethodHandle currentProcess = library.downcall(
                    "GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS));
            MethodHandle getHandleCount = library.downcall(
                    "GetProcessHandleCount",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            WindowsKernel32Library.NativeCall processCall =
                    library.invoke(currentProcess, "current_process");
            MemorySegment count = outputArena.allocate(ValueLayout.JAVA_INT);
            WindowsKernel32Library.NativeCall countCall = library.invoke(
                    getHandleCount, "get_process_handle_count", processCall.value(), count);

            assertEquals(1, countCall.value());
            assertTrue(Integer.toUnsignedLong(count.get(ValueLayout.JAVA_INT, 0)) > 0);
        }
    }

    /**
     * 关闭必须幂等，关闭后的符号解析和调用都稳定失败，防止释放后的 MethodHandle 被继续使用。
     */
    @Test
    void rejectsResolutionAndInvocationAfterClose() {
        WindowsKernel32Library library =
                new WindowsKernel32Library("windows_test_symbols_unavailable");
        MethodHandle currentProcess = library.downcall(
                "GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS));
        library.close();
        library.close();

        IllegalStateException resolutionFailure = assertThrows(
                IllegalStateException.class,
                () -> library.downcall(
                        "GetCurrentProcess", FunctionDescriptor.of(ValueLayout.ADDRESS)));
        WindowsKernel32Library.InvocationFailure invocationFailure = assertThrows(
                WindowsKernel32Library.InvocationFailure.class,
                () -> library.invoke(currentProcess, "current_process"));

        assertEquals("windows_native_library_closed", resolutionFailure.getMessage());
        assertEquals("current_process", invocationFailure.operation());
    }

}
