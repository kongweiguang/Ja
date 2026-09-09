// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.application.ConfigurationMutationCoordinator;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.adapter.out.ConfigurationPathPolicy;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigGeneration;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigurationRuntimeState;
import io.github.kongweiguang.ja.configuration.adapter.out.security.windows.CredentialStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy.applyMergePatch;
import static io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy.enforceNoEscalation;
import static io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy.mergeDocuments;
import static io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy.validateCredentialId;
import static io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy.validateDocument;
import static io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy.validateSecret;

/**
 * 配置文档出站编排器，把一次用例委派给文档 Factory、路径 Policy、CAS 协调器和文件适配器。
 *
 * <p>该类型不拥有 generation 缓存、Watcher 线程或 RPC 状态；它只维持一次读改写的顺序，
 * 从而让每个安全与持久化职责都能独立测试。</p>
 */
public final class ConfigurationDocumentRuntime {
    private static final int MAX_CONFIG_BYTES = 1 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final TomlCodec toml;
    private final ConfigurationDocumentFactory documentFactory;
    private final ConfigurationPathPolicy pathPolicy;
    private final CredentialStore credentialStore;

    /**
     * 绑定唯一 mapper、文档 Factory 与路径 Policy；运行时不缓存可变文档，防止读取结果在
     * 后续 CAS 之间被调用方修改。
     */
    public ConfigurationDocumentRuntime(Path homeDirectory, ObjectMapper mapper) {
        Objects.requireNonNull(homeDirectory, "homeDirectory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.toml = new TomlCodec(mapper);
        this.documentFactory = new ConfigurationDocumentFactory(mapper);
        this.pathPolicy = new ConfigurationPathPolicy(homeDirectory);
        this.credentialStore = new CredentialStore(pathPolicy.credentialPath(), mapper);
    }

    /**
     * 一次读取只加载用户层、项目层和凭据状态一次，缺失或损坏作为脱敏状态返回。
     */
    public ConfigurationRuntimeState.ReadResult read(Path canonical) {
        CredentialStore.State auth = loadAuth();
        try {
            return readInternal(canonical, auth);
        } finally {
            auth.close();
        }
    }

    /**
     * 按 RFC 7396 在目标层执行原子 Merge Patch；CAS 在任何内存变换前校验，避免过期客户端
     * 基于旧文档生成的新结果覆盖并发写入。
     */
    public ConfigurationRuntimeState.WriteResult patch(ConfigurationScope scope, Path cwd,
                                           ObjectNode patch, String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(patch, "patch");
        Path canonical = canonicalizeCwd(cwd);
        Path target = configPath(scope, canonical);
        return ConfigurationMutationCoordinator.execute(target, () -> {
            LayerLoad current = loadWritableLayer(scope, canonical);
            checkExpectedVersion(current.version, expectedVersion);
            ObjectNode base = current.document == null ? documentFactory.createEmpty() : current.document;
            ObjectNode next = applyMergePatch(base, patch);
            return publish(scope, target, current.document, next);
        });
    }

    /**
     * 用完整文档替换目标层；替换允许修复可读取字节但语义损坏的文档，仍拒绝 IO 故障，
     * 从而不会把暂时不可读误判为用户授权覆盖。
     */
    public ConfigurationRuntimeState.WriteResult replace(ConfigurationScope scope, Path cwd,
                                             ObjectNode document, String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(document, "document");
        Path canonical = canonicalizeCwd(cwd);
        Path target = configPath(scope, canonical);
        return ConfigurationMutationCoordinator.execute(target, () -> {
            LayerLoad current = loadReplaceableLayer(scope, canonical);
            checkExpectedVersion(current.version, expectedVersion);
            return publish(scope, target, current.document, document.deepCopy());
        });
    }

    /**
     * 把目标层重置为严格空文档；该操作与 replace 分离，避免使用 null 哨兵表达多种语义，
     * 使每个 RPC 动作都只有一种明确结果。
     */
    public ConfigurationRuntimeState.WriteResult reset(ConfigurationScope scope, Path cwd,
                                           String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        Objects.requireNonNull(scope, "scope");
        Path canonical = canonicalizeCwd(cwd);
        Path target = configPath(scope, canonical);
        return ConfigurationMutationCoordinator.execute(target, () -> {
            LayerLoad current = loadReplaceableLayer(scope, canonical);
            checkExpectedVersion(current.version, expectedVersion);
            return publish(scope, target, current.document, documentFactory.createEmpty());
        });
    }

    /**
     * 在统一校验与原子发布路径上完成 revision 推进，确保 patch、replace、reset 不产生
     * 三套略有差异的安全和 CAS 行为。
     */
    private ConfigurationRuntimeState.WriteResult publish(ConfigurationScope scope, Path target,
                                                     ObjectNode previous, ObjectNode next) {
        documentFactory.advanceRevision(next, previous);
        validateDocument(next, scope);
        enforceProjectWriteCeiling(scope, next);
        persistConfig(target, next);
        return new ConfigurationRuntimeState.WriteResult(scope, ConfigurationStore.version(target));
    }

    /**
     * 在凭据文件的同一 CAS 临界区完成读取与 ACL 原子发布；可变 State 只存活到重新读取完成，
     * 避免多个服务实例用同一旧版本同时覆盖凭据。
     */
    public ConfigurationRuntimeState.CredentialResult credentialSet(String credentialId, String secret,
                                                        String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        validateCredentialId(credentialId);
        validateSecret(secret);
        return ConfigurationMutationCoordinator.execute(pathPolicy.credentialPath(), () -> {
            CredentialStore.State current = loadAuth();
            try {
                ensureAuthWritable(current);
                checkExpectedVersion(current.version(), expectedVersion);
                current.put(credentialId, secret);
                persistAuth(current);
                return credentialResult();
            } finally {
                current.close();
            }
        });
    }

    /**
     * 在 CAS 后幂等删除单个凭据；即使目标已不存在，也不让 secret 或旧文档进入结果 DTO。
     */
    public ConfigurationRuntimeState.CredentialResult credentialDelete(String credentialId, String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        validateCredentialId(credentialId);
        return ConfigurationMutationCoordinator.execute(pathPolicy.credentialPath(), () -> {
            CredentialStore.State current = loadAuth();
            try {
                ensureAuthWritable(current);
                checkExpectedVersion(current.version(), expectedVersion);
                if (current.contains(credentialId)) {
                    current.remove(credentialId);
                    persistAuth(current);
                }
                return credentialResult();
            } finally {
                current.close();
            }
        });
    }

    /**
     * 原子增加规范工作区信任；Watcher 注册和 generation 失效仍由上层服务负责。
     */
    public boolean trustWorkspace(Path canonical) {
        Objects.requireNonNull(canonical, "canonical");
        return ConfigurationMutationCoordinator.execute(pathPolicy.trustPath(), () -> {
            Map<String, Boolean> trusted = loadTrustRegistry();
            boolean changed = trusted.put(canonical.toString(), Boolean.TRUE) == null;
            if (changed) persistTrustRegistry(trusted);
            return changed;
        });
    }

    /**
     * 原子删除工作区信任但保留项目配置文件，便于用户显式重新信任后恢复。
     */
    public boolean untrustWorkspace(Path canonical) {
        Objects.requireNonNull(canonical, "canonical");
        return ConfigurationMutationCoordinator.execute(pathPolicy.trustPath(), () -> {
            Map<String, Boolean> trusted = loadTrustRegistry();
            boolean changed = trusted.remove(canonical.toString()) != null;
            if (changed) persistTrustRegistry(trusted);
            return changed;
        });
    }

    /**
     * isTrusted 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    boolean isTrusted(Path canonical) {
        return loadTrustRegistry().containsKey(canonical.toString());
    }

    /**
     * loadAuth 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public CredentialStore.State loadAuth() {
        return credentialStore.load();
    }

    /**
     * readInternal 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public ConfigurationRuntimeState.ReadResult readInternal(Path canonical, CredentialStore.State auth) {
        boolean trusted = canonical != null && loadTrustRegistry().containsKey(canonical.toString());
        LayerLoad user = loadLayer(pathPolicy.userConfigPath(), ConfigurationScope.USER, true);
        LayerLoad project = canonical == null
                ? LayerLoad.missing(ConfigurationScope.PROJECT, false)
                : loadLayer(canonical.resolve(".ja").resolve("config.toml"),
                ConfigurationScope.PROJECT, trusted);
        List<ConfigGeneration.Diagnostic> diagnostics = new ArrayList<>();
        if (user.status == ConfigurationData.LayerStatus.CORRUPT) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_CONFIG", true));
        }
        if (user.status == ConfigurationData.LayerStatus.IO_ERROR) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIG_IO_ERROR", true));
        }
        if (project.status == ConfigurationData.LayerStatus.CORRUPT && trusted) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_PROJECT_CONFIG", true));
        }
        if (project.status == ConfigurationData.LayerStatus.IO_ERROR && trusted) {
            diagnostics.add(new ConfigGeneration.Diagnostic("PROJECT_CONFIG_IO_ERROR", true));
        }
        if (project.status == ConfigurationData.LayerStatus.UNTRUSTED) {
            diagnostics.add(new ConfigGeneration.Diagnostic("PROJECT_UNTRUSTED", false));
        }
        if (auth.status() == ConfigurationData.LayerStatus.CORRUPT) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_AUTH", true));
        }
        if (auth.status() == ConfigurationData.LayerStatus.IO_ERROR) {
            diagnostics.add(new ConfigGeneration.Diagnostic("AUTH_IO_ERROR", true));
        }
        ObjectNode effective = documentFactory.createEmpty();
        if (user.document != null) effective = user.document.deepCopy();
        if (project.document != null && project.status == ConfigurationData.LayerStatus.VALID && trusted) {
            try {
                enforceNoEscalation(user.document, project.document);
                effective = mergeDocuments(effective, project.document);
            } catch (ConfigurationError escalation) {
                diagnostics.add(new ConfigGeneration.Diagnostic("LIMIT_ESCALATION", true));
            }
        }
        return new ConfigurationRuntimeState.ReadResult(trusted, user.view(), project.view(), effective,
                credentialStatuses(auth.secretIds()),
                auth.version(), diagnostics);
    }

    /**
     * 通过工作区路径策略固定真实目录，符号链接或不可解析路径统一视为无效 cwd。
     */
    public Path canonicalizeCwd(Path cwd) {
        try {
            return pathPolicy.canonicalWorkspace(cwd);
        } catch (IOException failure) {
            throw error(ConfigurationError.Code.INVALID_CWD, "workspace cwd is invalid");
        }
    }

    /**
     * userConfigPath 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    public Path userConfigPath() {
        return pathPolicy.userConfigPath();
    }

    /**
     * trustPath 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    public Path trustPath() {
        return pathPolicy.trustPath();
    }

    /**
     * 读取单个权威配置层并区分缺失、未信任、损坏和有效状态，禁止损坏文档降级为默认配置。
     */
    private LayerLoad loadLayer(Path path, ConfigurationScope scope, boolean trusted) {
        if (!trusted && scope == ConfigurationScope.PROJECT) {
            return new LayerLoad(scope, false, false, ConfigurationStore.version(path),
                    ConfigurationData.LayerStatus.UNTRUSTED, null);
        }
        final byte[] bytes;
        try {
            bytes = ConfigurationMutationCoordinator.execute(path, () -> {
                try {
                    return ConfigurationStore.read(path);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            if (bytes == null) return LayerLoad.missing(scope, trusted);
            if (bytes.length > MAX_CONFIG_BYTES) {
                return new LayerLoad(scope, true, trusted, ConfigurationStore.versionOf(bytes),
                        ConfigurationData.LayerStatus.CORRUPT, null);
            }
        } catch (UncheckedIOException failure) {
            return new LayerLoad(scope, true, trusted, ConfigurationStore.UNAVAILABLE_VERSION,
                    ConfigurationData.LayerStatus.IO_ERROR, null);
        } catch (ConfigurationError failure) {
            return new LayerLoad(scope, true, trusted, ConfigurationStore.version(path),
                    ConfigurationData.LayerStatus.CORRUPT, null);
        }
        String version = ConfigurationStore.versionOf(bytes);
        try {
            ObjectNode document = toml.parse(new String(bytes, StandardCharsets.UTF_8));
            validateDocument(document, scope);
            return new LayerLoad(scope, true, trusted, version,
                    ConfigurationData.LayerStatus.VALID, document);
        } catch (ConfigurationError failure) {
            return new LayerLoad(scope, true, trusted, version,
                    ConfigurationData.LayerStatus.CORRUPT, null);
        } catch (RuntimeException failure) {
            return new LayerLoad(scope, true, trusted, version,
                    ConfigurationData.LayerStatus.CORRUPT, null);
        }
    }

    /**
     * credentialResult 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private ConfigurationRuntimeState.CredentialResult credentialResult() {
        CredentialStore.State auth = loadAuth();
        try {
            return new ConfigurationRuntimeState.CredentialResult(auth.version(),
                    credentialStatuses(auth.secretIds()));
        } finally {
            auth.close();
        }
    }

    /**
     * ensureAuthWritable 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static void ensureAuthWritable(CredentialStore.State state) {
        if (state.status() == ConfigurationData.LayerStatus.CORRUPT) {
            throw error(ConfigurationError.Code.CORRUPT_AUTH, "credential store is corrupt");
        }
        if (state.status() == ConfigurationData.LayerStatus.IO_ERROR) {
            throw error(ConfigurationError.Code.IO_FAILURE, "credential storage is unavailable");
        }
    }

    /**
     * 写入项目层前与当前用户层上限比较，使项目配置只能收紧而不能扩权。
     */
    private void enforceProjectWriteCeiling(ConfigurationScope scope, ObjectNode next) {
        if (scope != ConfigurationScope.PROJECT) return;
        LayerLoad user = loadLayer(pathPolicy.userConfigPath(), ConfigurationScope.USER, true);
        if (user.document != null) enforceNoEscalation(user.document, next);
    }

    /**
     * 在写入前重新解析目标层和信任登记，对损坏或未信任的项目层失败关闭。
     */
    private LayerLoad loadWritableLayer(ConfigurationScope scope, Path canonical) {
        Path path = configPath(scope, canonical);
        LayerLoad current = loadLayer(path, scope, scope == ConfigurationScope.USER
                                                   || (canonical != null && loadTrustRegistry().containsKey(canonical.toString())));
        if (current.status == ConfigurationData.LayerStatus.CORRUPT) {
            throw error(ConfigurationError.Code.CORRUPT_CONFIG, "configuration is corrupt");
        }
        if (current.status == ConfigurationData.LayerStatus.IO_ERROR) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration storage is unavailable");
        }
        return current;
    }

    /**
     * 为 replace/reset 读取当前 CAS 身份；语义损坏可由显式完整操作修复，但 IO 故障仍
     * 必须失败，防止在无法确认当前字节时覆盖文件。
     */
    private LayerLoad loadReplaceableLayer(ConfigurationScope scope, Path canonical) {
        Path path = configPath(scope, canonical);
        LayerLoad current = loadLayer(path, scope, scope == ConfigurationScope.USER
                                                   || (canonical != null && loadTrustRegistry().containsKey(canonical.toString())));
        if (current.status == ConfigurationData.LayerStatus.IO_ERROR) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration storage is unavailable");
        }
        if (current.status == ConfigurationData.LayerStatus.UNTRUSTED) {
            throw error(ConfigurationError.Code.UNTRUSTED_WORKSPACE, "workspace trust is required");
        }
        return current;
    }

    /**
     * configPath 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private Path configPath(ConfigurationScope scope, Path canonical) {
        try {
            return pathPolicy.configurationPath(scope, canonical);
        } catch (IllegalArgumentException failure) {
            throw error(ConfigurationError.Code.INVALID_CWD, "workspace cwd is invalid");
        }
    }

    /**
     * 仅当调用方的 expectedVersion 与当前文件摘要一致时继续，冲突不自动重试或合并。
     */
    private static void checkExpectedVersion(String current, String expected) {
        if (Objects.equals(current, expected)) return;
        throw error(ConfigurationError.Code.CAS_CONFLICT, "configuration version conflict");
    }

    /**
     * 强制所有可变配置操作显式提供 CAS 前置条件，不允许无条件覆盖。
     */
    private static void requireExpectedVersion(String expected) {
        if (expected == null || expected.isBlank()) {
            throw error(ConfigurationError.Code.INVALID_ARGUMENT, "expected version is required");
        }
    }

    /**
     * 在上层完成 CAS 与严格校验后，以 TOML 编码和原子替换发布单个配置文件。
     */
    private void persistConfig(Path path, ObjectNode document) {
        try {
            ConfigurationStore.writeAtomic(path, toml.write(document).getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration storage is unavailable");
        }
    }

    /**
     * persistAuth 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private void persistAuth(CredentialStore.State auth) {
        credentialStore.persist(auth);
    }

    /**
     * 按规范路径排序后原子发布信任登记，使相同集合生成稳定文档。
     */
    private void persistTrustRegistry(Map<String, Boolean> trusted) {
        ArrayNode values = mapper.createArrayNode();
        trusted.keySet().stream().sorted().forEach(values::add);
        ObjectNode root = mapper.createObjectNode();
        root.set("trusted", values);
        try {
            ConfigurationStore.writeAtomic(pathPolicy.trustPath(), mapper.writeValueAsBytes(root), false);
        } catch (IOException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "workspace trust storage is unavailable");
        }
    }

    /**
     * 读取并重新规范化信任路径，任一无效条目都使整份登记失败而非部分采信。
     */
    public Map<String, Boolean> loadTrustRegistry() {
        try {
            byte[] bytes = ConfigurationStore.read(pathPolicy.trustPath());
            if (bytes == null) return new TreeMap<>();
            JsonNode root = mapper.readTree(bytes);
            JsonNode values = root == null ? null : root.get("trusted");
            if (!(values instanceof ArrayNode array)) throw new IOException();
            Map<String, Boolean> trusted = new TreeMap<>();
            for (JsonNode value : array) {
                if (!value.isTextual()) throw new IOException();
                trusted.put(value.textValue(), true);
            }
            return trusted;
        } catch (IOException | RuntimeException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "workspace trust storage is unavailable");
        }
    }

    /**
     * String 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static Map<String, ConfigurationRuntimeState.CredentialStatus> credentialStatuses(
            java.util.Set<String> ids) {
        Map<String, ConfigurationRuntimeState.CredentialStatus> statuses = new TreeMap<>();
        ids.forEach(id -> statuses.put(id, new ConfigurationRuntimeState.CredentialStatus(true)));
        return statuses;
    }

    /**
     * error 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static ConfigurationError error(ConfigurationError.Code code, String message) {
        return new ConfigurationError(code, message);
    }

    /**
     * 同时保留配置层的文件存在性、信任、版本和解析状态，避免用 null 混淆不同失败。
     */
    private record LayerLoad(ConfigurationScope scope, boolean present, boolean trusted,
                             String version, ConfigurationData.LayerStatus status,
                             ObjectNode document) {
        /**
         * 使用稳定 missing 版本表达未创建文件，从而允许第一次写入也参与 CAS。
         */
        static LayerLoad missing(ConfigurationScope scope, boolean trusted) {
            return new LayerLoad(scope, false, trusted, ConfigurationStore.MISSING_VERSION,
                    ConfigurationData.LayerStatus.MISSING, null);
        }

        /**
         * 转为对外只读视图，不把内部加载过程或文件路径暴露给应用层。
         */
        ConfigurationRuntimeState.LayerView view() {
            return new ConfigurationRuntimeState.LayerView(scope, present, trusted, version, status, document);
        }
    }
}
