// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.jdbc;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.SkillCatalog;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource;

import java.util.List;
import java.util.Objects;

/** 将 Core PG Skill 来源适配为演示管理用例，同时保留同一来源供 Engine 注册。 */
public final class PostgresSkillCatalog implements SkillCatalog {
    private final PostgresSkillSource source;

    /** 仅依赖 PG 来源的领域操作，避免 Spring 管理层重复掌握其 SQL 与资源事务。 */
    public PostgresSkillCatalog(PostgresSkillSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 有界目录供页面和播种逻辑复用。 */
    @Override
    public List<SkillDescriptor> list(SkillRequestContext context) {
        return source.discover(Objects.requireNonNull(context, "context"), null, 256).skills();
    }

    /** Core 来源根据描述符版本读取，避免陈旧页面读取到新正文。 */
    @Override
    public String read(SkillRequestContext context, SkillDescriptor descriptor) {
        return source.read(Objects.requireNonNull(context, "context"), descriptor,
                new SkillReadRequest(descriptor.name(), "SKILL.md", 32_768)).content();
    }

    /** 委托文档 CAS 写入；资源集合由 PG 来源保留，管理适配器不读取或拼装持久化数据。 */
    @Override
    public SkillDescriptor write(SkillRequestContext context, String name, String description, String body,
                                 long expectedVersion) {
        return source.writeDocument(Objects.requireNonNull(context, "context"), name, description, body,
                expectedVersion);
    }

    /** 删除委托来源的版本 CAS，避免重复实现 Skill 持久化规则。 */
    @Override
    public void delete(SkillRequestContext context, String name, long expectedVersion) {
        source.delete(Objects.requireNonNull(context, "context"), name, expectedVersion);
    }
}
