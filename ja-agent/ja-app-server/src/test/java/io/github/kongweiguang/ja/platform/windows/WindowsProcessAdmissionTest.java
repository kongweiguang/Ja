// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证 Windows 进程接纳在匿名管道分阶段失败时仍回收全部已取得句柄。 */
final class WindowsProcessAdmissionTest {
    @TempDir
    private Path temporary;

    /**
     * 管道模式的第 2/3 根管道创建失败时，先前子端与父端各关闭一次；不混入 ConPTY 的资源路径。
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void closesEveryEarlierPipeWhenLaterCreationFails(int failedPipe) throws Exception {
        assumeTrue(WindowsJobObject.isSupported(), "Windows process admission tests require Windows 11");
        WindowsJobObject job = WindowsJobObject.create();
        AtomicInteger createCalls = new AtomicInteger();
        List<Long> closedHandles = new ArrayList<>();
        WindowsProcessLaunchPolicy.LaunchSpec spec = new WindowsProcessLaunchPolicy.LaunchSpec(
                List.of("cmd.exe"), temporary, Map.of(), false);

        try {
            WindowsProcessNativeApi api = new WindowsProcessNativeApi();
            IOException failure = assertThrows(IOException.class, () -> WindowsProcessAdmission.launch(
                    spec,
                    job,
                    api,
                    () -> {
                        int pipe = createCalls.incrementAndGet();
                        if (pipe == failedPipe) {
                            throw new WindowsProcessNativeApi.WindowsFailure("create_pipe", 5);
                        }
                        long base = pipe * 10L;
                        return new WindowsProcessNativeApi.Pipe(
                                MemorySegment.ofAddress(base + 1), MemorySegment.ofAddress(base + 2));
                    },
                    handle -> closedHandles.add(handle.address())));

            assertEquals("windows_process_launch_failed_5", failure.getMessage());
            assertEquals(failedPipe, createCalls.get());
            List<Long> expected = failedPipe == 2 ? List.of(11L, 12L) : List.of(11L, 22L, 12L, 21L);
            assertEquals(expected, closedHandles);
        } finally {
            job.closeAfterRootExit();
        }
    }

    /**
     * 子端句柄关闭中途失败时只保留失败项，防止失败清理重复关闭已经释放且可能复用的句柄值。
     */
    @Test
    void retainsOnlyHandlesWhoseCloseDidNotSucceed() throws Exception {
        List<MemorySegment> handles = new ArrayList<>(List.of(
                MemorySegment.ofAddress(1),
                MemorySegment.ofAddress(2),
                MemorySegment.ofAddress(3)));
        List<Long> closeAttempts = new ArrayList<>();

        WindowsProcessNativeApi.WindowsFailure failure = assertThrows(
                WindowsProcessNativeApi.WindowsFailure.class,
                () -> WindowsProcessAdmission.closeTrackedHandles(handles, handle -> {
                    closeAttempts.add(handle.address());
                    if (handle.address() == 2) {
                        throw new WindowsProcessNativeApi.WindowsFailure("close_handle", 5);
                    }
                }));

        assertEquals(5, failure.error());
        assertEquals(List.of(1L, 2L), closeAttempts);
        assertEquals(List.of(2L, 3L), handles.stream().map(MemorySegment::address).toList());

        List<Long> retries = new ArrayList<>();
        WindowsProcessAdmission.closeTrackedHandles(
                handles, handle -> retries.add(handle.address()));

        assertEquals(List.of(2L, 3L), retries);
        assertEquals(List.of(1L, 2L), closeAttempts);
        assertEquals(List.of(), handles);
    }
}
