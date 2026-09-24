// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 配置 v2 的来源限定 Skill 授权与可信项目 MCP 策略测试。 */
final class ConfigurationPolicyV2Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用户层只接受显式的 user/ja 引用，避免发现到同名项目包时产生隐式继承。 */
    @Test
    void userSkillsRequireGlobalSourceReferences() {
        ObjectNode user = userDocument("user:review", "ja:coding");

        ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER);

        user.withArray("skills").add("project:review");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 项目层只表达本项目授权与对已有全局授权的收紧，不能承载其它配置所有权。 */
    @Test
    void projectDocumentCannotOwnUserDefaults() {
        ObjectNode project = projectDocument("project:review", "user:review");

        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);

        project.put("default_access_mode", "full_access");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 项目 MCP 保留完整服务定义；同名服务按 ID 区分，跨层 ID 冲突则必须显式报错。 */
    @Test
    void projectMcpMergesByIdentityAndRejectsGlobalIdCollision() {
        ObjectNode user = userDocument();
        ObjectNode project = projectDocument("project:review");
        mcp(user, "mcp_global", "Kerminal");
        mcp(project, "mcp_project", "Kerminal");

        ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT);
        ConfigurationPolicy.enforceNoEscalation(user, project);
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(user, project);
        assertEquals(2, effective.withArray("mcp_servers").size());
        assertEquals("mcp_project", effective.withArray("mcp_servers").get(1).path("mcp_id").textValue());

        ((ObjectNode) project.withArray("mcp_servers").get(0)).put("mcp_id", "mcp_global");
        ConfigurationError collision = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.enforceNoEscalation(user, project));
        assertEquals(ConfigurationError.Code.LIMIT_ESCALATION, collision.code());
    }

    /** 测试服务使用规范完整定义，避免策略测试靠缺字段误报冲突。 */
    private static void mcp(ObjectNode document, String id, String name) {
        ObjectNode server = document.withArray("mcp_servers").addObject();
        server.put("mcp_id", id).put("name", name).put("transport", "stdio")
                .put("endpoint", "node").put("enabled", true);
        server.putArray("args");
        server.putObject("env");
        server.putObject("headers");
        server.putObject("auth").put("kind", "none");
    }

    /** 项目禁用项只能收紧已显式启用的全局引用，不能成为跨项目启用通道。 */
    @Test
    void projectDisabledSkillMustExistInUserAuthorization() {
        ObjectNode user = userDocument("user:review");
        ObjectNode project = projectDocument("project:review", "user:review");

        ConfigurationPolicy.enforceNoEscalation(user, project);

        project.withArray("disabled_skills").removeAll().add("ja:missing");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.enforceNoEscalation(user, project));
        assertEquals(ConfigurationError.Code.LIMIT_ESCALATION, failure.code());
    }

    /** 有效配置保留项目引用、移除项目收紧的全局引用，且不泄漏 disabled_skills 控制字段。 */
    @Test
    void effectiveSkillsAreMergedWithoutProjectControlFields() {
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(
                userDocument("user:review", "ja:coding"),
                projectDocument("project:review", "user:review"));

        assertEquals(java.util.List.of("ja:coding", "project:review"), skillReferences(effective));
        assertFalse(effective.has("disabled_skills"));
    }

    /** 旧对象数组和旧 schema 没有兼容路径，必须进入配置恢复而不是被静默改写。 */
    @Test
    void legacySkillConfigurationIsRejected() {
        ObjectNode user = userDocument();
        user.put("schema_version", 1);
        user.withArray("skills").addObject().put("skill_id", "skill_review");

        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 最小用户文档保留 Provider/MCP 的现有严格 owner，而测试只暴露关联的 Skill 字段。 */
    private static ObjectNode userDocument(String... skills) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 2);
        root.put("config_revision", 1);
        root.put("default_access_mode", "approval_required");
        root.putNull("default_provider_id");
        root.putNull("default_model_id");
        root.putNull("default_reasoning_level");
        root.putObject("subagents").put("enabled", true).putNull("provider_id").putNull("model_id")
                .putNull("reasoning_level");
        root.putArray("providers");
        root.putArray("mcp_servers");
        ArrayNode values = root.putArray("skills");
        for (String skill : skills) values.add(skill);
        return root;
    }

    /** 项目文件始终有 skills 数组；disabled_skills 仅在需要收紧时写入。 */
    private static ObjectNode projectDocument(String projectSkill, String... disabled) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 2);
        root.put("config_revision", 1);
        root.putArray("skills").add(projectSkill);
        if (disabled.length > 0) {
            ArrayNode values = root.putArray("disabled_skills");
            for (String skill : disabled) values.add(skill);
        }
        return root;
    }

    /** 只读取引用字符串，使断言不依赖 TOML 格式或可能变化的发现元数据。 */
    private static java.util.List<String> skillReferences(ObjectNode document) {
        return document.withArray("skills").valueStream().map(node -> node.textValue()).toList();
    }
}
