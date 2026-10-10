// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 原生环境只由 Ja 在内存中持有；Core 的持久请求不包含环境变量或 Shell 配置。
 */
public final class JaExecutionContexts {
    private final ConcurrentHashMap<AgentIdentity, Entry> bindings = new ConcurrentHashMap<>();

    /**
     * 显式继承优先于当前认证连接；返回租约以隔离并发提交的释放时机。
     */
    public Binding bind(AgentIdentity identity, NativeExecutionSnapshot inherited) {
        AgentIdentity key = Objects.requireNonNull(identity, "identity");
        NativeExecutionSnapshot snapshot = inherited != null ? inherited
                : NativeExecutionContext.shared().current().orElse(null);
        if (snapshot == null && NativeExecutionContext.shared().sharedMode()) {
            throw new IllegalStateException("native execution context must be rebound before execution");
        }
        Entry entry = bindings.compute(key, (ignored, current) -> {
            if (current == null) {
                return new Entry(snapshot);
            }
            if (!Objects.equals(current.snapshot, snapshot)) {
                throw new IllegalStateException("native execution context is already bound");
            }
            current.references++;
            return current;
        });
        return new Binding(this, key, entry);
    }

    /**
     * Resolver 只读取同一执行的显式绑定；缺失环境在共享后台中必须明确失败。
     */
    public NativeExecutionSnapshot find(AgentIdentity identity) {
        Entry entry = bindings.get(Objects.requireNonNull(identity, "identity"));
        if (entry == null) {
            if (NativeExecutionContext.shared().sharedMode()) {
                throw new IllegalStateException("native execution context is unavailable");
            }
            return null;
        }
        if (entry.snapshot == null && NativeExecutionContext.shared().sharedMode()) {
            throw new IllegalStateException("native execution context is unavailable");
        }
        return entry.snapshot;
    }

    /**
     * 租约只回收自己创建的绑定代际，迟到的重复请求不能移除另一调用仍在使用的环境。
     */
    private void release(AgentIdentity identity, Entry expected) {
        bindings.computeIfPresent(identity, (ignored, current) -> {
            if (current != expected) {
                return current;
            }
            if (--current.references == 0) {
                return null;
            }
            return current;
        });
    }

    /**
     * 一次 Engine 提交对内存原生环境的短期引用，完成或准入失败后幂等释放。
     */
    public static final class Binding implements AutoCloseable {
        private final JaExecutionContexts owner;
        private final AgentIdentity identity;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 绑定由 JaExecutionContexts 创建，调用方只可通过 close 结束自己的引用。
         */
        private Binding(JaExecutionContexts owner, AgentIdentity identity, Entry entry) {
            this.owner = owner;
            this.identity = identity;
            this.entry = entry;
        }

        /**
         * 幂等结束单个调用的租约，不影响同身份上正在解析或执行的并发提交。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(identity, entry);
            }
        }
    }

    /**
     * 同一身份的并发重试只共享完全相等的不可变快照。
     */
    private static final class Entry {
        private final NativeExecutionSnapshot snapshot;
        private int references = 1;

        /**
         * 保存唯一绑定值，引用计数只在 ConcurrentHashMap 的键级原子区间内修改。
         */
        private Entry(NativeExecutionSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }
}
