// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationPolicy;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationStore;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigGeneration;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigurationRuntimeState;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigGenerationTestAccess.isClosed;

/** 验证配置运行时适配器的严格文档、CAS、凭据脱敏、Watcher 与 generation 租约语义。 */
final class ConfigurationRuntimeAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryRoot;

    /** missingConfigurationIsDegradedButServiceReady 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    void missingConfigurationIsDegradedButServiceReady() {
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            assertEquals(ConfigurationUseCase.LayerStatus.MISSING, read.user().status());
            assertNotNull(read.effective());
            ConfigGeneration generation = service.resolveGeneration(null);
            assertFalse(generation.ready());
            assertTrue(generation.diagnostics().stream()
                    .anyMatch(diagnostic -> "MISSING_PROVIDER".equals(diagnostic.code())));
        }
    }

    /** 缺失必填 nullable 字段的 v1 文档必须失败关闭，读取层不得替旧形状补字段。 */
    @Test
    void rejectsV1DocumentWithMissingNullableFields() throws Exception {
        String omittedNulls = profileConfig("nullable-model")
                .replace("default_reasoning_level = \"medium\"\n", "")
                .replace("reasoning_level_map = { medium = \"medium\" }", "reasoning_level_map = {}");
        Files.writeString(homeDirectory().resolve("config.toml"), omittedNulls);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);
            ObjectNode effective = node(read.effective());

            assertEquals(ConfigurationUseCase.LayerStatus.CORRUPT, read.user().status());
            assertNull(read.user().document());
            assertTrue(effective.get("default_reasoning_level").isNull());
            assertTrue(effective.withArray("providers").isEmpty());
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

    /** 验证配置与凭据使用统一不透明版本，且过期 CAS 不会覆盖已提交内容。 */
    @Test
    void configAndCredentialCasAreOpaqueAndRedacted() {
        ObjectMapper mapper = new ObjectMapper();
        try (ConfigurationRuntimeAdapter service = service()) {
            ObjectNode firstPatch = userDocument("provider_model", "model_model", "ja-e2e-fake");
            ((ObjectNode) firstPatch.withArray("providers").get(0))
                    .put("credential_id", "cred_deep.seek");
            ConfigurationError missingExpected = assertThrows(ConfigurationError.class, () -> service.patch(
                    ConfigurationScope.USER, null, document(firstPatch), null));
            assertEquals(ConfigurationError.Code.INVALID_ARGUMENT, missingExpected.code());
            ConfigurationUseCase.MutationResult first = service.patch(
                    ConfigurationScope.USER, null, document(firstPatch), "cfg_missing");
            assertTrue(first.version().startsWith("cfg_"));
            ConfigurationError stale = assertThrows(ConfigurationError.class, () -> service.patch(
                    ConfigurationScope.USER, null,
                    document(userDocument("provider_other", "model_other", "other-model")),
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

    /** v1 只接受 Provider/Model 能力与自动压缩开关，并拒绝闭集外压缩阈值字段。 */
    @Test
    void mergePatchAcceptsV1CapabilitiesAndRejectsUnknownContextFields() {
        ObjectNode patch = userDocument("provider_budget", "model_budget", "budget-model");
        ObjectNode invalidDocument = patch.deepCopy();
        ((ObjectNode) invalidDocument.withArray("providers").get(0)
                .path("agent_defaults").path("context")).put("window_tokens", 128_000);
        ConfigurationError literal = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(invalidDocument));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, literal.code());

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.MutationResult result = service.patch(
                    ConfigurationScope.USER, null, document(patch), "cfg_missing");
            assertTrue(result.version().startsWith("cfg_"));
            assertEquals(128_000, node(service.read(null).effective()).path("providers").get(0)
                    .path("models").get(0).path("capabilities")
                    .path("context_window_tokens").intValue());
            assertEquals("openai_responses", node(service.read(null).effective())
                    .path("providers").get(0).path("api").textValue());
        }
    }

    /** projectConfigurationRequiresJavaTrust 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    void projectConfigurationRequiresJavaTrust() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("user-model"));
        Path projectJa = Files.createDirectories(workspace.resolve(".ja"));
        Files.writeString(projectJa.resolve("config.toml"), projectOverlayConfig(4_096));

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult untrusted = service.read(workspace);
            assertEquals(ConfigurationUseCase.LayerStatus.UNTRUSTED, untrusted.project().status());
            assertEquals(8_192, selectedModel(node(untrusted.effective()))
                    .path("capabilities").path("max_output_tokens").intValue());
            try (ConfigGeneration.Lease lease = service.acquire(workspace)) {
                assertFalse(lease.snapshot().trusted());
            }

            assertTrue(service.synchronizeWorkspaceTrust(workspace, true));
            ConfigurationUseCase.ReadResult trusted = service.read(workspace);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, trusted.project().status());
            assertEquals(4_096, selectedModel(node(trusted.effective()))
                    .path("capabilities").path("max_output_tokens").intValue());
            try (ConfigGeneration.Lease lease = service.acquire(workspace)) {
                assertTrue(lease.snapshot().trusted());
            }

            assertTrue(service.synchronizeWorkspaceTrust(workspace, false));
            assertEquals(ConfigurationUseCase.LayerStatus.UNTRUSTED,
                    service.read(workspace).project().status());
            assertEquals(8_192, selectedModel(node(service.read(workspace).effective()))
                    .path("capabilities").path("max_output_tokens").intValue());
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

    /** generationCacheDoesNotRetainUnleasedRotations 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    void generationCacheDoesNotRetainUnleasedRotations() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), profileConfig("cache-model-0"));
        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigGeneration first = service.resolveGeneration(null);
            assertSame(first, service.resolveGeneration(null));
            assertEquals(1, service.trackedGenerationCount());
            try (ConfigGeneration.Lease lease = first.acquire()) {
                for (int index = 1; index <= 16; index++) {
                    Files.writeString(homeDirectory().resolve("config.toml"),
                            profileConfig("cache-model-" + index));
                    ConfigGeneration current = service.resolveGeneration(null);
                    assertTrue(service.trackedGenerationCount() <= 2);
                    assertEquals("cache-model-" + index,
                            selectedModel(current.effectiveConfig()).path("model").textValue());
                }
                assertTrue(isClosed(first));
                assertEquals("cache-model-0",
                        selectedModel(lease.generation().effectiveConfig()).path("model").textValue());
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

    /** 验证外部文件变更仅发布脱敏元数据，并延迟释放仍被租约持有的旧 generation。 */
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
                assertEquals("watch-model-1",
                        selectedModel(lease.generation().effectiveConfig()).path("model").textValue());
                ConfigGeneration second = service.resolveGeneration(null);
                assertNotSame(first, second);
                assertEquals("watch-model-2",
                        selectedModel(second.effectiveConfig()).path("model").textValue());
            }
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
            assertThrows(IllegalStateException.class, lease::generation);
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
            assertThrows(IllegalStateException.class, second::generation);
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
            assertThrows(IllegalStateException.class, keeper::generation);
        }
    }

    /** 验证原子 acquire 将旧配置与凭据固定在同一代际，轮换后新租约不污染旧视图。 */
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
                    assertNotSame(oldLease.generation(), newLease.generation());
                    assertEquals("atomic-old-model", selectedModel(oldLease.generation().effectiveConfig())
                            .path("model").textValue());
                    assertEquals("atomic-new-model", selectedModel(newLease.generation().effectiveConfig())
                            .path("model").textValue());
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

    /** generationCatalogIsFrozenAndMissingReferencesBlockAdmission 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。 */
    @Test
    void generationCatalogIsFrozenAndMissingEnabledStateBlocksAdmission() throws Exception {
        Files.writeString(homeDirectory().resolve("config.toml"), catalogConfig("http://127.0.0.1:1"));
        try (ConfigurationRuntimeAdapter service = service()) {
            service.setCredential("cred_model", "catalog-secret", "cfg_missing");
            ConfigGeneration first = service.resolveGeneration(null);
            assertTrue(first.ready());
            assertEquals(1, first.skills().size());
            assertEquals(1, first.mcpServers().size());
            String digest = first.catalogDigest();
            try (ConfigGeneration.Lease lease = first.acquire()) {
                Files.writeString(homeDirectory().resolve("config.toml"), catalogConfig("http://127.0.0.1:2"));
                ConfigGeneration second = service.resolveGeneration(null);
                assertNotEquals(digest, second.catalogDigest());
                assertEquals("http://127.0.0.1:1", lease.generation().mcpServers().get(0).path("endpoint").textValue());
            }
            Files.writeString(homeDirectory().resolve("config.toml"), catalogConfigWithMissingMcpEnabled());
            ConfigGeneration missing = service.resolveGeneration(null);
            assertFalse(missing.ready());
            assertTrue(missing.diagnostics().stream()
                    .anyMatch(diagnostic -> "CORRUPT_CONFIG".equals(diagnostic.code())));
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
            assertEquals(ConfigurationUseCase.LayerStatus.CORRUPT, read.user().status());
            assertTrue(read.diagnostics().contains("CORRUPT_AUTH"));
            assertFalse(read.toString().contains("secret-in-file"));
            assertFalse(new ConfigurationError(ConfigurationError.Code.CORRUPT_AUTH,
                    "credential secret-in-file is corrupt").toString().contains("secret-in-file"));
        }
    }

    /** 读取边界只接受当前 v1；其它版本不转换，也不改写调用方的原始字节。 */
    @Test
    void unsupportedConfigurationSchemaIsRejectedWithoutMigration() throws Exception {
        Path config = homeDirectory().resolve("config.toml");
        String unsupportedSchema = profileConfig("unsupported-schema-model")
                .replace("schema_version = 1", "schema_version = 2");
        Files.writeString(config, unsupportedSchema);

        try (ConfigurationRuntimeAdapter service = service()) {
            ConfigurationUseCase.ReadResult read = service.read(null);

            assertEquals(ConfigurationUseCase.LayerStatus.CORRUPT, read.user().status());
            assertTrue(read.diagnostics().contains("CORRUPT_CONFIG"));
            assertEquals(unsupportedSchema, Files.readString(config));
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
        if (node.isTextual()) return new ConfigurationUseCase.TextValue(node.textValue());
        if (node.isNumber()) return new ConfigurationUseCase.NumberValue(node.decimalValue());
        if (node.isBoolean()) return new ConfigurationUseCase.BooleanValue(node.booleanValue());
        if (node.isNull()) return ConfigurationUseCase.NullValue.INSTANCE;
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
        ObjectNode patch = userDocument("provider_" + suffix, "model_" + suffix, suffix + "-model");
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

    /** 生成完整 v1 用户配置，使运行时代际测试只改变上游模型名称。 */
    private static String profileConfig(String model) {
        return "schema_version = 1\n"
                + "config_revision = 1\n"
                + "default_access_mode = \"approval_required\"\n"
                + "default_provider_id = \"provider_model\"\n"
                + "default_model_id = \"model_model\"\n"
                + "default_reasoning_level = \"medium\"\n"
                + "mcp_servers = []\nskills = []\n"
                + "[[providers]]\n"
                + "provider_id = \"provider_model\"\n"
                + "name = \"Test\"\n"
                + "api = \"openai_responses\"\n"
                + "base_url = \"http://127.0.0.1\"\n"
                + "credential_id = \"cred_model\"\n"
                + "[providers.network_timeouts]\nconnect_timeout_ms = 10000\nrequest_timeout_ms = 120000\n"
                + "[providers.agent_defaults]\n"
                + "[providers.agent_defaults.context]\nauto_compact = true\n"
                + "[providers.agent_defaults.turn_limits]\nmax_model_rounds = 32\nmax_tool_calls = 128\nwall_timeout_ms = 3600000\n"
                + "[[providers.models]]\nmodel_id = \"model_model\"\nname = \"Test Model\"\n"
                + "model = \"" + model + "\"\nreasoning_level_map = { medium = \"medium\" }\n"
                + "default_reasoning_level = \"medium\"\n"
                + "[providers.models.capabilities]\ncontext_window_tokens = 128000\n"
                + "max_output_tokens = 8192\n";
    }

    /** 生成只收紧模型输出上限的项目 overlay，项目层不复制 Provider 路由。 */
    private static String projectOverlayConfig(int maxOutputTokens) {
        return "schema_version = 1\nconfig_revision = 1\ndefault_access_mode = \"approval_required\"\n"
                + "default_provider_id = \"provider_model\"\ndefault_model_id = \"model_model\"\n"
                + "default_reasoning_level = \"medium\"\nmcp_servers = []\nskills = []\n"
                + "[[providers]]\nprovider_id = \"provider_model\"\n"
                + "[[providers.models]]\nmodel_id = \"model_model\"\nreasoning_level_map = { medium = \"medium\" }\n"
                + "default_reasoning_level = \"medium\"\n"
                + "[providers.models.capabilities]\ncontext_window_tokens = 128000\n"
                + "max_output_tokens = " + maxOutputTokens + "\n";
    }

    /** catalogConfig 固定 Provider/Model 与 Skill/MCP 引用闭包。 */
    private static String catalogConfig(String endpoint) {
        return "schema_version = 1\nconfig_revision = 1\ndefault_access_mode = \"full_access\"\n"
                + "default_provider_id = \"provider_catalog\"\ndefault_model_id = \"model_catalog\"\n"
                + "default_reasoning_level = \"medium\"\n"
                + "[[providers]]\nprovider_id = \"provider_catalog\"\nname = \"Catalog\"\napi = \"openai_responses\"\nbase_url = \"http://127.0.0.1\"\ncredential_id = \"cred_model\"\n"
                + "[providers.network_timeouts]\nconnect_timeout_ms = 10000\nrequest_timeout_ms = 120000\n"
                + "[providers.agent_defaults]\n"
                + "[providers.agent_defaults.context]\nauto_compact = true\n"
                + "[providers.agent_defaults.turn_limits]\nmax_model_rounds = 32\nmax_tool_calls = 128\nwall_timeout_ms = 3600000\n"
                + "[[providers.models]]\nmodel_id = \"model_catalog\"\nname = \"Catalog Model\"\nmodel = \"fixture\"\nreasoning_level_map = { medium = \"medium\" }\ndefault_reasoning_level = \"medium\"\n"
                + "[providers.models.capabilities]\ncontext_window_tokens = 128000\nmax_output_tokens = 8192\n"
                + "[[mcp_servers]]\nmcp_id = \"mcp_catalog\"\nname = \"MCP\"\ntransport = \"stdio\"\n"
                + "endpoint = \"" + endpoint + "\"\nargs = []\nenv = {}\nheaders = {}\n"
                + "auth = { kind = \"none\" }\nenabled = true\n"
                + "[[skills]]\nskill_id = \"skill_catalog\"\nname = \"Skill\"\nscope = \"user\"\nenabled = true\ndescription = \"Catalog\"\n";
    }

    /** 生成仅缺失根级 MCP 启用事实的配置，避免其他 schema 错误干扰断言。 */
    private static String catalogConfigWithMissingMcpEnabled() {
        return catalogConfig("http://127.0.0.1:1")
                .replace("enabled = true\n[[skills]]", "[[skills]]");
    }

    /** 构造可直接 merge-patch 到缺失配置的完整 v1 用户文档。 */
    private static ObjectNode userDocument(String providerId, String modelId, String upstreamModel) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1).put("config_revision", 1)
                .put("default_access_mode", "approval_required")
                .put("default_provider_id", providerId).put("default_model_id", modelId)
                .put("default_reasoning_level", "medium");
        root.putArray("mcp_servers");
        root.putArray("skills");
        ObjectNode provider = root.putArray("providers").addObject();
        provider.put("provider_id", providerId).put("name", "Fixture")
                .put("api", "openai_responses")
                .put("base_url", "http://127.0.0.1:9/v1").put("credential_id", "cred_model");
        provider.putObject("network_timeouts").put("connect_timeout_ms", 5_000)
                .put("request_timeout_ms", 30_000);
        ObjectNode defaults = provider.putObject("agent_defaults");
        defaults.putObject("context").put("auto_compact", true);
        defaults.putObject("turn_limits").put("max_model_rounds", 32)
                .put("max_tool_calls", 128).put("wall_timeout_ms", 30_000);
        ObjectNode model = provider.putArray("models").addObject();
        model.put("model_id", modelId).put("name", "Fixture Model").put("model", upstreamModel)
                .put("default_reasoning_level", "medium");
        model.putObject("reasoning_level_map").put("medium", "medium");
        model.putObject("capabilities").put("context_window_tokens", 128_000)
                .put("max_output_tokens", 8_192);
        return root;
    }

    /** 返回测试文档的唯一模型节点，避免断言再次复制 Provider/Model 嵌套路径。 */
    private static JsonNode selectedModel(JsonNode root) {
        return root.path("providers").path(0).path("models").path(0);
    }
}

