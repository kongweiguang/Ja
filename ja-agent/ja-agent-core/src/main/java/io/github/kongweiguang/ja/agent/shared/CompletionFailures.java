// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 统一异步清理路径的异常拆包，保留真实根因并避免并发边界泄漏包装类型。
 */
public final class CompletionFailures {
    /**
     * 禁止实例化无状态异常工具类，避免误将异步失败策略当作可变对象持有。
     */
    private CompletionFailures() {
    }

    /**
     * 去掉 CompletionStage/Future 包装，直到得到实际失败原因。
     *
     * @param failure 异步失败
     * @return 未包装的失败原因
     */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 将异步失败转换为稳定运行时异常；已有 RuntimeException 保持对象身份供调用方判断。
     *
     * @param owner 发生失败的资源 owner
     * @param failure 异步失败
     * @return 可直接抛出的运行时异常
     */
    public static RuntimeException propagate(String owner, Throwable failure) {
        String scope = Objects.requireNonNull(owner, "owner");
        if (scope.isBlank()) {
            throw new IllegalArgumentException("owner is required");
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(scope, cause);
    }
}
