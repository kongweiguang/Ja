// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.skill;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;

/**
 * 提供租户范围内的 Skill 元数据分页和正文读取；来源只负责自己的存取与隔离，不决定激活、同名覆盖或模型权限。
 */
public interface SkillSource {
    /**
     * 返回稳定来源 ID；格式为小写字母数字和连字符分段，注册后不得变化，因为目录引用和禁用规则依赖它。
     */
    String id();

    /**
     * 返回非负显式优先级；数值越大越先覆盖同名项，平级时由 Core 按注册顺序稳定裁决。
     */
    int priority();

    /**
     * 按页发现元数据，使数据库来源无需一次装载完整目录；传入 null cursor 表示首批，返回 null nextCursor 表示终页。
     * pageSize 是 Core 的有界读取上限，来源必须保证返回不超过该数量且续页游标能够前进。
     */
    SkillPage discover(SkillRequestContext context, String cursor, int pageSize);

    /**
     * 读取发现时同租户、同来源的资源；适配器必须在实际存储查询中重新执行租户和资源边界校验，不能把目录引用当授权。
     * 请求已限制相对路径和字符数，但本地来源仍须验证符号链接后的最终路径，数据库来源须校验目标 Skill 归属。
     */
    SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor, SkillReadRequest request);
}
