// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;

import java.util.ArrayList;
import java.util.List;

/** JA-RPC v1 结构化用户内容的共享严格解析器；Task 与普通 Turn 必须使用相同闭集。 */
final class RpcUserContent {
    /** 解析判别联合并让 UserContent 复核顺序、重复引用与可操作内容约束。 */
    static UserContent parse(JsonNode value) {
        if (!(value instanceof ArrayNode array) || array.isEmpty() || array.size() > 64) {
            throw JaRpcException.invalidParams();
        }
        List<UserContentBlock> blocks = new ArrayList<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) throw JaRpcException.invalidParams();
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
                    blocks.add(new WorkspaceReferenceContent(
                            RpcParams.identifier(object, "workspaceId", "ws_", 128),
                            RpcParams.text(object, "relativePath", 4_096, false),
                            workspaceKind(RpcParams.text(object, "kind", 16, false))));
                }
                case "skill_reference" -> {
                    RpcParams.requireExact(object, "type", "skillId");
                    blocks.add(new SkillReferenceContent(
                            RpcParams.identifier(object, "skillId", "skill_", 128)));
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

    /** Workspace kind 只接受 wire 闭集，不查询文件系统猜测类型。 */
    private static WorkspaceReferenceContent.Kind workspaceKind(String value) {
        return switch (value) {
            case "file" -> WorkspaceReferenceContent.Kind.FILE;
            case "directory" -> WorkspaceReferenceContent.Kind.DIRECTORY;
            default -> throw JaRpcException.invalidParams();
        };
    }

    /** 无状态解析器禁止实例化。 */
    private RpcUserContent() { }
}
