// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;

import java.util.ArrayList;
import java.util.List;

/**
 * JA-RPC v2 结构化用户内容的共享严格解析器；Task 与普通 Turn 必须使用相同闭集。
 */
final class RpcUserContent {
    /**
     * 无状态解析器禁止实例化。
     */
    private RpcUserContent() {
    }

    /**
     * 解析判别联合并复核内容约束；拒绝错误只说明参数无效，避免把不可信正文写入诊断。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    static UserContent parse(JsonNode value) {
        if (!(value instanceof ArrayNode array) || array.isEmpty() || array.size() > 64) {
            throw JaRpcException.invalidParams();
        }
        List<UserContentBlock> blocks = new ArrayList<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) {
                throw JaRpcException.invalidParams();
            }
            String type = RpcParams.text(object, "type", 32, false);
            switch (type) {
                case "text" -> {
                    RpcParams.requireExact(object, "type", "text");
                    blocks.add(new TextContent(RpcParams.text(object, "text", 4_000_000, false)));
                }
                case "attachment" -> {
                    RpcParams.requireExact(object, "type", "attachmentId");
                    blocks.add(new AttachmentContent(RpcParams.identifier(object, "attachmentId", "att_", 128)));
                }
                case "workspace_reference" -> {
                    RpcParams.requireExact(object, "type", "workspaceId", "relativePath", "kind");
                    blocks.add(new ResourceReferenceContent(
                            RpcParams.identifier(object, "workspaceId", "ws_", 128),
                            RpcParams.text(object, "relativePath", 4_096, false),
                            workspaceKind(RpcParams.text(object, "kind", 16, false))));
                }
                case "skill_reference" -> {
                    RpcParams.requireExact(object, "type", "skillId");
                    blocks.add(new SkillReferenceContent(publicSkillReference(object)));
                }
                default -> throw JaRpcException.invalidParams();
            }
        }
        try {
            return new UserContent(blocks);
        } catch (IllegalArgumentException failure) {
            throw JaRpcException.invalidParams();
        }
    }

    /**
     * RPC 只公开 user、ja 和 project 来源；Core 的扩展来源能力不属于外部线协议。
     */
    private static String publicSkillReference(ObjectNode object) {
        String reference = RpcParams.text(object, "skillId", 264, false);
        int separator = reference.indexOf(':');
        if (separator <= 0 || separator != reference.lastIndexOf(':')) {
            throw JaRpcException.invalidParams();
        }
        String source = reference.substring(0, separator);
        if (!source.equals("user") && !source.equals("ja") && !source.equals("project")) {
            throw JaRpcException.invalidParams();
        }
        try {
            return ContractChecks.runtimeSkillIdentifier(reference);
        } catch (IllegalArgumentException invalidReference) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_PARAMS,
                    "request parameters are invalid", invalidReference);
        }
    }

    /**
     * Workspace kind 只接受 wire 闭集，不查询文件系统猜测类型。
     */
    private static String workspaceKind(String value) {
        return switch (value) {
            case "file", "directory" -> value;
            default -> throw JaRpcException.invalidParams();
        };
    }
}
