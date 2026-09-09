// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.adapter.out.security.windows.CredentialStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * ConfigurationGenerationRuntime 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
public final class ConfigurationGenerationRuntime implements AutoCloseable {
    private static final String GENERAL_GENERATION_KEY = "<general>";

    private final ObjectMapper mapper;
    private final Supplier<CredentialStore.State> authLoader;
    private final BiFunction<java.nio.file.Path, CredentialStore.State,
            ConfigurationRuntimeState.ReadResult> reader;
    private final Set<ConfigGeneration> generations =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, ConfigGeneration> currentGenerations = new HashMap<>();

    /**
     * ConfigurationGenerationRuntime 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public ConfigurationGenerationRuntime(ObjectMapper mapper,
                                   Supplier<CredentialStore.State> authLoader,
                                   BiFunction<java.nio.file.Path, CredentialStore.State,
                                           ConfigurationRuntimeState.ReadResult> reader) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.authLoader = Objects.requireNonNull(authLoader, "authLoader");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * resolve 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized ConfigGeneration resolve(java.nio.file.Path canonical) {
        CredentialStore.State auth = authLoader.get();
        Map<String, ConfigGeneration.SecretValue> secrets = new LinkedHashMap<>();
        boolean generationCreated = false;
        try {
            ConfigurationRuntimeState.ReadResult read = reader.apply(canonical, auth);
            List<ConfigGeneration.Diagnostic> diagnostics =
                    new ArrayList<>(read.diagnostics());
            String selectedProvider = selectProvider(read.effective());
            if (selectedProvider == null) {
                diagnostics.add(new ConfigGeneration.Diagnostic("MISSING_PROVIDER", true));
            } else {
                String credentialId = credentialForProvider(read.effective(), selectedProvider);
                if (!auth.contains(credentialId)) {
                    diagnostics.add(new ConfigGeneration.Diagnostic("MISSING_CREDENTIAL", true));
                }
            }
            List<JsonNode> skills = catalogEntries(read.effective(), "skills");
            List<JsonNode> mcpServers = catalogEntries(read.effective(), "mcp_servers");
            String catalogDigest = catalogDigest(skills, mcpServers);
            String generationId = generationId(canonical, read.effective(), read.user().version(),
                    read.project().version(), auth.version(), diagnostics);
            String generationKey = generationKey(canonical);
            ConfigGeneration current = currentGenerations.get(generationKey);
            if (current != null && !current.isClosed()
                && Objects.equals(current.generationId(), generationId)) {
                return current;
            }
            if (auth.status() == ConfigurationData.LayerStatus.VALID) {
                // 只有经 ACL 与文档校验的凭据状态才能转移给新 generation，后者负责最终清零。
                secrets = auth.copyForGeneration();
            }
            ConfigGeneration generation = new ConfigGeneration(generationId,
                    canonical == null ? null : canonical.toString(), read.user().version(),
                    read.project().version(), (ObjectNode) read.effective(),
                    credentialStatusFlags(auth.secretIds()), diagnostics, read.trusted(), secrets,
                    skills, mcpServers, catalogDigest, this::forgetGeneration);
            generationCreated = true;
            generations.add(generation);
            ConfigGeneration previous = currentGenerations.put(generationKey, generation);
            if (previous != null && previous != generation) previous.close();
            return generation;
        } finally {
            auth.close();
            if (!generationCreated) clearSecretMap(secrets);
        }
    }

    /**
     * acquire 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public synchronized ConfigGeneration.Lease acquire(java.nio.file.Path canonical) {
        return resolve(canonical).acquire();
    }

    /**
     * invalidateAll 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public synchronized void invalidateAll() {
        List<ConfigGeneration> current = List.copyOf(currentGenerations.values());
        currentGenerations.clear();
        current.forEach(ConfigGeneration::close);
    }

    /**
     * invalidate 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized void invalidate(java.nio.file.Path canonical) {
        ConfigGeneration generation = currentGenerations.remove(generationKey(canonical));
        if (generation != null) generation.close();
    }

    /**
     * close 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public synchronized void close() {
        currentGenerations.clear();
        for (ConfigGeneration generation : List.copyOf(generations)) generation.close();
        generations.clear();
    }

    /**
     * trackedGenerationCount 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    public synchronized int trackedGenerationCount() {
        return generations.size();
    }

    /**
     * forgetGeneration 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    private synchronized void forgetGeneration(ConfigGeneration generation) {
        generations.remove(generation);
        currentGenerations.values().removeIf(candidate -> candidate == generation);
    }

    /** 从严格 v1 文档复制显式目录；缺失或错误类型必须失败，不能降级为空目录。 */
    private static List<JsonNode> catalogEntries(JsonNode effective, String key) {
        JsonNode value = effective.get(key);
        if (!(value instanceof ArrayNode array)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration catalog is invalid");
        }
        List<JsonNode> entries = new ArrayList<>(array.size());
        for (JsonNode entry : array) entries.add(entry.deepCopy());
        return List.copyOf(entries);
    }

    /**
     * 对冻结的 Skill/MCP 投影生成稳定摘要，用于识别 catalog 变更而不暴露文档内容。
     */
    private String catalogDigest(List<JsonNode> skills, List<JsonNode> mcpServers) {
        try {
            ObjectNode catalog = mapper.createObjectNode();
            ArrayNode skillArray = catalog.putArray("skills");
            skills.forEach(skillArray::add);
            ArrayNode mcpArray = catalog.putArray("mcpServers");
            mcpServers.forEach(mcpArray::add);
            return "cat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(catalog)));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration catalog is unavailable");
        }
    }

    /** 只返回根级显式默认 Provider；完整 v1 文档中的错误类型或悬空引用立即失败。 */
    private static String selectProvider(JsonNode document) {
        JsonNode defaultId = document.get("default_provider_id");
        if (defaultId == null || (!defaultId.isNull() && !defaultId.isTextual())) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                    "default provider is invalid");
        }
        if (defaultId.isNull()) return null;
        JsonNode providers = document.get("providers");
        if (!(providers instanceof ArrayNode array)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                    "provider catalog is invalid");
        }
        for (JsonNode provider : array) {
            JsonNode providerId = provider.get("provider_id");
            if (providerId != null && providerId.isTextual()
                && defaultId.textValue().equals(providerId.textValue())) {
                return defaultId.textValue();
            }
        }
        throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                "default provider is unavailable");
    }

    /** 按稳定 Provider ID 读取当前 v1 的必填 credential 引用，不为损坏目录返回空值。 */
    private static String credentialForProvider(JsonNode document, String providerId) {
        JsonNode providers = document.get("providers");
        if (!(providers instanceof ArrayNode array)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                    "provider catalog is invalid");
        }
        for (JsonNode provider : array) {
            JsonNode identity = provider.get("provider_id");
            if (identity != null && identity.isTextual() && identity.textValue().equals(providerId)) {
                JsonNode credential = provider.get("credential_id");
                if (credential == null || !credential.isTextual() || credential.textValue().isBlank()) {
                    throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                            "provider credential reference is invalid");
                }
                return credential.textValue();
            }
        }
        throw error(ConfigurationError.Code.INVALID_DOCUMENT,
                "provider credential reference is unavailable");
    }

    /**
     * credentialStatusFlags 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static Map<String, Boolean> credentialStatusFlags(Set<String> ids) {
        Map<String, Boolean> statuses = new TreeMap<>();
        ids.forEach(id -> statuses.put(id, true));
        return statuses;
    }

    /**
     * clearSecretMap 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static void clearSecretMap(Map<String, ConfigGeneration.SecretValue> secrets) {
        if (secrets.isEmpty()) return;
        secrets.values().forEach(ConfigGeneration.SecretValue::clear);
        secrets.clear();
    }

    /**
     * generationKey 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    private static String generationKey(java.nio.file.Path canonical) {
        return canonical == null ? GENERAL_GENERATION_KEY : canonical.toString();
    }

    /**
     * generationId 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private String generationId(java.nio.file.Path cwd, JsonNode effective, String userVersion,
                                String projectVersion, String authVersion,
                                List<ConfigGeneration.Diagnostic> diagnostics) {
        try {
            String payload = mapper.writeValueAsString(effective) + '|' + userVersion + '|'
                             + projectVersion + '|' + authVersion + '|' + diagnostics + '|'
                             + (cwd == null ? "general" : cwd);
            return "cfg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw error(ConfigurationError.Code.IO_FAILURE, "configuration generation is unavailable");
        }
    }

    /**
     * error 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static ConfigurationError error(ConfigurationError.Code code, String message) {
        return new ConfigurationError(code, message);
    }
}
