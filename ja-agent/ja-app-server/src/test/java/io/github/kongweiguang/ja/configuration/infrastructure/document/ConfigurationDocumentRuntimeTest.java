// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.infrastructure.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用隔离文件失败验证退避取消的内部诊断，不增加生产注入或公开测试接口。 */
final class ConfigurationDocumentRuntimeTest {
    @TempDir Path root;

    /** 中断必须终止重试并保留读取失败；测试结束清除本次注入的中断以免污染下一项。 */
    @Test
    void interruptedReadRetainsLastIoFailure() throws Exception {
        Path invalidFile = Files.createDirectory(root.resolve("config.toml"));
        Method readWithRetry = ConfigurationDocumentRuntime.class.getDeclaredMethod("readWithRetry", Path.class);
        readWithRetry.setAccessible(true);
        try {
            Thread.currentThread().interrupt();
            InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                    () -> readWithRetry.invoke(null, invalidFile));
            IOException failure = assertInstanceOf(IOException.class, wrapped.getCause());
            assertEquals("configuration_read_interrupted", failure.getMessage());
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
