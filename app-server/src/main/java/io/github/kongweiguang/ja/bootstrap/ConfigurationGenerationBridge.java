// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 在唯一组合根桥接 configuration 提供者与 catalog 消费者拥有的端口。
 *
 * <p>桥接只转换端口形状，不复制配置、不延长租约，也不把任一业务域的入站接口泄漏给
 * 对方的 application 或 adapter。</p>
 */
final class ConfigurationGenerationBridge implements ConfigurationGenerationPort {
    private final ConfigurationGenerationUseCase delegate;

    /**
     * 绑定 configuration 的唯一应用入口；组合根之外禁止建立跨域入站依赖。
     */
    ConfigurationGenerationBridge(ConfigurationGenerationUseCase delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 每次调用都创建一对一租约包装，确保消费者关闭动作精确转发且不共享可变状态。
     */
    @Override
    public Lease acquire(Path workspaceRoot) {
        return new BridgedLease(delegate.acquire(workspaceRoot));
    }

    /**
     * 只投影 catalog 需要的领域能力，并让底层租约继续执行关闭后访问校验。
     */
    private record BridgedLease(ConfigurationGenerationLease delegate) implements Lease {
        /** 拒绝空租约，避免组合错误延迟到第一次 Secret 读取。 */
        private BridgedLease {
            Objects.requireNonNull(delegate, "delegate");
        }

        /** 原样返回不透明代际标识，避免组合根派生第二套 identity。 */
        @Override
        public String generationId() {
            return delegate.generationId();
        }

        /** 仅上转为纯领域投影，禁止 catalog 看到 configuration 入站视图类型。 */
        @Override
        public ConfigurationGenerationSnapshot snapshot() {
            return delegate.view();
        }

        /** 直接借用底层 Secret，使其生命周期仍受同一租约约束。 */
        @Override
        public String secretFor(String credentialId) {
            return delegate.secretFor(credentialId);
        }

        /** 把幂等和最后持有者清理由 configuration Owner 保持为唯一权威。 */
        @Override
        public void close() {
            delegate.close();
        }
    }
}
