// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.port.out;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;

import java.nio.file.Path;

/**
 * 由 catalog 消费方定义的配置代际出站端口，隔离 configuration 入站用例及其生命周期类型。
 */
public interface ConfigurationGenerationPort {
    /**
     * 为规范工作区取得一个不可变配置代际；null 仅表示 Java 所有的通用工作区。
     */
    Lease acquire(Path workspaceRoot);

    /**
     * 绑定一个代际的领域投影与 Secret 借用权，关闭后不得继续读取或解析凭据。
     */
    interface Lease extends AutoCloseable {
        /** 返回不透明代际标识，用于事件关联与缓存隔离。 */
        String generationId();

        /** 返回不含 Secret 和适配器类型的冻结领域投影。 */
        ConfigurationGenerationSnapshot snapshot();

        /**
         * 短时解析选中凭据；调用方不得缓存、记录或把结果写入共享目录。
         */
        String secretFor(String credentialId);

        /** 幂等释放底层代际所有权，使最后一个租约可以清除 Secret。 */
        @Override
        void close();
    }
}
