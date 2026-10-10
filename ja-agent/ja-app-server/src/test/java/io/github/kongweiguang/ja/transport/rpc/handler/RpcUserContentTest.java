// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定 Ja workspace wire 类型与 Core 中立资源引用之间的边界映射。 */
final class RpcUserContentTest {
    /** 入站只接受 Ja 已定义的文件/目录闭集，并原样保留路径身份供 Workspace 校验器检查。 */
    @Test
    void parsesWorkspaceReferenceIntoNeutralResourceIdentity() throws Exception {
        JsonNode wire = new ObjectMapper().readTree("""
                [{"type":"workspace_reference","workspaceId":"ws_demo","relativePath":"docs/notes.md","kind":"file"}]
                """);

        ResourceReferenceContent reference = (ResourceReferenceContent) RpcUserContent.parse(wire)
                .blocks().getFirst();

        assertEquals("ws_demo", reference.sourceId());
        assertEquals("docs/notes.md", reference.resourceId());
        assertEquals("file", reference.kind());
    }

    /** 旧 wire DTO 只能从经 Ja 验证的中立身份投影，不将 Core 任意资源伪装成工作区路径。 */
    @Test
    void projectsOnlyJaWorkspaceResourceReferences() {
        ObjectMapper mapper = new ObjectMapper();
        UserContent content = new UserContent(java.util.List.of(
                new ResourceReferenceContent("ws_demo", "docs/notes.md", "directory")));

        JsonNode result = RpcResults.userContent(mapper, content).get(0);

        assertEquals("workspace_reference", result.path("type").textValue());
        assertEquals("ws_demo", result.path("workspaceId").textValue());
        assertEquals("docs/notes.md", result.path("relativePath").textValue());
        assertEquals("directory", result.path("kind").textValue());
    }

    /** 不支持的业务 kind 在入站边界失败关闭，不能被隐式解释成目录。 */
    @Test
    void rejectsUnknownWorkspaceKind() throws Exception {
        JsonNode wire = new ObjectMapper().readTree("""
                [{"type":"workspace_reference","workspaceId":"ws_demo","relativePath":"docs/notes.md","kind":"symlink"}]
                """);

        assertThrows(JaRpcException.class, () -> RpcUserContent.parse(wire));
    }

    /** 公开三种来源共享 256 字符名称上限，投影到 Core 时保留原始身份。 */
    @Test
    void acceptsMaximumAsciiKebabNameForPublicSources() {
        ObjectMapper mapper = new ObjectMapper();
        String name = "a".repeat(256);

        for (String source : java.util.List.of("user", "ja", "project")) {
            String skillId = source + ":" + name;
            ArrayNode wire = mapper.createArrayNode();
            wire.addObject().put("type", "skill_reference").put("skillId", skillId);
            wire.addObject().put("type", "text").put("text", "run");

            SkillReferenceContent reference = (SkillReferenceContent) RpcUserContent.parse(wire)
                    .blocks().getFirst();

            assertEquals(skillId, reference.skillId());
        }
    }

    /** RPC 边界拒绝超长、非 ASCII、大写或非公开来源，避免无效身份进入 Core。 */
    @Test
    void rejectsInvalidPublicSkillReferences() {
        ObjectMapper mapper = new ObjectMapper();
        java.util.List<String> invalidReferences = java.util.List.of(
                "user:" + "a".repeat(257),
                "user:天气",
                "user:Upper",
                "bundled:demo");

        for (String skillId : invalidReferences) {
            ArrayNode wire = mapper.createArrayNode();
            wire.addObject().put("type", "skill_reference").put("skillId", skillId);
            wire.addObject().put("type", "text").put("text", "run");

            assertThrows(JaRpcException.class, () -> RpcUserContent.parse(wire));
        }
    }
}
