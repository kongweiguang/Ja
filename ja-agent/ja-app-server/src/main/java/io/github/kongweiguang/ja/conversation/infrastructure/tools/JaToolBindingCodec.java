// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 统一生成Ja工具路由和权限指纹，使运行调用与历史迁移采用相同安全定义。
 */
public final class JaToolBindingCodec {
    private static final int ROUTE_SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;

    /**
     * 固定规范 JSON 摘要实现，避免适配器与迁移各自拼接权限字符串。
     */
    public JaToolBindingCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 拒绝空白与控制字符，防止路由记录混入未显示的字段分隔内容。
     */
    private static String bounded(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 工具目录修订必须是完整小写 SHA-256，避免弱摘要形成旧授权别名。
     */
    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 权限摘要使用 JDK SHA-256，对应有序 JSON 安全字段的 UTF-8 字节。
     */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /**
     * 把实际访问级别、逐调用副作用和冻结路由编成Core绑定，防止权限由指纹反推。
     */
    public ToolExecutionBinding encode(String toolName, String catalogFingerprint, AccessMode accessMode,
                                       ToolSideEffect sideEffect, JaTool.ToolBindingDescriptor descriptor) {
        JaTool.ToolBindingDescriptor route = Objects.requireNonNull(descriptor, "descriptor");
        return encode(toolName, catalogFingerprint, accessMode, sideEffect,
                route.routeKind().name(), route.localName(), route.serverId(), route.remoteName(),
                route.schemaHash(), route.routeHash());
    }

    /**
     * 从旧迁移保留的路由字段构造同一绑定；历史摘要不可逆，不尝试猜测原始配置。
     */
    public ToolExecutionBinding encode(String toolName, String catalogFingerprint, AccessMode accessMode,
                                       ToolSideEffect sideEffect, String routeKind, String localName,
                                       String serverId, String remoteName, String schemaHash, String routeHash) {
        String checkedTool = ContractChecks.identifier(toolName, "toolName");
        String checkedCatalog = fingerprint(catalogFingerprint, "catalogFingerprint");
        AccessMode checkedAccess = Objects.requireNonNull(accessMode, "accessMode");
        ToolSideEffect checkedEffect = Objects.requireNonNull(sideEffect, "sideEffect");
        String checkedKind = ContractChecks.identifier(routeKind, "routeKind");
        String checkedLocal = ContractChecks.identifier(localName, "localName");
        String checkedServer = ContractChecks.identifier(serverId, "serverId");
        String checkedRemote = bounded(remoteName, "remoteName", 512);
        String checkedSchema = fingerprint(schemaHash, "schemaHash");
        String checkedRoute = fingerprint(routeHash, "routeHash");

        ObjectNode security = mapper.createObjectNode().put("schemaVersion", ROUTE_SCHEMA_VERSION)
                .put("toolName", checkedTool).put("accessMode", checkedAccess.name())
                .put("sideEffect", checkedEffect.name()).put("routeKind", checkedKind)
                .put("localName", checkedLocal).put("serverId", checkedServer).put("remoteName", checkedRemote)
                .put("schemaHash", checkedSchema).put("routeHash", checkedRoute);
        String permissionFingerprint = sha256(writeCanonicalSecurity(security));
        JsonObject route = JsonObjects.builder().putNumber("schemaVersion", ROUTE_SCHEMA_VERSION)
                .putText("routeKind", checkedKind).putText("localName", checkedLocal)
                .putText("serverId", checkedServer).putText("remoteName", checkedRemote)
                .putText("schemaHash", checkedSchema).putText("routeHash", checkedRoute)
                .putText("catalogRevision", checkedCatalog).putText("accessMode", checkedAccess.name())
                .putText("sideEffect", checkedEffect.name()).build();
        return new ToolExecutionBinding(checkedTool, checkedSchema, checkedCatalog, permissionFingerprint, route);
    }

    /**
     * 通过有序 ObjectNode 序列化安全字段，避免分隔符和空值令权限身份产生歧义。
     */
    private byte[] writeCanonicalSecurity(ObjectNode security) {
        try {
            return mapper.writeValueAsBytes(security);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("cannot encode Ja tool security binding", failure);
        }
    }
}
