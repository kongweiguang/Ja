// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.out.ConfigurationRuntimePort;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationDocumentRuntime;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationWatcher;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigGeneration;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigurationGenerationRuntime;
import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigurationRuntimeState;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Java 配置运行时适配器；组合文件、凭据、Watcher 与代际实现并发布纯 JDK 出站端口。
 *
 * <p>Jackson 只在此出站适配器内部用于 TOML/JSON 树转换，transport 与 application 只能观察
 * {@link ConfigurationRuntimePort} 的不可变投影。</p>
 */
public final class ConfigurationRuntimeAdapter implements AutoCloseable, ConfigurationRuntimePort {
    private final Path homeDirectory;
    private final ObjectMapper mapper;
    private final ConfigurationDocumentRuntime documents;
    private final ConfigurationGenerationRuntime generationRuntime;
    private final CopyOnWriteArrayList<Consumer<ConfigurationRuntimeState.ConfigChanged>> changeListeners =
            new CopyOnWriteArrayList<>();
    private final ConfigurationWatcher watcher;
    private boolean closed;

    /** 按 sidecar 四根目录组装文档、凭据、Watcher 和 generation 所有者，构造失败不留后台监听。 */
    public ConfigurationRuntimeAdapter(SidecarConfiguration configuration, ObjectMapper mapper) {
        Objects.requireNonNull(configuration, "configuration");
        this.homeDirectory = requireHome(configuration.homeDirectory());
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.documents = new ConfigurationDocumentRuntime(homeDirectory, this.mapper);
        this.generationRuntime = new ConfigurationGenerationRuntime(this.mapper, documents::loadAuth,
                documents::readInternal);
        this.watcher = new ConfigurationWatcher(homeDirectory, documents.userConfigPath(),
                documents.trustPath(), documents::loadTrustRegistry, this::invalidateAllGenerations,
                this::invalidateGeneration, this::publishChanges);
        startWatching();
    }

    /** 为只提供 home 的嵌入调用构造完整配置，其他根目录由同一基线规则派生。 */
    public ConfigurationRuntimeAdapter(Path homeDirectory) {
        this(homeOnlyConfiguration(homeDirectory), new ObjectMapper());
    }

    /** 允许测试或 bootstrap 注入统一 ObjectMapper，同时保持 home-only 路径派生规则。 */
    public ConfigurationRuntimeAdapter(Path homeDirectory, ObjectMapper mapper) {
        this(homeOnlyConfiguration(homeDirectory), mapper);
    }

    /** 注册脱敏变更监听并返回可幂等注销的窄生命周期句柄。 */
    AutoCloseable addChangeListener(Consumer<ConfigurationRuntimeState.ConfigChanged> listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) throw error(ConfigurationError.Code.IO_FAILURE, "configuration service is closed");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /** 启动唯一 Watcher；重复调用由 Watcher 自身幂等处理，不创建第二个监听线程。 */
    private synchronized void startWatching() {
        watcher.start();
    }

    /**
     * 读取并转换为纯 JDK 脱敏投影；规范路径只参与内部文件解析，不进入端口结果。
     */
    @Override
    public synchronized ConfigurationData.ReadResult read(Path cwd) {
        ensureOpen();
        return toPortReadResult(documents.read(documents.canonicalizeCwd(cwd)));
    }

    /** 按 RFC 7396 应用对象 Merge Patch，并在同一监视器内完成 CAS 与原子发布。 */
    @Override
    public synchronized ConfigurationData.MutationResult patch(
            ConfigurationScope scope, Path cwd, ConfigurationData.Document patch,
            String expectedVersion) {
        ensureOpen();
        return toPortMutation(documents.patch(scope, cwd, toObjectNode(patch), expectedVersion));
    }

    /** 用完整严格文档替换目标层，显式支持修复语义损坏的配置。 */
    @Override
    public synchronized ConfigurationData.MutationResult replace(
            ConfigurationScope scope, Path cwd, ConfigurationData.Document document,
            String expectedVersion) {
        ensureOpen();
        return toPortMutation(documents.replace(scope, cwd, toObjectNode(document), expectedVersion));
    }

    /** 把目标层重置为当前严格空文档，不复用 replace 的 null 哨兵语义。 */
    @Override
    public synchronized ConfigurationData.MutationResult reset(
            ConfigurationScope scope, Path cwd, String expectedVersion) {
        ensureOpen();
        return toPortMutation(documents.reset(scope, cwd, expectedVersion));
    }

    /** 把唯一允许携带 Secret 的输入交给凭据文件所有者，结果只保留 configured 状态。 */
    @Override
    public synchronized ConfigurationData.CredentialResult setCredential(
            String credentialId, String secret, String expectedVersion) {
        ensureOpen();
        return toPortCredential(credentialId,
                documents.credentialSet(credentialId, secret, expectedVersion));
    }

    /** 删除凭据后重新读取脱敏状态，使幂等删除也返回当前权威 CAS 版本。 */
    @Override
    public synchronized ConfigurationData.CredentialResult deleteCredential(
            String credentialId, String expectedVersion) {
        ensureOpen();
        return toPortCredential(credentialId,
                documents.credentialDelete(credentialId, expectedVersion));
    }

    /**
     * 从通用配置读取结果派生健康状态；只返回稳定诊断代码，损坏文件仍保留 RPC 修复入口。
     */
    @Override
    public synchronized ConfigurationData.HealthResult health() {
        ensureOpen();
        ConfigurationRuntimeState.ReadResult result = documents.read(null);
        boolean degraded = result.diagnostics().stream().anyMatch(ConfigGeneration.Diagnostic::blocking);
        List<String> diagnostics = result.diagnostics().stream()
                .limit(16).map(ConfigGeneration.Diagnostic::code).toList();
        return new ConfigurationData.HealthResult(
                degraded ? ConfigurationData.HealthStatus.DEGRADED
                        : ConfigurationData.HealthStatus.HEALTHY,
                diagnostics);
    }

    /**
     * 先固定工作区真实路径，再按目标信任状态持久化并同步 Watcher；同值调用保持幂等。
     */
    @Override
    public synchronized boolean synchronizeWorkspaceTrust(Path cwd, boolean trusted) {
        ensureOpen();
        Path canonical = documents.canonicalizeCwd(cwd);
        boolean changed = trusted
                ? documents.trustWorkspace(canonical)
                : documents.untrustWorkspace(canonical);
        if (changed) {
            if (trusted) watcher.registerWorkspaceWatches(canonical);
            else watcher.unregisterWorkspaceWatches(canonical);
            invalidateGeneration(canonical);
        }
        return changed;
    }

    /** 解析当前配置代际但不增加租约引用，仅供同包缓存与生命周期测试检查。 */
    synchronized ConfigGeneration resolveGeneration(Path cwd) {
        ensureOpen();
        return generationRuntime.resolve(documents.canonicalizeCwd(cwd));
    }

    /** 原子解析并持有当前代际，调用方必须关闭 Lease 才能释放关联 Secret 缓冲区。 */
    synchronized ConfigGeneration.Lease acquireGeneration(Path cwd) {
        ensureOpen();
        return generationRuntime.acquire(documents.canonicalizeCwd(cwd));
    }

    /** 通过稳定出站端口获取租约；应用层负责投影为入站生命周期。 */
    @Override
    public ConfigGeneration.Lease acquire(Path workspaceRoot) {
        return acquireGeneration(workspaceRoot);
    }

    /** 按代际、监听者、Watcher 的所有权顺序幂等关闭，禁止关闭后重新读取配置。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generationRuntime.close();
        changeListeners.clear();
        watcher.close();
    }

    /** 把内部 Jackson 读取结果一次性冻结为端口投影，避免 transport 再理解配置实现 DTO。 */
    private ConfigurationData.ReadResult toPortReadResult(ConfigurationRuntimeState.ReadResult result) {
        Map<String, ConfigurationData.CredentialStatus> credentials = new LinkedHashMap<>();
        result.credentials().forEach((credentialId, status) -> credentials.put(
                credentialId, new ConfigurationData.CredentialStatus(status.configured())));
        List<String> diagnostics = result.diagnostics().stream()
                .map(ConfigGeneration.Diagnostic::code).toList();
        return new ConfigurationData.ReadResult(result.trusted(), toPortLayer(result.user()),
                toPortLayer(result.project()), toDocument(result.effective()), credentials,
                result.credentialVersion(), diagnostics);
    }

    /** 转换单个配置层；只有成功解析的文档才创建端口 Document。 */
    private ConfigurationData.Layer toPortLayer(ConfigurationRuntimeState.LayerView layer) {
        JsonNode document = layer.document();
        return new ConfigurationData.Layer(layer.scope(), layer.present(), layer.trusted(),
                layer.version(), layer.status(),
                document == null ? null : toDocument(document));
    }

    /** 把内部原子写入结果压缩为作用域和新版本，重读快照不跨越用例边界。 */
    private static ConfigurationData.MutationResult toPortMutation(ConfigurationRuntimeState.WriteResult result) {
        return new ConfigurationData.MutationResult(result.scope(), result.version());
    }

    /** 把凭据存储结果转换为指定身份的脱敏状态，缺失身份明确表示 configured=false。 */
    private static ConfigurationData.CredentialResult toPortCredential(
            String credentialId, ConfigurationRuntimeState.CredentialResult result) {
        boolean configured = result.statuses().getOrDefault(credentialId,
                new ConfigurationRuntimeState.CredentialStatus(false)).configured();
        return new ConfigurationData.CredentialResult(credentialId, configured, result.version());
    }

    /** 将 Jackson 根对象复制为纯 JDK 配置文档，拒绝内部非对象根意外穿透。 */
    private static ConfigurationData.Document toDocument(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration document is invalid");
        }
        Map<String, ConfigurationData.Value> properties = new LinkedHashMap<>();
        object.properties().forEach(entry -> properties.put(entry.getKey(), toValue(entry.getValue())));
        return new ConfigurationData.Document(properties);
    }

    /** 递归复制一个 JSON 值并保留数组顺序与任意精度数值，不携带 Jackson 节点引用。 */
    private static ConfigurationData.Value toValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, ConfigurationData.Value> properties = new LinkedHashMap<>();
            node.properties().forEach(entry -> properties.put(entry.getKey(), toValue(entry.getValue())));
            return new ConfigurationData.ObjectValue(properties);
        }
        if (node.isArray()) {
            List<ConfigurationData.Value> values = new ArrayList<>();
            node.forEach(value -> values.add(toValue(value)));
            return new ConfigurationData.ArrayValue(values);
        }
        if (node.isTextual()) return new ConfigurationData.TextValue(node.textValue());
        if (node.isNumber()) return new ConfigurationData.NumberValue(node.decimalValue());
        if (node.isBoolean()) return new ConfigurationData.BooleanValue(node.booleanValue());
        if (node.isNull()) return ConfigurationData.NullValue.INSTANCE;
        throw error(ConfigurationError.Code.INVALID_DOCUMENT, "configuration value is invalid");
    }

    /** 把端口文档复制回适配器私有 Jackson 树，作为 TOML 校验与原子写入输入。 */
    private ObjectNode toObjectNode(ConfigurationData.Document document) {
        Objects.requireNonNull(document, "document");
        ObjectNode result = mapper.createObjectNode();
        document.properties().forEach((name, value) -> result.set(name, toJsonNode(value)));
        return result;
    }

    /** 穷举转换纯 JDK 配置值；sealed 层级确保新增值类型时由编译器要求更新。 */
    private JsonNode toJsonNode(ConfigurationData.Value value) {
        return switch (value) {
            case ConfigurationData.ObjectValue object -> {
                ObjectNode result = mapper.createObjectNode();
                object.properties().forEach((name, child) -> result.set(name, toJsonNode(child)));
                yield result;
            }
            case ConfigurationData.ArrayValue array -> {
                ArrayNode result = mapper.createArrayNode();
                array.values().forEach(child -> result.add(toJsonNode(child)));
                yield result;
            }
            case ConfigurationData.TextValue text -> mapper.getNodeFactory().textNode(text.value());
            case ConfigurationData.NumberValue number -> number.value().scale() <= 0
                    ? mapper.getNodeFactory().numberNode(number.value().toBigIntegerExact())
                    : mapper.getNodeFactory().numberNode(number.value());
            case ConfigurationData.BooleanValue bool -> mapper.getNodeFactory().booleanNode(bool.value());
            case ConfigurationData.NullValue ignored -> mapper.getNodeFactory().nullNode();
        };
    }

    /** 在任何读取或写入前拒绝已关闭所有者，避免重建 Watcher 或配置代际。 */
    private void ensureOpen() {
        if (closed) throw error(ConfigurationError.Code.IO_FAILURE, "configuration service is closed");
    }

    /** 将已确认的变更依次通知监听者，单个订阅者失败不得中断其他通知。 */
    private void publishChanges(List<ConfigurationRuntimeState.ConfigChanged> changes) {
        for (ConfigurationRuntimeState.ConfigChanged change : changes) {
            for (Consumer<ConfigurationRuntimeState.ConfigChanged> listener : changeListeners) {
                try {
                    listener.accept(change);
                } catch (RuntimeException ignored) {
                    // 监听者是通知边界；一个订阅者的运行时异常不能回滚已发布的配置。
                }
            }
        }
    }

    /** 用户层、凭据或信任登记变化时废弃全部缓存代际，新请求必须重新解析。 */
    private void invalidateAllGenerations() {
        generationRuntime.invalidateAll();
    }

    /** 只废弃指定工作区代际，已经持有的 Lease 仍按引用计数安全完成。 */
    private void invalidateGeneration(Path canonical) {
        generationRuntime.invalidate(canonical);
    }

    /** 要求 sidecar home 为显式绝对路径并清理语法冗余，禁止相对 cwd 影响存储归属。 */
    private static Path requireHome(Path home) {
        if (home == null || !home.isAbsolute()) throw error(ConfigurationError.Code.INVALID_ARGUMENT,
                "sidecar home directory is invalid");
        return home.toAbsolutePath().normalize();
    }

    /** 从单一 home 派生 data、run 和 logs 根，仅用于嵌入式构造且不读取进程 cwd。 */
    private static SidecarConfiguration homeOnlyConfiguration(Path home) {
        Path root = requireHome(home);
        return new SidecarConfiguration(root, root.resolve("data"), root.resolve("run"), root.resolve("logs"));
    }

    /** 返回测试可见的缓存代际数量，用于验证轮换不会无限保留无租约快照。 */
    synchronized int trackedGenerationCount() {
        return generationRuntime.trackedGenerationCount();
    }

    /** 统一构造配置域错误，使服务关闭与参数失败使用相同脱敏异常边界。 */
    private static ConfigurationError error(ConfigurationError.Code code, String message) {
        return new ConfigurationError(code, message);
    }

}
