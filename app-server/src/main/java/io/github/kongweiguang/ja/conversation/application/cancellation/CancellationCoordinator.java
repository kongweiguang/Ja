// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.cancellation;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 拥有活动 Turn 的进程内取消 Scope 与清理屏障，属于应用生命周期协调。
 */
public interface CancellationCoordinator extends AutoCloseable {
    /**
     * 为全局唯一 Turn 创建 Scope；重复身份必须拒绝而不是复用旧取消状态。
     */
    CancellationScope open(String threadId, String turnId);

    /**
     * 原子发布取消并等待已登记清理动作完成，重复请求共享同一屏障。
     */
    CompletionStage<CancelOutcome> cancel(String threadId, String turnId, String reason);

    /**
     * 返回活动 Scope 的只读令牌，禁止调用方取得发布取消的所有权。
     */
    Optional<CancellationToken> find(String threadId, String turnId);

    /**
     * Turn 完成后关闭 Scope，并在清理屏障结束前保持它可被迟到回调观察。
     */
    void complete(String threadId, String turnId);

    /**
     * 停止协调器并收敛全部活动 Scope；实现必须幂等。
     */
    @Override
    void close();

    /**
     * 单个 Turn 的取消发布权与清理完成屏障。
     */
    interface CancellationScope extends CancellationToken, AutoCloseable {
        /**
         * 仅第一次发布返回 true，后续请求不得覆盖首次取消原因。
         */
        boolean requestCancellation(String reason);

        /**
         * 返回全部已登记清理回调完成的共享阶段。
         */
        CompletionStage<Void> cleanupCompletion();

        /**
         * 关闭 Scope 并解除回调引用，但不得跳过已经发布的清理。
         */
        @Override
        void close();
    }

    /**
     * 取消请求提交到进程内 Scope 后的幂等结果。
     */
    enum CancelOutcome {
        /**
         * 本次调用首次发布取消并启动清理。
         */
        REQUESTED,
        /**
         * 取消此前已发布，本次调用复用同一清理屏障。
         */
        ALREADY_REQUESTED,
        /**
         * 当前进程不存在对应的活动 Turn Scope。
         */
        NOT_FOUND
    }
}
