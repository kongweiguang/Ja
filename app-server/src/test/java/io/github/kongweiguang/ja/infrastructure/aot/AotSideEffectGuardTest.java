// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.aot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.junit.jupiter.api.Test;

/** 验证 Solon AOT 分析阶段不会误开文件、锁、数据源或网络资源。 */
final class AotSideEffectGuardTest {
    /** 属性值本身没有业务语义，只要 Solon 设置该标记就必须禁止运行时 I/O。 */
    @Test
    void rejectsRuntimeIoDuringAotProcessing() {
        String property = "solon.aot.processing";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "");

            assertTrue(AotSideEffectGuard.processing());
            StorageException failure = assertThrows(StorageException.class,
                    AotSideEffectGuard::requireRuntimeIo);
            assertEquals(StorageException.Code.INVALID_CONFIGURATION, failure.code());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    /** 普通运行期不应增加额外状态或副作用，只需允许真正的资源 owner 继续执行。 */
    @Test
    void allowsRuntimeIoOutsideAotProcessing() {
        String property = "solon.aot.processing";
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);

            assertFalse(AotSideEffectGuard.processing());
            AotSideEffectGuard.requireRuntimeIo();
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }
}
