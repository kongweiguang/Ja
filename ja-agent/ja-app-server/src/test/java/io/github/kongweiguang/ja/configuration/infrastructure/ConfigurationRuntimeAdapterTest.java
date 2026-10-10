// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationMutationCoordinator;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationPolicy;
import io.github.kongweiguang.ja.configuration.infrastructure.document.ConfigurationStore;
import io.github.kongweiguang.ja.configuration.infrastructure.generation.ConfigGeneration;
import io.github.kongweiguang.ja.configuration.infrastructure.generation.ConfigurationRuntimeState;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.configuration.infrastructure.generation.ConfigGenerationTestAccess.isClosed;

/** 验证配置运行时适配器的严格文档、CAS、凭据脱敏、Watcher 与 generation 租约语义。 */
final class ConfigurationRuntimeAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryRoot;

    /** 缺失配置只影响模型选择，运行时本身仍必须启动并让历史、项目与设置入口可用。 */
    @Test
    void missingConfigurationIsDegradedButServiceReady() {
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.MISSING, read.user().status());
            assertEquals("default", read.sources().get("default_access_mode"));
            assertNotNull(read.effective());
            assertTrue(node(read.effective()).path("subagents").path("enabled").asBoolean());
            assertTrue(node(read.effective()).path("subagents").path("provider_id").isNull());
            ConfigGeneration generation = service.resolveGeneration(null);
            assertTrue(generation.ready());
            assertTrue(generation.diagnostics().stream()
                    .anyMatch(diagnostic -> "MISSING_PROVIDER".equals(diagnostic.code())));
        }
    }

    /** 读取不会迁移旧字段或改写源文件；未支持字段只在运行投影中忽略。 */
    @Test
    void readDoesNotRewriteUnknownConfigurationFields() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String previous = profileConfig("migrated-model").replace(
                "[providers.agent_defaults.context]\nauto_compact = true\n",
                """
                    [providers.agent_defaults.context]
                    auto_compact = true
                    [providers.agent_defaults.turn_limits]
                    max_model_rounds = 32
                    max_tool_calls = 128
                    wall_timeout_ms = 3600000
                    """);
        Files.writeString(config, previous);
        try (ConfigurationRuntimeAdapter service = service()) {
            assertFalse(node(service.read(null).effective()).path("providers").get(0)
                    .path("agent_defaults").has("turn_limits"));
            assertEquals(previous, Files.readString(config));
            assertEquals(0, backupCount());
            service.read(null);
            assertEquals(0, backupCount());
        }
    }

    /** 只统计本测试配置目录中的已提交备份，不扫描用户真实资料。 */
    private long backupCount() throws Exception {
        try (Stream<Path> backups = Files.list(homeDirectory())) {
            return backups.filter(path -> path.getFileName().toString()
                    .startsWith("config.toml.backup-")).count();
        }
    }

    /** 缺失普通字段只回退受影响字段；不带历史版本号的测试文档仍按当前格式解释。 */
    @Test
    void missingFieldsKeepUsableProviderProjection() throws Exception {
        String omittedNulls = profileConfig("nullable-model")
                .replace("[providers.network_timeouts]\nconnect_timeout_ms = 10000\nrequest_timeout_ms = 120000\n", "")
                .replace("default_reasoning_level = \"medium\"\n", "")
                .replace("reasoning_level_map = { medium = \"medium\" }", "reasoning_level_map = {}");
        Files.writeString(homeDirectory().resolve("config.toml"), omittedNulls);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.user().status());
            assertEquals(1, node(read.effective()).withArray("providers").size());
            assertEquals(1, node(read.effective()).withArray("providers").get(0).withArray("models").size());
            assertEquals(300_000, node(read.effective()).path("providers").path(0)
                    .path("network_timeouts").path("request_timeout_ms").intValue());
            assertEquals(omittedNulls, Files.readString(homeDirectory().resolve("config.toml")));
        }
    }

    /**
     * 损坏用户层拒绝稳定 ID 普通 patch，且只允许完整严格 replace；回归同时核对拒绝前正文与修复备份逐字节相同。
     */
    @Test
    void fullReplaceRepairsCorruptUserConfigurationWithOriginalCas() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String damaged = "schema_version = 2\nproviders = [\n";
        Files.writeString(config, damaged);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult corrupt = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.PARTIAL, corrupt.user().status());
            assertNotNull(corrupt.user().document());
            assertNotEquals(ConfigurationStore.UNAVAILABLE_VERSION, corrupt.user().version());

            ConfigurationError patchFailure = assertThrows(ConfigurationError.class, () -> service.patch(
                    ConfigurationScope.USER, null, document(userPatchFromDocument(userDocument(
                            "provider_recovered", "model_recovered", "recovered-model"))),
                    corrupt.user().version()));
            assertEquals(ConfigurationError.Code.CORRUPT_CONFIG, patchFailure.code());
            assertEquals(damaged, Files.readString(config));

            ConfigurationUseCase.MutationResult repaired = service.replace(
                    ConfigurationScope.USER, null,
                    document(userDocument("provider_recovered", "model_recovered", "recovered-model")),
                    corrupt.user().version());
            ConfigurationUseCase.ReadResult restored = service.read(null);

            assertEquals(ConfigurationUseCase.LayerStatus.VALID, restored.user().status());
            assertEquals(repaired.version(), restored.user().version());
            assertEquals("provider_recovered", node(restored.effective())
                    .path("default_provider_id").textValue());
            List<Path> backups;
            try (Stream<Path> files = Files.list(homeDirectory())) {
                backups = files.filter(path -> path.getFileName().toString()
                        .startsWith("config.toml.backup-")).toList();
            }
            assertEquals(1, backups.size());
            assertEquals(damaged, Files.readString(backups.get(0)));
        }
    }

    /** 固定不可读凭据文件的恢复边界，避免脱敏读取因空 CAS 版本崩溃并封死设置修复入口。 */
    @Test
    void unreadableCredentialStoreKeepsConfigurationRepairSnapshotAvailable() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("ja-e2e-fake"));
        Files.createDirectory(homeDirectory().resolve("auth.json"));

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);

            assertEquals("provider_model", node(read.effective()).path("default_provider_id").asText());
            assertTrue(read.credentials().isEmpty());
            assertEquals(ConfigurationStore.UNAVAILABLE_VERSION, read.credentialVersion());
            assertTrue(read.diagnostics().contains("AUTH_IO_ERROR"));
        }
    }

    /** 固定不可读用户配置的恢复边界，确保设置页收到 IO 诊断和不可写哨兵而不是内部异常。 */
    @Test
    void unreadableUserConfigKeepsRedactedSnapshotAvailable() throws Exception {
        Files.createDirectory(homeDirectory().resolve("config.toml"));

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);

            assertEquals(ConfigurationUseCase.LayerStatus.IO_ERROR, read.user().status());
            assertEquals(ConfigurationStore.UNAVAILABLE_VERSION, read.user().version());
            assertTrue(read.diagnostics().contains("CONFIG_IO_ERROR"));
        }
    }

    /** 首次设置提交稀疏 Provider patch 时必须保留默认文档字段，保证 CLI setup 可以闭环落盘。 */
    @Test
    void firstSparseProviderPatchKeepsRequiredUserDefaults() {
        try (ConfigurationRuntimeAdapter service = service()) {
            ObjectNode complete = userDocument("provider_first", "model_first", "ja-local-preview");
            ObjectNode patch = JSON.createObjectNode()
                    .put("default_provider_id", "provider_first")
                    .put("default_model_id", "model_first");
            patch.set("providers", providerOperations(complete));

            service.patch(ConfigurationScope.USER, null, document(patch), "cfg_missing");

            ConfigurationUseCase.ReadResult read = service.read(null);
            ObjectNode effective = node(read.effective());
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.user().status());
            assertEquals(2, effective.path("schema_version").intValue());
            assertEquals("full_access", effective.path("default_access_mode").textValue());
            assertEquals("provider_first", effective.path("default_provider_id").textValue());
            assertEquals("model_first", effective.path("default_model_id").textValue());
            assertTrue(effective.path("subagents").path("enabled").asBoolean());
        }
    }

    /** 用户目录稀疏修改只触碰指定实体，并把原始未知项与损坏条目留在权威 TOML 中。 */
    @Test
    void userProviderAndModelPatchesPreserveUnknownAndBrokenRawEntries() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String original = profileConfig("before-model")
                .replace("name = \"Test\"", "name = \"Test\"\nfuture_provider_toggle = \"keep-provider\"")
                .replace("model = \"before-model\"",
                        "model = \"before-model\"\nfuture_model_toggle = \"keep-model\"")
                + "\n[[providers]]\nprovider_id = \"provider_broken\"\nfuture_entry = \"keep-broken\"\n";
        Files.writeString(config, original);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult before = service.read(null);
            ObjectNode patch = JSON.createObjectNode();
            ObjectNode providerPatch = patch.putObject("providers").putObject("provider_model");
            providerPatch.putObject("network_timeouts").put("request_timeout_ms", 60_000);
            providerPatch.putObject("models").putObject("model_model")
                    .put("model", "after-model")
                    .putObject("default_reasoning_level").put("__ja_null", true);

            service.patch(ConfigurationScope.USER, null, document(patch), before.user().version());

            ConfigurationUseCase.ReadResult after = service.read(null);
            String persisted = Files.readString(config);
            assertEquals(60_000, node(after.effective()).path("providers").get(0)
                    .path("network_timeouts").path("request_timeout_ms").intValue());
            assertEquals("after-model", node(after.effective()).path("providers").get(0)
                    .path("models").get(0).path("model").textValue());
            assertTrue(persisted.contains("future_provider_toggle = \"keep-provider\""));
            assertTrue(persisted.contains("future_model_toggle = \"keep-model\""));
            assertTrue(persisted.contains("default_reasoning_level = { __ja_null = true }"));
            assertTrue(persisted.contains("future_entry = \"keep-broken\""));
            assertTrue(after.issues().stream().anyMatch(issue -> "provider_broken".equals(issue.entityId())));
        }
    }

    /** provider_order 与 model_order 只重排已列出 ID 的原槽位，控制字段不写入 TOML。 */
    @Test
    void userProviderAndModelOrdersRearrangeOnlyListedSlots() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String original = profileConfig("first-model") + """
            [[providers.models]]
            model_id = "model_second"
            name = "Second"
            model = "second-model"
            reasoning_level_map = {}
            default_reasoning_level = { __ja_null = true }
            [providers.models.capabilities]
            context_window_tokens = 128000
            max_output_tokens = 8192
            [[providers]]
            provider_id = "provider_broken"
            name = "Hidden broken entry"
            [[providers]]
            provider_id = "provider_beta"
            name = "Beta"
            api = "openai_responses"
            base_url = "http://127.0.0.1"
            credential_id = "cred_beta"
            [providers.network_timeouts]
            connect_timeout_ms = 10000
            request_timeout_ms = 120000
            [providers.agent_defaults.context]
            auto_compact = true
            [[providers.models]]
            model_id = "model_beta"
            name = "Beta model"
            model = "beta-model"
            reasoning_level_map = {}
            default_reasoning_level = { __ja_null = true }
            [providers.models.capabilities]
            context_window_tokens = 128000
            max_output_tokens = 8192
            """;
        Files.writeString(config, original);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult before = service.read(null);
            ObjectNode patch = JSON.createObjectNode();
            ArrayNode providerOrder = patch.putArray("provider_order");
            providerOrder.add("provider_beta").add("provider_model");
            patch.putObject("providers").putObject("provider_model").putArray("model_order")
                    .add("model_second").add("model_model");

            service.patch(ConfigurationScope.USER, null, document(patch), before.user().version());

            ConfigurationUseCase.ReadResult after = service.read(null);
            JsonNode effective = node(after.effective());
            String persisted = Files.readString(config);
            assertEquals("provider_beta", effective.path("providers").get(0).path("provider_id").asText());
            assertEquals("provider_model", effective.path("providers").get(1).path("provider_id").asText());
            JsonNode reorderedModels = effective.path("providers").get(1).path("models");
            assertEquals("model_second", reorderedModels.get(0).path("model_id").asText());
            assertEquals("model_model", reorderedModels.get(1).path("model_id").asText());
            assertTrue(persisted.indexOf("\nprovider_id = \"provider_beta\"")
                    < persisted.indexOf("\nprovider_id = \"provider_broken\""));
            assertTrue(persisted.indexOf("\nprovider_id = \"provider_broken\"")
                    < persisted.indexOf("\nprovider_id = \"provider_model\""), persisted);
            assertFalse(persisted.contains("provider_order"));
            assertFalse(persisted.contains("model_order"));
            assertTrue(persisted.contains("provider_id = \"provider_broken\""));
        }
    }

    /** 未知、重复或指向重複原文身份的排序一律拒绝，且 CAS 目标文件保持逐字节不变。 */
    @Test
    void userProviderAndModelOrdersRejectUnknownAndDuplicateIdsAtomically() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String original = profileConfig("stable-model");
        Files.writeString(config, original);

        try (ConfigurationRuntimeAdapter service = service()) {
            String expectedVersion = service.read(null).user().version();
            List<ObjectNode> invalidPatches = new ArrayList<>();
            ObjectNode unknownProvider = JSON.createObjectNode();
            unknownProvider.putArray("provider_order").add("provider_missing");
            invalidPatches.add(unknownProvider);
            ObjectNode duplicateProvider = JSON.createObjectNode();
            duplicateProvider.putArray("provider_order").add("provider_model").add("provider_model");
            invalidPatches.add(duplicateProvider);
            ObjectNode unknownModel = JSON.createObjectNode();
            unknownModel.putObject("providers").putObject("provider_model").putArray("model_order")
                    .add("model_missing");
            invalidPatches.add(unknownModel);
            ObjectNode duplicateModel = JSON.createObjectNode();
            duplicateModel.putObject("providers").putObject("provider_model").putArray("model_order")
                    .add("model_model").add("model_model");
            invalidPatches.add(duplicateModel);
            ObjectNode arrayReplacement = JSON.createObjectNode();
            arrayReplacement.putArray("providers");
            invalidPatches.add(arrayReplacement);

            for (ObjectNode patch : invalidPatches) {
                ConfigurationError failure = assertThrows(ConfigurationError.class,
                        () -> service.patch(ConfigurationScope.USER, null, document(patch), expectedVersion));
                assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
                assertEquals(original, Files.readString(config));
            }
        }
    }

    /** Provider 删除必须在同一 CAS patch 中解除默认模型与子智能体引用，最后一个 Provider 可清空目录。 */
    @Test
    void userProviderDeleteRequiresAtomicReferenceClearingAndAllowsEmptyCatalog() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String original = profileConfig("referenced-model")
                .replace("provider_id = { __ja_null = true }", "provider_id = \"provider_model\"")
                .replace("model_id = { __ja_null = true }", "model_id = \"model_model\"")
                .replace("reasoning_level = { __ja_null = true }", "reasoning_level = \"medium\"");
        Files.writeString(config, original);

        try (ConfigurationRuntimeAdapter service = service()) {
            String expectedVersion = service.read(null).user().version();
            ObjectNode deleteOnly = JSON.createObjectNode();
            deleteOnly.putObject("providers").putNull("provider_model");
            ConfigurationError referenced = assertThrows(ConfigurationError.class,
                    () -> service.patch(ConfigurationScope.USER, null, document(deleteOnly), expectedVersion));
            assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, referenced.code());
            assertEquals(original, Files.readString(config));

            ObjectNode complete = JSON.createObjectNode();
            complete.putObject("default_provider_id").put("__ja_null", true);
            complete.putObject("default_model_id").put("__ja_null", true);
            complete.putObject("default_reasoning_level").put("__ja_null", true);
            ObjectNode subagents = complete.putObject("subagents");
            subagents.putObject("provider_id").put("__ja_null", true);
            subagents.putObject("model_id").put("__ja_null", true);
            subagents.putObject("reasoning_level").put("__ja_null", true);
            complete.putObject("providers").putNull("provider_model");
            service.patch(ConfigurationScope.USER, null, document(complete), expectedVersion);

            JsonNode effective = node(service.read(null).effective());
            String persisted = Files.readString(config);
            assertTrue(effective.path("providers").isEmpty());
            assertTrue(effective.path("default_provider_id").isNull());
            assertTrue(effective.path("default_model_id").isNull());
            assertTrue(effective.path("default_reasoning_level").isNull());
            assertTrue(effective.path("subagents").path("provider_id").isNull());
            assertTrue(effective.path("subagents").path("model_id").isNull());
            assertTrue(effective.path("subagents").path("reasoning_level").isNull());
            assertTrue(persisted.contains("default_provider_id = { __ja_null = true }"));
            assertTrue(persisted.contains("default_model_id = { __ja_null = true }"));
            assertTrue(persisted.contains("default_reasoning_level = { __ja_null = true }"));
            assertTrue(persisted.contains("provider_id = { __ja_null = true }"));
            assertTrue(persisted.contains("model_id = { __ja_null = true }"));
            assertTrue(persisted.contains("reasoning_level = { __ja_null = true }"));
        }
    }

    /** 普通 Merge Patch 不能借 tolerant 投影把显式旧版或非法 schema 写回原文。 */
    @Test
    void patchRejectsUnsupportedOrInvalidSchemaVersionWithoutChangingEitherLayer() throws Exception {
        Path userConfig = homeDirectory().resolve("config.toml");
        String originalUser = profileConfig("patch-schema-user");
        Files.writeString(userConfig, originalUser);

        Path workspace = Files.createDirectory(temporaryRoot.resolve("patch-schema-project"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        String originalProject = "schema_version = 2\nconfig_revision = 1\n";
        Files.writeString(projectConfig, originalProject);
        List<JsonNode> invalidVersions = List.of(
                JSON.getNodeFactory().numberNode(1),
                JSON.getNodeFactory().textNode("two"),
                JSON.readTree("2147483648"));

        try (ConfigurationRuntimeAdapter service = service()) {
            assertTrue(service.synchronizeWorkspaceTrust(workspace, true));
            ConfigurationUseCase.ReadResult before = service.read(workspace);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, before.user().status());
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, before.project().status());

            for (ConfigurationScope scope : List.of(ConfigurationScope.USER, ConfigurationScope.PROJECT)) {
                String expectedSource = scope == ConfigurationScope.USER ? originalUser : originalProject;
                String expectedVersion = scope == ConfigurationScope.USER
                        ? before.user().version() : before.project().version();
                Path cwd = scope == ConfigurationScope.USER ? null : workspace;
                Path source = scope == ConfigurationScope.USER ? userConfig : projectConfig;
                for (JsonNode invalidVersion : invalidVersions) {
                    ObjectNode patch = JSON.createObjectNode();
                    patch.set("schema_version", invalidVersion.deepCopy());

                    ConfigurationError failure = assertThrows(ConfigurationError.class,
                            () -> service.patch(scope, cwd, document(patch), expectedVersion));

                    assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
                    assertEquals(expectedSource, Files.readString(source));
                }
            }

            ConfigurationUseCase.ReadResult after = service.read(workspace);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, after.user().status());
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, after.project().status());
        }
    }

    /** 验证配置与凭据使用统一不透明版本，且过期 CAS 不会覆盖已提交内容。 */
    @Test
    void configAndCredentialCasAreOpaqueAndRedacted() {
        ObjectMapper mapper = new ObjectMapper();
        try (ConfigurationRuntimeAdapter service = service()) {
            ObjectNode firstPatch = userPatchFromDocument(
                    userDocument("provider_model", "model_model", "ja-e2e-fake"));
            ((ObjectNode) firstPatch.path("providers").path("provider_model"))
                    .put("credential_id", "cred_deep.seek");
            ConfigurationError missingExpected = assertThrows(ConfigurationError.class, () -> service.patch(
                    ConfigurationScope.USER, null, document(firstPatch), null));
            assertEquals(ConfigurationError.Code.INVALID_ARGUMENT, missingExpected.code());
            ConfigurationUseCase.MutationResult first = service.patch(
                    ConfigurationScope.USER, null, document(firstPatch), "cfg_missing");
            assertTrue(first.version().startsWith("cfg_"));
            ConfigurationError stale = assertThrows(ConfigurationError.class, () -> service.patch(
                    ConfigurationScope.USER, null,
                    document(userPatchFromDocument(
                            userDocument("provider_other", "model_other", "other-model"))),
                    "cfg_missing"));
            assertEquals(ConfigurationError.Code.CAS_CONFLICT, stale.code());

            ConfigurationUseCase.CredentialResult credential = service.setCredential(
                    "cred_deep.seek", "never-print-this-secret", "cfg_missing");
            assertTrue(credential.version().startsWith("cfg_"));
            assertTrue(credential.configured());
            assertFalse(credential.toString().contains("never-print-this-secret"));
        }
    }

    /**
     * 验证两个独立服务实例不能同时接受同一个 missing 版本；进程级协调必须让一个写入成功，
     * 另一个在读取已发布字节后得到 CAS_CONFLICT，而不是静默覆盖。
     */
    @Test
    void concurrentServicesRejectDuplicateConfigurationCas() throws Exception {
        try (ConfigurationRuntimeAdapter firstService = service();
             ConfigurationRuntimeAdapter secondService = service();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ConfigurationError.Code> first = executor.submit(() -> concurrentPatch(
                    firstService, "first", ready, start));
            Future<ConfigurationError.Code> second = executor.submit(() -> concurrentPatch(
                    secondService, "second", ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            ConfigurationError.Code firstResult = first.get(5, TimeUnit.SECONDS);
            ConfigurationError.Code secondResult = second.get(5, TimeUnit.SECONDS);
            long successes = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(java.util.Objects::isNull).count();
            long conflicts = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(ConfigurationError.Code.CAS_CONFLICT::equals).count();
            assertEquals(1L, successes);
            assertEquals(1L, conflicts);
        }
    }

    /** 验证 replace 仍执行严格文档与 secret 策略，不因“完整替换”成为绕过校验的修复通道。 */
    @Test
    void replaceRejectsUnknownAndLiteralSecretFields() {
        try (ConfigurationRuntimeAdapter service = service()) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode unknown = mapper.createObjectNode().put("unsupportedField", "invalid");
            ConfigurationError unsupported = assertThrows(ConfigurationError.class,
                    () -> service.replace(ConfigurationScope.USER, null, document(unknown), "cfg_missing"));
            assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, unsupported.code());

            ObjectNode literalSecret = userDocument("provider_secret", "model_secret", "secret-model");
            ((ObjectNode) literalSecret.withArray("providers").get(0))
                    .put("api_token", "must-not-persist");
            ConfigurationError rejected = assertThrows(ConfigurationError.class,
                    () -> service.replace(ConfigurationScope.USER, null,
                            document(literalSecret), "cfg_missing"));
            assertTrue(rejected.code() == ConfigurationError.Code.INVALID_DOCUMENT
                    || rejected.code() == ConfigurationError.Code.LITERAL_SECRET);
            assertFalse(Files.exists(homeDirectory().resolve("config.toml")));
        }
    }

    /** 完整 replace 缺失 schema 时必须拒绝，发布路径不得自动写入当前版本。 */
    @Test
    void replaceRejectsMissingSchemaWithoutRepairingDocument() {
        ObjectNode value = userDocument("provider_strict", "model_strict", "strict-model");
        value.remove("schema_version");

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationError failure = assertThrows(ConfigurationError.class,
                    () -> service.replace(ConfigurationScope.USER, null, document(value), "cfg_missing"));

            assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
            assertFalse(Files.exists(homeDirectory().resolve("config.toml")));
        }
    }

    /** 验证子智能体策略经过 TOML 持久化、服务重建和 CAS 更新后仍保持严格快照语义。 */
    @Test
    void subagentPolicyPersistsAcrossRestartAndRejectsStaleOrDeletedReferences() {
        ObjectNode configured = userDocument("provider_subagent", "model_subagent", "subagent-model");
        ObjectNode provider = (ObjectNode) configured.withArray("providers").get(0);
        ObjectNode fallback = provider.withArray("models").get(0).deepCopy();
        fallback.put("model_id", "model_default");
        provider.withArray("models").add(fallback);
        configured.put("default_model_id", "model_default");
        configured.with("subagents").put("enabled", false)
                .put("provider_id", "provider_subagent").put("model_id", "model_subagent")
                .put("reasoning_level", "medium");

        String configuredVersion;
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.MutationResult result = service.replace(
                    ConfigurationScope.USER, null, document(configured), "cfg_missing");
            configuredVersion = result.version();
        }

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult persisted = service.read(null);
            JsonNode policy = node(persisted.effective()).path("subagents");
            assertFalse(policy.path("enabled").asBoolean());
            assertEquals("provider_subagent", policy.path("provider_id").asText());
            assertEquals("model_subagent", policy.path("model_id").asText());
            assertEquals("medium", policy.path("reasoning_level").asText());
            assertEquals(configuredVersion, persisted.user().version());

            ObjectNode followParent = configured.deepCopy();
            followParent.with("subagents").put("enabled", true).putNull("provider_id").putNull("model_id")
                    .putNull("reasoning_level");
            ConfigurationUseCase.MutationResult switched = service.replace(
                    ConfigurationScope.USER, null, document(followParent), configuredVersion);

            ObjectNode stale = followParent.deepCopy();
            stale.with("subagents").put("enabled", false)
                    .put("provider_id", "provider_subagent").put("model_id", "model_subagent");
            ConfigurationError conflict = assertThrows(ConfigurationError.class, () -> service.replace(
                    ConfigurationScope.USER, null, document(stale), configuredVersion));
            assertEquals(ConfigurationError.Code.CAS_CONFLICT, conflict.code());
            assertEquals(switched.version(), service.read(null).user().version());

            ObjectNode deletedModel = followParent.deepCopy();
            deletedModel.with("subagents").put("enabled", false)
                    .put("provider_id", "provider_subagent").put("model_id", "model_subagent");
            ((ArrayNode) deletedModel.withArray("providers").get(0).get("models")).remove(0);
            ConfigurationError missingReference = assertThrows(ConfigurationError.class, () -> service.replace(
                    ConfigurationScope.USER, null, document(deletedModel), switched.version()));
            assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, missingReference.code());
            assertEquals(switched.version(), service.read(null).user().version());
        }

        try (ConfigurationRuntimeAdapter service = service()) {
            JsonNode policy = node(service.read(null).effective()).path("subagents");
            assertTrue(policy.path("enabled").asBoolean());
            assertTrue(policy.path("provider_id").isNull());
            assertTrue(policy.path("model_id").isNull());
        }
    }

    /** v2 只接受 Provider/Model 能力与自动压缩开关，并拒绝闭集外压缩阈值字段。 */
    @Test
    void mergePatchAcceptsV2CapabilitiesAndRejectsUnknownContextFields() {
        ObjectNode patch = userDocument("provider_budget", "model_budget", "budget-model");
        ObjectNode invalidDocument = patch.deepCopy();
        ((ObjectNode) invalidDocument.withArray("providers").get(0)
                .path("agent_defaults").path("context")).put("window_tokens", 128_000);
        ConfigurationError literal = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(invalidDocument));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, literal.code());

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.MutationResult result = service.patch(
                    ConfigurationScope.USER, null, document(userPatchFromDocument(patch)), "cfg_missing");
            assertTrue(result.version().startsWith("cfg_"));
            assertEquals(128_000, node(service.read(null).effective()).path("providers").get(0)
                    .path("models").get(0).path("capabilities")
                    .path("context_window_tokens").intValue());
            assertEquals("openai_responses", node(service.read(null).effective())
                    .path("providers").get(0).path("api").textValue());
        }
    }

    /** 仅缺失或非法额度采用新默认值；旧配置中显式保存的额度保持不变。 */
    @Test
    void defaultsMissingOutputCeilingTo16384AndPreservesExplicitLegacyValue() {
        ObjectNode missingOutput = userDocument("provider_default_output", "model_default_output", "fixture-model");
        ((ObjectNode) missingOutput.withArray("providers").get(0).path("models").get(0)
                .path("capabilities")).remove("max_output_tokens");

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.MutationResult defaulted = service.patch(
                    ConfigurationScope.USER, null, document(userPatchFromDocument(missingOutput)), "cfg_missing");
            assertEquals(16_384, node(service.read(null).effective()).path("providers").get(0)
                    .path("models").get(0).path("capabilities").path("max_output_tokens").intValue());

            ObjectNode legacyValue = userDocument("provider_default_output", "model_default_output", "fixture-model");
            ((ObjectNode) legacyValue.withArray("providers").get(0).path("models").get(0)
                    .path("capabilities")).put("max_output_tokens", 8_192);
            service.patch(ConfigurationScope.USER, null,
                    document(userPatchFromDocument(legacyValue)), defaulted.version());
            assertEquals(8_192, node(service.read(null).effective()).path("providers").get(0)
                    .path("models").get(0).path("capabilities").path("max_output_tokens").intValue());
        }
    }

    /** projectConfigurationRequiresJavaTrust 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    void projectConfigurationRequiresJavaTrust() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("user-model")
                .replace("disabled_skills = []", "disabled_skills = [\"user:global-fixture\"]"));
        Path projectJa = Files.createDirectories(workspace.resolve(".ja"));
        Files.writeString(projectJa.resolve("config.toml"), projectSkillConfig());

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult untrusted = service.read(workspace);
            assertEquals(ConfigurationUseCase.LayerStatus.UNTRUSTED, untrusted.project().status());
            assertEquals("user:global-fixture", node(untrusted.effective()).withArray("disabled_skills").get(0).textValue());
            try (ConfigGeneration.Lease lease = service.acquire(workspace)) {
                assertFalse(lease.snapshot().trusted());
            }

            assertTrue(service.synchronizeWorkspaceTrust(workspace, true));
            ConfigurationUseCase.ReadResult trusted = service.read(workspace);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, trusted.project().status());
            assertEquals("project:workspace-fixture", node(trusted.project().document())
                    .withArray("disabled_skills").get(0).textValue());
            assertEquals(1, node(trusted.effective()).withArray("disabled_skills").size());
            try (ConfigGeneration.Lease lease = service.acquire(workspace)) {
                assertTrue(lease.snapshot().trusted());
            }

            assertTrue(service.synchronizeWorkspaceTrust(workspace, false));
            assertEquals(ConfigurationUseCase.LayerStatus.UNTRUSTED,
                    service.read(workspace).project().status());
            assertEquals("user:global-fixture", node(service.read(workspace).effective())
                    .withArray("disabled_skills").get(0).textValue());
        }
    }

    /** 可信项目按字段覆盖用户配置，未提及的同一对象字段继续继承，集合空值明确清空下层列表。 */
    @Test
    void trustedProjectOverridesOrdinaryRuntimeConfigurationByFieldAndIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("project-overrides"));
        Files.writeString(homeDirectory().resolve("config.toml"), catalogConfig("http://127.0.0.1:9"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        Files.writeString(projectConfig, """
            schema_version = 2
            config_revision = 1
            default_access_mode = "full_access"
            default_provider_id = "provider_catalog"
            default_model_id = "model_catalog"
            default_reasoning_level = "medium"
            disabled_skills = []
            [interaction]
            clarification_enabled = false
            [subagents]
            enabled = false
            [[providers]]
            provider_id = "provider_catalog"
            api = "anthropic_messages"
            base_url = "https://project.example/v1"
            credential_id = "cred_never_defined_project_route_9a7"
            [providers.network_timeouts]
            request_timeout_ms = 60000
            [providers.agent_defaults.context]
            auto_compact = false
            [[mcp_servers]]
            mcp_id = "mcp_catalog"
            name = "Project MCP"
            transport = "stdio"
            endpoint = "project-command"
            args = []
            env = {}
            headers = {}
            auth = { kind = "none" }
            enabled = false
            """);

        try (ConfigurationRuntimeAdapter service = service()) {
            service.synchronizeWorkspaceTrust(workspace, true);
            ConfigurationUseCase.ReadResult read = service.read(workspace);
            JsonNode effective = node(read.effective());
            JsonNode provider = effective.path("providers").get(0);
            JsonNode projectMcp = effective.path("mcp_servers").get(0);

            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.project().status());
            assertEquals("full_access", effective.path("default_access_mode").asText());
            assertEquals("project", read.sources().get("default_access_mode"));
            assertEquals("project", read.sources().get(
                    "providers.provider_catalog.network_timeouts.request_timeout_ms"));
            assertEquals("http://127.0.0.1", provider.path("base_url").asText());
            assertEquals("user", read.sources().get("providers.provider_catalog.base_url"));
            assertTrue(read.issues().stream().anyMatch(issue ->
                    "CREDENTIAL_REFERENCE_UNAVAILABLE".equals(issue.reason())
                            && "provider_catalog".equals(issue.entityId())));
            assertEquals("user", read.sources().get(
                    "providers.provider_catalog.network_timeouts.connect_timeout_ms"));
            assertFalse(effective.path("interaction").path("clarification_enabled").asBoolean());
            assertFalse(effective.path("subagents").path("enabled").asBoolean());
            assertEquals(60_000, provider.path("network_timeouts").path("request_timeout_ms").intValue());
            assertEquals(10_000, provider.path("network_timeouts").path("connect_timeout_ms").intValue());
            assertFalse(provider.path("agent_defaults").path("context").path("auto_compact").asBoolean());
            assertTrue(effective.withArray("disabled_skills").isEmpty());
            assertEquals("project-command", projectMcp.path("endpoint").asText());
            assertFalse(projectMcp.path("enabled").asBoolean());
            try (ConfigGeneration.Lease lease = service.acquireGeneration(workspace)) {
                assertEquals(ConfigurationGenerationSnapshot.Scope.PROJECT,
                        lease.snapshot().mcpDefinitions().stream()
                                .filter(server -> "mcp_catalog".equals(server.mcpId()))
                                .findFirst().orElseThrow().scope());
            }
        }
    }

    /** 稳定 ID patch 只改目标 Provider 叶字段，并保留该条目未识别的原始字段。 */
    @Test
    void projectProviderPatchPreservesUntouchedUnknownFields() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("project-provider-patch"));
        Files.writeString(homeDirectory().resolve("config.toml"), catalogConfig("http://127.0.0.1"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        Files.writeString(projectConfig, """
            schema_version = 2
            config_revision = 1
            [[providers]]
            provider_id = "provider_catalog"
            future_provider_toggle = "preserve-me"
            [providers.network_timeouts]
            request_timeout_ms = 120000
            [providers.agent_defaults.context]
            auto_compact = true
            """);

        try (ConfigurationRuntimeAdapter service = service()) {
            service.synchronizeWorkspaceTrust(workspace, true);
            ConfigurationUseCase.ReadResult before = service.read(workspace);
            ObjectNode patch = JSON.createObjectNode();
            patch.putObject("providers").putObject("provider_catalog")
                    .putObject("network_timeouts").put("request_timeout_ms", 60_000);

            service.patch(ConfigurationScope.PROJECT, workspace, document(patch), before.project().version());

            String persisted = Files.readString(projectConfig);
            ConfigurationUseCase.ReadResult after = service.read(workspace);
            assertTrue(persisted.contains("future_provider_toggle = \"preserve-me\""));
            assertEquals(60_000, node(after.effective()).path("providers").get(0)
                    .path("network_timeouts").path("request_timeout_ms").intValue());
            assertTrue(after.issues().stream().anyMatch(issue -> "UNKNOWN_FIELD".equals(issue.reason())
                    && "future_provider_toggle".equals(issue.field())));
        }
    }

    /** Model patch 按稳定 ID 持久化项目覆盖，显式 null 后恢复用户模型与来源。 */
    @Test
    void projectModelPatchCanOverrideAndInheritByStableIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("project-model-patch"));
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("global-model"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        Files.writeString(projectConfig, "schema_version = 2\nconfig_revision = 1\n");

        try (ConfigurationRuntimeAdapter service = service()) {
            service.synchronizeWorkspaceTrust(workspace, true);
            ConfigurationUseCase.ReadResult before = service.read(workspace);
            ObjectNode model = JSON.createObjectNode()
                    .put("name", "Project Model")
                    .put("model", "project-model");
            model.putObject("capabilities")
                    .put("context_window_tokens", 128_000)
                    .put("max_output_tokens", 8_192);
            model.putObject("reasoning_level_map").put("medium", "medium");
            model.set("default_reasoning_level", JSON.createObjectNode().put("__ja_null", true));
            ObjectNode override = JSON.createObjectNode();
            override.putObject("providers").putObject("provider_model")
                    .putObject("models").set("model_model", model);

            service.patch(ConfigurationScope.PROJECT, workspace, document(override), before.project().version());

            ConfigurationUseCase.ReadResult overridden = service.read(workspace);
            String modelPath = "providers.provider_model.models.model_model.model";
            assertEquals("project-model", node(overridden.effective()).path("providers").get(0)
                    .path("models").get(0).path("model").asText());
            assertEquals("project", overridden.sources().get(modelPath));
            assertTrue(Files.readString(projectConfig).contains("model = \"project-model\""));
            assertTrue(Files.readString(projectConfig).contains("default_reasoning_level = { __ja_null = true }"));
            assertTrue(overridden.issues().stream().noneMatch(issue ->
                    "INVALID_FIELD".equals(issue.reason()) && "default_reasoning_level".equals(issue.field())));

            ObjectNode inherit = JSON.createObjectNode();
            inherit.putObject("providers").putObject("provider_model")
                    .putObject("models").putNull("model_model");
            service.patch(ConfigurationScope.PROJECT, workspace, document(inherit), overridden.project().version());

            ConfigurationUseCase.ReadResult inherited = service.read(workspace);
            assertEquals("global-model", node(inherited.effective()).path("providers").get(0)
                    .path("models").get(0).path("model").asText());
            assertEquals("user", inherited.sources().get(modelPath));
        }
    }

    /** 项目显式空模型集合只禁用该 Provider 的项目模型目录，不阻断有效配置读取或代际创建。 */
    @Test
    void projectCanExplicitlyClearProviderModelsWithoutBlockingConfiguration() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("project-empty-models"));
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("global-model"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        Files.writeString(projectConfig, """
            schema_version = 2
            config_revision = 1
            [[providers]]
            provider_id = "provider_model"
            models = []
            """);

        try (ConfigurationRuntimeAdapter service = service()) {
            service.synchronizeWorkspaceTrust(workspace, true);
            ConfigurationUseCase.ReadResult read = service.read(workspace);
            JsonNode effective = node(read.effective());

            assertTrue(effective.path("providers").get(0).path("models").isEmpty());
            assertTrue(effective.path("default_provider_id").isNull());
            assertTrue(effective.path("default_model_id").isNull());
            assertEquals("project", read.sources().get("default_model_id"));
            try (ConfigGeneration.Lease lease = service.acquireGeneration(workspace)) {
                assertTrue(lease.snapshot().defaultModelId().isEmpty());
                assertTrue(lease.snapshot().requireProvider("provider_model").models().isEmpty());
            }
        }
    }

    /** generationReReadsExternalChangeAndPinsOldView 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    void generationReReadsExternalChangeAndPinsOldView() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("first-model"));
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigGeneration first = service.resolveGeneration(null);
            Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("second-model"));
            ConfigGeneration second = service.resolveGeneration(null);

            assertNotEquals(first.generationId(), second.generationId());
            assertEquals("first-model", selectedModel(first.effectiveConfig()).path("model").textValue());
            assertEquals("second-model", selectedModel(second.effectiveConfig()).path("model").textValue());
            assertEquals(first.catalogDigest(), second.catalogDigest());
        }
    }

    /** 通过租约公开快照固定 Turn 配置，并确认历史轮换只在最后一个持有者释放后回收。 */
    @Test
    void generationCacheDoesNotRetainUnleasedRotations() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("cache-model-0"));
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigGeneration first = service.resolveGeneration(null);
            assertSame(first, service.resolveGeneration(null));
            assertEquals(1, service.trackedGenerationCount());
            try (ConfigGeneration.Lease lease = first.acquire()) {
                String leasedGenerationId = lease.generationId();
                for (int index = 1; index <= 16; index++) {
                    Files.writeString(homeDirectory().resolve("config.toml"),
                            profileConfig("cache-model-" + index));
                    ConfigGeneration current = service.resolveGeneration(null);
                    assertTrue(service.trackedGenerationCount() <= 2);
                    assertEquals("cache-model-" + index,
                            selectedModel(current.effectiveConfig()).path("model").textValue());
                }
                assertTrue(isClosed(first));
                assertEquals(leasedGenerationId, lease.generationId());
                assertEquals("cache-model-0", selectedModel(lease.snapshot()));
            }
            /* 最后一个旧租约释放后，连续轮换的代际应收敛为唯一当前快照。 */
            service.resolveGeneration(null);
            /*
             * Watcher 可在 resolve 返回后立即失效外部写入产生的当前代际，因此零个和一个都表示
             * 缓存已收敛；此处只冻结“不会保留历史轮换”这一资源上限。
             */
            assertTrue(service.trackedGenerationCount() <= 1);
        }
    }

    /** 验证外部变更只发布脱敏元数据，旧配置仍由存活租约的公开快照固定。 */
    @Test
    @SuppressWarnings("try")
    void externalEditPublishesRedactedChangeAndInvalidatesGeneration() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("watch-model-1"));
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigGeneration first = service.resolveGeneration(null);
            try (ConfigGeneration.Lease lease = first.acquire()) {
                CountDownLatch changed = new CountDownLatch(1);
                AtomicReference<ConfigurationRuntimeState.ConfigChanged> event = new AtomicReference<>();
                try (AutoCloseable ignored = service.addChangeListener(value -> {
                    if ("user".equals(value.scope())) {
                        event.set(value);
                        changed.countDown();
                    }
                })) {
                    Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("watch-model-2"));
                    assertTrue(changed.await(5, TimeUnit.SECONDS));
                }
                ConfigurationRuntimeState.ConfigChanged value = event.get();
                assertNotNull(value);
                assertTrue(value.version().startsWith("cfg_"));
                assertNull(value.canonicalCwd());
                assertTrue(isClosed(first));
                assertEquals("watch-model-1", selectedModel(lease.snapshot()));
                ConfigGeneration second = service.resolveGeneration(null);
                assertNotSame(first, second);
                assertEquals("watch-model-2",
                        selectedModel(second.effectiveConfig()).path("model").textValue());
            }
        }
    }

    /**
     * 用规范路径持有与生产写入相同的协调条带，并区分旧基线回调和本次 patch 版本；旧版本可迟到，
     * 但 patch 返回的新版本必须等写入条带释放后才允许发布。
     */
    @Test
    @SuppressWarnings("try")
    void projectPatchWatcherNotificationWaitsForMutationCoordinator() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("project-patch-watch"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        Files.writeString(projectConfig, "schema_version = 2\nconfig_revision = 1\n");
        Path canonicalWorkspace = workspace.toRealPath();
        Path canonicalProjectConfig = canonicalWorkspace.resolve(".ja").resolve("config.toml");
        assertEquals(canonicalProjectConfig, projectConfig.toRealPath(),
                "test lock must identify the same file as the production path policy");

        try (ConfigurationRuntimeAdapter service = service()) {
            service.synchronizeWorkspaceTrust(canonicalWorkspace, true);
            ConfigurationUseCase.ReadResult before = service.read(canonicalWorkspace);
            String baselineVersion = before.project().version();
            ObjectNode patch = JSON.createObjectNode().put("default_access_mode", "approval_required");
            CountDownLatch patched = new CountDownLatch(1);
            CountDownLatch releaseMutation = new CountDownLatch(1);
            AtomicReference<String> patchedVersion = new AtomicReference<>();
            CountDownLatch patchVersionPublished = new CountDownLatch(1);
            List<ConfigurationRuntimeState.ConfigChanged> events = new CopyOnWriteArrayList<>();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (AutoCloseable ignored = service.addChangeListener(value -> {
                if ("project".equals(value.scope()) && canonicalWorkspace.equals(value.canonicalCwd())) {
                    events.add(value);
                    if (value.version().equals(patchedVersion.get())) {
                        patchVersionPublished.countDown();
                    }
                }
            })) {
                Future<?> mutation = executor.submit(() -> {
                    return ConfigurationMutationCoordinator.execute(canonicalProjectConfig, () -> {
                        patchedVersion.set(service.patch(ConfigurationScope.PROJECT, canonicalWorkspace,
                                document(patch), baselineVersion).version());
                        patched.countDown();
                        try {
                            releaseMutation.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test mutation interrupted", interrupted);
                        }
                        return null;
                    });
                });
                assertTrue(patched.await(5, TimeUnit.SECONDS));
                String newVersion = patchedVersion.get();
                assertNotNull(newVersion);
                assertNotEquals(baselineVersion, newVersion);
                assertFalse(patchVersionPublished.await(500, TimeUnit.MILLISECONDS),
                        "Watcher must not publish the patch version while its path is locked");
                List<String> heldVersions = events.stream()
                        .map(ConfigurationRuntimeState.ConfigChanged::version).toList();
                assertTrue(heldVersions.stream().allMatch(baselineVersion::equals),
                        "Only a previously stabilized baseline notification may arrive while locked: "
                                + heldVersions);
                releaseMutation.countDown();
                mutation.get(5, TimeUnit.SECONDS);
                assertTrue(patchVersionPublished.await(5, TimeUnit.SECONDS));
                assertTrue(newVersion.startsWith("cfg_"));
                assertEquals(ConfigurationStore.version(canonicalProjectConfig), newVersion);
                assertTrue(events.stream().anyMatch(value -> newVersion.equals(value.version())));
            } finally {
                releaseMutation.countDown();
                executor.shutdownNow();
            }
        }
    }

    /** 关闭与注册共享监视器后，终态服务必须拒绝新订阅。 */
    @Test
    void rejectsChangeListenerRegistrationAfterClose() {
        try (ConfigurationRuntimeAdapter service = service()) {
            service.close();

            ConfigurationError failure = assertThrows(ConfigurationError.class,
                    () -> service.addChangeListener(change -> { }));
            assertEquals(ConfigurationError.Code.IO_FAILURE, failure.code());
        }
    }

    /** SuppressWarnings 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    @SuppressWarnings("try")
    void generationLeaseOwnsSecretUntilRelease() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("lease-model"));
        try (ConfigurationRuntimeAdapter service = service()) {
            service.setCredential("cred_model", "lease-only-secret", "cfg_missing");
            ConfigGeneration generation = service.resolveGeneration(null);
            ConfigGeneration.Lease lease = generation.acquire();
            try {
                service.close();
                assertEquals("lease-only-secret", lease.secretFor("cred_model"));
            } finally {
                lease.close();
            }
            assertThrows(IllegalStateException.class, lease::generationId);
        }
    }

    /** SuppressWarnings 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    @SuppressWarnings("try")
    void generationReferenceCountClearsOnlyAfterLastLease() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("refcount-model"));
        try (ConfigurationRuntimeAdapter service = service()) {
            service.setCredential("cred_model", "refcount-secret", "cfg_missing");
            ConfigGeneration generation = service.resolveGeneration(null);
            ConfigGeneration.Lease first = generation.acquire();
            ConfigGeneration.Lease second = generation.acquire();
            service.close();
            first.close();
            assertEquals("refcount-secret", second.secretFor("cred_model"));
            second.close();
            assertThrows(IllegalStateException.class, second::generationId);
            assertThrows(IllegalStateException.class, generation::acquire);
        }
    }

    /** SuppressWarnings 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    @SuppressWarnings("try")
    void concurrentLeaseCloseIsOneShot() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("close-race-model"));
        try (ConfigurationRuntimeAdapter service = service()) {
            service.setCredential("cred_model", "close-race-secret", "cfg_missing");
            ConfigGeneration generation = service.resolveGeneration(null);
            ConfigGeneration.Lease raced = generation.acquire();
            ConfigGeneration.Lease keeper = generation.acquire();
            service.close();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<?> first = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    raced.close();
                    return null;
                });
                Future<?> second = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    raced.close();
                    return null;
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            }

            assertEquals("close-race-secret", keeper.secretFor("cred_model"));
            keeper.close();
            assertThrows(IllegalStateException.class, keeper::generationId);
        }
    }

    /** 比较公开代际标识与快照，验证原子 acquire 在轮换后固定旧模型和凭据。 */
    @Test
    @SuppressWarnings("try")
    void atomicAcquirePinsOldGenerationAcrossRotation() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("atomic-old-model"));
        try (ConfigurationRuntimeAdapter service = service()) {
            service.setCredential("cred_model", "atomic-secret", "cfg_missing");
            ConfigGeneration.Lease oldLease = service.acquireGeneration(null);
            try {
                Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("atomic-new-model"));
                String credentialVersion = service.read(null).credentialVersion();
                service.setCredential("cred_model", "rotated-atomic-secret", credentialVersion);
                ConfigGeneration.Lease newLease = service.acquireGeneration(null);
                try {
                    assertNotEquals(oldLease.generationId(), newLease.generationId());
                    assertEquals("atomic-old-model", selectedModel(oldLease.snapshot()));
                    assertEquals("atomic-new-model", selectedModel(newLease.snapshot()));
                    assertEquals("atomic-secret", oldLease.secretFor("cred_model"));
                    assertEquals("rotated-atomic-secret", newLease.secretFor("cred_model"));
                } finally {
                    newLease.close();
                }
            } finally {
                oldLease.close();
            }
        }
    }

    /** 仅用端口公开快照验证目录轮换与缺省值，避免测试依赖可关闭的内部 ConfigGeneration。 */
    @Test
    void generationCatalogIsFrozenAndMissingMcpEnabledDefaultsToTrue() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), catalogConfig("http://127.0.0.1:1"));
        try (ConfigurationRuntimeAdapter service = service()) {
            service.setCredential("cred_model", "catalog-secret", "cfg_missing");
            try (ConfigGeneration.Lease firstLease = service.acquireGeneration(null)) {
                ConfigurationGenerationSnapshot first = firstLease.snapshot();
                assertTrue(first.defaultProviderId().isPresent());
                assertTrue(first.defaultModelId().isPresent());
                assertEquals(1, first.skillDefinitions().size());
                assertEquals(1, first.mcpDefinitions().size());
                Files.writeString(homeDirectory().resolve("config.toml"), catalogConfig("http://127.0.0.1:2"));
                try (ConfigGeneration.Lease secondLease = service.acquireGeneration(null)) {
                    ConfigurationGenerationSnapshot second = secondLease.snapshot();
                    assertNotEquals(first.generationId(), second.generationId());
                    assertEquals("http://127.0.0.1:1", first.mcpDefinitions().get(0).endpoint());
                    assertEquals("http://127.0.0.1:2", second.mcpDefinitions().get(0).endpoint());
                }
                Files.writeString(homeDirectory().resolve("config.toml"), catalogConfigWithMissingMcpEnabled());
                try (ConfigGeneration.Lease missingLease = service.acquireGeneration(null)) {
                    ConfigurationGenerationSnapshot missing = missingLease.snapshot();
                    assertEquals(1, missing.mcpDefinitions().size());
                    assertTrue(missing.mcpDefinitions().get(0).enabled());
                }
            }
        }
    }

    /** corruptFilesNeverEchoSensitiveText 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void corruptFilesNeverEchoSensitiveText() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), "schema_version = [broken");
        ConfigurationStore.writeAtomic(homeDirectory().resolve("auth.json"),
                "{\"cred_model\":\"secret-in-file\"".getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.PARTIAL, read.user().status());
            assertTrue(read.diagnostics().contains("CORRUPT_AUTH"));
            assertFalse(read.toString().contains("secret-in-file"));
            assertFalse(new ConfigurationError(ConfigurationError.Code.CORRUPT_AUTH,
                    "credential secret-in-file is corrupt").toString().contains("secret-in-file"));
        }
    }

    /** 显式非法、旧版或未来 schema 整层隔离；读取和稳定 ID 普通 patch 都不得迁移或覆盖原文。 */
    @Test
    void explicitInvalidOrUnsupportedSchemaIsolatesEntireUserLayerWithoutMigration() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        for (String schemaVersion : List.of("1", "3", "\"two\"", "2147483648")) {
            String unsupportedSchema = profileConfig("unsupported-schema-model")
                    .replace("schema_version = 2", "schema_version = " + schemaVersion);
            Files.writeString(config, unsupportedSchema);

            try (ConfigurationRuntimeAdapter service = service()) {
                ConfigurationUseCase.ReadResult read = service.read(null);

                assertEquals(ConfigurationUseCase.LayerStatus.CORRUPT, read.user().status());
                assertNull(read.user().document());
                assertEquals(0, node(read.effective()).withArray("providers").size());
                assertTrue(read.issues().stream().anyMatch(issue -> "UNSUPPORTED_SCHEMA_VERSION".equals(issue.reason())
                        && "schema_version".equals(issue.field()) && "user".equals(issue.scope())));
                assertFalse(read.issues().stream().anyMatch(issue -> "TOML_PARSE_FAILED".equals(issue.reason())));
                ObjectNode idPatch = JSON.createObjectNode();
                idPatch.putObject("providers").putObject("provider_schema_guard").put("name", "must-not-write");
                ConfigurationError patchFailure = assertThrows(ConfigurationError.class,
                        () -> service.patch(ConfigurationScope.USER, null, document(idPatch), read.user().version()));
                assertEquals(ConfigurationError.Code.CORRUPT_CONFIG, patchFailure.code());
                assertEquals(unsupportedSchema, Files.readString(config));
            }
        }
    }

    /** 可信项目的显式不兼容 schema 不覆盖全局值，且问题投影不误报为 TOML 语法损坏。 */
    @Test
    void explicitUnsupportedProjectSchemaIsIgnoredAndPreserved() throws Exception {
        Path workspace = Files.createDirectory(temporaryRoot.resolve("unsupported-project-schema"));
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("user-model"));
        Path projectConfig = Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml");
        String unsupportedSchema = "schema_version = 3\nconfig_revision = 1\ndefault_access_mode = \"full_access\"\n";
        Files.writeString(projectConfig, unsupportedSchema);

        try (ConfigurationRuntimeAdapter service = service()) {
            assertTrue(service.synchronizeWorkspaceTrust(workspace, true));

            ConfigurationUseCase.ReadResult read = service.read(workspace);

            assertEquals(ConfigurationUseCase.LayerStatus.CORRUPT, read.project().status());
            assertNull(read.project().document());
            assertEquals("approval_required", node(read.effective()).path("default_access_mode").textValue());
            assertTrue(read.issues().stream().anyMatch(issue -> "UNSUPPORTED_SCHEMA_VERSION".equals(issue.reason())
                    && "schema_version".equals(issue.field()) && "project".equals(issue.scope())));
            assertFalse(read.issues().stream().anyMatch(issue -> "TOML_PARSE_FAILED".equals(issue.reason())));
            assertEquals(unsupportedSchema, Files.readString(projectConfig));
        }
    }

    /**
     * 根层缺少 schema 时，MCP 内同名字段保持为未知字段；根层按当前格式读取且其它 Provider/Model
     * 不会因该错位字段消失。
     */
    @Test
    void misplacedSchemaVersionDoesNotDiscardRootProviders() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String misplacedSchema = profileConfig("misplaced-schema-model")
                .replace("schema_version = 2\n", "")
                + "[metadata]\nschema_version = 2\n";
        Files.writeString(config, misplacedSchema);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);

            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.user().status());
            assertEquals(1, node(read.effective()).withArray("providers").size());
            assertTrue(read.issues().stream().anyMatch(issue -> "metadata".equals(issue.field())
                    && "ignored".equals(issue.impact())));
            assertEquals(misplacedSchema, Files.readString(config));
        }
    }

    /**
     * 回归 Kerminal 表内错位 schema：根缺失元数据只按当前格式读取，MCP 的未知字段不应让 3 个
     * Provider、5 个 Model 或正常默认选择全部消失。
     */
    @Test
    void misplacedMcpSchemaKeepsThreeProvidersAndFiveModelsUsable() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), availabilityRegressionConfig());

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            ObjectNode effective = node(read.effective());

            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.user().status());
            assertEquals(3, effective.withArray("providers").size());
            assertEquals(5, effective.withArray("providers").valueStream()
                    .mapToInt(provider -> provider.path("models").size()).sum());
            assertEquals(1, effective.withArray("mcp_servers").size());
            assertTrue(read.issues().stream().anyMatch(issue -> "mcp_kerminal".equals(issue.entityId())
                    && "schema_version".equals(issue.field()) && "ignored".equals(issue.impact())));
            assertEquals(1, read.issues().size(),
                    "正常的 nullable 子智能体配置不能与 MCP 的未知字段一起制造额外问题");
            assertEquals(3, service.resolveGeneration(null).providers().size());
        }
    }

    /**
     * Provider 路由、单个模型、MCP 启用字段和 Skill 引用分别损坏时，只隔离关联实体；其余模型
     * 仍必须可见，不能重新触发“配置全空、要求重配”的旧恢复分支。
     */
    @Test
    void badCatalogEntriesAreIsolatedFromRemainingProviderAndModel() throws Exception {
        String source = availabilityRegressionConfig()
                .replace("disabled_skills = []", "disabled_skills = [\"user:valid\", \"project:invalid\"]")
                .replace("https://beta.example.test/v1", "not-a-provider-url")
                .replace("model = \"gamma-one\"", "model = \"\"")
                .replace("enabled = true\nschema_version = 2", "enabled = \"yes\"\nschema_version = 2");
        Path config = homeDirectory().resolve("config.toml");
        Files.writeString(config, source);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            ObjectNode effective = node(read.effective());

            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.user().status());
            assertEquals(1, effective.withArray("providers").size());
            assertEquals("provider_alpha", effective.withArray("providers").get(0).path("provider_id").textValue());
            assertEquals(2, effective.withArray("providers").get(0).withArray("models").size());
            assertEquals(List.of("user:valid"), effective.withArray("disabled_skills").valueStream()
                    .map(JsonNode::textValue).toList());
            assertTrue(effective.withArray("mcp_servers").isEmpty());
            assertTrue(read.issues().stream().anyMatch(issue -> "provider_unavailable".equals(issue.impact())));
            assertTrue(read.issues().stream().anyMatch(issue -> "entry_skipped".equals(issue.impact())));
            assertEquals(source, Files.readString(config));
        }
    }

    /** MCP 参数格式错误时保留可修复条目，但须定位坏字段并阻止运行时启用。 */
    @Test
    void malformedMcpParametersAreLocatedAndDisableTheEntry() throws Exception {
        String source = availabilityRegressionConfig()
                .replace("args = []", "args = [\"launch\", 7]")
                .replace("env = {}", "env = \"invalid\"")
                .replace("headers = {}", "headers = { Authorization = 7 }");
        Path config = homeDirectory().resolve("config.toml");
        Files.writeString(config, source);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            ObjectNode effective = node(read.effective());
            JsonNode mcp = effective.withArray("mcp_servers").get(0);

            assertEquals(ConfigurationUseCase.LayerStatus.VALID, read.user().status());
            assertEquals("mcp_kerminal", mcp.path("mcp_id").asText());
            assertFalse(mcp.path("enabled").asBoolean());
            assertEquals(0, mcp.path("args").size());
            assertEquals(0, mcp.path("env").size());
            assertEquals(0, mcp.path("headers").size());
            for (String field : List.of("args", "env", "headers")) {
                assertTrue(read.issues().stream().anyMatch(issue -> "mcp_kerminal".equals(issue.entityId())
                        && field.equals(issue.field()) && "INVALID_FIELD".equals(issue.reason())
                        && "disabled_for_safety".equals(issue.impact())), field);
            }
            assertEquals(source, Files.readString(config));
        }
    }

    /** 只使用语法错误前的安全前缀；显式完整 replace 先备份原文再修复。 */
    @Test
    void syntaxFailureUsesSafePrefixAndExplicitReplaceBacksUpSource() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String damaged = "default_access_mode = \"approval_required\"\nproviders = [\n";
        Files.writeString(config, damaged);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult partial = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.PARTIAL, partial.user().status());
            assertEquals("approval_required", node(partial.effective()).path("default_access_mode").asText());
            assertTrue(node(partial.effective()).withArray("providers").isEmpty());
            assertEquals(damaged, Files.readString(config));

            service.replace(ConfigurationScope.USER, null,
                    document(userDocument("provider_repaired", "model_repaired", "repaired-model")),
                    partial.user().version());
            assertEquals("provider_repaired", node(service.read(null).effective())
                    .path("default_provider_id").asText());
            try (Stream<Path> files = Files.list(homeDirectory())) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("config.toml.backup-")));
            }
        }
    }

    /** 首次语法失败仍保留默认配置及修复入口，读取不创建或覆盖原 TOML。 */
    @Test
    void firstSyntaxFailureUsesDefaultsWithoutCreatingAReplacementFile() throws Exception {
        String damaged = "providers = [\n";
        Path config = homeDirectory().resolve("config.toml");
        Files.writeString(config, damaged);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.PARTIAL, read.user().status());
            assertTrue(node(read.effective()).withArray("providers").isEmpty());
            assertEquals(damaged, Files.readString(config));
            assertFalse(Files.exists(homeDirectory().resolve("config.toml.last-known-good")));
        }
    }

    /**
     * 普通 patch 不得以局部字段覆盖含未知根字段或坏 MCP 条目的损坏原文；用户必须显式提交完整
     * 且严格 v2 的 replace，避免把错误数据悄然清空。
     */
    @Test
    void patchRefusesCorruptTomlWithoutOverwritingOriginalBytes() throws Exception {
        String source = profileConfig("preserve-model")
                .replace("[[providers]]", "future_switch = \"keep\"\n[[providers]]")
                + """
                    
                    [[mcp_servers]]
                    mcp_id = "broken"
                    name = "Broken"
                    transport = "stdio"
                    endpoint = "cmd"
                    auth = { kind = "none" }
                    """;
        Path config = homeDirectory().resolve("config.toml");
        Files.writeString(config, source);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult before = service.read(null);
            ObjectNode patch = JSON.createObjectNode().put("default_access_mode", "full_access");
            assertTrue(before.user().status() == ConfigurationUseCase.LayerStatus.VALID
                    || before.user().status() == ConfigurationUseCase.LayerStatus.PARTIAL);
            ConfigurationError failure = assertThrows(ConfigurationError.class,
                    () -> service.patch(ConfigurationScope.USER, null, document(patch), before.user().version()));

            assertEquals(ConfigurationError.Code.CORRUPT_CONFIG, failure.code());
            assertEquals(source, Files.readString(config));
        }
    }

    /** windowsAuthFileIsCurrentUserOnly 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void windowsAuthFileIsCurrentUserOnly() throws Exception {
        assertTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "Windows auth ACL gate must not be skipped");
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.CredentialResult first = service.setCredential(
                    "cred_acl", "acl-secret", "cfg_missing");
            Path auth = homeDirectory().resolve("auth.json");
            service.setCredential("cred_acl", "acl-secret-rotated", first.version());
            AclFileAttributeView view = Files.getFileAttributeView(auth, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            UserPrincipal owner = Files.getOwner(auth, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(view);
            assertEquals(1, view.getAcl().size());
            AclEntry entry = view.getAcl().get(0);
            assertEquals(owner.getName(), entry.principal().getName());
            assertTrue(entry.flags().isEmpty());
            assertTrue(entry.permissions().containsAll(java.util.EnumSet.allOf(AclEntryPermission.class)));
        }
    }

    /** 把测试 JSON 对象复制成纯 JDK 端口文档，使用例测试不依赖生产 Wire 转换器。 */
    private static ConfigurationUseCase.Document document(ObjectNode object) {
        Map<String, ConfigurationUseCase.Value> properties = new LinkedHashMap<>();
        object.properties().forEach(entry -> properties.put(entry.getKey(), value(entry.getValue())));
        return new ConfigurationUseCase.Document(properties);
    }

    /** 递归复制测试 JSON 值并保留任意精度数值，覆盖配置数组与显式 null。 */
    private static ConfigurationUseCase.Value value(JsonNode node) {
        if (node.isObject()) {
            Map<String, ConfigurationUseCase.Value> properties = new LinkedHashMap<>();
            node.properties().forEach(entry -> properties.put(entry.getKey(), value(entry.getValue())));
            return new ConfigurationUseCase.ObjectValue(properties);
        }
        if (node.isArray()) {
            List<ConfigurationUseCase.Value> values = new ArrayList<>();
            node.forEach(child -> values.add(value(child)));
            return new ConfigurationUseCase.ArrayValue(values);
        }
        if (node.isTextual()) {
            return new ConfigurationUseCase.TextValue(node.textValue());
        }
        if (node.isNumber()) {
            return new ConfigurationUseCase.NumberValue(node.decimalValue());
        }
        if (node.isBoolean()) {
            return new ConfigurationUseCase.BooleanValue(node.booleanValue());
        }
        if (node.isNull()) {
            return ConfigurationUseCase.NullValue.INSTANCE;
        }
        throw new IllegalArgumentException("unsupported test JSON value");
    }

    /** 把端口文档还原为测试断言使用的 Jackson 对象，不复用生产适配器私有实现。 */
    private static ObjectNode node(ConfigurationUseCase.Document document) {
        ObjectNode result = JSON.createObjectNode();
        document.properties().forEach((name, value) -> result.set(name, node(value)));
        return result;
    }

    /** 穷举端口值到测试 JSON 节点的映射，使断言仍能精确检查嵌套配置内容。 */
    private static JsonNode node(ConfigurationUseCase.Value value) {
        return switch (value) {
            case ConfigurationUseCase.ObjectValue object -> {
                ObjectNode result = JSON.createObjectNode();
                object.properties().forEach((name, child) -> result.set(name, node(child)));
                yield result;
            }
            case ConfigurationUseCase.ArrayValue array -> {
                ArrayNode result = JSON.createArrayNode();
                array.values().forEach(child -> result.add(node(child)));
                yield result;
            }
            case ConfigurationUseCase.TextValue text -> JSON.getNodeFactory().textNode(text.value());
            case ConfigurationUseCase.NumberValue number -> number.value().scale() <= 0
                    ? JSON.getNodeFactory().numberNode(number.value().toBigIntegerExact())
                    : JSON.getNodeFactory().numberNode(number.value());
            case ConfigurationUseCase.BooleanValue bool -> JSON.getNodeFactory().booleanNode(bool.value());
            case ConfigurationUseCase.NullValue ignored -> JSON.getNodeFactory().nullNode();
        };
    }

    /** 用每个测试独立的四根目录构造生产模式服务，避免 fixture 读写用户真实配置。 */
    private ConfigurationRuntimeAdapter service() {
        Path home = homeDirectory();
        return new ConfigurationRuntimeAdapter(new SidecarConfiguration(
                home, home.resolve("data"),
                home.resolve("run"), home.resolve("logs")), new ObjectMapper());
    }

    /** 在线程屏障后提交一次相同 CAS，并把成功折叠为 null 以便精确统计唯一赢家。 */
    private static ConfigurationError.Code concurrentPatch(ConfigurationRuntimeAdapter service, String suffix,
                                                            CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        ObjectNode patch = userPatchFromDocument(
                userDocument("provider_" + suffix, "model_" + suffix, suffix + "-model"));
        try {
            service.patch(ConfigurationScope.USER, null, document(patch), "cfg_missing");
            return null;
        } catch (ConfigurationError failure) {
            return failure.code();
        }
    }

    /** 在 JUnit 临时根目录内创建稳定 home，使同一测试的多次服务构造共享权威文件。 */
    private Path homeDirectory() {
        Path home = temporaryRoot.resolve("home");
        try {
            return Files.createDirectories(home);
        } catch (Exception failure) {
            throw new IllegalStateException("test home unavailable", failure);
        }
    }

    /** 生成完整 v2 用户配置，使运行时代际测试只改变上游模型名称。 */
    private static String profileConfig(String model) {
        return """
            schema_version = 2
            config_revision = 1
            default_access_mode = "approval_required"
            default_provider_id = "provider_model"
            default_model_id = "model_model"
            default_reasoning_level = "medium"
            mcp_servers = []
            disabled_skills = []
            [subagents]
            enabled = true
            provider_id = { __ja_null = true }
            model_id = { __ja_null = true }
            reasoning_level = { __ja_null = true }
            [[providers]]
            provider_id = "provider_model"
            name = "Test"
            api = "openai_responses"
            base_url = "http://127.0.0.1"
            credential_id = "cred_model"
            [providers.network_timeouts]
            connect_timeout_ms = 10000
            request_timeout_ms = 120000
            [providers.agent_defaults]
            [providers.agent_defaults.context]
            auto_compact = true
            [[providers.models]]
            model_id = "model_model"
            name = "Test Model"
            model = "\
            """ + model + """
                    "
                    reasoning_level_map = { medium = "medium" }
                    default_reasoning_level = "medium"
                    [providers.models.capabilities]
                    context_window_tokens = 128000
                    max_output_tokens = 8192
                    """;
    }

    /**
     * 以真实 TOML table 归属固定本轮启动故障：最后一个 schema_version 故意位于 MCP 表内，
     * 不是根元数据，帮助防止未来把局部未知字段重新升级成整份配置损坏。
     */
    private static String availabilityRegressionConfig() {
        return profileConfig("alpha-one")
                .replace("provider_model", "provider_alpha")
                .replace("model_model", "model_alpha_one")
                .replace("cred_model", "cred_alpha")
                .replace("mcp_servers = []\n", "")
                + """
                    [[providers.models]]
                    model_id = "model_alpha_two"
                    name = "Alpha Two"
                    model = "alpha-two"
                    reasoning_level_map = { medium = "medium" }
                    default_reasoning_level = "medium"
                    [providers.models.capabilities]
                    context_window_tokens = 128000
                    max_output_tokens = 8192
                    [[providers]]
                    provider_id = "provider_beta"
                    name = "Beta"
                    api = "openai_chat_completions"
                    base_url = "https://beta.example.test/v1"
                    credential_id = "cred_beta"
                    [providers.network_timeouts]
                    connect_timeout_ms = 10000
                    request_timeout_ms = 120000
                    [providers.agent_defaults]
                    [providers.agent_defaults.context]
                    auto_compact = true
                    [[providers.models]]
                    model_id = "model_beta_one"
                    name = "Beta One"
                    model = "beta-one"
                    reasoning_level_map = { medium = "medium" }
                    default_reasoning_level = "medium"
                    [providers.models.capabilities]
                    context_window_tokens = 128000
                    max_output_tokens = 8192
                    [[providers.models]]
                    model_id = "model_beta_two"
                    name = "Beta Two"
                    model = "beta-two"
                    reasoning_level_map = { medium = "medium" }
                    default_reasoning_level = "medium"
                    [providers.models.capabilities]
                    context_window_tokens = 128000
                    max_output_tokens = 8192
                    [[providers]]
                    provider_id = "provider_gamma"
                    name = "Gamma"
                    api = "anthropic_messages"
                    base_url = "https://gamma.example.test"
                    credential_id = "cred_gamma"
                    [providers.network_timeouts]
                    connect_timeout_ms = 10000
                    request_timeout_ms = 120000
                    [providers.agent_defaults]
                    [providers.agent_defaults.context]
                    auto_compact = true
                    [[providers.models]]
                    model_id = "model_gamma_one"
                    name = "Gamma One"
                    model = "gamma-one"
                    reasoning_level_map = { medium = "medium" }
                    default_reasoning_level = "medium"
                    [providers.models.capabilities]
                    context_window_tokens = 128000
                    max_output_tokens = 8192
                    [[mcp_servers]]
                    mcp_id = "mcp_kerminal"
                    name = "Kerminal"
                    transport = "stdio"
                    endpoint = "kerminal"
                    args = []
                    env = {}
                    headers = {}
                    auth = { kind = "none" }
                    enabled = true
                    schema_version = 2
                    """;
    }

    /** 项目层只持有其自身启用项和全局收紧项，禁止复制 Provider/MCP 或模型配置。 */
    private static String projectSkillConfig() {
        return "schema_version = 2\nconfig_revision = 1\ndisabled_skills = [\"project:workspace-fixture\"]\n";
    }

    /** catalogConfig 固定 Provider/Model 与 Skill/MCP 引用闭包。 */
    private static String catalogConfig(String endpoint) {
        return """
            schema_version = 2
            config_revision = 1
            default_access_mode = "full_access"
            default_provider_id = "provider_catalog"
            default_model_id = "model_catalog"
            default_reasoning_level = "medium"
            disabled_skills = ["user:catalog"]
            [subagents]
            enabled = true
            provider_id = { __ja_null = true }
            model_id = { __ja_null = true }
            reasoning_level = { __ja_null = true }
            [[providers]]
            provider_id = "provider_catalog"
            name = "Catalog"
            api = "openai_responses"
            base_url = "http://127.0.0.1"
            credential_id = "cred_model"
            [providers.network_timeouts]
            connect_timeout_ms = 10000
            request_timeout_ms = 120000
            [providers.agent_defaults]
            [providers.agent_defaults.context]
            auto_compact = true
            [[providers.models]]
            model_id = "model_catalog"
            name = "Catalog Model"
            model = "fixture"
            reasoning_level_map = { medium = "medium" }
            default_reasoning_level = "medium"
            [providers.models.capabilities]
            context_window_tokens = 128000
            max_output_tokens = 8192
            [[mcp_servers]]
            mcp_id = "mcp_catalog"
            name = "MCP"
            transport = "stdio"
            endpoint = "\
            """ + endpoint + """
                    "
                    args = []
                    env = {}
                    headers = {}
                    auth = { kind = "none" }
                    enabled = true
                    """;
    }

    /** 生成仅缺失根级 MCP 启用事实的配置，避免其他 schema 错误干扰断言。 */
    private static String catalogConfigWithMissingMcpEnabled() {
        return catalogConfig("http://127.0.0.1:1")
                .replace("auth = { kind = \"none\" }\nenabled = true\n", "auth = { kind = \"none\" }\n");
    }

    /** 构造可直接 merge-patch 到缺失配置的完整 v2 用户文档。 */
    private static ObjectNode userDocument(String providerId, String modelId, String upstreamModel) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 2).put("config_revision", 1)
                .put("default_access_mode", "approval_required")
                .put("default_provider_id", providerId).put("default_model_id", modelId)
                .put("default_reasoning_level", "medium");
        root.putObject("subagents").put("enabled", true).put("provider_id", providerId).put("model_id", modelId)
                .put("reasoning_level", "medium");
        root.putArray("mcp_servers");
        root.putArray("disabled_skills");
        ObjectNode provider = root.putArray("providers").addObject();
        provider.put("provider_id", providerId).put("name", "Fixture")
                .put("api", "openai_responses")
                .put("base_url", "http://127.0.0.1:9/v1").put("credential_id", "cred_model");
        provider.putObject("network_timeouts").put("connect_timeout_ms", 5_000)
                .put("request_timeout_ms", 30_000);
        ObjectNode defaults = provider.putObject("agent_defaults");
        defaults.putObject("context").put("auto_compact", true);
        ObjectNode model = provider.putArray("models").addObject();
        model.put("model_id", modelId).put("name", "Fixture Model").put("model", upstreamModel)
                .put("default_reasoning_level", "medium");
        model.putObject("reasoning_level_map").put("medium", "medium");
        model.putObject("capabilities").put("context_window_tokens", 128_000)
                .put("max_output_tokens", 8_192);
        return root;
    }

    /** 把严格完整文档 fixture 转成用户层稀疏稳定 ID 操作，避免测试沿用数组替换协议。 */
    private static ObjectNode userPatchFromDocument(ObjectNode completeDocument) {
        ObjectNode patch = completeDocument.deepCopy();
        patch.set("providers", providerOperations(completeDocument));
        return patch;
    }

    /** 将完整 Provider/Model 数组投影为 map key 赋身份的 provider_id/model_id 操作。 */
    private static ObjectNode providerOperations(ObjectNode completeDocument) {
        ObjectNode operations = JSON.createObjectNode();
        JsonNode providers = completeDocument.path("providers");
        if (!(providers instanceof ArrayNode providerArray)) {
            return operations;
        }
        for (JsonNode providerValue : providerArray) {
            ObjectNode provider = ((ObjectNode) providerValue).deepCopy();
            String providerId = provider.path("provider_id").asText();
            provider.remove("provider_id");
            JsonNode modelsValue = provider.remove("models");
            ObjectNode modelOperations = JSON.createObjectNode();
            if (modelsValue instanceof ArrayNode models) {
                for (JsonNode modelValue : models) {
                    ObjectNode model = ((ObjectNode) modelValue).deepCopy();
                    String modelId = model.path("model_id").asText();
                    model.remove("model_id");
                    modelOperations.set(modelId, model);
                }
            }
            provider.set("models", modelOperations);
            operations.set(providerId, provider);
        }
        return operations;
    }

    /** 返回测试文档的唯一模型节点，避免断言再次复制 Provider/Model 嵌套路径。 */
    private static JsonNode selectedModel(JsonNode root) {
        return root.path("providers").path(0).path("models").path(0);
    }

    /** 从租约公开投影解析默认上游模型，防止测试持有绕过租约生命周期的适配器内部对象。 */
    private static String selectedModel(ConfigurationGenerationSnapshot snapshot) {
        return snapshot.requireModel(snapshot.defaultProviderId().orElseThrow(),
                snapshot.defaultModelId().orElseThrow()).model();
    }
}

