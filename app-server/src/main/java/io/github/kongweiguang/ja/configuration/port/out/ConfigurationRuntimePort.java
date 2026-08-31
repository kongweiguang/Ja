// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.port.out;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;

import java.nio.file.Path;

/**
 * 配置应用层访问文件、凭据、Watcher 与代际运行时的唯一出站端口。
 *
 * <p>端口共享配置域的不可变模型，不暴露 Jackson、文件句柄或具体适配器类型。</p>
 */
public interface ConfigurationRuntimePort extends ConfigurationData {
    /** 读取用户层与可选项目层的脱敏快照。 */
    ReadResult read(Path workspaceRoot);

    /** 在适配器的原子 CAS 边界内应用 RFC 7396 Merge Patch。 */
    MutationResult patch(ConfigurationScope scope, Path workspaceRoot, Document patch,
                         String expectedVersion);

    /** 在适配器的原子 CAS 边界内完整替换配置文档。 */
    MutationResult replace(ConfigurationScope scope, Path workspaceRoot, Document document,
                           String expectedVersion);

    /** 原子重置指定配置层。 */
    MutationResult reset(ConfigurationScope scope, Path workspaceRoot, String expectedVersion);

    /** 写入 Secret 并只返回脱敏状态。 */
    CredentialResult setCredential(String credentialId, String secret, String expectedVersion);

    /** 删除 Secret 并只返回脱敏状态。 */
    CredentialResult deleteCredential(String credentialId, String expectedVersion);

    /** 返回文件运行时根据阻断诊断计算的有界健康结果。 */
    HealthResult health();

    /** 持久化工作区信任并同步 Watcher 与代际缓存。 */
    boolean synchronizeWorkspaceTrust(Path workspaceRoot, boolean trusted);

    /** 原子解析并持有指定工作区当前配置代际。 */
    GenerationLease acquire(Path workspaceRoot);

    /**
     * 出站代际租约；应用层负责把该生命周期能力投影为入站租约，避免适配器实现 port.in。
     */
    interface GenerationLease extends AutoCloseable {
        /** 返回不透明代际标识。 */
        String generationId();

        /** 返回当前租约固定的领域投影。 */
        ConfigurationGenerationSnapshot snapshot();

        /** 为当前连接短时借用一个 Secret，调用方不得缓存或记录返回值。 */
        String secretFor(String credentialId);

        /** 幂等释放租约，并在最后一个引用结束时清除 Secret。 */
        @Override
        void close();
    }
}
