// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.support;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 为 transport 测试提供不进入生产包的显式缺失配置端口。 */
public class TestConfigurationPorts implements ConfigurationUseCase, ConfigurationGenerationUseCase {
    private static final TestConfigurationPorts UNAVAILABLE = new TestConfigurationPorts();

    /** 允许职责测试扩展拒绝式配置端口，但禁止夹具隐式持有外部文件状态。 */
    protected TestConfigurationPorts() {
    }

    /** 返回无状态共享夹具；测试不得依赖实例身份或在其中保存 Secret。 */
    public static TestConfigurationPorts unavailable() {
        return UNAVAILABLE;
    }

    /** 返回不含配置值和路径的缺失快照，使健康与握手测试可以独立于本地文件。 */
    @Override
    public ReadResult read(Path workspaceRoot) {
        Layer user = missingLayer(ConfigurationScope.USER, true);
        Layer project = missingLayer(ConfigurationScope.PROJECT, false);
        return new ReadResult(false, user, project, new Document(Map.of()), Map.of(),
                "cfg_missing", List.of("CONFIGURATION_UNAVAILABLE"));
    }

    /** 缺失配置所有者时拒绝 Patch，避免测试夹具产生未持久化的成功假象。 */
    @Override
    public MutationResult patch(ConfigurationScope scope, Path workspaceRoot, Document patch,
                                String expectedVersion) {
        throw unavailableFailure();
    }

    /** 缺失配置所有者时拒绝完整替换。 */
    @Override
    public MutationResult replace(ConfigurationScope scope, Path workspaceRoot, Document document,
                                  String expectedVersion) {
        throw unavailableFailure();
    }

    /** 缺失配置所有者时拒绝重置。 */
    @Override
    public MutationResult reset(ConfigurationScope scope, Path workspaceRoot, String expectedVersion) {
        throw unavailableFailure();
    }

    /** 缺失凭据所有者时拒绝 Secret 写入，夹具不提供任何内存 Secret 存储。 */
    @Override
    public CredentialResult setCredential(String credentialId, String secret, String expectedVersion) {
        throw unavailableFailure();
    }

    /** 缺失凭据所有者时拒绝删除，防止伪造 CAS 成功。 */
    @Override
    public CredentialResult deleteCredential(String credentialId, String expectedVersion) {
        throw unavailableFailure();
    }

    /** 返回稳定退化状态，使 runtime/health 测试无需打开真实配置文件。 */
    @Override
    public HealthResult health() {
        return new HealthResult(HealthStatus.DEGRADED, List.of("CONFIGURATION_UNAVAILABLE"));
    }

    /** 普通 transport 测试未配置代际目录，意外访问应立即失败。 */
    @Override
    public ConfigurationGenerationLease acquire(Path workspaceRoot) {
        throw unavailableFailure();
    }

    /** 构造单层缺失投影，确保 read Wire 仍覆盖完整字段闭集。 */
    private static Layer missingLayer(ConfigurationScope scope, boolean trusted) {
        return new Layer(scope, false, trusted, "cfg_missing", LayerStatus.MISSING, null);
    }

    /** 使用稳定配置域分类表达夹具缺能力，不引入生产空实现或 transport 私有异常。 */
    private static ConfigurationError unavailableFailure() {
        return new ConfigurationError(ConfigurationError.Code.IO_FAILURE,
                "configuration service is unavailable");
    }
}
