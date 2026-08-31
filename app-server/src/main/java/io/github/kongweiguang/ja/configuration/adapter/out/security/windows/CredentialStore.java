// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationStore;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigGeneration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * CredentialStore 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
public final class CredentialStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path path;
    private final ObjectMapper mapper;

    /**
     * CredentialStore 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public CredentialStore(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    /**
     * load 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public State load() {
        byte[] bytes = null;
        try {
            bytes = ConfigurationStore.readSecret(path);
            if (bytes == null) return State.missing();
            if (bytes.length > MAX_BYTES) return State.invalid(ConfigurationData.LayerStatus.CORRUPT, null);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            return State.invalid(ConfigurationData.LayerStatus.IO_ERROR, null);
        }
        JsonNode parsed = null;
        String version = null;
        try {
            version = ConfigurationStore.versionOf(bytes);
            parsed = mapper.readTree(bytes);
            if (!(parsed instanceof ObjectNode object)) {
                return State.invalid(ConfigurationData.LayerStatus.CORRUPT, version);
            }
            ConfigurationPolicy.validateAuth(object);
            State state = new State(version, ConfigurationData.LayerStatus.VALID);
            try {
                object.properties().forEach(entry ->
                        state.secrets.put(entry.getKey(), new ConfigGeneration.SecretValue(
                                entry.getValue().textValue())));
                return state;
            } catch (RuntimeException failure) {
                state.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            return State.invalid(ConfigurationData.LayerStatus.CORRUPT, version);
        } finally {
            // Jackson 树和原始字节都只是加载期临时载体，无论解析结果如何都立即清除。
            if (parsed instanceof ObjectNode object) object.removeAll();
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * persist 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public void persist(State state) {
        Objects.requireNonNull(state, "state");
        ObjectNode auth = state.toJson(mapper);
        byte[] bytes = null;
        try {
            ConfigurationPolicy.validateAuth(auth);
            bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(auth);
            ConfigurationStore.writeAtomic(path, bytes, true);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            throw new ConfigurationError(ConfigurationError.Code.IO_FAILURE,
                    "credential storage is unavailable");
        } finally {
            // 序列化树和字节组在原子写入后不再属于状态，必须在成功与失败路径上清除。
            auth.removeAll();
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * State 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static final class State implements AutoCloseable {
        private final String version;
        private final ConfigurationData.LayerStatus status;
        private final Map<String, ConfigGeneration.SecretValue> secrets = new TreeMap<>();
        private boolean closed;

        /**
         * State 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        private State(String version, ConfigurationData.LayerStatus status) {
            this.version = version;
            this.status = status;
        }

        /**
         * missing 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        static State missing() {
            return new State(ConfigurationStore.MISSING_VERSION, ConfigurationData.LayerStatus.MISSING);
        }

        /**
         * 构造不携带 secret 的失败状态，保留可用的内容版本供诊断和 CAS 冲突判定。
         */
        static State invalid(ConfigurationData.LayerStatus status, String version) {
            return new State(version == null ? ConfigurationStore.UNAVAILABLE_VERSION : version, status);
        }

        /**
         * version 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public String version() {
            return version;
        }

        /**
         * status 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
         */
        public ConfigurationData.LayerStatus status() {
            return status;
        }

        /**
         * contains 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public boolean contains(String credentialId) {
            return secrets.containsKey(credentialId);
        }

        /**
         * secretIds 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public Set<String> secretIds() {
            return Collections.unmodifiableSet(new TreeMap<>(secrets).keySet());
        }

        /**
         * copyForGeneration 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public Map<String, ConfigGeneration.SecretValue> copyForGeneration() {
            ensureOpen();
            Map<String, ConfigGeneration.SecretValue> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ConfigGeneration.SecretValue> entry : secrets.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().copy());
            }
            return copy;
        }

        /**
         * put 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public void put(String credentialId, String secret) {
            ensureOpen();
            ConfigGeneration.SecretValue previous = secrets.put(credentialId,
                    new ConfigGeneration.SecretValue(secret));
            if (previous != null) previous.clear();
        }

        /**
         * remove 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        public void remove(String credentialId) {
            ensureOpen();
            ConfigGeneration.SecretValue removed = secrets.remove(credentialId);
            if (removed != null) removed.clear();
        }

        /**
         * 创建仅供一次持久化的明文树，调用方必须在写入结束后立即移除所有节点。
         */
        public ObjectNode toJson(ObjectMapper objectMapper) {
            ensureOpen();
            ObjectNode document = objectMapper.createObjectNode();
            for (Map.Entry<String, ConfigGeneration.SecretValue> entry : secrets.entrySet()) {
                document.put(entry.getKey(), entry.getValue().asString());
            }
            return document;
        }

        /**
         * close 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            secrets.values().forEach(ConfigGeneration.SecretValue::clear);
            secrets.clear();
        }

        /**
         * 禁止在 State 清零后再读取或派生 secret，避免重新建立已释放的敏感数据所有权。
         */
        private void ensureOpen() {
            if (closed) throw new IllegalStateException("credential state is closed");
        }

        /**
         * toString 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        @Override
        public String toString() {
            return "CredentialState[status=" + status + ", version=" + version
                   + ", credentials=" + secrets.size() + "]";
        }
    }
}
