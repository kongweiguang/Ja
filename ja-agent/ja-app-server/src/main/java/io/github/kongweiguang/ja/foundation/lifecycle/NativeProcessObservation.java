// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.lifecycle;

import java.time.Instant;

/**
 * 进程观察所需的最小生命周期契约；只暴露 JDK 值和操作，不把平台句柄或业务适配泄漏到公共基础层。
 * @author kongweiguang
 */
public interface NativeProcessObservation extends AutoCloseable {
    /** 返回原生进程真实创建时刻，用于区分操作系统复用后的 PID。 */
    Instant creationTime();

    /** 仅按已观察的同一进程身份查询存活状态。 */
    boolean isAlive();

    /** 在给定有界等待内检查同一进程是否退出。 */
    boolean awaitExit(int timeoutMillis);

    /** 释放此观察器独占的操作系统资源。 */
    @Override
    void close();
}
