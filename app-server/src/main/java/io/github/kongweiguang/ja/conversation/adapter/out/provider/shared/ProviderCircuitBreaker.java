// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 为共享 Provider 传输提供按端点、模型和操作隔离的熔断状态。 */
public final class ProviderCircuitBreaker {
    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration OPEN_DURATION = Duration.ofMinutes(5);

    private final Clock clock;
    private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap<>();

    /** 使用注入时钟计算开路窗口，使五分钟恢复与半开竞态可以确定性验证。 */
    public ProviderCircuitBreaker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 在 Provider IO 前取得许可；开路到期后只允许一个半开探测，其他调用快速失败。 */
    public Permit acquire(ModelPort.ModelConfiguration configuration, Operation operation) {
        Objects.requireNonNull(configuration, "configuration");
        Key key = new Key(configuration.api(), configuration.baseUri(), configuration.model(), operation);
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            Instant now = clock.instant();
            if (state.openedAt != null) {
                if (now.isBefore(state.openedAt.plus(OPEN_DURATION)) || state.halfOpenProbe) {
                    throw new CircuitOpenException();
                }
                state.halfOpenProbe = true;
                return new Permit(state, true, now);
            }
            return new Permit(state, false, now);
        }
    }

    /** 普通发送与摘要各自熔断；纯本地预算估算不属于 Provider 操作。 */
    public enum Operation {
        /** Agent 普通模型发送。 */
        SEND,
        /** Context Summary 结构化生成。 */
        SUMMARY
    }

    /** 开路只暴露稳定本地分类，不携带端点、模型或上游异常文本。 */
    public static final class CircuitOpenException extends ModelPort.ModelUnavailableException {
        private static final long serialVersionUID = 1L;

        /** 构造无堆栈稳定失败，避免高频开路请求扩大日志与内存压力。 */
        private CircuitOpenException() {
            super("provider circuit is open", null);
        }
    }

    /** 一次许可必须终结；半开许可据此释放唯一探测占用。 */
    public final class Permit {
        private final State state;
        private final boolean probe;
        private final Instant acquiredAt;
        private boolean completed;

        /** Permit 只能由当前 breaker 创建，并保留取得时刻供失败开路使用。 */
        private Permit(State state, boolean probe, Instant acquiredAt) {
            this.state = state;
            this.probe = probe;
            this.acquiredAt = acquiredAt;
        }

        /** 成功关闭熔断并清零连续失败，半开探测由此恢复正常流量。 */
        public void success() {
            synchronized (state) {
                if (completed) return;
                completed = true;
                state.consecutiveFailures = 0;
                state.openedAt = null;
                state.halfOpenProbe = false;
            }
        }

        /** 非用户失败累计三次后开路；半开探测失败立即重新开始五分钟窗口。 */
        public void failure() {
            synchronized (state) {
                if (completed) return;
                completed = true;
                state.consecutiveFailures++;
                if (probe || state.consecutiveFailures >= FAILURE_THRESHOLD) state.openedAt = acquiredAt;
                state.halfOpenProbe = false;
            }
        }

        /** 用户取消不计入连续失败；半开探测取消时只释放探测占用。 */
        public void cancelled() {
            synchronized (state) {
                if (completed) return;
                completed = true;
                state.halfOpenProbe = false;
            }
        }
    }

    /** Key 不含凭据与 Prompt，只使用治理所需的非敏感 API、端点与模型身份。 */
    private record Key(ModelPort.Api api, URI baseUri, String model, Operation operation) {
        /** 冻结完整键值，禁止 null 造成多个故障面意外合并。 */
        private Key {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(operation, "operation");
        }
    }

    /** 每个 Key 的可变状态只在自身 monitor 下访问，避免全局锁阻塞无关 Provider。 */
    private static final class State {
        private int consecutiveFailures;
        private Instant openedAt;
        private boolean halfOpenProbe;
    }
}
