// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.experience.port.out;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import java.util.List;

/** Skill 管理端口；版本化写入语义由 Core PG 来源实现。 */
public interface SkillCatalog {
    /** 发现阶段只返回描述，不加载全部正文。 */
    List<SkillDescriptor> list(SkillRequestContext context);

    /** 按已发现版本读取正文，变更时由来源报冲突。 */
    String read(SkillRequestContext context, SkillDescriptor descriptor);

    /** 写入时保留既有附属资源，防止管理页编辑正文误删资源。 */
    SkillDescriptor write(SkillRequestContext context, String name, String description, String body,
                          long expectedVersion);

    /** 删除必须引用已观察版本。 */
    void delete(SkillRequestContext context, String name, long expectedVersion);
}
