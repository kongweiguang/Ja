// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.ExperienceService;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.core.EnterpriseDocumentMiddleware;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.jdbc.PostgresSkillCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.NoteRepository;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.SkillCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.mcp.McpService;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.core.ExperienceExtension;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.ExperienceJsonCodec;
import io.github.kongweiguang.ja.agent.example.springboot.experience.infrastructure.json.JacksonExperienceJsonCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSchema;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 演示扩展与普通 MVC 服务分开装配，测试或接入方可显式关闭整组体验能力。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.experience.enabled", havingValue = "true", matchIfMissing = true)
public class ExperienceConfiguration {
    /**
     * PG 来源只初始化自己的表，示例包不会覆盖已有同名包。
     */
    @Bean
    public PostgresSkillSource demoSkills(DataSource dataSource) {
        PostgresSkillSchema.install(dataSource);
        return new PostgresSkillSource(dataSource, "demo-pg", 100);
    }

    /**
     * 目录连接按需建立，Spring 负责释放 SDK 会话资源。
     */
    @Bean(destroyMethod = "close")
    public McpService mcpService(AgentProperties properties) {
        return new McpService(properties.mcpServers());
    }

    /** PG 来源负责技能资源与 CAS 事务，组合根仅将其绑定到业务目录端口。 */
    @Bean
    public SkillCatalog skillCatalog(PostgresSkillSource skills) {
        return new PostgresSkillCatalog(skills);
    }

    /** 用例仅依赖持久化端口，注册到 Core 的 PG 来源仍由装配层持有。 */
    @Bean
    public ExperienceService experienceService(SkillCatalog skills, NoteRepository notes,
                                               AttachmentService attachments, CurrentActorProvider actors,
                                               ExtensionFactRecordCodec factCodec) {
        return new ExperienceService(skills, notes, attachments, actors, factCodec);
    }

    /** JSON 转换由组合根提供，Core 扩展和 HTTP 层共享同一套字段与数字语义。 */
    @Bean
    public ExperienceJsonCodec experienceJsonCodec(ObjectMapper mapper) {
        return new JacksonExperienceJsonCodec(mapper);
    }

    /**
     * 扩展一次性注册技能、工具和中间件，避免页面操作改写运行时注册表。
     */
    @Bean
    public ExperienceExtension experienceExtension(ExperienceService experience, McpService mcp, AgentStore store,
                                                   PostgresSkillSource skills, ExecutionControlCodec controlCodec,
                                                   ExperienceJsonCodec jsonCodec, AttachmentService attachments,
                                                   NoteRepository notes) {
        return new ExperienceExtension(experience, mcp, store, skills, controlCodec, jsonCodec,
                new EnterpriseDocumentMiddleware(attachments, notes));
    }
}
