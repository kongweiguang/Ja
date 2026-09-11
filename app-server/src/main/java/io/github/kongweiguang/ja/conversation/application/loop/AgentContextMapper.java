// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.conversation.application.context.ModelContinuation;
import io.github.kongweiguang.ja.conversation.application.discovery.McpToolExposure;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;

/**
 * 在持久化消息、上下文预算模型与 Provider 请求之间转换，避免 Wire 或存储表示进入 Agent Loop。
 */
final class AgentContextMapper {
    private static final int MAX_CROSS_PROVIDER_REASONING = 1_048_576;
    private final JsonValueCodec argumentsCodec;

    /**
     * 固定结构化参数编解码协议，保证 Tool 调用写入历史后仍能按同一语义回放。
     */
    AgentContextMapper(JsonValueCodec argumentsCodec) {
        this.argumentsCodec = Objects.requireNonNull(argumentsCodec, "argumentsCodec");
    }

    /**
     * 只投影当前 Turn 及其之前的稳定历史，并重建 Tool call/result 的名称关联与提示顺序。
     */
    List<ContextMessage> fromSnapshot(ConversationRepository.ThreadSnapshot snapshot, String currentTurnId) {
        Objects.requireNonNull(snapshot, "snapshot");
        LinkedHashSet<String> allowedTurns = new LinkedHashSet<>();
        boolean found = false;
        for (ConversationRepository.TurnSnapshot turn : snapshot.turns()) {
            allowedTurns.add(turn.turnId());
            if (turn.turnId().equals(currentTurnId)) {
                found = true;
                break;
            }
        }
        if (!found) throw new AgentLoop.LoopFailure("INVALID_STATE", "current Turn is absent from history");
        List<ContextMessage> result = new ArrayList<>();
        Map<String, String> toolNames = new java.util.HashMap<>();
        long promptOrdinal = 1;
        for (String turnId : allowedTurns) {
            List<ConversationRepository.StoredMessage> turnMessages = snapshot.messages().stream()
                    .filter(message -> message.turnId().equals(turnId))
                    .sorted(java.util.Comparator.comparingLong(ConversationRepository.StoredMessage::ordinal))
                    .toList();
            for (ConversationRepository.StoredMessage message : turnMessages) {
                result.add(fromStoredMessage(message, promptOrdinal++, toolNames));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 手动压缩只在全部 Turn 终态后调用，因此投影完整永久历史而不伪造一个“当前 Turn”。
     */
    List<ContextMessage> fromCompleteSnapshot(ConversationRepository.ThreadSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.turns().stream().anyMatch(turn -> !turn.state().terminal())) {
            throw new AgentLoop.LoopFailure("THREAD_BUSY", "Thread has an active Turn");
        }
        List<ContextMessage> result = new ArrayList<>();
        Map<String, String> toolNames = new java.util.HashMap<>();
        long promptOrdinal = 1;
        for (ConversationRepository.TurnSnapshot turn : snapshot.turns()) {
            List<ConversationRepository.StoredMessage> turnMessages = snapshot.messages().stream()
                    .filter(message -> message.turnId().equals(turn.turnId()))
                    .sorted(java.util.Comparator.comparingLong(ConversationRepository.StoredMessage::ordinal))
                    .toList();
            for (ConversationRepository.StoredMessage message : turnMessages) {
                result.add(fromStoredMessage(message, promptOrdinal++, toolNames));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 把动态 System snapshot 与预算裁剪后的持久上下文组装成不可变 Provider 请求；summary
     * 已由 Prompt Session 放入 System，禁止再次生成普通 Message。工具声明从同一份保留历史派生，
     * 因而压缩估算、实际发送和重启恢复不会分别维护容易失步的 MCP 激活集合。
     */
    ModelPort.ModelRequest toModelRequest(
            ContextOrchestrator.PreparedPrompt prompt,
            ModelPort.ModelConfiguration configuration,
            AgentPromptSnapshot snapshot,
            List<AgentTool> tools,
            ModelPort.Continuation continuation,
            int round,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport) {
        NativeAttachmentBudget nativeBudget = new NativeAttachmentBudget(
                Objects.requireNonNull(nativeSupport, "nativeSupport").maxTotalBytes());
        return new ModelPort.ModelRequest(
                configuration,
                new ModelPort.PromptPayload(snapshot.systemPrompt(), snapshot.revision()),
                prompt.messages().stream().map(message -> toModelMessage(message, configuration,
                        threadId, attachments, nativeSupport, nativeBudget))
                        .filter(Objects::nonNull).toList(),
                McpToolExposure.modelTools(tools, prompt.messages(), argumentsCodec),
                continuation,
                round);
    }

    /** 本地搜索与完整执行目录一起建立，模型暴露筛选不能移除真实调用的权限与路由检查。 */
    List<AgentTool> toolCatalog(List<AgentTool> tools) {
        return McpToolExposure.catalog(tools, argumentsCodec);
    }

    /**
     * 将 Provider 可选续传令牌收敛为应用层值，阻止空引用在上下文编排中传播。
     */
    Optional<ModelContinuation> continuation(ModelPort.Continuation continuation) {
        return continuation == null
                ? Optional.empty()
                : Optional.of(new ModelContinuation(continuation.protocol(), continuation.opaqueState()));
    }

    /**
     * 按消息块重建上下文并估算预算；孤立 Tool result 会拒绝恢复，以免向模型伪造调用关系。
     */
    private ContextMessage fromStoredMessage(
            ConversationRepository.StoredMessage stored, long promptOrdinal, Map<String, String> toolNames) {
        List<ContextMessage.Block> blocks = new ArrayList<>();
        int characters = 0;
        for (ModelContent content : stored.message().content()) {
            if (content instanceof AttachmentContent attachment) {
                String manifest = attachmentManifest(attachment.attachmentId());
                blocks.add(new ContextMessage.AttachmentBlock(attachment.attachmentId()));
                characters = Math.addExact(characters, manifest.length());
            } else if (content instanceof TextContent text) {
                blocks.add(new ContextMessage.TextBlock(text.text()));
                characters = Math.addExact(characters, text.text().length());
            } else if (content instanceof ReasoningContent reasoning) {
                /* opaque 原文参与同身份请求的预算，但不进入摘要文本或普通日志。 */
                blocks.add(new ContextMessage.ReasoningBlock(reasoning));
                characters = Math.addExact(characters, reasoning.nativeJson().length());
            } else if (content instanceof WorkspaceReferenceContent reference) {
                String manifest = workspaceReferenceManifest(reference);
                blocks.add(new ContextMessage.TextBlock(manifest));
                characters = Math.addExact(characters, manifest.length());
            } else if (content instanceof SkillReferenceContent) {
                // Skill 正文只进入本条消息的动态 System；用户历史保留引用但不能把 ID 伪装成提示词。
            } else if (content instanceof ToolCallContent call) {
                String arguments = writeArguments(call.arguments());
                blocks.add(new ContextMessage.ToolCallBlock(call.callId(), call.name(), arguments));
                toolNames.put(call.callId(), call.name());
                characters = Math.addExact(characters, arguments.length());
            } else if (content instanceof ToolResultContent result) {
                String toolName = toolNames.get(result.callId());
                if (toolName == null) {
                    throw new AgentLoop.LoopFailure("INVALID_STATE", "Tool result has no prepared call");
                }
                blocks.add(new ContextMessage.ToolResultBlock(result.callId(), toolName,
                        ContextMessage.ToolOutput.full(result.content(), artifactUri(stored.messageId(), result.callId()), null,
                                result.error() ? "tool_error" : null)));
                characters = Math.addExact(characters, result.content().length());
            }
        }
        int estimatedTokens = Math.max(1, (characters + 3) / 4);
        return new ContextMessage(
                stored.messageId(),
                stored.turnId(),
                promptOrdinal,
                contextRole(stored.message().role()),
                blocks,
                estimatedTokens);
    }

    /**
     * 从不可变消息与调用身份派生线程内 artifact URI；读取端仍必须重新验证 Thread 可见性。
     */
    private static String artifactUri(String messageId, String callId) {
        return "ja-artifact://tool-result/" + messageId + "/" + callId;
    }

    /**
     * 把应用层上下文块还原为 Provider 领域消息，保持原有块顺序和 Tool 错误语义。
     */
    private ModelMessage toModelMessage(
            ContextMessage message,
            ModelPort.ModelConfiguration configuration,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport,
            NativeAttachmentBudget nativeBudget) {
        List<ModelContent> blocks = new ArrayList<>();
        String endpointFingerprint = ReasoningContent.endpointFingerprint(configuration.baseUri());
        for (ContextMessage.Block block : message.blocks()) {
            if (block instanceof ContextMessage.TextBlock text) {
                blocks.add(new TextContent(text.value()));
            } else if (block instanceof ContextMessage.AttachmentBlock attachment) {
                blocks.add(routeAttachment(attachment, configuration, threadId, attachments,
                        nativeSupport, nativeBudget));
            } else if (block instanceof ContextMessage.ToolCallBlock call) {
                blocks.add(new ToolCallContent(call.callId(), call.name(), readArguments(call.arguments())));
            } else if (block instanceof ContextMessage.ToolResultBlock result) {
                blocks.add(new ToolResultContent(result.callId(), result.output().content(),
                        result.output().error() != null));
            } else if (block instanceof ContextMessage.ReasoningBlock reasoning) {
                if (reasoning.content().matches(configuration.providerId(), configuration.modelId(),
                        configuration.api().name().toLowerCase(Locale.ROOT), configuration.model(),
                        endpointFingerprint)) {
                    blocks.add(reasoning.content());
                } else {
                    /*
                     * Provider 签名/encrypted_content 只能同身份回放；跨身份仍保留 Provider 明确公开的
                     * thinking/summary 文本，避免切换模型时上下文出现静默断层。解码失败或 redacted
                     * 块没有公开文本时安全丢弃，绝不把 opaque JSON 当普通提示词发送。
                     */
                    String publicReasoning = crossProviderReasoningText(reasoning.content());
                    if (publicReasoning != null) blocks.add(new TextContent(publicReasoning));
                }
            }
        }
        /* 模型或端点切换时丢弃不匹配的 opaque 块，绝不把签名材料发送给新身份。 */
        return blocks.isEmpty() ? null : new ModelMessage(modelRole(message.role()), blocks);
    }

    /**
     * 从 Provider 原生块提取已经公开给调用方的 thinking/summary 文本，供跨身份 assistant 历史降级。
     * 该路径只读取明确的文本字段和 Responses summary_text，永远不读取 signature、data 或
     * encrypted_content；异常按“没有可安全降级的文本”处理，避免损坏历史扩大为请求失败。
     */
    private String crossProviderReasoningText(ReasoningContent reasoning) {
        try {
            JsonValue decoded = argumentsCodec.decode(reasoning.nativeJson());
            if (!(decoded instanceof JsonObject object)) return null;
            String direct = directReasoningText(object, reasoning.wireField());
            if (!direct.isBlank()) return boundCrossProviderReasoning(direct);
            String summary = responsesSummaryText(object.get("summary"));
            return summary.isBlank() ? null : boundCrossProviderReasoning(summary);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 只接受协议中可公开展示的文本字段；redacted_thinking/data 等 opaque 字段不进入降级路径。
     */
    private static String directReasoningText(JsonObject object, String wireField) {
        if (!Set.of("thinking", "reasoning_content", "reasoning", "reasoning_text").contains(wireField)) {
            return "";
        }
        JsonValue value = object.get(wireField);
        return value instanceof JsonText text ? text.value() : "";
    }

    /**
     * Responses 的公开摘要由多个 summary_text part 组成，按 Provider 顺序用空行恢复段落边界。
     */
    private static String responsesSummaryText(JsonValue value) {
        if (!(value instanceof JsonArray summary)) return "";
        StringBuilder result = new StringBuilder();
        for (JsonValue member : summary.values()) {
            if (!(member instanceof JsonObject part)
                    || !(part.get("type") instanceof JsonText type)
                    || !"summary_text".equals(type.value())) {
                continue;
            }
            if (!(part.get("text") instanceof JsonText text) || text.value().isEmpty()) continue;
            if (!result.isEmpty()) result.append("\n\n");
            result.append(text.value());
        }
        return result.toString();
    }

    /**
     * 跨身份文本复用与 Timeline 摘要相同的 1 MiB 上限，并避免在 UTF-16 surrogate 中间截断。
     */
    private static String boundCrossProviderReasoning(String value) {
        if (value.length() <= MAX_CROSS_PROVIDER_REASONING) return value;
        int end = MAX_CROSS_PROVIDER_REASONING;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    /**
     * 模型模态与 Codec 规则必须同时允许才读取完整内容；任一门不满足就保留原 Tool 入口。
     */
    private static ModelContent routeAttachment(
            ContextMessage.AttachmentBlock attachment,
            ModelPort.ModelConfiguration configuration,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport,
            NativeAttachmentBudget nativeBudget) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(attachments, "attachments");
        boolean modelMayAcceptNative = configuration.inputModalities().contains(ModelPort.InputModality.IMAGE)
                || configuration.inputModalities().contains(ModelPort.InputModality.PDF);
        if (!modelMayAcceptNative || nativeSupport.rules().isEmpty()) {
            return new TextContent(attachmentManifest(attachment.attachmentId()));
        }
        ManagedAttachmentReader.Descriptor descriptor = attachments.inspect(attachment.attachmentId(), threadId);
        NativeAttachmentContent.Kind kind = switch (descriptor.mediaKind()) {
            case "image" -> NativeAttachmentContent.Kind.IMAGE;
            case "pdf" -> NativeAttachmentContent.Kind.PDF;
            default -> null;
        };
        if (kind == null || !configuration.inputModalities().contains(
                kind == NativeAttachmentContent.Kind.IMAGE
                        ? ModelPort.InputModality.IMAGE : ModelPort.InputModality.PDF)) {
            return new TextContent(attachmentManifest(attachment.attachmentId()));
        }
        java.util.OptionalLong maxBytes = nativeSupport.maxBytes(kind, descriptor.mediaType(), descriptor.sizeBytes());
        if (maxBytes.isEmpty() || !nativeBudget.canReserve(descriptor.sizeBytes())) {
            return new TextContent(attachmentManifest(attachment.attachmentId()));
        }
        ManagedAttachmentReader.NativeReadResult nativeRead = attachments.readNative(
                new ManagedAttachmentReader.NativeReadRequest(
                        attachment.attachmentId(), threadId, maxBytes.orElseThrow()));
        ManagedAttachmentReader.Descriptor resolved = nativeRead.descriptor();
        if (!descriptor.equals(resolved)) {
            throw new IllegalStateException("managed attachment descriptor changed during native routing");
        }
        nativeBudget.reserve(descriptor.sizeBytes());
        return new NativeAttachmentContent(descriptor.attachmentId(), kind, descriptor.displayName(),
                descriptor.mediaType(), descriptor.sizeBytes(), nativeRead.base64Data());
    }

    /** 统一生成安全 fallback，使不支持的媒体不会被伪装成已原生理解。 */
    private static String attachmentManifest(String attachmentId) {
        return "Attachment " + attachmentId + " is available through the read_attachment tool.";
    }

    /**
     * Workspace 引用只告诉模型路径与类型，并明确正文未预加载；是否读取由后续真实 Tool 决策。
     */
    private static String workspaceReferenceManifest(WorkspaceReferenceContent reference) {
        return "Workspace reference [" + reference.kind().name().toLowerCase(java.util.Locale.ROOT)
                + "] " + reference.relativePath() + " (content not preloaded).";
    }

    /** 单个冻结请求共享 Codec 总量预算，超过时按附件顺序稳定回退到 Tool。 */
    private static final class NativeAttachmentBudget {
        private final long maximum;
        private long used;

        /** 总量来自 Codec 能力快照，application 不能自行扩大。 */
        private NativeAttachmentBudget(long maximum) {
            this.maximum = maximum;
        }

        /** 使用减法判断避免大值相加回绕。 */
        private boolean canReserve(long sizeBytes) {
            return sizeBytes > 0 && used <= maximum - sizeBytes;
        }

        /** 只在完整读取与描述复核成功后计入，失败不得污染后续路由决定。 */
        private void reserve(long sizeBytes) {
            if (!canReserve(sizeBytes)) throw new IllegalStateException("native attachment budget exceeded");
            used += sizeBytes;
        }
    }

    /**
     * 使用唯一编解码端口序列化参数，避免各 Provider 或存储适配器产生不同 JSON 形态。
     */
    private String writeArguments(JsonObject arguments) {
        return Objects.requireNonNull(argumentsCodec.encode(arguments), "encoded arguments");
    }

    /**
     * 解码历史中的 Tool 参数并冻结结果，防止模型请求提交后被调用方继续修改。
     */
    private JsonObject readArguments(String arguments) {
        return Objects.requireNonNull(argumentsCodec.decodeObject(arguments), "decoded arguments");
    }

    /**
     * 将可持久化消息角色映射到上下文角色，显式限制历史中允许出现的角色集合。
     */
    private static ContextMessage.Role contextRole(ModelRole role) {
        return switch (role) {
            case USER -> ContextMessage.Role.USER;
            case ASSISTANT -> ContextMessage.Role.ASSISTANT;
            case TOOL -> ContextMessage.Role.TOOL;
        };
    }

    /**
     * 将上下文角色映射回模型角色；SYSTEM 必须进入 instructions，不能伪装为普通消息。
     */
    private static ModelRole modelRole(ContextMessage.Role role) {
        return switch (role) {
            case USER -> ModelRole.USER;
            case ASSISTANT -> ModelRole.ASSISTANT;
            case TOOL -> ModelRole.TOOL;
            case SYSTEM -> throw new AgentLoop.LoopFailure(
                    "INVALID_STATE", "SYSTEM context must be rendered as instructions");
        };
    }

    /**
     * 复制续传协议与不透明状态，保持应用层不解析 Provider 私有恢复令牌。
     */
    ModelPort.Continuation toModelContinuation(ModelContinuation continuation) {
        return new ModelPort.Continuation(continuation.protocol(), continuation.opaqueState());
    }
}
