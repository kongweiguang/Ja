// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 一条用户输入的规范内容序列；首轮、队列、历史与恢复共用同一值对象。 */
public record UserContent(List<UserContentBlock> blocks) {
    /**
     * 输入必须已按上下文引用、附件、单个正文块排列，避免接收端静默重排掩盖跨端签名漂移；
     * Skill 单独存在不能发送，因为它没有用户问题或可供 Agent 处理的资源。
     */
    public UserContent {
        List<UserContentBlock> source = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (source.isEmpty() || source.size() > 64) throw new IllegalArgumentException("invalid user content");
        List<UserContentBlock> contexts = new ArrayList<>();
        List<UserContentBlock> attachments = new ArrayList<>();
        List<UserContentBlock> texts = new ArrayList<>();
        HashSet<String> identities = new HashSet<>();
        int actionable = 0;
        for (UserContentBlock block : source) {
            Objects.requireNonNull(block, "user content block");
            if (block instanceof WorkspaceReferenceContent workspace) {
                if (!identities.add("workspace:" + workspace.workspaceId() + ':' + workspace.relativePath())) {
                    throw new IllegalArgumentException("duplicate workspace reference");
                }
                contexts.add(workspace);
                actionable++;
            } else if (block instanceof SkillReferenceContent skill) {
                if (!identities.add("skill:" + skill.skillId())) {
                    throw new IllegalArgumentException("duplicate skill reference");
                }
                contexts.add(skill);
            } else if (block instanceof AttachmentContent attachment) {
                if (!identities.add("attachment:" + attachment.attachmentId()) || attachments.size() >= 10) {
                    throw new IllegalArgumentException("invalid attachment references");
                }
                attachments.add(attachment);
                actionable++;
            } else if (block instanceof TextContent text) {
                if (!texts.isEmpty()) throw new IllegalArgumentException("multiple text blocks are not allowed");
                texts.add(text);
                actionable++;
            }
        }
        if (actionable == 0) throw new IllegalArgumentException("user content requires a question or resource");
        contexts.addAll(attachments);
        contexts.addAll(texts);
        if (!contexts.equals(source)) {
            throw new IllegalArgumentException("user content blocks are not in canonical order");
        }
        blocks = source;
    }

    /** 返回正文供标题等纯文本消费者使用；结构化引用不会伪装成已读取内容。 */
    public String text() {
        return blocks.stream().filter(TextContent.class::isInstance).map(TextContent.class::cast)
                .map(TextContent::text).findFirst().orElse("");
    }

    /** 返回附件 ID 快照，受管读取仍须按 Thread 重新鉴权。 */
    public List<String> attachmentIds() {
        return blocks.stream().filter(AttachmentContent.class::isInstance).map(AttachmentContent.class::cast)
                .map(AttachmentContent::attachmentId).toList();
    }

    /** 返回本条消息显式选择的 Skill ID，顺序与 Chip 选择顺序一致。 */
    public List<String> skillIds() {
        return blocks.stream().filter(SkillReferenceContent.class::isInstance)
                .map(SkillReferenceContent.class::cast).map(SkillReferenceContent::skillId).toList();
    }

    /** 返回 Workspace 引用供权威 owner 在每个执行边界重新校验。 */
    public List<WorkspaceReferenceContent> workspaceReferences() {
        return blocks.stream().filter(WorkspaceReferenceContent.class::isInstance)
                .map(WorkspaceReferenceContent.class::cast).toList();
    }
}
