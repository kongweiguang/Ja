// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.infrastructure.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationPathPolicy;
import io.github.kongweiguang.ja.configuration.infrastructure.generation.ConfigGeneration;
import io.github.kongweiguang.ja.configuration.infrastructure.generation.ConfigurationRuntimeState;
import io.github.kongweiguang.ja.configuration.infrastructure.security.windows.CredentialStore;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationMutationCoordinator;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationScope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy.applyMergePatch;
import static io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy.enforceNoEscalation;
import static io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy.mergeDocuments;
import static io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy.validateCredentialId;
import static io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy.validateDocument;
import static io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy.validateSecret;

/**
 * 配置文档出站编排器，把一次用例委派给文档 Factory、路径 Policy、CAS 协调器和文件适配器。
 *
 * <p>该类型不拥有 generation 缓存、Watcher 线程或 RPC 状态；它只维持一次读改写的顺序，
 * 从而让每个安全与持久化职责都能独立测试。</p>
 */
public final class ConfigurationDocumentRuntime {
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final System.Logger LOGGER = System.getLogger(ConfigurationDocumentRuntime.class.getName());

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
     * 项目模型 patch 用既有 TOML null 哨兵区分可空值与 Merge Patch 的字段删除。
     */
    private static boolean isNullSentinel(JsonNode value) {
        return value instanceof ObjectNode object && object.size() == 1
               && object.path("__ja_null").isBoolean() && object.path("__ja_null").booleanValue();
    }

    /**
     * 删除叶字段撤销后留下的空 Provider 子对象，同时保留任何未知非空字段。
     */
    private static void removeEmptyObjects(ObjectNode object) {
        List<String> emptyObjects = new ArrayList<>();
        object.properties().forEach(entry -> {
            if (entry.getValue() instanceof ObjectNode child) {
                removeEmptyObjects(child);
                if (child.isEmpty()) {
                    emptyObjects.add(entry.getKey());
                }
            }
        });
        emptyObjects.forEach(object::remove);
    }

    /**
     * 为每个有效配置叶生成 default/user/project 来源标记，编辑界面不必根据值相等猜来源。
     */
    private static Map<String, String> configurationSources(ObjectNode effective, ObjectNode user,
                                                            ObjectNode project,
                                                            List<ConfigurationData.Issue> issues) {
        Map<String, String> result = new TreeMap<>();
        collectSources(effective, user, project, "", null, result);
        for (ConfigurationData.Issue issue : issues) {
            if (!"user".equals(issue.scope()) || issue.field() == null) {
                continue;
            }
            String path = issue.field();
            if (issue.entityId() != null && ("providers".equals(path) || "models".equals(path))) {
                path = "providers." + issue.entityId();
            }
            if (result.containsKey(path) && !"project".equals(result.get(path))
                && "default_in_use".equals(issue.impact())) {
                result.put(path, "default");
            }
        }
        for (ConfigurationData.Issue issue : issues) {
            if ("project".equals(issue.scope()) && "default_model_id".equals(issue.field())
                && "MODEL_UNAVAILABLE".equals(issue.reason())
                && "lower_layer_in_use".equals(issue.impact())) {
                result.put("default_provider_id", "project");
                result.put("default_model_id", "project");
                result.put("default_reasoning_level", "project");
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 递归比较同一稳定字段在有效、用户原文和项目稀疏层中的存在性。
     */
    private static void collectSources(JsonNode effective, JsonNode user, JsonNode project,
                                       String path, String parentField, Map<String, String> result) {
        if (effective == null) {
            return;
        }
        if (effective.isNull()) {
            if (!path.isEmpty() && !path.endsWith("schema_version") && !path.endsWith("config_revision")) {
                result.put(path, source(project, user));
            }
            return;
        }
        if (effective instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                JsonNode userChild = user instanceof ObjectNode values ? values.get(entry.getKey()) : null;
                JsonNode projectChild = project instanceof ObjectNode values ? values.get(entry.getKey()) : null;
                collectSources(entry.getValue(), userChild, projectChild, childPath,
                        entry.getKey(), result);
            });
            return;
        }
        if (effective instanceof ArrayNode array) {
            String identityField = switch (parentField == null ? path : parentField) {
                case "providers" -> "provider_id";
                case "models" -> "model_id";
                case "mcp_servers" -> "mcp_id";
                default -> null;
            };
            JsonNode userArray = user;
            JsonNode projectArray = project;
            if (Set.of("providers", "mcp_servers", "disabled_skills").contains(path)) {
                result.put(path, source(projectArray, userArray));
            }
            for (JsonNode item : array) {
                if (!(item instanceof ObjectNode object) || identityField == null) {
                    continue;
                }
                String identity = object.path(identityField).asText();
                if (identity.isBlank()) {
                    continue;
                }
                String childPath = path + "." + identity;
                JsonNode userItem = findIdentity(userArray, identityField, identity);
                JsonNode projectItem = findIdentity(projectArray, identityField, identity);
                result.put(childPath, source(projectItem, userItem));
                collectSources(item, userItem, projectItem, childPath, path.substring(path.lastIndexOf('.') + 1), result);
            }
            return;
        }
        if (!path.endsWith("schema_version") && !path.endsWith("config_revision")) {
            result.put(path, source(project, user));
        }
    }

    /**
     * 从数组中按稳定 Provider/Model/MCP ID 取对应层条目。
     */
    private static JsonNode findIdentity(JsonNode array, String identityField, String identity) {
        if (!(array instanceof ArrayNode values)) {
            return null;
        }
        for (JsonNode value : values) {
            if (identity.equals(value.path(identityField).asText())) {
                return value;
            }
        }
        return null;
    }

    /**
     * 单层来源判定只看有效投影中的显式存在性。
     */
    private static String source(JsonNode project, JsonNode user) {
        if (project != null) {
            return "project";
        }
        if (user != null) {
            return "user";
        }
        return "default";
    }

    /**
     * 只比较已验证目录的稳定身份，避免通过同名或端点推测配置来源。
     */
    private static java.util.Set<String> mcpIds(ObjectNode document) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (document.get("mcp_servers") instanceof ArrayNode servers) {
            servers.forEach(server -> ids.add(server.path("mcp_id").asText()));
        }
        return ids;
    }

    /**
     * 只接受已有稳定 Provider 与凭据引用；失效引用丢弃整组路由并继承原连接。
     */
    private static void filterUnknownProjectProviders(ObjectNode base, ObjectNode project,
                                                      java.util.Set<String> credentialIds,
                                                      List<ConfigurationData.Issue> issues) {
        if (!(project.get("providers") instanceof ArrayNode overrides)) {
            return;
        }
        java.util.Set<String> userIds = new java.util.HashSet<>();
        if (base.get("providers") instanceof ArrayNode providers) {
            providers.forEach(provider -> userIds.add(provider.path("provider_id").asText()));
        }
        ArrayNode accepted = project.arrayNode();
        for (JsonNode override : overrides) {
            String providerId = override.path("provider_id").asText();
            if (!userIds.contains(providerId)) {
                issues.add(issue("project_provider_unavailable", "project", "providers", providerId,
                        "PROVIDER_UNAVAILABLE", "entry_skipped", List.of("edit")));
                continue;
            }
            ObjectNode safeOverride = override.deepCopy();
            String credentialId = safeOverride.path("credential_id").asText(null);
            if (credentialId != null && !credentialIds.contains(credentialId)) {
                for (String field : List.of("api", "base_url", "credential_id")) {
                    safeOverride.remove(field);
                }
                issues.add(issue("project_provider_credential", "project", "providers.credential_id",
                        providerId, "CREDENTIAL_REFERENCE_UNAVAILABLE", "lower_layer_in_use", List.of("edit")));
            }
            accepted.add(safeOverride);
        }
        project.set("providers", accepted);
    }

    /**
     * 无效项目 MCP credentialRef 跳过同 ID 覆盖，使已存在的下层服务保持可用。
     */
    private static void filterInvalidProjectMcpCredentials(ObjectNode base, ObjectNode project,
                                                           java.util.Set<String> credentialIds,
                                                           List<ConfigurationData.Issue> issues) {
        if (!(project.get("mcp_servers") instanceof ArrayNode overrides)) {
            return;
        }
        ArrayNode accepted = project.arrayNode();
        for (JsonNode override : overrides) {
            String mcpId = override.path("mcp_id").asText();
            String credentialId = override.path("auth").path("credential_id").asText(null);
            if (credentialId == null || credentialIds.contains(credentialId)) {
                accepted.add(override.deepCopy());
                continue;
            }
            boolean hasLowerLayer = false;
            if (base.get("mcp_servers") instanceof ArrayNode baseServers) {
                for (JsonNode baseServer : baseServers) {
                    if (mcpId.equals(baseServer.path("mcp_id").asText())) {
                        hasLowerLayer = true;
                        break;
                    }
                }
            }
            issues.add(issue("project_mcp_credential", "project", "mcp_servers.auth.credential_id", mcpId,
                    "CREDENTIAL_REFERENCE_UNAVAILABLE", hasLowerLayer ? "lower_layer_in_use" : "entry_skipped",
                    List.of("edit")));
        }
        project.set("mcp_servers", accepted);
    }

    /**
     * 项目任一显式默认选择字段都需要作为一组验证，避免半个引用覆盖下层。
     */
    private static boolean hasProjectDefaultSelection(ObjectNode project) {
        return project.has("default_provider_id") || project.has("default_model_id")
               || project.has("default_reasoning_level");
    }

    /**
     * Project Model catalog 的增删可能使下层默认引用失效，应与显式选择共同触发局部回退。
     */
    private static boolean hasProjectProviderModelOverrides(ObjectNode project) {
        if (!(project.get("providers") instanceof ArrayNode providers)) {
            return false;
        }
        for (JsonNode provider : providers) {
            if (provider.has("models") || provider.has("removed_model_ids")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 关联模型已不可用时选择状态回退到明确空值，避免无效 ID 进入有效配置。
     */
    private static void clearDefaultSelection(ObjectNode document) {
        document.putNull("default_provider_id");
        document.putNull("default_model_id");
        document.putNull("default_reasoning_level");
    }

    /**
     * 项目默认选择关联错误时回退完整三元组，不让合法片段拼成另一个模型。
     */
    private static void restoreDefaultSelection(ObjectNode base, ObjectNode candidate) {
        for (String field : List.of("default_provider_id", "default_model_id", "default_reasoning_level")) {
            JsonNode value = base.get(field);
            if (value == null) {
                candidate.remove(field);
            }
            else {
                candidate.set(field, value.deepCopy());
            }
        }
    }

    /**
     * 原子替换和病毒扫描会让 Windows 上的文件在很短窗口内不可读；三次有界重试避免把该瞬态转化为
     * 空配置；只有读取失败才会进入退避，中断时直接附带该根因。每次失败都会更新 last，
     * 且只有第三次 IOException 才能到达循环出口，因此出口可直接抛出该根因。
     */
    private static byte[] readWithRetry(Path path) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return ConfigurationStore.read(path);
            } catch (IOException failure) {
                last = failure;
            }
            if (attempt == 2) {
                break;
            }
            try {
                Thread.sleep(80L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                IOException interruptionFailure = new IOException("configuration_read_interrupted", interrupted);
                interruptionFailure.addSuppressed(last);
                throw interruptionFailure;
            }
        }
        throw last;
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
     * 仅当调用方的 expectedVersion 与当前文件摘要一致时继续，冲突不自动重试或合并。
     */
    private static void checkExpectedVersion(String current, String expected) {
        if (Objects.equals(current, expected)) {
            return;
        }
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
        String safeId = id.replaceAll("[^A-Za-z0-9_-]", "_");
        return new ConfigurationData.Issue("cfg_" + safeId, scope, field, entityId, null, null,
                reason, impact, actions);
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
     * 按 RFC 7396 在目标层执行原子 Merge Patch；缺失的用户层以完整默认文档作为写入基线，
     * 否则首次设置只提交模型字段会丢掉 schema 必填默认项。CAS 仍在变换前校验以拒绝过期写入。
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
            ObjectNode currentEffective = current.document == null
                    ? scope == ConfigurationScope.PROJECT ? documentFactory.createProjectEmpty()
                      : documentFactory.createEmpty() : current.document;
            validatePatchAgainstEffective(scope, currentEffective, patch);
            ObjectNode source = current.sourceDocument == null
                    ? scope == ConfigurationScope.PROJECT ? documentFactory.createProjectEmpty()
                      : currentEffective.deepCopy() : current.sourceDocument.deepCopy();
            ObjectNode next = applyScopedPatch(source, scope, patch);
            documentFactory.advanceRevision(next, currentEffective);
            persistConfig(target, next);
            return new ConfigurationRuntimeState.WriteResult(scope, configurationVersion(target));
        });
    }

    /**
     * 校验被 patch 改动的候选投影；schema 元数据必须单独拒绝不兼容值，不能让只用于投影的
     * tolerant normalizer 把它改成当前版本后误认为 patch 合法。持久写入仍基于原文合并。
     */
    private void validatePatchAgainstEffective(ConfigurationScope scope, ObjectNode baseline,
                                               ObjectNode patch) {
        JsonNode requestedSchemaVersion = patch.get("schema_version");
        if (requestedSchemaVersion != null && !requestedSchemaVersion.isNull()
            && (!requestedSchemaVersion.isIntegralNumber() || !requestedSchemaVersion.canConvertToInt()
                || requestedSchemaVersion.intValue() != ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration patch is invalid");
        }
        ObjectNode candidate = applyScopedPatch(baseline, scope, patch);
        TolerantConfigurationDocumentReader.Result result =
                TolerantConfigurationDocumentReader.normalize(candidate, scope);
        if (!result.issues().isEmpty()) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration patch is invalid");
        }
    }

    /**
     * 用户 Provider 目录使用 ID 稀疏操作，排序字段只控制当前请求中的顺序，不进入配置文档；
     * 项目层继续使用既有的稀疏覆盖规则。
     */
    private ObjectNode applyScopedPatch(ObjectNode source, ConfigurationScope scope, ObjectNode patch) {
        JsonNode providerPatch = patch.get("providers");
        if (scope == ConfigurationScope.USER) {
            JsonNode providerOrder = patch.get("provider_order");
            if (providerPatch != null && !(providerPatch instanceof ObjectNode)
                || providerOrder != null && !(providerOrder instanceof ArrayNode)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "user provider patch is invalid");
            }
            ObjectNode rootPatch = patch.deepCopy();
            rootPatch.remove(List.of("providers", "provider_order"));
            ObjectNode result = applyUserRootPatch(source, rootPatch);
            if (providerPatch instanceof ObjectNode providerOperations) {
                applyUserProviderPatches(result, providerOperations);
            }
            if (providerOrder instanceof ArrayNode order) {
                reorderByListedIdentity(result.withArray("providers"), order, "provider_id", "provider_");
            }
            return result;
        }
        if (!(providerPatch instanceof ObjectNode providerOperations)) {
            return applyMergePatch(source, patch);
        }
        ObjectNode rootPatch = patch.deepCopy();
        rootPatch.remove("providers");
        ObjectNode result = applyMergePatch(source, rootPatch);
        applyProjectProviderPatchById(result, providerOperations);
        return result;
    }

    /**
     * RFC 7396 的原生 null 仍表示移除字段；专用哨兵表示用户明确保存可空配置值，避免两种意图混淆。
     */
    private ObjectNode applyUserRootPatch(ObjectNode source, ObjectNode patch) {
        ObjectNode ordinaryPatch = patch.deepCopy();
        List<String> nullDefaults = new ArrayList<>();
        for (String field : List.of("default_provider_id", "default_model_id", "default_reasoning_level")) {
            if (isNullSentinel(ordinaryPatch.get(field))) {
                ordinaryPatch.remove(field);
                nullDefaults.add(field);
            }
        }
        List<String> nullSubagentFields = new ArrayList<>();
        if (ordinaryPatch.get("subagents") instanceof ObjectNode subagentPatch) {
            ObjectNode sanitizedSubagents = subagentPatch.deepCopy();
            for (String field : List.of("provider_id", "model_id", "reasoning_level")) {
                if (isNullSentinel(sanitizedSubagents.get(field))) {
                    sanitizedSubagents.remove(field);
                    nullSubagentFields.add(field);
                }
            }
            if (sanitizedSubagents.isEmpty()) {
                ordinaryPatch.remove("subagents");
            }
            else {
                ordinaryPatch.set("subagents", sanitizedSubagents);
            }
        }
        ObjectNode result = applyMergePatch(source, ordinaryPatch);
        nullDefaults.forEach(field -> result.putNull(field));
        if (!nullSubagentFields.isEmpty()) {
            ObjectNode subagents = result.get("subagents") instanceof ObjectNode current
                    ? current.deepCopy() : result.objectNode();
            nullSubagentFields.forEach(field -> subagents.putNull(field));
            result.set("subagents", subagents);
        }
        return result;
    }

    /**
     * 将用户层 Provider 与 Model 操作按稳定 ID 应用到源数组；先在有效投影校验，落盘时再保留
     * 未触及的原始条目和未知字段，避免脱敏投影覆盖原文。
     */
    private void applyUserProviderPatches(ObjectNode document, ObjectNode operations) {
        ArrayNode providers = document.withArray("providers");
        operations.properties().forEach(operation -> {
            String providerId = operation.getKey();
            requireStableIdentity(providerId, "provider_", "provider patch identity is invalid");
            JsonNode value = operation.getValue();
            if (value.isNull()) {
                removeAllByIdentity(providers, "provider_id", providerId);
                return;
            }
            if (!(value instanceof ObjectNode fields) || fields.has("provider_id")) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider patch is invalid");
            }
            JsonNode modelOperations = fields.get("models");
            JsonNode modelOrder = fields.get("model_order");
            if (modelOperations != null && !(modelOperations instanceof ObjectNode)
                || modelOrder != null && !(modelOrder instanceof ArrayNode)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider model patch is invalid");
            }
            int index = uniqueIdentityIndex(providers, "provider_id", providerId);
            ObjectNode base = index >= 0 ? ((ObjectNode) providers.get(index)).deepCopy()
                    : document.objectNode().put("provider_id", providerId);
            ObjectNode providerFields = fields.deepCopy();
            providerFields.remove(List.of("models", "model_order"));
            ObjectNode merged = applyMergePatch(base, providerFields);
            if (modelOperations instanceof ObjectNode modelPatches) {
                applyUserModelPatches(merged, modelPatches);
            }
            if (modelOrder instanceof ArrayNode order) {
                JsonNode models = merged.get("models");
                if (!(models instanceof ArrayNode modelArray)) {
                    throw error(ConfigurationError.Code.INVALID_DOCUMENT, "provider model order is invalid");
                }
                reorderByListedIdentity(modelArray, order, "model_id", "model_");
            }
            if (index >= 0) {
                providers.set(index, merged);
            } else {
                providers.add(merged);
            }
        });
    }

    /**
     * 模型字段按 Merge Patch 合并，显式可空值必须用哨兵；实体级 null 删除对应 ID，并清理重复
     * 原始匹配项以免被后续读投影重新显现。
     */
    private void applyUserModelPatches(ObjectNode provider, ObjectNode operations) {
        if (operations.isEmpty()) {
            return;
        }
        JsonNode current = provider.get("models");
        ArrayNode models = current instanceof ArrayNode array ? array.deepCopy() : provider.arrayNode();
        operations.properties().forEach(operation -> {
            String modelId = operation.getKey();
            requireStableIdentity(modelId, "model_", "model patch identity is invalid");
            JsonNode value = operation.getValue();
            if (value.isNull()) {
                removeAllByIdentity(models, "model_id", modelId);
                return;
            }
            if (!(value instanceof ObjectNode fields) || fields.has("model_id")) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "model patch is invalid");
            }
            int index = uniqueIdentityIndex(models, "model_id", modelId);
            ObjectNode base = index >= 0 ? ((ObjectNode) models.get(index)).deepCopy()
                    : provider.objectNode().put("model_id", modelId);
            boolean explicitDefaultNull = isNullSentinel(fields.get("default_reasoning_level"));
            ObjectNode modelFields = fields.deepCopy();
            if (explicitDefaultNull) {
                modelFields.remove("default_reasoning_level");
            }
            ObjectNode merged = applyMergePatch(base, modelFields);
            if (explicitDefaultNull) {
                merged.putNull("default_reasoning_level");
            }
            merged.put("model_id", modelId);
            if (index >= 0) {
                models.set(index, merged);
            } else {
                models.add(merged);
            }
        });
        provider.set("models", models);
    }

    /**
     * 只在被列出的稳定 ID 原有槽位间交换条目；未列出的有效或损坏原始条目保持位置不变。
     */
    private void reorderByListedIdentity(ArrayNode entries, ArrayNode order, String idField, String prefix) {
        if (order.size() > 512) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration order is invalid");
        }
        Set<String> seen = new HashSet<>();
        List<Integer> slots = new ArrayList<>();
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode value : order) {
            if (!value.isTextual()) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration order is invalid");
            }
            String id = value.textValue();
            requireStableIdentity(id, prefix, "configuration order identity is invalid");
            if (!seen.add(id)) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration order contains duplicates");
            }
            int index = uniqueIdentityIndex(entries, idField, id);
            if (index < 0) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration order identity is unknown");
            }
            slots.add(index);
            selected.add(entries.get(index).deepCopy());
        }
        slots.sort(Integer::compareTo);
        for (int index = 0; index < slots.size(); index++) {
            entries.set(slots.get(index), selected.get(index));
        }
    }

    /**
     * 找到唯一原始实体；重复 ID 会让单项更新含义不确定，因此 fail closed，删除路径另行全量清除。
     */
    private int uniqueIdentityIndex(ArrayNode entries, String idField, String id) {
        int match = -1;
        for (int index = 0; index < entries.size(); index++) {
            if (!id.equals(entries.get(index).path(idField).asText(null))) {
                continue;
            }
            if (match >= 0) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration identity is ambiguous");
            }
            match = index;
        }
        return match;
    }

    /**
     * 删除所有具有目标身份的原始条目，避免损坏或重复副本在下一次容错读取中重新显现。
     */
    private void removeAllByIdentity(ArrayNode entries, String idField, String id) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (id.equals(entries.get(index).path(idField).asText(null))) {
                entries.remove(index);
            }
        }
    }

    /**
     * Provider 与 Model ID 使用同一受限字符集合；patch map key 和排序 ID 都在写入前统一校验。
     */
    private void requireStableIdentity(String id, String prefix, String message) {
        if (id == null || !id.matches(java.util.regex.Pattern.quote(prefix)
                + "[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, message);
        }
    }

    /**
     * 按 `providers.<provider_id>` 合并或删除一个稀疏项目 Provider 覆盖。
     */
    private void applyProjectProviderPatchById(ObjectNode document, ObjectNode operations) {
        ArrayNode providers = document.withArray("providers");
        operations.properties().forEach(operation -> {
            String providerId = operation.getKey();
            if (!providerId.matches("provider_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "project provider patch identity is invalid");
            }
            int index = -1;
            for (int item = 0; item < providers.size(); item++) {
                if (providerId.equals(providers.get(item).path("provider_id").asText())) {
                    index = item;
                    break;
                }
            }
            JsonNode value = operation.getValue();
            if (value.isNull()) {
                if (index >= 0) {
                    providers.remove(index);
                }
                return;
            }
            if (!(value instanceof ObjectNode fields) || fields.has("provider_id")) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "project provider patch is invalid");
            }
            ObjectNode base = index >= 0 ? ((ObjectNode) providers.get(index)).deepCopy()
                    : document.objectNode().put("provider_id", providerId);
            JsonNode modelOperations = fields.get("models");
            ObjectNode providerFields = fields.deepCopy();
            providerFields.remove("models");
            ObjectNode merged = applyMergePatch(base, providerFields);
            if (modelOperations instanceof ObjectNode modelPatches) {
                applyProjectModelPatches(merged, modelPatches);
            } else if (modelOperations != null && modelOperations.isNull()) {
                merged.remove("models");
            } else if (modelOperations != null) {
                merged.set("models", modelOperations.deepCopy());
            }
            removeEmptyObjects(merged);
            if (merged.size() == 1) {
                if (index >= 0) {
                    providers.remove(index);
                }
            } else if (index >= 0) {
                providers.set(index, merged);
            } else {
                providers.add(merged);
            }
        });
    }

    /**
     * Model patch 也使用 model_id 作键，未触及模型和原始字段留在原数组中。
     */
    private void applyProjectModelPatches(ObjectNode provider, ObjectNode operations) {
        if (operations.isEmpty()) {
            return;
        }
        ArrayNode models = provider.withArray("models");
        operations.properties().forEach(operation -> {
            String modelId = operation.getKey();
            if (!modelId.matches("model_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "project model patch identity is invalid");
            }
            int index = -1;
            for (int item = 0; item < models.size(); item++) {
                if (modelId.equals(models.get(item).path("model_id").asText())) {
                    index = item;
                    break;
                }
            }
            JsonNode value = operation.getValue();
            if (value.isNull()) {
                if (index >= 0) {
                    models.remove(index);
                }
                return;
            }
            if (!(value instanceof ObjectNode fields) || fields.has("model_id")) {
                throw error(ConfigurationError.Code.INVALID_DOCUMENT, "project model patch is invalid");
            }
            ObjectNode base = index >= 0 ? ((ObjectNode) models.get(index)).deepCopy()
                    : provider.objectNode().put("model_id", modelId);
            boolean explicitDefaultNull = isNullSentinel(fields.get("default_reasoning_level"));
            ObjectNode modelFields = fields.deepCopy();
            if (explicitDefaultNull) {
                modelFields.remove("default_reasoning_level");
            }
            ObjectNode merged = applyMergePatch(base, modelFields);
            if (explicitDefaultNull) {
                merged.putNull("default_reasoning_level");
            }
            merged.put("model_id", modelId);
            if (index >= 0) {
                models.set(index, merged);
            }
            else {
                models.add(merged);
            }
        });
        if (models.isEmpty()) {
            provider.remove("models");
        }
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
            backupIfRepairing(current, target);
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
            backupIfRepairing(current, target);
            return publish(scope, target, current.document, scope == ConfigurationScope.PROJECT
                    ? documentFactory.createProjectEmpty() : documentFactory.createEmpty());
        });
    }

    /**
     * 只有用户显式 replace/reset 才能修复语义或语法损坏，并且必须先在目标目录成功备份原字节。
     * 失败日志仅记录异常类型和固定来源，公开 ConfigurationError 继续丢弃本地路径与异常消息。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void backupIfRepairing(LayerLoad current, Path target) {
        if (!current.present || current.status == ConfigurationData.LayerStatus.VALID
            || current.status == ConfigurationData.LayerStatus.MISSING) {
                return;
            }
        try {
            byte[] bytes = readWithRetry(target);
            if (bytes == null) {
                return;
            }
            Path backup = target.resolveSibling(target.getFileName() + ".backup-"
                                                + Long.toUnsignedString(System.nanoTime()));
            ConfigurationStore.writeAtomic(backup, bytes, false);
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configuration repair backup failed origin=ConfigurationDocumentRuntime.backupIfRepairing exceptionType="
                    + failure.getClass().getName());
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration backup could not be created");
        }
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
        return new ConfigurationRuntimeState.WriteResult(scope, configurationVersion(target));
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
            if (changed) {
                persistTrustRegistry(trusted);
            }
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
            if (changed) {
                persistTrustRegistry(trusted);
            }
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
     * 只合并可执行配置层；层级问题保留为精确诊断，避免通用文件错误覆盖已有原因。
     */
    public ConfigurationRuntimeState.ReadResult readInternal(Path canonical, CredentialStore.State auth) {
        boolean trusted = false;
        boolean trustUnavailable = false;
        if (canonical != null) {
            try {
                trusted = loadTrustRegistry().containsKey(canonical.toString());
            } catch (RuntimeException unavailable) {
                trustUnavailable = true;
            }
        }
        LayerLoad user = loadLayer(pathPolicy.userConfigPath(), ConfigurationScope.USER, true);
        LayerLoad project = canonical == null
                ? LayerLoad.missing(ConfigurationScope.PROJECT, false)
                : loadLayer(canonical.resolve(".ja").resolve("config.toml"),
                ConfigurationScope.PROJECT, trusted);
        List<ConfigGeneration.Diagnostic> diagnostics = new ArrayList<>();
        List<ConfigurationData.Issue> issues = new ArrayList<>();
        issues.addAll(user.issues);
        issues.addAll(project.issues);
        if (trustUnavailable) {
            issues.add(issue("project_trust_storage", "project", "trust", null,
                    "TRUST_STORAGE_UNAVAILABLE", "project_ignored", List.of("retry")));
        }
        if (!issues.isEmpty()) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIGURATION_ISSUES", false));
        }
        if (user.status == ConfigurationData.LayerStatus.CORRUPT) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_CONFIG", false));
        }
        if (user.status == ConfigurationData.LayerStatus.PARTIAL) {
            diagnostics.add(new ConfigGeneration.Diagnostic("PARTIAL_CONFIG", false));
        }
        if (user.status == ConfigurationData.LayerStatus.IO_ERROR) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIG_IO_ERROR", false));
        }
        if (project.status == ConfigurationData.LayerStatus.CORRUPT && trusted) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CORRUPT_PROJECT_CONFIG", false));
            if (project.issues.isEmpty()) {
                issues.add(issue("project_file", "project", null, null, "TOML_PARSE_FAILED",
                        "project_ignored", List.of("repair")));
            }
        }
        if (project.status == ConfigurationData.LayerStatus.PARTIAL && trusted) {
            diagnostics.add(new ConfigGeneration.Diagnostic("PARTIAL_PROJECT_CONFIG", false));
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
        if (user.document != null && (user.status == ConfigurationData.LayerStatus.VALID
                                      || user.status == ConfigurationData.LayerStatus.PARTIAL)) {
            effective = user.document.deepCopy();
        } else if (user.status == ConfigurationData.LayerStatus.CORRUPT
                   || user.status == ConfigurationData.LayerStatus.IO_ERROR) {
            if (user.issues.isEmpty()) {
                issues.add(issue("user_file", "user", null, null,
                        user.status == ConfigurationData.LayerStatus.CORRUPT ? "TOML_PARSE_FAILED" : "FILE_UNAVAILABLE",
                        "defaults_in_use", List.of(user.status == ConfigurationData.LayerStatus.CORRUPT
                                ? "repair" : "retry")));
            }
        }
        java.util.Set<String> projectMcpIds = new java.util.HashSet<>();
        ObjectNode effectiveProjectSource = null;
        if (project.document != null && (project.status == ConfigurationData.LayerStatus.VALID
                                         || project.status == ConfigurationData.LayerStatus.PARTIAL) && trusted) {
            try {
                ObjectNode applicableProject = project.document.deepCopy();
                enforceNoEscalation(user.document, applicableProject);
                filterUnknownProjectProviders(effective, applicableProject, auth.secretIds(), issues);
                filterInvalidProjectMcpCredentials(effective, applicableProject, auth.secretIds(), issues);
                ObjectNode candidate = mergeDocuments(effective, applicableProject);
                if (!ConfigurationPolicy.validDefaultSelection(candidate)) {
                    restoreDefaultSelection(effective, candidate);
                    if (!ConfigurationPolicy.validDefaultSelection(candidate)) {
                        clearDefaultSelection(candidate);
                    }
                    if (hasProjectDefaultSelection(applicableProject)
                        || hasProjectProviderModelOverrides(applicableProject)) {
                        applicableProject.remove("default_provider_id");
                        applicableProject.remove("default_model_id");
                        applicableProject.remove("default_reasoning_level");
                        issues.add(issue("project_default_selection", "project", "default_model_id", null,
                                "MODEL_UNAVAILABLE", "lower_layer_in_use", List.of("edit")));
                    }
                }
                try {
                    ConfigurationPolicy.validateEffectiveSubagents(candidate);
                } catch (ConfigurationError invalidSubagents) {
                    ObjectNode fallback = effective.path("subagents").deepCopy();
                    ObjectNode fallbackCandidate = candidate.deepCopy();
                    fallbackCandidate.set("subagents", fallback);
                    try {
                        ConfigurationPolicy.validateEffectiveSubagents(fallbackCandidate);
                    } catch (ConfigurationError lowerLayerModelUnavailable) {
                        fallback.putNull("provider_id");
                        fallback.putNull("model_id");
                        fallback.putNull("reasoning_level");
                    }
                    candidate.set("subagents", fallback);
                    applicableProject.remove("subagents");
                    issues.add(issue("project_subagents", "project", "subagents", null,
                            "INVALID_SUBAGENT_SELECTION", "lower_layer_in_use", List.of("edit")));
                }
                effectiveProjectSource = applicableProject.deepCopy();
                effective = candidate;
                projectMcpIds.addAll(mcpIds(applicableProject));
            } catch (ConfigurationError invalidProject) {
                issues.add(issue("project_overlay", "project", null, null,
                        "INVALID_PROJECT_CONFIGURATION", "project_ignored", List.of("edit")));
            }
        }
        if (!issues.isEmpty() && diagnostics.stream().noneMatch(
                diagnostic -> "CONFIGURATION_ISSUES".equals(diagnostic.code()))) {
            diagnostics.add(new ConfigGeneration.Diagnostic("CONFIGURATION_ISSUES", false));
        }
        if (issues.size() > 64) {
            issues = new ArrayList<>(issues.subList(0, 64));
        }
        Map<String, String> sources = configurationSources(effective, user.sourceDocument,
                effectiveProjectSource, issues);
        return new ConfigurationRuntimeState.ReadResult(trusted, user.view(), project.view(), effective,
                credentialStatuses(auth.secretIds()),
                auth.version(), diagnostics, issues, projectMcpIds, sources);
    }

    /**
     * 通过工作区路径策略固定真实目录，符号链接或不可解析路径统一视为无效 cwd。
     * 公开失败不保留文件系统 cause，避免异常链暴露调用方的真实路径。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
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
     * 读取权威配置层：缺省 schema 按当前格式，显式不支持版本则保留原文并隔离整层；其余字段级
     * 错误仍由 tolerant reader 局部降级，避免扩大到无关配置。
     */
    private LayerLoad loadLayer(Path path, ConfigurationScope scope, boolean trusted) {
        if (!trusted && scope == ConfigurationScope.PROJECT) {
            return new LayerLoad(scope, false, false, configurationVersion(path),
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
            if (bytes == null) {
                return LayerLoad.missing(scope, trusted);
            }
            if (bytes.length > MAX_CONFIG_BYTES) {
                return new LayerLoad(scope, true, trusted, ConfigurationStore.versionOf(bytes),
                        ConfigurationData.LayerStatus.CORRUPT, null, null, List.of());
            }
        } catch (UncheckedIOException failure) {
            return new LayerLoad(scope, true, trusted, ConfigurationStore.UNAVAILABLE_VERSION,
                    ConfigurationData.LayerStatus.IO_ERROR, null, null, List.of());
        } catch (ConfigurationError failure) {
            return new LayerLoad(scope, true, trusted, configurationVersion(path),
                    ConfigurationData.LayerStatus.CORRUPT, null, null, List.of());
        }
        String version = ConfigurationStore.versionOf(bytes);
        try {
            TomlCodec.ReadResult read = toml.parseForRead(new String(bytes, StandardCharsets.UTF_8));
            ObjectNode document = read.document();
            List<ConfigurationData.Issue> issues = new ArrayList<>();
            ConfigurationData.LayerStatus status = read.partial()
                    ? ConfigurationData.LayerStatus.PARTIAL : ConfigurationData.LayerStatus.VALID;
            if (read.partial()) {
                String scopeName = scope == ConfigurationScope.USER ? "user" : "project";
                issues.add(new ConfigurationData.Issue(
                        "cfg_toml_syntax_" + scopeName, scopeName, null, null,
                        read.errorLine(), read.errorColumn(), "TOML_PARSE_FAILED", "valid_prefix_in_use",
                        List.of("repair")));
            }
            JsonNode declaredSchemaVersion = document.get("schema_version");
            if (declaredSchemaVersion != null
                && (!declaredSchemaVersion.isIntegralNumber() || !declaredSchemaVersion.canConvertToInt()
                    || declaredSchemaVersion.intValue() != ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION)) {
                String scopeName = scope == ConfigurationScope.USER ? "user" : "project";
                String impact = scope == ConfigurationScope.USER ? "defaults_in_use" : "project_ignored";
                ConfigurationData.Issue issue = new ConfigurationData.Issue(
                        "cfg_schema_version_" + scopeName, scopeName, "schema_version", null, null, null,
                        "UNSUPPORTED_SCHEMA_VERSION", impact, List.of("repair"));
                return new LayerLoad(scope, true, trusted, version, ConfigurationData.LayerStatus.CORRUPT,
                        null, document, List.of(issue));
            }
            try {
                validateDocument(document, scope);
                return new LayerLoad(scope, true, trusted, version,
                        status, document, document, issues);
            } catch (ConfigurationError strictFailure) {
                TolerantConfigurationDocumentReader.Result tolerant =
                        TolerantConfigurationDocumentReader.normalize(document, scope);
                issues.addAll(tolerant.issues());
                return new LayerLoad(scope, true, trusted, version,
                        status, tolerant.document(), document, List.copyOf(issues));
            }
        } catch (RuntimeException failure) {
            return new LayerLoad(scope, true, trusted, version,
                    ConfigurationData.LayerStatus.CORRUPT, null, null, List.of());
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
     * 写入项目层前与当前用户层上限比较，使项目配置只能收紧而不能扩权。
     */
    private void enforceProjectWriteCeiling(ConfigurationScope scope, ObjectNode next) {
        if (scope != ConfigurationScope.PROJECT) {
            return;
        }
        LayerLoad user = loadLayer(pathPolicy.userConfigPath(), ConfigurationScope.USER, true);
        if (user.document != null) {
            enforceNoEscalation(user.document, next);
        }
    }

    /**
     * 在写入前重新解析目标层和信任登记，对损坏或未信任的项目层失败关闭。
     */
    private LayerLoad loadWritableLayer(ConfigurationScope scope, Path canonical) {
        Path path = configPath(scope, canonical);
        LayerLoad current = loadLayer(path, scope, scope == ConfigurationScope.USER
                                                   || isProjectTrustedForWrite(canonical));
        if (current.status == ConfigurationData.LayerStatus.CORRUPT
            || current.status == ConfigurationData.LayerStatus.PARTIAL) {
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
                                                   || isProjectTrustedForWrite(canonical));
        if (current.status == ConfigurationData.LayerStatus.IO_ERROR) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration storage is unavailable");
        }
        if (current.status == ConfigurationData.LayerStatus.UNTRUSTED) {
            throw error(ConfigurationError.Code.UNTRUSTED_WORKSPACE, "workspace trust is required");
        }
        return current;
    }

    /**
     * 写入路径先持有项目配置条带，再读取 trust 登记；保持 project → trust 单向锁序，
     * trust 写入不得在持有 trust 条带时反向获取项目配置条带。
     */
    private boolean isProjectTrustedForWrite(Path canonical) {
        return canonical != null && loadTrustRegistry().containsKey(canonical.toString());
    }

    /**
     * 路径策略的参数错误转换为稳定 cwd 分类，公开异常链不携带项目路径或原始消息。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private Path configPath(ConfigurationScope scope, Path canonical) {
        try {
            return pathPolicy.configurationPath(scope, canonical);
        } catch (IllegalArgumentException failure) {
            throw error(ConfigurationError.Code.INVALID_CWD, "workspace cwd is invalid");
        }
    }

    /**
     * 在上层完成 CAS 与严格校验后，以 TOML 编码和原子替换发布配置文件；写失败时只记录异常类型与固定来源，
     * 避免既丢失诊断线索又把路径、配置正文或原始异常消息泄漏到日志和公开异常。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void persistConfig(Path path, ObjectNode document) {
        try {
            ConfigurationStore.writeAtomic(path, toml.write(document).getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configuration persistence failed origin=ConfigurationDocumentRuntime.persistConfig exceptionType="
                    + failure.getClass().getName());
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
     * 按规范路径排序后原子发布信任登记，使相同集合生成稳定文档；失败诊断只包含固定来源与异常类型，
     * 不记录工作区路径、配置数据或原始异常消息。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void persistTrustRegistry(Map<String, Boolean> trusted) {
        ArrayNode values = mapper.createArrayNode();
        trusted.keySet().stream().sorted().forEach(values::add);
        ObjectNode root = mapper.createObjectNode();
        root.set("trusted", values);
        try {
            ConfigurationStore.writeAtomic(pathPolicy.trustPath(), mapper.writeValueAsBytes(root), false);
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configuration persistence failed origin=ConfigurationDocumentRuntime.persistTrustRegistry exceptionType="
                    + failure.getClass().getName());
            throw error(ConfigurationError.Code.IO_FAILURE, "workspace trust storage is unavailable");
        }
    }

    /**
     * 让文件版本与同进程中的 CAS 写入共享路径条带，避免版本快照跨过原子替换中间态。
     */
    private static String configurationVersion(Path path) {
        return ConfigurationMutationCoordinator.execute(path, () -> ConfigurationStore.version(path));
    }

    /**
     * 读取并重新规范化信任路径，任一无效条目都使整份登记失败而非部分采信。
     * 解析与文件系统 cause 可能含信任路径，只向调用方发布固定分类和消息。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public Map<String, Boolean> loadTrustRegistry() {
        try {
            Path trustPath = pathPolicy.trustPath();
            byte[] bytes = ConfigurationMutationCoordinator.execute(trustPath, () -> {
                try {
                    return ConfigurationStore.read(trustPath);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            if (bytes == null) {
                return new TreeMap<>();
            }
            JsonNode root = mapper.readTree(bytes);
            JsonNode values = root == null ? null : root.get("trusted");
            if (!(values instanceof ArrayNode array)) {
                throw new IOException();
            }
            Map<String, Boolean> trusted = new TreeMap<>();
            for (JsonNode value : array) {
                if (!value.isTextual()) {
                    throw new IOException();
                }
                trusted.put(value.textValue(), true);
            }
            return trusted;
        } catch (IOException | RuntimeException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "workspace trust storage is unavailable");
        }
    }

    /**
     * 同时保留配置层的文件存在性、信任、版本和解析状态，避免用 null 混淆不同失败。
     *
     * @param scope 适用范围
     * @param present 是否存在
     * @param trusted 工作区是否已获信任
     * @param version 版本
     * @param status 当前状态分类
     * @param document 文档
     * @param sourceDocument 来源文档
     * @param issues 问题列表
     */
    private record LayerLoad(ConfigurationScope scope,
                             boolean present,
                             boolean trusted,
                             String version,
                             ConfigurationData.LayerStatus status,
                             ObjectNode document,
                             ObjectNode sourceDocument,
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
