// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.skill;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * Skill 来源读取到的有界纯文本及截断事实；Core 在收到后计算指纹，来源无需实现重复的摘要协议。
 *
 * @param content 内容
 * @param truncated 结果是否被截断
 */
public record SkillResourceContent(String content,
                                   boolean truncated) {
    /**
     * 只接收纯文本和真实截断标记；读取异常必须由来源抛出，不能伪装为空正文继续执行。
     */
    public SkillResourceContent {
        content = ContractChecks.text(content, "content", 4_000_000, true);
    }
}
