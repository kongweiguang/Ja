// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationStore;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件适配器内部的 Jackson 状态载体；集中隔离文档运行时、Watcher 与代际缓存的共享数据。
 */
public final class ConfigurationRuntimeState {
    /** 该类型只作为内部状态命名空间，禁止实例化。 */
    private ConfigurationRuntimeState() {
    }

    /** 适配器内部凭据状态只表达是否已配置，绝不保存或格式化 Secret。 */
    public record CredentialStatus(boolean configured) {
    }

    /** Watcher 发布的脱敏变更事件；路径只用于进程内代际失效。 */
    public static final class ConfigChanged {
        private final String scope;
        private final Path canonicalCwd;
        private final String version;

        /** 将变更通知收敛为稳定 scope 和 version，避免 Watcher 回调传播空元数据。 */
        public ConfigChanged(String scope, Path canonicalCwd, String version) {
            this.scope = scope == null || scope.isBlank() ? "unknown" : scope;
            this.canonicalCwd = canonicalCwd;
            this.version = version == null || version.isBlank()
                    ? ConfigurationStore.MISSING_VERSION : version;
        }

        /** 返回发生变化的配置层类别。 */
        public String scope() {
            return scope;
        }

        /** 返回仅供 Watcher 与代际缓存关联使用的规范工作区路径。 */
        public Path canonicalCwd() {
            return canonicalCwd;
        }

        /** 返回变更后的权威版本，文件缺失时使用稳定 missing 标记。 */
        public String version() {
            return version;
        }

        /** 只暴露稳定且脱敏的诊断语义，禁止路径、正文或敏感值进入日志。 */
        @Override
        public String toString() {
            return "ConfigChanged[scope=" + scope + ", workspace="
                    + (canonicalCwd == null ? "none" : "present") + ", version=" + version + "]";
        }
    }

    /** 单个配置层的适配器内部快照；文档在构造与读取时都执行深复制。 */
    public static final class LayerView {
        private final ConfigurationScope scope;
        private final boolean present;
        private final boolean trusted;
        private final String version;
        private final ConfigurationData.LayerStatus status;
        private final ObjectNode document;

        /** 冻结单层读取结果并深拷贝文档，防止调用方改写 CAS 所对应的快照。 */
        public LayerView(ConfigurationScope scope, boolean present, boolean trusted, String version,
                  ConfigurationData.LayerStatus status, ObjectNode document) {
            this.scope = scope;
            this.present = present;
            this.trusted = trusted;
            this.version = version;
            this.status = status;
            this.document = document == null ? null : document.deepCopy();
        }

        /** 返回该层的持久化作用域。 */
        public ConfigurationScope scope() {
            return scope;
        }

        /** 返回权威文件是否存在。 */
        public boolean present() {
            return present;
        }

        /** 返回项目层是否可参与当前合并。 */
        public boolean trusted() {
            return trusted;
        }

        /** 返回该层内容摘要或 missing 标记。 */
        public String version() {
            return version;
        }

        /** 返回缺失、有效、未信任、损坏或 IO 故障的精确状态。 */
        public ConfigurationData.LayerStatus status() {
            return status;
        }

        /** 返回文档深副本；无可用文档时返回 null。 */
        public JsonNode document() {
            return document == null ? null : document.deepCopy();
        }
    }

    /** 文档运行时内部读取结果；配置正文仍是 Jackson 树。 */
    public static final class ReadResult {
        private final boolean trusted;
        private final LayerView user;
        private final LayerView project;
        private final ObjectNode effective;
        private final Map<String, CredentialStatus> credentials;
        private final String credentialVersion;
        private final List<ConfigGeneration.Diagnostic> diagnostics;

        /** 冻结一次配置读取的合并结果、凭据状态和诊断，不携带任何 Secret。 */
        public ReadResult(boolean trusted, LayerView user, LayerView project,
                   ObjectNode effective, Map<String, CredentialStatus> credentials,
                   String credentialVersion, List<ConfigGeneration.Diagnostic> diagnostics) {
            this.trusted = trusted;
            this.user = user;
            this.project = project;
            this.effective = effective.deepCopy();
            this.credentials = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
            this.credentialVersion = credentialVersion;
            this.diagnostics = List.copyOf(diagnostics);
        }

        /** 返回项目层是否已获 Java 侧信任。 */
        public boolean trusted() {
            return trusted;
        }

        /** 返回用户层快照。 */
        public LayerView user() {
            return user;
        }

        /** 返回项目层快照。 */
        public LayerView project() {
            return project;
        }

        /** 返回合并后文档深副本。 */
        public JsonNode effective() {
            return effective.deepCopy();
        }

        /** 返回按身份索引的脱敏凭据状态。 */
        public Map<String, CredentialStatus> credentials() {
            return credentials;
        }

        /** 返回凭据文件的 CAS 版本。 */
        public String credentialVersion() {
            return credentialVersion;
        }

        /** 返回稳定诊断及其阻断属性。 */
        public List<ConfigGeneration.Diagnostic> diagnostics() {
            return diagnostics;
        }
    }

    /** 配置写入的原子结果，只携带目标层与新 CAS 版本。 */
    public record WriteResult(ConfigurationScope scope, String version) {
    }

    /** 凭据文件内部写入结果；只保存版本和 configured 状态集合。 */
    public record CredentialResult(String version, Map<String, CredentialStatus> statuses) {
        /** 冻结凭据版本与脱敏状态，禁止调用方改写后续 CAS 视图。 */
        public CredentialResult {
            statuses = Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
        }
    }
}
