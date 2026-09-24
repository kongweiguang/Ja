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
    private final Path lastKnownGoodPath;

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
        this.lastKnownGoodPath = pathPolicy.userConfigPath().resolveSibling("config.toml.last-known-good");
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
            ObjectNode currentDocument = current.document == null
                    ? scope == ConfigurationScope.PROJECT ? documentFactory.createProjectEmpty()
                            : documentFactory.createEmpty() : current.document;
            ObjectNode next = applyMergePatch(currentDocument, patch);
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
            return publish(scope, target, current.document, scope == ConfigurationScope.PROJECT
                    ? documentFactory.createProjectEmpty() : documentFactory.createEmpty());
        });
    }

    /**
     * 仅恢复 Java 管理的用户层最近完整快照，并在替换前保留当前原始文件副本。
     *
     * <p>语法损坏时不能让 renderer 拼一份空文档覆盖现场；恢复必须由 App Server 读取快照、
     * 校验当前 CAS、备份可读原文后再原子发布。项目层没有共享快照，避免把一个工作区的信任或
     * 限制意外带到另一个工作区。</p>
     */
    public ConfigurationRuntimeState.WriteResult restoreLastKnownGood(ConfigurationScope scope, Path cwd,
                                                                        String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (scope != ConfigurationScope.USER) {
            throw error(ConfigurationError.Code.INVALID_ARGUMENT, "only user configuration can be restored");
        }
        Path canonical = canonicalizeCwd(cwd);
        Path target = configPath(scope, canonical);
        return ConfigurationMutationCoordinator.execute(target, () -> {
            final byte[] current;
            try {
                current = readWithRetry(target);
            } catch (IOException failure) {
                throw error(ConfigurationError.Code.IO_FAILURE, "configuration storage is unavailable");
            }
            String currentVersion = current == null
                    ? ConfigurationStore.MISSING_VERSION : ConfigurationStore.versionOf(current);
            checkExpectedVersion(currentVersion, expectedVersion);
            ObjectNode recovered = loadLastKnownGoodSource();
            if (recovered == null) {
                throw error(ConfigurationError.Code.CORRUPT_CONFIG, "no recoverable configuration snapshot exists");
            }
            if (current != null) backupUserConfiguration(current);
            documentFactory.advanceRevision(recovered, recovered);
            persistConfig(target, recovered);
            return new ConfigurationRuntimeState.WriteResult(scope, ConfigurationStore.version(target));
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
        List<ConfigurationData.Issue> issues = new ArrayList<>();
        issues.addAll(user.issues);
        issues.addAll(project.issues);
        if (!issues.isEmpty()) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIGURATION_ISSUES", false));
        }
        if (user.status == ConfigurationData.LayerStatus.CORRUPT) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_CONFIG", false));
            issues.add(issue("user_file", "user", null, null, "TOML_PARSE_FAILED",
                    "defaults_in_use", List.of("edit", "restore")));
        }
        if (user.status == ConfigurationData.LayerStatus.IO_ERROR) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIG_IO_ERROR", false));
            issues.add(issue("user_file", "user", null, null, "FILE_UNAVAILABLE",
                    "defaults_in_use", List.of("retry")));
        }
        if (project.status == ConfigurationData.LayerStatus.CORRUPT && trusted) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_PROJECT_CONFIG", false));
            issues.add(issue("project_file", "project", null, null, "TOML_PARSE_FAILED",
                    "project_ignored", List.of("edit", "restore")));
        }
        if (project.status == ConfigurationData.LayerStatus.IO_ERROR && trusted) {
            diagnostics.add(new ConfigGeneration.Diagnostic("PROJECT_CONFIG_IO_ERROR", false));
            issues.add(issue("project_file", "project", null, null, "FILE_UNAVAILABLE",
                    "project_ignored", List.of("retry")));
        }
        if (project.status == ConfigurationData.LayerStatus.UNTRUSTED) {
            diagnostics.add(new ConfigGeneration.Diagnostic("PROJECT_UNTRUSTED", false));
        }
        if (auth.status() == ConfigurationData.LayerStatus.CORRUPT) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_AUTH", false));
            issues.add(issue("credentials", "credential", null, null, "CREDENTIALS_UNAVAILABLE",
                    "credential_connections_unavailable", List.of("retry")));
        }
        if (auth.status() == ConfigurationData.LayerStatus.IO_ERROR) {
            diagnostics.add(new ConfigGeneration.Diagnostic("AUTH_IO_ERROR", false));
            issues.add(issue("credentials", "credential", null, null, "CREDENTIALS_UNAVAILABLE",
                    "credential_connections_unavailable", List.of("retry")));
        }
        ObjectNode effective = documentFactory.createEmpty();
        if (user.document != null) effective = user.document.deepCopy();
        else if (user.status == ConfigurationData.LayerStatus.CORRUPT
                || user.status == ConfigurationData.LayerStatus.IO_ERROR) {
            ObjectNode recovered = loadLastKnownGood();
            if (recovered != null) {
                effective = recovered;
                issues.add(issue("last_known_good", "user", null, null, "LAST_KNOWN_GOOD_IN_USE",
                        "snapshot_in_use", List.of("edit", "restore")));
            }
        }
        java.util.Set<String> globalMcpIds = mcpIds(effective);
        if (project.document != null && project.status == ConfigurationData.LayerStatus.VALID && trusted) {
            try {
                ObjectNode applicableProject = project.document.deepCopy();
                filterConflictingProjectMcp(effective, applicableProject, issues);
                enforceNoEscalation(user.document, applicableProject);
                effective = mergeDocuments(effective, applicableProject);
            } catch (ConfigurationError escalation) {
                diagnostics.add(new ConfigGeneration.Diagnostic("LIMIT_ESCALATION", false));
                issues.add(issue("project_limits", "project", null, null, "LIMIT_ESCALATION",
                        "project_ignored", List.of("edit")));
            }
        }
        if (!issues.isEmpty() && diagnostics.stream().noneMatch(
                diagnostic -> "CONFIGURATION_ISSUES".equals(diagnostic.code()))) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIGURATION_ISSUES", false));
        }
        java.util.Set<String> projectMcpIds = new java.util.HashSet<>();
        if (project.document != null && project.status == ConfigurationData.LayerStatus.VALID && trusted) {
            projectMcpIds.addAll(mcpIds(effective));
            projectMcpIds.removeAll(globalMcpIds);
        }
        return new ConfigurationRuntimeState.ReadResult(trusted, user.view(), project.view(), effective,
                credentialStatuses(auth.secretIds()),
                auth.version(), diagnostics, issues, projectMcpIds);
    }

    /** 只比较已验证目录的稳定身份，避免通过同名或端点推测配置来源。 */
    private static java.util.Set<String> mcpIds(ObjectNode document) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (document.get("mcp_servers") instanceof ArrayNode servers) {
            servers.forEach(server -> ids.add(server.path("mcp_id").asText()));
        }
        return ids;
    }

    /** 项目与全局 ID 冲突时只隔离项目条目，保留其它项目服务和 Skill。 */
    private static void filterConflictingProjectMcp(ObjectNode base, ObjectNode project,
                                                     List<ConfigurationData.Issue> issues) {
        if (!(project.get("mcp_servers") instanceof com.fasterxml.jackson.databind.node.ArrayNode projectServers)) {
            return;
        }
        java.util.Set<String> globalIds = new java.util.HashSet<>();
        if (base.get("mcp_servers") instanceof com.fasterxml.jackson.databind.node.ArrayNode globalServers) {
            globalServers.forEach(server -> globalIds.add(server.path("mcp_id").asText()));
        }
        com.fasterxml.jackson.databind.node.ArrayNode accepted = project.arrayNode();
        for (JsonNode server : projectServers) {
            String id = server.path("mcp_id").asText();
            if (globalIds.contains(id)) {
                issues.add(issue("project_mcp_conflict_" + id, "project", "mcp_servers", id,
                        "MCP_ID_CONFLICT", "entry_skipped", List.of("edit")));
            } else {
                accepted.add(server.deepCopy());
            }
        }
        project.set("mcp_servers", accepted);
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
     * 读取单个权威配置层并区分缺失、未信任和 TOML 语法损坏。可解析文档先尝试严格路径以更新
     * 最近有效快照；严格语义失败后只隔离坏字段或条目，绝不把整个用户层投影为空。
     */
    private LayerLoad loadLayer(Path path, ConfigurationScope scope, boolean trusted) {
        if (!trusted && scope == ConfigurationScope.PROJECT) {
            return new LayerLoad(scope, false, false, ConfigurationStore.version(path),
                    ConfigurationData.LayerStatus.UNTRUSTED, null, null, List.of());
        }
        final byte[] bytes;
        try {
            bytes = ConfigurationMutationCoordinator.execute(path, () -> {
                try {
                    return readWithRetry(path);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            if (bytes == null) return LayerLoad.missing(scope, trusted);
            if (bytes.length > MAX_CONFIG_BYTES) {
                return new LayerLoad(scope, true, trusted, ConfigurationStore.versionOf(bytes),
                        ConfigurationData.LayerStatus.CORRUPT, null, null, List.of());
            }
        } catch (UncheckedIOException failure) {
            return new LayerLoad(scope, true, trusted, ConfigurationStore.UNAVAILABLE_VERSION,
                    ConfigurationData.LayerStatus.IO_ERROR, null, null, List.of());
        } catch (ConfigurationError failure) {
            return new LayerLoad(scope, true, trusted, ConfigurationStore.version(path),
                    ConfigurationData.LayerStatus.CORRUPT, null, null, List.of());
        }
        String version = ConfigurationStore.versionOf(bytes);
        try {
            ObjectNode document = toml.parse(new String(bytes, StandardCharsets.UTF_8));
            try {
                validateDocument(document, scope);
                if (scope == ConfigurationScope.USER) persistLastKnownGood(document);
                return new LayerLoad(scope, true, trusted, version,
                        ConfigurationData.LayerStatus.VALID, document, document, List.of());
            } catch (ConfigurationError strictFailure) {
                TolerantConfigurationDocumentReader.Result tolerant =
                        TolerantConfigurationDocumentReader.normalize(document, scope);
                return new LayerLoad(scope, true, trusted, version,
                        ConfigurationData.LayerStatus.VALID, tolerant.document(), document, tolerant.issues());
            }
        } catch (RuntimeException failure) {
            return new LayerLoad(scope, true, trusted, version,
                    ConfigurationData.LayerStatus.CORRUPT, null, null, List.of());
        }
    }

    /**
     * 原子替换和病毒扫描会让 Windows 上的文件在很短窗口内不可读；三次有界重试避免把该瞬态转化为
     * 空配置，而持续失败仍交给上层以可见问题呈现。
     */
    private static byte[] readWithRetry(Path path) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return ConfigurationStore.read(path);
            } catch (IOException failure) {
                last = failure;
                if (attempt == 2) break;
                try {
                    Thread.sleep(80L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("configuration_read_interrupted", interrupted);
                }
            }
        }
        throw last == null ? new IOException("configuration_read_failed") : last;
    }

    /**
     * 写入无密钥、不可编辑的最近完整用户配置原文快照。快照失败不影响本次正常读取，因为它只是后续
     * 语法或 I/O 故障的恢复资源，不能成为启动阻塞点。
     */
    private void persistLastKnownGood(ObjectNode document) {
        try {
            ConfigurationStore.writeAtomic(lastKnownGoodPath,
                    toml.write(document).getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException | RuntimeException ignored) {
            // 快照是增强恢复能力；原始用户配置已验证可用时不得因旁路存储失败失去服务能力。
        }
    }

    /**
     * 只在源文件整体不可用时读取已严格校验的快照；可解析文件永远以当前内容为准，避免回退复活
     * 用户明确删除或停用的条目。无效、过大的或读失败的快照按不存在处理。
     */
    private ObjectNode loadLastKnownGood() {
        return loadLastKnownGoodSource();
    }

    /**
     * 返回完整通过当前 v2 校验的原始快照。快照是恢复资源而非兼容层，因此旧 schema、未知字段或
     * 不完整文档与当前文件一样不可用。
     */
    private ObjectNode loadLastKnownGoodSource() {
        try {
            byte[] bytes = readWithRetry(lastKnownGoodPath);
            if (bytes == null || bytes.length > MAX_CONFIG_BYTES) return null;
            ObjectNode document = toml.parse(new String(bytes, StandardCharsets.UTF_8));
            validateDocument(document, ConfigurationScope.USER);
            return document;
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * 在当前同目录创建一次性备份；备份失败必须中止恢复，避免用户同时失去损坏原文与上次快照之间
     * 的手工改动。普通配置不含凭据，仍保持仅本机文件所有者可读的存储边界。
     */
    private void backupUserConfiguration(byte[] source) {
        Path backup = pathPolicy.userConfigPath().resolveSibling(
                "config.toml.backup-" + Long.toUnsignedString(System.nanoTime()) + ".toml");
        try {
            ConfigurationStore.writeAtomic(backup, source, false);
        } catch (IOException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration backup could not be created");
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
     * 文档运行时只生成稳定、脱敏的问题对象；TOML 原文、异常消息与路径不得穿过该边界。
     */
    private static ConfigurationData.Issue issue(String id, String scope, String field, String entityId,
                                                 String reason, String impact, List<String> actions) {
        return new ConfigurationData.Issue("cfg_" + id, scope, field, entityId, null, null,
                reason, impact, actions);
    }

    /**
     * 同时保留配置层的文件存在性、信任、版本和解析状态，避免用 null 混淆不同失败。
     */
    private record LayerLoad(ConfigurationScope scope, boolean present, boolean trusted,
                             String version, ConfigurationData.LayerStatus status,
                             ObjectNode document, ObjectNode sourceDocument,
                             List<ConfigurationData.Issue> issues) {
        /**
         * 使用稳定 missing 版本表达未创建文件，从而允许第一次写入也参与 CAS。
         */
        static LayerLoad missing(ConfigurationScope scope, boolean trusted) {
            return new LayerLoad(scope, false, trusted, ConfigurationStore.MISSING_VERSION,
                    ConfigurationData.LayerStatus.MISSING, null, null, List.of());
        }

        /**
         * 转为对外只读视图，不把内部加载过程或文件路径暴露给应用层。
         */
        ConfigurationRuntimeState.LayerView view() {
            return new ConfigurationRuntimeState.LayerView(scope, present, trusted, version, status, document);
        }
    }
}
