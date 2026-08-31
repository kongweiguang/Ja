// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 配置 v3 到 v4 的原子迁移、崩溃恢复和旧字段清理测试。 */
final class ConfigurationSchemaMigrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temporary;

    /** v4 权威文档只严格回读，不产生迁移工件或改写字节。 */
    @Test
    void currentV4RemainsUnchangedWithoutMigrationArtifacts() throws Exception {
        Path target = temporary.resolve("config.toml");
        byte[] original = v4Config().getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        byte[] current = migration().readCurrent(target, ConfigurationScope.USER).orElseThrow();

        assertArrayEquals(original, current);
        assertFalse(Files.exists(ConfigurationSchemaMigration.backupPath(target)));
        assertFalse(Files.exists(ConfigurationSchemaMigration.markerPath(target)));
    }

    /** v3 迁移一次性删除旧作用对象和输入模态，并保留逻辑档位到上游值的显式映射。 */
    @Test
    void v3MigratesOnceWithBackupAndStrictV4Shape() throws Exception {
        Path target = temporary.resolve("config.toml");
        byte[] original = v3Config().getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        byte[] migrated = migration().readCurrent(target, ConfigurationScope.USER).orElseThrow();
        ObjectNode document = new TomlCodec(MAPPER).parse(new String(migrated, StandardCharsets.UTF_8));
        ObjectNode provider = (ObjectNode) document.withArray("providers").get(0);
        ObjectNode model = (ObjectNode) provider.withArray("models").get(0);

        assertEquals(4, document.path("schema_version").intValue());
        assertEquals("medium", document.path("default_reasoning_level").textValue());
        assertFalse(document.has("default_reasoning_effort"));
        assertFalse(provider.withObject("agent_defaults").has("skill_ids"));
        assertFalse(provider.withObject("agent_defaults").has("mcp_ids"));
        assertFalse(model.withObject("capabilities").has("input_modalities"));
        assertEquals("medium", model.withObject("reasoning_level_map").path("medium").textValue());
        assertArrayEquals(original, Files.readAllBytes(ConfigurationSchemaMigration.backupPath(target)));
        assertEquals("complete", marker(target).path("state").textValue());
    }

    /** 完成迁移后重启只校验 v4，不重复推进 revision 或改写恢复工件。 */
    @Test
    void completedMigrationIsIdempotent() throws Exception {
        Path target = temporary.resolve("config.toml");
        Files.writeString(target, v3Config());
        byte[] migrated = migration().readCurrent(target, ConfigurationScope.USER).orElseThrow();
        byte[] marker = Files.readAllBytes(ConfigurationSchemaMigration.markerPath(target));

        byte[] restarted = migration().readCurrent(target, ConfigurationScope.USER).orElseThrow();

        assertArrayEquals(migrated, restarted);
        assertArrayEquals(marker, Files.readAllBytes(ConfigurationSchemaMigration.markerPath(target)));
    }

    /** 发布失败留下 prepared marker，下一次启动按摘要完成同一个 v4 发布。 */
    @Test
    void preparedMigrationRecoversAfterPublishFailure() throws Exception {
        Path target = temporary.resolve("config.toml");
        byte[] original = v3Config().getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);
        FailingFileOperations failing = new FailingFileOperations(target);

        assertThrows(IOException.class, () -> new ConfigurationSchemaMigration(MAPPER, failing)
                .readCurrent(target, ConfigurationScope.USER));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("prepared", marker(target).path("state").textValue());

        byte[] recovered = migration().readCurrent(target, ConfigurationScope.USER).orElseThrow();
        assertEquals(4, new TomlCodec(MAPPER).parse(new String(recovered, StandardCharsets.UTF_8))
                .path("schema_version").intValue());
        assertEquals("complete", marker(target).path("state").textValue());
        assertFalse(Files.exists(ConfigurationSchemaMigration.preparedPath(target)));
    }

    /** v2 和未来版本都不能越过唯一迁移窗口，也不得产生恢复工件。 */
    @Test
    void unsupportedSchemaIsRejectedWithoutArtifacts() throws Exception {
        Path target = temporary.resolve("config.toml");
        Files.writeString(target, "schema_version = 2\nconfig_revision = 1\n");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> migration().readCurrent(target, ConfigurationScope.USER));

        assertEquals(ConfigurationError.Code.CORRUPT_CONFIG, failure.code());
        assertFalse(Files.exists(ConfigurationSchemaMigration.backupPath(target)));
        assertFalse(Files.exists(ConfigurationSchemaMigration.markerPath(target)));
    }

    /** 项目稀疏文档使用同一转换，但 v4 结果仍由项目 Policy 禁止扩权。 */
    @Test
    void projectV3SparseOverlayMigratesToV4() throws Exception {
        Path target = temporary.resolve("project.toml");
        Files.writeString(target, projectV3Config());

        ObjectNode migrated = new TomlCodec(MAPPER).parse(new String(
                migration().readCurrent(target, ConfigurationScope.PROJECT).orElseThrow(),
                StandardCharsets.UTF_8));

        assertEquals(4, migrated.path("schema_version").intValue());
        ObjectNode model = (ObjectNode) migrated.withArray("providers").get(0)
                .withArray("models").get(0);
        assertEquals("high", model.withObject("reasoning_level_map").path("high").textValue());
    }

    /** 每个测试创建独立生产迁移器，避免共享文件状态。 */
    private static ConfigurationSchemaMigration migration() {
        return new ConfigurationSchemaMigration(MAPPER);
    }

    /** marker 必须可以被严格解析为 JSON 对象。 */
    private static ObjectNode marker(Path target) throws IOException {
        return (ObjectNode) MAPPER.readTree(Files.readAllBytes(ConfigurationSchemaMigration.markerPath(target)));
    }

    /** 构造唯一允许输入迁移器的旧 v3 完整用户文档。 */
    private static String v3Config() {
        return "schema_version = 3\nconfig_revision = 7\ndefault_access_mode = \"approval_required\"\n"
                + "default_provider_id = \"provider_fixture\"\ndefault_model_id = \"model_fixture\"\n"
                + "default_reasoning_effort = \"medium\"\nmcp_servers = []\nskills = []\n"
                + "[[providers]]\nprovider_id = \"provider_fixture\"\nname = \"Fixture\"\n"
                + "provider = \"openai\"\napi = \"openai_responses\"\n"
                + "base_url = \"http://127.0.0.1\"\ncredential_id = \"cred_fixture\"\n"
                + "[providers.network_timeouts]\nconnect_timeout_ms = 10000\nrequest_timeout_ms = 120000\n"
                + "[providers.agent_defaults]\nskill_ids = []\nmcp_ids = []\n"
                + "[providers.agent_defaults.context]\nauto_compact = true\n"
                + "[providers.agent_defaults.turn_limits]\nmax_model_rounds = 32\nmax_tool_calls = 128\n"
                + "wall_timeout_ms = 600000\n"
                + "[[providers.models]]\nmodel_id = \"model_fixture\"\nname = \"Fixture Model\"\n"
                + "model = \"gpt-5\"\nreasoning_efforts = [\"low\", \"medium\", \"high\"]\n"
                + "default_reasoning_effort = \"medium\"\n"
                + "[providers.models.capabilities]\ncontext_window_tokens = 128000\n"
                + "max_output_tokens = 8192\ninput_modalities = [\"text\", \"image\", \"pdf\"]\n";
    }

    /** 构造无需迁移的严格 v4 文档。 */
    private static String v4Config() {
        return v3Config().replace("schema_version = 3", "schema_version = 4")
                .replace("default_reasoning_effort", "default_reasoning_level")
                .replace("reasoning_efforts = [\"low\", \"medium\", \"high\"]",
                        "reasoning_level_map = { low = \"low\", medium = \"medium\", high = \"high\" }")
                .replace("skill_ids = []\nmcp_ids = []\n", "")
                .replace("input_modalities = [\"text\", \"image\", \"pdf\"]\n", "");
    }

    /** 构造只有模型思考覆盖的旧项目稀疏文档。 */
    private static String projectV3Config() {
        return "schema_version = 3\nconfig_revision = 2\ndefault_access_mode = \"approval_required\"\n"
                + "default_provider_id = \"provider_fixture\"\ndefault_model_id = \"model_fixture\"\n"
                + "default_reasoning_effort = \"high\"\nmcp_servers = []\nskills = []\n"
                + "[[providers]]\nprovider_id = \"provider_fixture\"\n"
                + "[[providers.models]]\nmodel_id = \"model_fixture\"\n"
                + "reasoning_efforts = [\"high\"]\ndefault_reasoning_effort = \"high\"\n";
    }

    /** 只在指定目标第一次写入前失败，其余副作用复用生产存储。 */
    private static final class FailingFileOperations implements ConfigurationSchemaMigration.FileOperations {
        private final Path failingTarget;
        private final AtomicBoolean pending = new AtomicBoolean(true);

        /** 固定绝对目标，避免 cwd 影响路径比较。 */
        private FailingFileOperations(Path failingTarget) {
            this.failingTarget = failingTarget.toAbsolutePath().normalize();
        }

        /** 复用生产有界读取。 */
        @Override
        public byte[] read(Path path) throws IOException {
            return ConfigurationStore.read(path);
        }

        /** 在精确目标第一次写入前注入崩溃点。 */
        @Override
        public void writeAtomic(Path path, byte[] bytes) throws IOException {
            if (path.toAbsolutePath().normalize().equals(failingTarget)
                && pending.compareAndSet(true, false)) {
                throw new IOException("injected_migration_write_failure");
            }
            ConfigurationStore.writeAtomicRequired(path, bytes);
        }

        /** 只删除 prepared 文件。 */
        @Override
        public void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }
}
