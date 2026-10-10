// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fork 冻结历史中附件身份的唯一重映射边界，正文和来源身份仍由 Seed 原样保留。
 */
public final class InitialContextSeedAttachmentMapper {
    /**
     * 防止建立第二个可变状态所有者；每次映射只依赖调用方提供的冻结 Seed 与授权映射。
     */
    private InitialContextSeedAttachmentMapper() {
    }

    /**
     * 按消息块出现顺序枚举附件与 Tool 图片引用，稳定去重后供来源 Thread 授权查询。
     */
    public static List<String> attachmentIds(InitialContextSeed seed) {
        InitialContextSeed value = Objects.requireNonNull(seed, "seed");
        return attachmentIds(value.messages());
    }

    /**
     * 同时收集完整原始前缀与压缩后有效 Seed 的附件句柄，避免仅复制当前可见部分而丢失后续分叉所需媒体。
     *
     * @author kongweiguang
     */
    public static List<String> attachmentIds(List<ContextMessage> messages) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ContextMessage message : Objects.requireNonNull(messages, "messages")) {
            for (ContextMessage.Block block : message.blocks()) {
                if (block instanceof ContextMessage.AttachmentBlock attachment) {
                    ids.add(attachment.attachmentId());
                } else if (block instanceof ContextMessage.ToolResultBlock result) {
                    result.output().media().stream().map(ToolMediaReference::attachmentId).forEach(ids::add);
                }
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 仅依据来源已授权的完整映射重建 Seed；任何遗漏或目标身份碰撞都阻断 Fork。
     */
    public static InitialContextSeed remap(InitialContextSeed seed, Map<String, String> attachmentIdMap) {
        InitialContextSeed value = Objects.requireNonNull(seed, "seed");
        Map<String, String> mapping = Map.copyOf(Objects.requireNonNull(attachmentIdMap, "attachmentIdMap"));
        Set<String> targets = new HashSet<>();
        for (String sourceId : attachmentIds(value)) {
            String targetId = requiredMapping(mapping, sourceId);
            if (!targets.add(targetId)) {
                throw new IllegalArgumentException("initial seed attachment mapping is not one-to-one");
            }
        }
        List<ContextMessage> messages = remapMessages(value.messages(), mapping);
        return new InitialContextSeed(value.sourceIdentity(), value.sourceOrdinal(), messages, value.summary());
    }

    /**
     * 使用同一目标 Thread 授权映射重建附件和 Tool 图片引用，未映射的来源句柄由下层失败关闭。
     *
     * @author kongweiguang
     */
    public static List<ContextMessage> remapMessages(List<ContextMessage> messages, Map<String, String> attachmentIdMap) {
        Map<String, String> mapping = Map.copyOf(Objects.requireNonNull(attachmentIdMap, "attachmentIdMap"));
        return Objects.requireNonNull(messages, "messages").stream()
                .map(message -> remapMessage(message, mapping)).toList();
    }

    /**
     * 重建消息仅替换受管附件 ID，消息、Turn、ordinal 与工具输出的其它事实保持冻结值。
     */
    public static ContextMessage remapMessage(ContextMessage message, Map<String, String> mapping) {
        List<ContextMessage.Block> blocks = new ArrayList<>(message.blocks().size());
        for (ContextMessage.Block block : message.blocks()) {
            if (block instanceof ContextMessage.AttachmentBlock attachment) {
                blocks.add(new ContextMessage.AttachmentBlock(requiredMapping(mapping, attachment.attachmentId())));
            } else if (block instanceof ContextMessage.ToolResultBlock result) {
                ContextMessage.ToolOutput output = result.output();
                List<ToolMediaReference> media = output.media().stream()
                        .map(reference -> remapMedia(reference, mapping)).toList();
                blocks.add(new ContextMessage.ToolResultBlock(result.callId(), result.name(),
                        new ContextMessage.ToolOutput(output.content(), output.artifactReference(), output.exitCode(),
                                output.error(), output.promptProjection(), media)));
            } else {
                blocks.add(block);
            }
        }
        return new ContextMessage(message.messageId(), message.turnId(), message.ordinal(), message.role(),
                blocks, message.estimatedTokens());
    }

    /**
     * 重建媒体元数据并只替换授权句柄，不能把旧 Thread 的附件 ID 泄漏给 child。
     */
    private static ToolMediaReference remapMedia(ToolMediaReference reference, Map<String, String> mapping) {
        return new ToolMediaReference(requiredMapping(mapping, reference.attachmentId()), reference.mediaType(),
                reference.displayName(), reference.sizeBytes());
    }

    /**
     * 映射必须来自来源附件 owner 的授权结果；缺失或空目标不能退回原 ID。
     */
    private static String requiredMapping(Map<String, String> mapping, String sourceId) {
        String targetId = mapping.get(sourceId);
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("initial seed attachment mapping is incomplete");
        }
        return targetId;
    }
}
