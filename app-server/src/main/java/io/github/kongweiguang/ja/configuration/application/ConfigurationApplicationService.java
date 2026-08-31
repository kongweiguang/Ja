// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.application;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationView;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationWorkspaceTrustUseCase;
import io.github.kongweiguang.ja.configuration.port.out.ConfigurationRuntimePort;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 配置域应用服务；统一发布配置、代际租约与工作区信任入站能力。
 *
 * <p>服务只依赖领域模型和出站端口。不可变配置数据可直接跨边界复用，代际生命周期则通过
 * 显式包装发布，确保文件适配器永远不实现或引用 port.in。</p>
 */
public final class ConfigurationApplicationService implements ConfigurationUseCase,
        ConfigurationGenerationUseCase, ConfigurationWorkspaceTrustUseCase {
    private final ConfigurationRuntimePort runtime;

    /** 绑定唯一配置运行时端口，避免应用层分散持有文档、凭据和代际所有者。 */
    public ConfigurationApplicationService(ConfigurationRuntimePort runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** 读取配置运行时生成的不可变脱敏快照。 */
    @Override
    public ReadResult read(Path workspaceRoot) {
        return runtime.read(workspaceRoot);
    }

    /** 把 Merge Patch 交给拥有文件 CAS 临界区的出站端口。 */
    @Override
    public MutationResult patch(ConfigurationScope scope, Path workspaceRoot, Document patch,
                                String expectedVersion) {
        return runtime.patch(scope, workspaceRoot, patch, expectedVersion);
    }

    /** 把完整文档交给文件适配器执行严格校验与原子替换。 */
    @Override
    public MutationResult replace(ConfigurationScope scope, Path workspaceRoot, Document document,
                                  String expectedVersion) {
        return runtime.replace(scope, workspaceRoot, document, expectedVersion);
    }

    /** 显式调用重置能力，不用 null 文档复用替换路径。 */
    @Override
    public MutationResult reset(ConfigurationScope scope, Path workspaceRoot, String expectedVersion) {
        return runtime.reset(scope, workspaceRoot, expectedVersion);
    }

    /** Secret 仅穿过该命令边界到达凭据适配器，应用层不缓存输入。 */
    @Override
    public CredentialResult setCredential(String credentialId, String secret, String expectedVersion) {
        return runtime.setCredential(credentialId, secret, expectedVersion);
    }

    /** 删除凭据并返回适配器产生的权威 CAS 版本。 */
    @Override
    public CredentialResult deleteCredential(String credentialId, String expectedVersion) {
        return runtime.deleteCredential(credentialId, expectedVersion);
    }

    /** 返回适配器从同一读取状态派生的有界健康投影，避免应用层重复执行文件 I/O。 */
    @Override
    public HealthResult health() {
        return runtime.health();
    }

    /**
     * 把 workspace 域已提交的布尔信任事实交给配置运行时；返回值只表示是否发生文件变化，
     * 不属于 workspace 用例的可观察结果，因此在此收敛。
     */
    @Override
    public void synchronize(Path workspaceRoot, boolean trusted) {
        runtime.synchronizeWorkspaceTrust(workspaceRoot, trusted);
    }

    /** 获取出站租约后立即包裹为入站生命周期，异常时不遗留第二个所有者。 */
    @Override
    public ConfigurationGenerationLease acquire(Path workspaceRoot) {
        return new InboundGenerationLease(runtime.acquire(workspaceRoot));
    }

    /** 仅负责端口方向转换的租约，不复制 Secret 或代际模型。 */
    private static final class InboundGenerationLease implements ConfigurationGenerationLease {
        private final ConfigurationRuntimePort.GenerationLease delegate;
        private final ConfigurationGenerationView view;

        /** 冻结同一个出站租约及其不可变视图，避免每次读取创建不同投影身份。 */
        private InboundGenerationLease(ConfigurationRuntimePort.GenerationLease delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.view = new InboundGenerationView(delegate.snapshot());
        }

        /** 返回出站租约分配的不透明代际标识。 */
        @Override
        public String generationId() {
            return delegate.generationId();
        }

        /** 返回应用层发布的稳定入站视图。 */
        @Override
        public ConfigurationGenerationView view() {
            /*
             * 出站租约负责关闭状态权威；即使视图对象已冻结，也必须先触发一次租约校验，
             * 否则关闭后的 lease.view() 会错误返回缓存投影并破坏既有生命周期合同。
             */
            delegate.snapshot();
            return view;
        }

        /** 在租约生命周期内短时借用 Secret，不在应用服务中保存副本。 */
        @Override
        public String secretFor(String credentialId) {
            return delegate.secretFor(credentialId);
        }

        /** 把幂等释放原样交还唯一出站租约。 */
        @Override
        public void close() {
            delegate.close();
        }
    }

    /** 把领域代际投影标记为已通过应用层发布，不复制其中任何集合或配置对象。 */
    private record InboundGenerationView(ConfigurationGenerationSnapshot delegate)
            implements ConfigurationGenerationView {
        /** 固定不可变领域投影，拒绝无来源的入站视图。 */
        private InboundGenerationView {
            Objects.requireNonNull(delegate, "delegate");
        }

        /** 返回领域投影的不透明代际标识。 */
        @Override
        public String generationId() {
            return delegate.generationId();
        }

        /** 返回领域投影中已冻结的 Skill 定义。 */
        @Override
        public List<Skill> skillDefinitions() {
            return delegate.skillDefinitions();
        }

        /** 返回领域投影中已冻结的 MCP 定义。 */
        @Override
        public List<McpServer> mcpDefinitions() {
            return delegate.mcpDefinitions();
        }

        /** 透传配置代际唯一的全局执行模式，不在应用层建立第二份权限状态。 */
        @Override
        public AccessMode accessMode() {
            return delegate.accessMode();
        }

        /** 透传租约冻结的 workspace trust，应用层不重新查询或推导当前文件状态。 */
        @Override
        public boolean trusted() {
            return delegate.trusted();
        }

        /** 按稳定标识委托领域投影解析 MCP。 */
        @Override
        public McpServer requireMcp(String mcpId) {
            return delegate.requireMcp(mcpId);
        }

        /** 透传配置 Owner 的默认 Provider，应用层不建立第二份选择规则。 */
        @Override
        public java.util.Optional<String> defaultProviderId() {
            return delegate.defaultProviderId();
        }

        /** 透传与默认 Provider 成对校验的 Model。 */
        @Override
        public java.util.Optional<String> defaultModelId() {
            return delegate.defaultModelId();
        }

        /** 透传默认思考档位闭集，不将其转换为厂商私有参数。 */
        @Override
        public java.util.Optional<ReasoningLevel> defaultReasoningLevel() {
            return delegate.defaultReasoningLevel();
        }

        /** 按稳定标识委托领域投影解析 Provider。 */
        @Override
        public Provider requireProvider(String providerId) {
            return delegate.requireProvider(providerId);
        }

        /** 在指定 Provider 内委托解析 Model，拒绝跨 Provider 模糊匹配。 */
        @Override
        public Model requireModel(String providerId, String modelId) {
            return delegate.requireModel(providerId, modelId);
        }
    }
}
