// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.port.in;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;

import java.nio.file.Path;

/**
 * 定义配置与凭据的入站用例；端口继承配置域的纯 JDK 数据模型，不暴露文件或序列化实现。
 *
 * <p>工作区路径是 workspace 域在进程内解析后的能力，不属于 JA-RPC Wire 字段。Secret 只允许进入
 * {@link #setCredential(String, String, String)}，任何读取结果都只能返回 configured 状态。</p>
 */
public interface ConfigurationUseCase extends ConfigurationData {
    /** 读取用户层与可选工作区层的脱敏快照，不返回规范路径或 Secret。 */
    ReadResult read(Path workspaceRoot);

    /** 按 RFC 7396 应用对象 Merge Patch，并以目标层版本执行 CAS。 */
    MutationResult patch(ConfigurationScope scope, Path workspaceRoot, Document patch,
                         String expectedVersion);

    /** 用完整严格文档替换目标层；该操作允许修复语义损坏但仍可读取的文档。 */
    MutationResult replace(ConfigurationScope scope, Path workspaceRoot, Document document,
                           String expectedVersion);

    /** 把目标层重置为严格空文档，不使用 null 或 mode 哨兵复用其它动作。 */
    MutationResult reset(ConfigurationScope scope, Path workspaceRoot, String expectedVersion);

    /** 原子设置一个凭据，并且只返回脱敏状态和新的 CAS 版本。 */
    CredentialResult setCredential(String credentialId, String secret, String expectedVersion);

    /** 原子删除一个凭据，并且只返回脱敏状态和新的 CAS 版本。 */
    CredentialResult deleteCredential(String credentialId, String expectedVersion);

    /** 返回配置子系统的有界健康状态，不暴露文档、路径或解析错误文本。 */
    HealthResult health();
}
