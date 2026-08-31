// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 将最后一个可迁移代际 v3 一次性转换为 v4；备份、prepared 文件与 marker 让 Windows
 * 进程在任一原子发布边界退出后，都能从内容摘要恢复，而不在正常运行时保留旧字段双读。
 */
final class ConfigurationSchemaMigration {
    private static final String MARKER_KIND = "configuration_v3_to_v4";
    private static final String PREPARED = "prepared";
    private static final String COMPLETE = "complete";

    private final ObjectMapper mapper;
    private final TomlCodec toml;
    private final FileOperations files;

    /** 生产迁移复用配置存储的有界读取和强原子替换，不创建第二套文件语义。 */
    ConfigurationSchemaMigration(ObjectMapper mapper) {
        this(mapper, new DefaultFileOperations());
    }

    /** 测试只能替换文件副作用端口，转换与恢复状态机保持生产实现。 */
    ConfigurationSchemaMigration(ObjectMapper mapper, FileOperations files) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.toml = new TomlCodec(mapper);
        this.files = Objects.requireNonNull(files, "files");
    }

    /**
     * 返回当前权威字节；仅 v3 可以触发迁移，v4 直接严格回读，其他代际一律失败关闭。
     */
    Optional<byte[]> readCurrent(Path target, ConfigurationScope scope) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scope, "scope");
        byte[] current = files.read(target);
        if (current == null) return Optional.empty();
        Marker marker = readMarker(markerPath(target));
        if (marker != null && PREPARED.equals(marker.state())) {
            return Optional.of(recoverPrepared(target, scope, current, marker));
        }
        ObjectNode document = parseToml(current);
        long schema = schemaVersion(document);
        if (schema == ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION) {
            ConfigurationPolicy.validateDocument(document, scope);
            return Optional.of(current);
        }
        if (marker != null && COMPLETE.equals(marker.state())) {
            throw corrupt("completed migration cannot consume v3 again");
        }
        if (schema != 3) throw corrupt("configuration schema is unsupported");
        return Optional.of(migrate(target, scope, current, document));
    }

    /** 备份发布在转换前，确保即使旧文档不满足迁移闭集也有可恢复原件。 */
    private byte[] migrate(Path target, ConfigurationScope scope, byte[] source, ObjectNode v3)
            throws IOException {
        String sourceVersion = ConfigurationStore.versionOf(source);
        ensureExactFile(backupPath(target), source, sourceVersion);
        ObjectNode v4 = transform(v3);
        ConfigurationPolicy.validateDocument(v4, scope);
        byte[] prepared = toml.write(v4).getBytes(StandardCharsets.UTF_8);
        String preparedVersion = ConfigurationStore.versionOf(prepared);
        files.writeAtomic(preparedPath(target), prepared);
        requireVersion(preparedPath(target), preparedVersion);
        Marker marker = new Marker(MARKER_KIND, PREPARED, sourceVersion,
                sourceVersion, preparedVersion);
        writeMarker(markerPath(target), marker);
        return publishPrepared(target, scope, marker, prepared);
    }

    /** prepared 恢复只接受 marker 中冻结的源或目标摘要，禁止重新猜测转换结果。 */
    private byte[] recoverPrepared(Path target, ConfigurationScope scope, byte[] current,
                                   Marker marker) throws IOException {
        validateMarker(marker);
        String currentVersion = ConfigurationStore.versionOf(current);
        if (marker.preparedVersion().equals(currentVersion)) {
            validatePublished(current, scope, marker.preparedVersion());
            complete(target, marker);
            return current;
        }
        if (!marker.sourceVersion().equals(currentVersion)) {
            throw new IOException("configuration_migration_target_changed");
        }
        requireVersion(backupPath(target), marker.backupVersion());
        byte[] prepared = files.read(preparedPath(target));
        if (prepared == null || !marker.preparedVersion().equals(ConfigurationStore.versionOf(prepared))) {
            throw new IOException("configuration_migration_prepared_invalid");
        }
        return publishPrepared(target, scope, marker, prepared);
    }

    /** 发布后执行摘要与 v4 Policy 双重回读，失败时保留 marker 和恢复文件。 */
    private byte[] publishPrepared(Path target, ConfigurationScope scope, Marker marker,
                                   byte[] prepared) throws IOException {
        files.writeAtomic(target, prepared);
        byte[] published = files.read(target);
        validatePublished(published, scope, marker.preparedVersion());
        complete(target, marker);
        return published;
    }

    /** 完成 marker 先于 prepared 清理发布，重启最多重复一次幂等清理。 */
    private void complete(Path target, Marker marker) throws IOException {
        writeMarker(markerPath(target), new Marker(marker.kind(), COMPLETE, marker.sourceVersion(),
                marker.backupVersion(), marker.preparedVersion()));
        files.delete(preparedPath(target));
    }

    /**
     * 纯转换只识别 v3 的旧字段：移除 Provider 隐含作用对象和 Renderer 可写输入模态，
     * 并把逻辑档位数组提升为显式上游值映射。
     */
    private ObjectNode transform(ObjectNode source) {
        ObjectNode result = source.deepCopy();
        result.put("schema_version", ConfigurationDocumentFactory.CURRENT_SCHEMA_VERSION);
        long revision = source.path("config_revision").asLong(0L);
        result.put("config_revision", revision == Long.MAX_VALUE ? 1L : revision + 1L);
        rename(result, "default_reasoning_effort", "default_reasoning_level");
        JsonNode providersValue = result.get("providers");
        if (providersValue instanceof ArrayNode providers) {
            for (JsonNode providerValue : providers) {
                if (!(providerValue instanceof ObjectNode provider)) {
                    throw corrupt("configuration v3 provider is invalid");
                }
                JsonNode defaultsValue = provider.get("agent_defaults");
                if (defaultsValue instanceof ObjectNode defaults) {
                    defaults.remove(Set.of("skill_ids", "mcp_ids"));
                }
                JsonNode modelsValue = provider.get("models");
                if (!(modelsValue instanceof ArrayNode models)) {
                    throw corrupt("configuration v3 models are invalid");
                }
                for (JsonNode modelValue : models) migrateModel(modelValue);
            }
        }
        return result;
    }

    /** 单模型迁移保留逻辑档位名称作为默认上游值，后续编辑只能写 v4 Map。 */
    private void migrateModel(JsonNode value) {
        if (!(value instanceof ObjectNode model)) throw corrupt("configuration v3 model is invalid");
        JsonNode capabilities = model.get("capabilities");
        if (capabilities instanceof ObjectNode object) object.remove("input_modalities");
        rename(model, "default_reasoning_effort", "default_reasoning_level");
        JsonNode legacy = model.remove("reasoning_efforts");
        if (legacy == null) legacy = model.remove("reasoning_level_map");
        ObjectNode mapping = mapper.createObjectNode();
        if (legacy instanceof ArrayNode values) {
            for (JsonNode entry : values) {
                if (!entry.isTextual()) throw corrupt("configuration v3 reasoning is invalid");
                mapping.put(entry.textValue(), entry.textValue());
            }
        } else if (legacy != null) {
            throw corrupt("configuration v3 reasoning is invalid");
        }
        model.set("reasoning_level_map", mapping);
    }

    /** 旧字段仅在迁移树内改名，目标字段同时存在时拒绝歧义输入。 */
    private static void rename(ObjectNode object, String oldKey, String newKey) {
        JsonNode oldValue = object.remove(oldKey);
        if (oldValue == null) return;
        if (object.has(newKey)) throw corrupt("configuration v3 contains ambiguous reasoning fields");
        object.set(newKey, oldValue);
    }

    /** 已存在备份必须与本次源摘要完全一致，绝不覆盖另一代际的恢复点。 */
    private void ensureExactFile(Path path, byte[] expected, String version) throws IOException {
        byte[] existing = files.read(path);
        if (existing == null) files.writeAtomic(path, expected);
        requireVersion(path, version);
    }

    /** 内容版本比较不暴露配置正文。 */
    private void requireVersion(Path path, String expected) throws IOException {
        byte[] bytes = files.read(path);
        if (bytes == null || !expected.equals(ConfigurationStore.versionOf(bytes))) {
            throw new IOException("configuration_migration_version_mismatch");
        }
    }

    /** 权威文件必须匹配 prepared 摘要并通过 v4 作用域策略。 */
    private void validatePublished(byte[] bytes, ConfigurationScope scope, String expectedVersion)
            throws IOException {
        if (bytes == null || !expectedVersion.equals(ConfigurationStore.versionOf(bytes))) {
            throw new IOException("configuration_migration_readback_failed");
        }
        ConfigurationPolicy.validateDocument(parseToml(bytes), scope);
    }

    /** marker 使用 JSON，避免被 TOML watcher 误识别为业务配置。 */
    private void writeMarker(Path path, Marker marker) throws IOException {
        ObjectNode value = mapper.createObjectNode();
        value.put("kind", marker.kind());
        value.put("state", marker.state());
        value.put("source_version", marker.sourceVersion());
        value.put("backup_version", marker.backupVersion());
        value.put("prepared_version", marker.preparedVersion());
        files.writeAtomic(path, mapper.writeValueAsBytes(value));
        if (!marker.equals(readMarker(path))) {
            throw new IOException("configuration_migration_marker_readback_failed");
        }
    }

    /** marker 字段闭集和值格式都必须精确，损坏状态不得静默忽略。 */
    private Marker readMarker(Path path) throws IOException {
        byte[] bytes = files.read(path);
        if (bytes == null) return null;
        JsonNode value = mapper.readTree(bytes);
        if (!(value instanceof ObjectNode object)
            || !fieldSet(object).equals(Set.of("kind", "state", "source_version",
                    "backup_version", "prepared_version"))) {
            throw new IOException("configuration_migration_marker_invalid");
        }
        Marker marker = new Marker(text(object, "kind"), text(object, "state"),
                text(object, "source_version"), text(object, "backup_version"),
                text(object, "prepared_version"));
        validateMarker(marker);
        return marker;
    }

    /** marker 只接受本次迁移类型、两个状态和配置存储生成的摘要。 */
    private static void validateMarker(Marker marker) throws IOException {
        if (!MARKER_KIND.equals(marker.kind())
            || !(PREPARED.equals(marker.state()) || COMPLETE.equals(marker.state()))
            || !validVersion(marker.sourceVersion()) || !validVersion(marker.backupVersion())
            || !validVersion(marker.preparedVersion())) {
            throw new IOException("configuration_migration_marker_invalid");
        }
    }

    /** 配置内容版本固定为 SHA-256 Base64URL 格式。 */
    private static boolean validVersion(String value) {
        return value != null && value.matches("cfg_[A-Za-z0-9_-]{43}");
    }

    /** TOML 解析错误统一进入配置损坏分类，不传播正文和解析细节。 */
    private ObjectNode parseToml(byte[] bytes) {
        return toml.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /** schema 必须使用整数，拒绝字符串或浮点隐式转换。 */
    private static long schemaVersion(ObjectNode document) {
        JsonNode schema = document.get("schema_version");
        if (schema == null || !schema.isIntegralNumber()) throw corrupt("configuration schema is invalid");
        return schema.longValue();
    }

    /** 收集 marker 字段用于闭集比较。 */
    private static Set<String> fieldSet(ObjectNode object) {
        Set<String> fields = new HashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** marker 文本字段必须存在且非空。 */
    private static String text(ObjectNode object, String key) throws IOException {
        JsonNode value = object.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("configuration_migration_marker_invalid");
        }
        return value.textValue();
    }

    /** 统一构造不携带配置正文的损坏错误。 */
    private static ConfigurationError corrupt(String message) {
        return new ConfigurationError(ConfigurationError.Code.CORRUPT_CONFIG, message);
    }

    /** v3 备份固定在目标同目录，确保发布和恢复不跨文件系统。 */
    static Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".v3.bak");
    }

    /** v4 prepared 文件固定在目标同目录并由摘要标识。 */
    static Path preparedPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".v4.prepared");
    }

    /** marker 路径明确标识唯一允许的迁移方向。 */
    static Path markerPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".v3-to-v4.marker.json");
    }

    /** marker 只保存状态和内容摘要，不保存路径、配置正文或 Secret。 */
    private record Marker(String kind, String state, String sourceVersion,
                          String backupVersion, String preparedVersion) {
    }

    /** 文件副作用端口让发布故障可测，生产控制流不含测试分支。 */
    interface FileOperations {
        /** 有界读取，缺失返回 null。 */
        byte[] read(Path path) throws IOException;

        /** 强原子替换同目录文件。 */
        void writeAtomic(Path path, byte[] bytes) throws IOException;

        /** 只清理可重建的 prepared 文件。 */
        void delete(Path path) throws IOException;
    }

    /** 生产文件端口复用 ConfigurationStore 的路径和原子性约束。 */
    private static final class DefaultFileOperations implements FileOperations {
        /** 使用统一有界、NOFOLLOW 读取。 */
        @Override
        public byte[] read(Path path) throws IOException {
            return ConfigurationStore.read(path);
        }

        /** schema 迁移禁止降级到非原子 move。 */
        @Override
        public void writeAtomic(Path path, byte[] bytes) throws IOException {
            ConfigurationStore.writeAtomicRequired(path, bytes);
        }

        /** 只删除调用方明确给出的 prepared 路径。 */
        @Override
        public void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
