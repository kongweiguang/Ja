// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 把严格 JA-RPC v1 配置命令映射到配置入站端口；Jackson 只存在于该 Wire 边界。
 *
 * <p>workspaceId 在此解析为进程内 Path 能力，配置域只接收纯 JDK 不可变文档；Secret 仅能
 * 通过 credential/set 进入配置用例；仅 credential/reveal-provider 可在编辑 Provider 时回显其
 * 绑定 API Key，其它响应和通知保持脱敏。</p>
 */
public final class ConfigurationHandler implements RpcHandler {
    private final RpcSession session;

    /** 绑定连接级工作区解析与配置端口，不缓存文档、路径、CAS 版本或 Secret。 */
    public ConfigurationHandler(RpcSession session) {
        this.session = session;
    }

    /** 只注册冻结后的配置与凭据方法；回显能力只绑定 Provider 身份，未知动作由关闭注册表拒绝。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.CONFIGURATION_READ, RpcMethod.CONFIGURATION_PATCH,
                RpcMethod.CONFIGURATION_REPLACE, RpcMethod.CONFIGURATION_RESET,
                RpcMethod.CONFIGURATION_RESTORE,
                RpcMethod.CREDENTIAL_SET, RpcMethod.CREDENTIAL_DELETE,
                RpcMethod.CREDENTIAL_REVEAL_PROVIDER);
    }

    /** 在握手就绪后分派一个同步、有界的配置命令，不把 Wire DTO 交给应用端口。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        return CompletableFuture.completedFuture(switch (command.method()) {
            case CONFIGURATION_READ -> read(command.params());
            case CONFIGURATION_PATCH -> patch(command.params());
            case CONFIGURATION_REPLACE -> replace(command.params());
            case CONFIGURATION_RESET -> reset(command.params());
            case CONFIGURATION_RESTORE -> restore(command.params());
            case CREDENTIAL_SET -> setCredential(command.params());
            case CREDENTIAL_DELETE -> deleteCredential(command.params());
            case CREDENTIAL_REVEAL_PROVIDER -> revealProviderCredential(command.params());
            default -> throw JaRpcException.methodNotFound();
        });
    }

    /** 解析可选工作区能力并映射脱敏快照；Wire 只返回 workspaceId，绝不返回本地路径。 */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId");
        Workspace workspace = optionalWorkspace(params);
        ConfigurationUseCase.ReadResult read = session.configurationUseCase().read(root(workspace));
        ObjectNode result = readResult(read);
        if (workspace == null) result.putNull("workspaceId");
        else result.put("workspaceId", workspace.workspaceId());
        return result;
    }

    /** 应用 RFC 7396 Merge Patch，并只发布作用域、工作区身份与新版本。 */
    private ObjectNode patch(ObjectNode params) {
        RpcParams.requireExact(params, requiredFields(params, "patch"));
        ConfigurationScope scope = requireScope(params);
        Workspace workspace = requireWorkspace(scope, params);
        String expectedVersion = requireVersion(params);
        if (!(params.get("patch") instanceof ObjectNode patch)) throw JaRpcException.invalidParams();
        ConfigurationUseCase.MutationResult result = session.configurationUseCase().patch(
                scope, root(workspace), document(patch), expectedVersion);
        ObjectNode wire = mutationResult(result, scope);
        publishChanged(workspace, result);
        return wire;
    }

    /** 用完整严格文档替换目标层，使损坏配置只能通过显式 replace 动作修复。 */
    private ObjectNode replace(ObjectNode params) {
        RpcParams.requireExact(params, requiredFields(params, "document"));
        ConfigurationScope scope = requireScope(params);
        Workspace workspace = requireWorkspace(scope, params);
        String expectedVersion = requireVersion(params);
        if (!(params.get("document") instanceof ObjectNode value)) throw JaRpcException.invalidParams();
        ConfigurationUseCase.MutationResult result = session.configurationUseCase().replace(
                scope, root(workspace), document(value), expectedVersion);
        ObjectNode wire = mutationResult(result, scope);
        publishChanged(workspace, result);
        return wire;
    }

    /** 通过独立 reset 动作恢复严格空文档，避免 null 或 mode 哨兵产生多义写入。 */
    private ObjectNode reset(ObjectNode params) {
        RpcParams.requireExact(params, requiredFields(params));
        ConfigurationScope scope = requireScope(params);
        Workspace workspace = requireWorkspace(scope, params);
        ConfigurationUseCase.MutationResult result = session.configurationUseCase().reset(
                scope, root(workspace), requireVersion(params));
        ObjectNode wire = mutationResult(result, scope);
        publishChanged(workspace, result);
        return wire;
    }

    /**
     * 恢复命令不接受 scope、workspace 或 document，避免一个含糊请求覆盖项目层，或让客户端伪造
     * 恢复内容。App Server 会在 CAS 通过后备份原始用户文件并从其私有快照发布。
     */
    private ObjectNode restore(ObjectNode params) {
        RpcParams.requireExact(params, "expectedVersion");
        ConfigurationUseCase.MutationResult result = session.configurationUseCase()
                .restoreLastKnownGood(requireVersion(params));
        ObjectNode wire = mutationResult(result, ConfigurationScope.USER);
        publishChanged(null, result);
        return wire;
    }

    /** 把唯一允许携带 Secret 的 Wire 输入直接交给配置用例，响应只投影脱敏状态。 */
    private ObjectNode setCredential(ObjectNode params) {
        RpcParams.requireExact(params, "credentialId", "secret", "expectedVersion");
        String credentialId = RpcParams.text(params, "credentialId", 128, false);
        String secret = RpcParams.text(params, "secret", 8192, false);
        ConfigurationUseCase.CredentialResult result = session.configurationUseCase().setCredential(
                credentialId, secret, requireVersion(params));
        return credentialResult(result, credentialId, true);
    }

    /** 按不透明标识删除凭据，响应固定 configured=false 且不携带旧凭据状态。 */
    private ObjectNode deleteCredential(ObjectNode params) {
        RpcParams.requireExact(params, "credentialId", "expectedVersion");
        String credentialId = RpcParams.text(params, "credentialId", 128, false);
        ConfigurationUseCase.CredentialResult result = session.configurationUseCase().deleteCredential(
                credentialId, requireVersion(params));
        return credentialResult(result, credentialId, false);
    }

    /**
     * 将用户显式选择的 Provider 映射为唯一允许回显的 Secret；Provider identity 由当前代际
     * 重新解析，避免客户端把任意 credentialId 作为读取钥匙，且该值不进入配置事件或快照。
     */
    private ObjectNode revealProviderCredential(ObjectNode params) {
        RpcParams.requireExact(params, "providerId");
        String providerId = RpcParams.text(params, "providerId", 128, false);
        String secret = session.configurationUseCase().revealProviderCredential(providerId);
        ObjectNode result = session.mapper().createObjectNode();
        if (secret == null) result.putNull("secret");
        else result.put("secret", secret);
        return result;
    }

    /** 在进入配置端口前把 Wire 字符串收敛为领域枚举。 */
    private static ConfigurationScope requireScope(ObjectNode params) {
        return switch (RpcParams.text(params, "scope", 16, false)) {
            case "user" -> ConfigurationScope.USER;
            case "project" -> ConfigurationScope.PROJECT;
            default -> throw JaRpcException.invalidParams();
        };
    }

    /** 返回非空 CAS 版本；目标层身份与冲突判定仍由配置所有者原子裁决。 */
    private static String requireVersion(ObjectNode params) {
        if (!params.has("expectedVersion")) throw JaRpcException.invalidParams();
        JsonNode value = params.get("expectedVersion");
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 256) {
            throw JaRpcException.invalidParams();
        }
        return value.textValue();
    }

    /** 只在端口成功且结果有效后刷新工作区目录，并发布不含配置正文的变更通知。 */
    private void publishChanged(Workspace workspace, ConfigurationUseCase.MutationResult result) {
        if (result.version().isBlank()) throw invalidConfigurationResult();
        session.refreshPreparedWorkspaces();
        session.notifyConfigChanged(result.scope() == ConfigurationScope.USER ? "user" : "project",
                workspace == null ? null : workspace.workspaceId(), result.version());
    }

    /** 项目操作只能解析已打开且受信任的 workspaceId；用户作用域禁止夹带工作区身份。 */
    private Workspace requireWorkspace(ConfigurationScope scope, ObjectNode params) {
        if (scope == ConfigurationScope.USER) {
            if (params.has("workspaceId")) throw JaRpcException.invalidParams();
            return null;
        }
        String workspaceId = RpcParams.text(params, "workspaceId", 100, false);
        Workspace workspace = session.workspaces().requireOpenWorkspace(workspaceId);
        if (workspace.trust() != Workspace.Trust.TRUSTED) {
            throw JaRpcException.of(JaErrorCatalog.WORKSPACE_TRUST_REQUIRED,
                    "workspace trust is required");
        }
        return workspace;
    }

    /** 读取允许省略 workspaceId 表示通用投影；显式字段仍必须解析为已打开工作区。 */
    private Workspace optionalWorkspace(ObjectNode params) {
        if (!params.has("workspaceId")) return null;
        return session.workspaces().requireOpenWorkspace(
                RpcParams.text(params, "workspaceId", 100, false));
    }

    /** 把工作区领域能力转换为配置域内部 Path；用户作用域和通用读取保留 null。 */
    private static Path root(Workspace workspace) {
        return workspace == null ? null : workspace.root();
    }

    /** 根据作用域生成严格字段闭集，使项目命令只能额外携带 workspaceId。 */
    private static String[] requiredFields(ObjectNode params, String... payloadFields) {
        boolean project = "project".equals(params.path("scope").textValue());
        String[] fields = new String[2 + payloadFields.length + (project ? 1 : 0)];
        fields[0] = "scope";
        fields[1] = "expectedVersion";
        System.arraycopy(payloadFields, 0, fields, 2, payloadFields.length);
        if (project) fields[fields.length - 1] = "workspaceId";
        return fields;
    }

    /** 把纯 JDK 配置文档构造成固定 Wire 对象，并保持配置字段插入顺序。 */
    private ObjectNode documentNode(ConfigurationUseCase.Document document) {
        ObjectNode result = session.mapper().createObjectNode();
        document.properties().forEach((name, value) -> result.set(name, valueNode(value)));
        return result;
    }

    /** 穷举配置值到 Jackson 节点的映射，新增端口值类型时由 sealed switch 强制更新。 */
    private JsonNode valueNode(ConfigurationUseCase.Value value) {
        return switch (value) {
            case ConfigurationUseCase.ObjectValue object -> {
                ObjectNode result = session.mapper().createObjectNode();
                object.properties().forEach((name, child) -> result.set(name, valueNode(child)));
                yield result;
            }
            case ConfigurationUseCase.ArrayValue array -> {
                ArrayNode result = session.mapper().createArrayNode();
                array.values().forEach(child -> result.add(valueNode(child)));
                yield result;
            }
            case ConfigurationUseCase.TextValue text ->
                    session.mapper().getNodeFactory().textNode(text.value());
            case ConfigurationUseCase.NumberValue number -> number.value().scale() <= 0
                    ? session.mapper().getNodeFactory().numberNode(number.value().toBigIntegerExact())
                    : session.mapper().getNodeFactory().numberNode(number.value());
            case ConfigurationUseCase.BooleanValue bool ->
                    session.mapper().getNodeFactory().booleanNode(bool.value());
            case ConfigurationUseCase.NullValue ignored -> session.mapper().getNodeFactory().nullNode();
        };
    }

    /** 把 Wire 根对象深复制为纯 JDK 文档，确保配置端口不持有 Jackson 可变节点。 */
    private static ConfigurationUseCase.Document document(ObjectNode object) {
        Map<String, ConfigurationUseCase.Value> properties = new LinkedHashMap<>();
        object.properties().forEach(entry -> properties.put(entry.getKey(), value(entry.getValue())));
        return new ConfigurationUseCase.Document(properties);
    }

    /** 递归转换一个 Wire JSON 值；二进制、POJO 等非 JSON 节点一律按非法参数拒绝。 */
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
        throw JaRpcException.invalidParams();
    }

    /** 构造完整脱敏读取结果，并校验诊断代码后再写入 Wire。 */
    private ObjectNode readResult(ConfigurationUseCase.ReadResult read) {
        ObjectNode result = session.mapper().createObjectNode();
        result.set("effective", documentNode(read.effective()));
        result.set("user", layer(read.user()));
        result.set("project", layer(read.project()));
        ObjectNode credentials = result.putObject("credentials");
        read.credentials().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                credentials.putObject(entry.getKey()).put("configured", entry.getValue().configured()));
        result.putObject("cas")
                .put("userVersion", read.user().version())
                .put("projectVersion", read.project().version())
                .put("credentialVersion", read.credentialVersion());
        ArrayNode diagnostics = result.putArray("diagnostics");
        read.diagnostics().forEach(code -> {
            if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalidConfigurationResult();
            diagnostics.add(code);
        });
        ArrayNode issues = result.putArray("issues");
        read.issues().forEach(issue -> {
            if (!issue.id().matches("cfg_[A-Za-z0-9_-]{1,64}")
                    || !issue.scope().matches("[a-z_]{1,32}")
                    || !issue.reason().matches("[A-Z][A-Z0-9_]{0,63}")
                    || !issue.impact().matches("[a-z_]{1,64}")) throw invalidConfigurationResult();
            ObjectNode value = issues.addObject().put("id", issue.id()).put("scope", issue.scope())
                    .put("reason", issue.reason()).put("impact", issue.impact());
            if (issue.field() == null) value.putNull("field"); else value.put("field", issue.field());
            if (issue.entityId() == null) value.putNull("entityId"); else value.put("entityId", issue.entityId());
            if (issue.line() == null) value.putNull("line"); else value.put("line", issue.line());
            if (issue.column() == null) value.putNull("column"); else value.put("column", issue.column());
            ArrayNode actions = value.putArray("actions");
            issue.actions().forEach(action -> {
                if (!action.matches("[a-z_]{1,32}")) throw invalidConfigurationResult();
                actions.add(action);
            });
        });
        result.put("trusted", read.trusted());
        return result;
    }

    /** 映射单层脱敏状态；文档不存在时显式返回 JSON null，避免缺字段产生多义契约。 */
    private ObjectNode layer(ConfigurationUseCase.Layer layer) {
        ObjectNode result = session.mapper().createObjectNode().put("present", layer.present())
                .put("trusted", layer.trusted())
                .put("status", layer.status().name().toLowerCase(Locale.ROOT));
        if (layer.document() == null) result.putNull("document");
        else result.set("document", documentNode(layer.document()));
        return result;
    }

    /** 验证配置变更身份与请求一致后构造冻结的 accepted/scope/version 结果。 */
    private ObjectNode mutationResult(ConfigurationUseCase.MutationResult result,
                                      ConfigurationScope requestedScope) {
        if (result.scope() != requestedScope || result.version().isBlank()) {
            throw invalidConfigurationResult();
        }
        return session.mapper().createObjectNode().put("accepted", true)
                .put("scope", result.scope() == ConfigurationScope.USER ? "user" : "project")
                .put("version", result.version());
    }

    /** 验证凭据结果没有身份漂移后构造唯一的无 Secret 响应。 */
    private ObjectNode credentialResult(ConfigurationUseCase.CredentialResult result,
                                        String credentialId, boolean expectedConfigured) {
        if (!credentialId.equals(result.credentialId())
                || result.configured() != expectedConfigured || result.version().isBlank()) {
            throw invalidConfigurationResult();
        }
        return session.mapper().createObjectNode().put("accepted", true)
                .put("credentialId", result.credentialId())
                .put("configured", result.configured()).put("version", result.version());
    }

    /** 把不满足端口投影不变量的实现结果压缩为稳定错误，不泄露内部字段。 */
    private static JaRpcException invalidConfigurationResult() {
        return JaRpcException.of(JaErrorCatalog.CONFIG_INVALID, "configuration result is invalid");
    }
}
