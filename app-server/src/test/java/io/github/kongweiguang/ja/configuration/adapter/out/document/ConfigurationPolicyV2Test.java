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
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 配置 v2 的来源限定 Skill 停用与可信项目 MCP 策略测试。 */
final class ConfigurationPolicyV2Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用户层停用名单只接受 user/ja，未列出的发现项由运行时默认开启。 */
    @Test
    void userSkillsRequireGlobalSourceReferences() {
        ObjectNode user = userDocument("user:review", "ja:coding");

        ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER);

        user.withArray("disabled_skills").add("project:review");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(user, ConfigurationScope.USER));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 项目层只表达本项目停用引用，不能承载用户级配置所有权。 */
    @Test
    void projectDocumentCannotOwnUserDefaults() {
        ObjectNode project = projectDocument("project:review");

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

    /** 合法省略 enabled 采用默认开启，非法类型只让对应服务从执行投影消失。 */
    @Test
    void mcpEnabledDefaultsTrueButInvalidTypeIsIsolated() {
        ObjectNode user = userDocument();
        mcp(user, "mcp_good", "Good");
        mcp(user, "mcp_bad", "Bad");
        ((ObjectNode) user.withArray("mcp_servers").get(0)).remove("enabled");
        ((ObjectNode) user.withArray("mcp_servers").get(1)).put("enabled", "yes");

        TolerantConfigurationDocumentReader.Result normalized =
                TolerantConfigurationDocumentReader.normalize(user, ConfigurationScope.USER);
        assertEquals(1, normalized.document().withArray("mcp_servers").size());
        assertEquals(true, normalized.document().withArray("mcp_servers").get(0).path("enabled").booleanValue());
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

    /** 项目停用项只能使用 project 来源，不能修改全局来源的状态。 */
    @Test
    void projectDisabledSkillCannotTargetGlobalSource() {
        ObjectNode project = projectDocument("project:review");
        project.withArray("disabled_skills").add("ja:missing");
        ConfigurationError failure = assertThrows(ConfigurationError.class,
                () -> ConfigurationPolicy.validateDocument(project, ConfigurationScope.PROJECT));
        assertEquals(ConfigurationError.Code.INVALID_DOCUMENT, failure.code());
    }

    /** 有效配置保留全局及项目停用引用，供运行时按来源过滤后解析同名覆盖。 */
    @Test
    void effectiveDisabledSkillsAreMergedBySource() {
        ObjectNode effective = ConfigurationPolicy.mergeDocuments(
                userDocument("user:review", "ja:coding"),
                projectDocument("project:review"));

        assertEquals(java.util.List.of("user:review", "ja:coding", "project:review"), skillReferences(effective));
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

    /** 宽容读取旧启用名单只报告问题，绝不能把旧名单反向解释成停用授权。 */
    @Test
    void legacyEnabledListDoesNotBecomeDisabledList() {
        ObjectNode user = userDocument();
        user.putArray("skills").add("user:review");

        TolerantConfigurationDocumentReader.Result read =
                TolerantConfigurationDocumentReader.normalize(user, ConfigurationScope.USER);
        assertEquals(0, read.document().withArray("disabled_skills").size());
        assertEquals(true, read.issues().stream().anyMatch(issue -> "skills".equals(issue.field())));
    }

    /** 最小用户文档保留 Provider/MCP 的现有严格 owner，而测试只暴露关联的 Skill 字段。 */
    private static ObjectNode userDocument(String... disabled) {
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
        ArrayNode values = root.putArray("disabled_skills");
        for (String skill : disabled) values.add(skill);
        return root;
    }

    /** 项目文档只记录本来源的停用引用。 */
    private static ObjectNode projectDocument(String... disabled) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", 2);
        root.put("config_revision", 1);
        ArrayNode values = root.putArray("disabled_skills");
        for (String skill : disabled) values.add(skill);
        return root;
    }

    /** 只读取引用字符串，使断言不依赖 TOML 格式或可能变化的发现元数据。 */
    private static java.util.List<String> skillReferences(ObjectNode document) {
        return document.withArray("disabled_skills").valueStream().map(node -> node.textValue()).toList();
    }
}
