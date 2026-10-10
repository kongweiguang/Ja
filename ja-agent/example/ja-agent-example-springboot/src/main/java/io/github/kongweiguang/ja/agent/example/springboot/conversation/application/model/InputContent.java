// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有新增用户输入共用的 HTTP 内容结构；附件身份只引用服务端已校验的 opaque ID。
 *
 * @param text 可选文本正文
 * @param skillIds 按选择顺序排列的 Skill 标识
 * @param attachmentIds 按选择顺序排列的附件标识
 */
public record InputContent(String text,
                           List<String> skillIds,
                           List<String> attachmentIds) {
    /** 输入快照保持有界且不可变；空正文允许与附件组合发送。 */
    public InputContent {
        text = text == null ? "" : text;
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        if (text.length() > 4_000_000 || skillIds.size() > 32 || attachmentIds.size() > 10
            || skillIds.stream().anyMatch(InputContent::invalidIdentifier)
            || attachmentIds.stream().anyMatch(InputContent::invalidIdentifier)
            || text.isBlank() && attachmentIds.isEmpty()) {
            throw new IllegalArgumentException("invalid input content");
        }
    }

    /** Core 要求规范块序，因此按 Skill、附件、正文构造且省略空正文。 */
    public UserContent toUserContent() {
        List<UserContentBlock> blocks = new ArrayList<>(skillIds.size() + attachmentIds.size() + 1);
        skillIds.forEach(id -> blocks.add(new SkillReferenceContent(id)));
        attachmentIds.forEach(id -> blocks.add(new AttachmentContent(id)));
        if (!text.isBlank()) {
            blocks.add(new TextContent(text));
        }
        return new UserContent(blocks);
    }

    /** HTTP 输入标识沿用 Core identifier 边界，拒绝空白、过长及控制字符。 */
    private static boolean invalidIdentifier(String value) {
        return value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}");
    }
}
