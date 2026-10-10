// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 为 active queue 与下次 admission 共用一套稳定的 Mailbox 到 Core command 映射。
 */
public final class TaskMailboxQueueCommandMapper {
    /**
     * mapper 无状态，命令的唯一事实来自已经持久化的 Mailbox 行。
     */
    private TaskMailboxQueueCommandMapper() {
        throw new AssertionError("no instances");
    }

    /**
     * 把已持久 Mailbox 行映射为可重试 MESSAGE；来源封装进入模型正文，opaque 元数据用于同事务核验而不授予权限。
     */
    public static AgentInputCommand.Enqueue toQueueCommand(TaskModels.MailboxMessage mailbox, JsonValueCodec json) {
        TaskModels.MailboxMessage message = Objects.requireNonNull(mailbox, "mailbox");
        Objects.requireNonNull(json, "json");
        if (message.state() != TaskModels.MailboxState.PENDING
            || message.kind() == TaskModels.MailboxKind.FOLLOW_UP) {
            throw new IllegalArgumentException("only pending conversation messages can enter the Core queue");
        }
        if (message.content().blocks().stream().anyMatch(SkillReferenceContent.class::isInstance)) {
            throw new IllegalArgumentException("mailbox messages cannot replace active Skills");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ja.mailbox.messageId", message.messageId());
        metadata.put("ja.mailbox.messageKind", message.kind().name());
        metadata.put("ja.mailbox.senderThreadId", message.senderThreadId());
        metadata.put("ja.mailbox.targetThreadId", message.targetThreadId());
        metadata.put("ja.mailbox.causalTurnId", Objects.toString(message.causalTurnId(), ""));
        metadata.put("ja.mailbox.senderTitle", message.senderTitle());
        metadata.put("ja.mailbox.operationKey", message.operationKey());
        metadata.put("ja.mailbox.createdAt", message.createdAt().toString());
        return new AgentInputCommand.Enqueue("taskmsg_" + digest("operation", message.messageId()),
                "input_" + digest("input", message.messageId()), AgentInputCommand.Kind.MESSAGE,
                modelContent(message, json), metadata);
    }

    /**
     * 来源与正文一起进入模型 USER 上下文，避免把另一会话的消息误当当前用户新指令；资源仍保持原授权身份。
     */
    private static UserContent modelContent(TaskModels.MailboxMessage message, JsonValueCodec json) {
        List<UserContentBlock> blocks = new ArrayList<>(message.content().blocks().stream()
                .filter(block -> !(block instanceof TextContent)).toList());
        String envelope = json.encode(JsonObjects.builder()
                .putText("kind", "external_thread_message")
                .putText("messageKind", message.kind().name())
                .putText("sourceThreadId", message.senderThreadId())
                .putText("sourceTitle", message.senderTitle())
                .putText("message", message.content().text()).build());
        blocks.add(new TextContent(envelope));
        return new UserContent(blocks);
    }

    /**
     * 将不同命令身份作 domain separation，避免一个消息 ID 被跨用途复用。
     */
    private static String digest(String purpose, String messageId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("ja.collaboration.mailbox." + purpose).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(messageId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}


