// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInput;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import java.util.Map.Entry;

/**
 * 将准入键与所有会改变首次执行行为的输入按规范顺序绑定。
 */
public final class AgentRequestFingerprint {
    private static final int VERSION = 1;

    /**
     * 禁止工具类实例化，使摘要格式集中维护且保持确定性。
     */
    private AgentRequestFingerprint() {
    }

    /**
     * 将首次用户准入语义与持久化前选定的精确运行时租约共同绑定。
     */
    public static PreparedRequest prepare(AgentRequest request, ModelPort.ModelConfiguration model,
                                          String runtimeBindingFingerprint) {
        Objects.requireNonNull(request, "request");
        String requestFingerprint = requestFingerprint(request);
        AgentStore.OperationIdentity operation = prepareAdmissionOperation(request.identity(), "agent.submit",
                request.operationKey(), requestFingerprint, model, runtimeBindingFingerprint, true);
        String inputMessageId = messageId(request.identity(), operation.operationKey());
        return new PreparedRequest(inputMessageId, operation, operation.fingerprint(), requestFingerprint);
    }

    /**
     * 同时固定隐藏指令、所选历史路径与解析后的运行时，避免续跑在重试中悄然换上下文。
     */
    public static PreparedInternalRequest prepareInternal(AgentInternalRequest request,
                                                          ModelPort.ModelConfiguration model, String runtimeBindingFingerprint) {
        Objects.requireNonNull(request, "request");
        String requestFingerprint = internalRequestFingerprint(request);
        AgentStore.OperationIdentity operation = prepareAdmissionOperation(request.identity(), "agent.internal",
                request.operationKey(), requestFingerprint, model, runtimeBindingFingerprint, false);
        return new PreparedInternalRequest(operation, operation.fingerprint(), requestFingerprint);
    }

    /**
     * 将独立压缩执行的历史、模型和运行时能力绑定到同一幂等准入。
     */
    public static PreparedCompactionRequest prepareCompaction(AgentCompactionRequest request,
                                                              ModelPort.ModelConfiguration model, String runtimeBindingFingerprint) {
        Objects.requireNonNull(request, "request");
        String requestFingerprint = compactionRequestFingerprint(request);
        AgentStore.OperationIdentity operation = prepareAdmissionOperation(request.identity(), "agent.compaction",
                request.operationKey(), requestFingerprint, model, runtimeBindingFingerprint, false);
        return new PreparedCompactionRequest(operation, operation.fingerprint(), requestFingerprint);
    }

    /**
     * 解析可能已变更的新运行时前先核对租户范围内的既有回执。
     */
    public static void requireSameSubmittedRequest(AgentStore.OperationReceipt receipt,
                                                   AgentRequest request) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(request, "request");
        AgentIdentity identity = request.identity();
        JsonObject body = readReceiptForOperation(receipt, identity, "agent.submit",
                request.operationKey(), "request");
        if (!requestFingerprint(request).equals(receiptText(body, "requestFingerprint"))) {
            throw new IllegalArgumentException("operation key was already used for a different request");
        }
    }

    /**
     * 在解析新模型或密钥前核对旧内部准入回执，允许相同指令在配置更新后安全重连。
     */
    public static void requireSameInternalRequest(AgentStore.OperationReceipt receipt,
                                                  AgentInternalRequest request) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(request, "request");
        AgentIdentity identity = request.identity();
        JsonObject body = readReceiptForOperation(receipt, identity, "agent.internal",
                request.operationKey(), "internal request");
        if (!internalRequestFingerprint(request).equals(receiptText(body, "requestFingerprint"))) {
            throw new IllegalArgumentException("operation key was already used for a different internal request");
        }
    }

    /**
     * 在路由解析前验证重复压缩准入，避免重试受当前模型配置变化影响。
     */
    public static void requireSameCompactionRequest(AgentStore.OperationReceipt receipt,
                                                     AgentCompactionRequest request) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(request, "request");
        AgentIdentity identity = request.identity();
        JsonObject body = readReceiptForOperation(receipt, identity, "agent.compaction",
                request.operationKey(), "compaction request");
        if (!compactionRequestFingerprint(request).equals(receiptText(body, "requestFingerprint"))) {
            throw new IllegalArgumentException("operation key was already used for a different compaction request");
        }
    }

    /**
     * 提供不依赖 Provider 配置的请求语义摘要，供本地活动句柄去重。
     */
    public static String requestFingerprint(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        return hashRequest(request);
    }

    /**
     * 使用完整调用方身份限定回执查询，且在解析含密钥运行时之前执行。
     */
    public static String submissionOperationKey(AgentIdentity identity, String operationKey) {
        Objects.requireNonNull(identity, "identity");
        return operationKey(identity, Objects.requireNonNull(operationKey, "operationKey"));
    }

    /**
     * 将内部准入键与用户消息准入键分域，允许业务以同一原始 key 做两类明确命令。
     */
    public static String internalSubmissionOperationKey(AgentIdentity identity, String operationKey) {
        Objects.requireNonNull(identity, "identity");
        return operationKey(identity, Objects.requireNonNull(operationKey, "operationKey"));
    }

    /**
     * 使用独立准入域查回压缩重试，不与普通对话或内部续跑冲突。
     */
    public static String compactionSubmissionOperationKey(AgentIdentity identity, String operationKey) {
        Objects.requireNonNull(identity, "identity");
        return operationKey(identity, ContractChecks.identifier(operationKey, "operationKey"));
    }

    /**
     * 为便捷追加输入生成可重试稳定 ID；宿主显式传入 ID 时应直接保留其业务关联。
     */
    public static String queuedInputId(AgentIdentity identity, String operationKey) {
        Objects.requireNonNull(identity, "identity");
        String key = ContractChecks.identifier(operationKey, "operationKey");
        return "input_" + hashStrings(identity.tenantId(), identity.threadId(), identity.turnId(),
                identity.userId(), "agent.input.queue", key);
    }

    /**
     * 从业务键和精确结构化内容派生可重试的追加输入身份。
     */
    public static PreparedInput prepareInput(AgentIdentity identity, AgentInput input) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(input, "input");
        String key = operationKey(identity, input.operationKey());
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, "agent.input.append");
            write(canonical, input.operationKey());
            writeUserContent(canonical, input.content());
        });
        String messageId = messageId(identity, key);
        return new PreparedInput(messageId, operation(identity, key, "agent.input.append", fingerprint));
    }

    /**
     * 为队列变更生成稳定 Store 幂等身份，且把条目 CAS 与宿主回执元数据一起纳入摘要。
     */
    public static PreparedQueueMutation prepareQueueMutation(AgentIdentity identity, AgentInputCommand command) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(command, "command");
        String operationType = "agent.input.queue";
        String key = operationKey(identity, command.operationKey());
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, operationType);
            write(canonical, command.operationKey());
            if (command instanceof AgentInputCommand.Enqueue enqueue) {
                write(canonical, "ENQUEUE");
                write(canonical, enqueue.inputId());
                write(canonical, enqueue.kind().name());
                writeUserContent(canonical, enqueue.content());
                writeMetadata(canonical, enqueue.hostMetadata());
            } else if (command instanceof AgentInputCommand.Update update) {
                write(canonical, "UPDATE");
                write(canonical, update.inputId());
                canonical.writeLong(update.expectedInputRevision());
                writeUserContent(canonical, update.content());
                writeMetadata(canonical, update.hostMetadata());
            } else if (command instanceof AgentInputCommand.Delete delete) {
                write(canonical, "DELETE");
                write(canonical, delete.inputId());
                canonical.writeLong(delete.expectedInputRevision());
                writeMetadata(canonical, delete.hostMetadata());
            } else if (command instanceof AgentInputCommand.Prioritize prioritize) {
                write(canonical, "PRIORITIZE");
                write(canonical, prioritize.inputId());
                canonical.writeLong(prioritize.expectedInputRevision());
                writeMetadata(canonical, prioritize.hostMetadata());
            } else {
                throw new IllegalArgumentException("unsupported input queue command");
            }
        });
        String inputId = switch (command) {
            case AgentInputCommand.Enqueue enqueue -> enqueue.inputId();
            case AgentInputCommand.Update update -> update.inputId();
            case AgentInputCommand.Delete delete -> delete.inputId();
            case AgentInputCommand.Prioritize prioritize -> prioritize.inputId();
        };
        JsonObject receipt = JsonObjects.builder().putNumber("schemaVersion", 1)
                .putText("tenantId", identity.tenantId()).putText("threadId", identity.threadId())
                .putText("turnId", identity.turnId()).putText("userId", identity.userId())
                .putText("inputId", inputId).putText("requestFingerprint", fingerprint).build();
        return new PreparedQueueMutation(inputId,
                new AgentStore.OperationIdentity(key, operationType, fingerprint, receipt));
    }

    /**
     * 排序宿主文本元数据，确保不同 Map 实现产生相同内容签名。
     */
    private static void writeMetadata(DataOutputStream canonical, java.util.Map<String, String> metadata)
            throws IOException {
        canonical.writeInt(metadata.size());
        for (Entry<String, String> entry : metadata.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            write(canonical, entry.getKey());
            write(canonical, entry.getValue());
        }
    }

    /**
     * 在服务结算持久等待点前，将回答绑定到目标执行和等待身份。
     */
    public static AgentStore.OperationIdentity prepareAnswer(AgentIdentity identity, AgentAnswer answer) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(answer, "answer");
        String key = operationKey(identity, answer.operationKey());
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, "agent.wait.answer");
            write(canonical, answer.operationKey());
            write(canonical, answer.interactionId());
            canonical.writeLong(answer.expectedInteractionRevision());
            canonical.writeInt(answer.answers().size());
            for (InteractionAnswer value : answer.answers()) {
                writeAnswer(canonical, value);
            }
            writeMetadata(canonical, answer.hostMetadata());
        });
        return operation(identity, key, "agent.wait.answer", fingerprint);
    }

    /**
     * 按请求版本关闭交互等待，明确区分取消与替代并绑定 Ja 幂等投影元数据。
     */
    public static AgentStore.OperationIdentity prepareWaitClose(AgentIdentity identity, String operationKey,
                                                                String interactionId, long expectedInteractionRevision, InteractionStatus nextStatus,
                                                                java.util.Map<String, String> hostMetadata) {
        Objects.requireNonNull(identity, "identity");
        String providedOperationKey = ContractChecks.identifier(operationKey, "operationKey");
        String wait = ContractChecks.identifier(interactionId, "interactionId");
        if (expectedInteractionRevision < 0) {
            throw new IllegalArgumentException("invalid expected wait revision");
        }
        if (nextStatus != InteractionStatus.CANCELLED && nextStatus != InteractionStatus.SUPERSEDED) {
            throw new IllegalArgumentException("wait can only be cancelled or superseded");
        }
        String scopedKey = operationKey(identity, providedOperationKey);
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, "agent.wait.close");
            write(canonical, providedOperationKey);
            write(canonical, wait);
            canonical.writeLong(expectedInteractionRevision);
            write(canonical, nextStatus.name());
            writeMetadata(canonical, hostMetadata);
        });
        return operation(identity, scopedKey, "agent.wait.close", fingerprint);
    }

    /**
     * 为取消生成重试身份，使取消意图先持久化、再通知共用作用域。
     */
    public static AgentStore.OperationIdentity prepareCancellation(
            AgentIdentity identity, String operationKey, String reason) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 512
            || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid cancellation reason");
        }
        String scopedKey = operationKey(identity, operationKey);
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, "agent.execution.cancel");
            write(canonical, operationKey);
            write(canonical, reason);
        });
        return operation(identity, scopedKey, "agent.execution.cancel", fingerprint);
    }

    /**
     * 为宿主暂停保留非终态事实，原因码参与幂等摘要以拒绝语义不同的键复用。
     */
    public static AgentStore.OperationIdentity prepareSuspend(AgentIdentity identity, String operationKey,
                                                              String reasonCode) {
        Objects.requireNonNull(identity, "identity");
        String providedOperationKey = ContractChecks.identifier(operationKey, "operationKey");
        String reason = ContractChecks.identifier(reasonCode, "reasonCode");
        String scopedKey = operationKey(identity, providedOperationKey);
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, "agent.execution.suspend");
            write(canonical, providedOperationKey);
            write(canonical, reason);
        });
        return operation(identity, scopedKey, "agent.execution.suspend", fingerprint);
    }

    /**
     * 为工具恢复命令绑定原调用、乐观版本与授权身份，防止相同幂等键改写裁决。
     */
    public static AgentStore.OperationIdentity prepareToolRecovery(AgentIdentity identity, String operationKey,
                                                                   String recoveryId, String callId, long expectedRecoveryRevision, RecoveryCommand.Decision decision,
                                                                   String authorizationId) {
        Objects.requireNonNull(identity, "identity");
        String providedOperationKey = ContractChecks.identifier(operationKey, "operationKey");
        String recovery = ContractChecks.identifier(recoveryId, "recoveryId");
        String call = ContractChecks.identifier(callId, "callId");
        if (expectedRecoveryRevision < 1) {
            throw new IllegalArgumentException("invalid expected recovery revision");
        }
        Objects.requireNonNull(decision, "decision");
        String authorization = ContractChecks.identifier(authorizationId, "authorizationId");
        String scopedKey = operationKey(identity, providedOperationKey);
        String fingerprint = hashCanonical(canonical -> {
            writeIdentity(canonical, identity);
            write(canonical, "agent.tool.recovery");
            write(canonical, providedOperationKey);
            write(canonical, recovery);
            write(canonical, call);
            canonical.writeLong(expectedRecoveryRevision);
            write(canonical, decision.name());
            write(canonical, authorization);
        });
        return operation(identity, scopedKey, "agent.tool.recovery", fingerprint);
    }

    /**
     * 为 Engine 内部关机等控制操作生成稳定幂等键，不依赖宿主传入的临时操作文本。
     */
    public static String systemOperationKey(AgentIdentity identity, String operationType) {
        Objects.requireNonNull(identity, "identity");
        String type = ContractChecks.identifier(operationType, "operationType");
        return operationKey(identity, type);
    }

    /**
     * 散列正文、终答 Schema、选择与有序首轮队列；同一操作键不能替换执行约束。
     */
    private static String hashRequest(AgentRequest request) {
        return hashCanonical(canonical -> {
            canonical.writeInt(VERSION);
            writeIdentity(canonical, request.identity());
            write(canonical, request.operationKey());
            writeUserContent(canonical, request.input());
            writeModelOverrideAndMetadata(canonical, request.modelOverride(), request.contextAttributes());
            writeHistory(canonical, request.history());
            writeOptionalSchema(canonical, request.outputSchema());
            writeInitialQueuedInputs(canonical, request.initialQueuedInputs());
            writeInitialContextSeed(canonical, request.initialContextSeed());
        });
    }

    /**
     * 用类型标记和排序对象键规范编码完整 Schema，使重试身份不依赖 Map 插入顺序。
     */
    private static void writeOptionalSchema(DataOutputStream target,
                                            java.util.Optional<JsonObject> schema) throws IOException {
        target.writeBoolean(schema.isPresent());
        if (schema.isPresent()) {
            writeJsonValue(target, schema.orElseThrow());
        }
    }

    /**
     * 递归编码 sealed JSON 值，保留数组顺序、类型和精确十进制语义。
     */
    private static void writeJsonValue(DataOutputStream target, JsonValue value) throws IOException {
        if (value instanceof JsonObject object) {
            write(target, "object");
            java.util.TreeMap<String, JsonValue> ordered = new java.util.TreeMap<>(object.members());
            target.writeInt(ordered.size());
            for (Entry<String, JsonValue> entry : ordered.entrySet()) {
                write(target, entry.getKey());
                writeJsonValue(target, entry.getValue());
            }
        } else if (value instanceof JsonArray array) {
            write(target, "array");
            target.writeInt(array.values().size());
            for (JsonValue child : array.values()) {
                writeJsonValue(target, child);
            }
        } else if (value instanceof JsonText text) {
            write(target, "text");
            write(target, text.value());
        } else if (value instanceof JsonNumber number) {
            write(target, "number");
            write(target, number.value().stripTrailingZeros().toPlainString());
        } else if (value instanceof JsonBoolean bool) {
            write(target, bool.value() ? "true" : "false");
        } else if (value == JsonNull.INSTANCE) {
            write(target, "null");
        } else {
            throw new IllegalArgumentException("unsupported JSON Schema value");
        }
    }

    /**
     * 将非空初始队列作为末尾版本字段编码，顺序和宿主来源都会改变准入重试指纹。
     */
    private static void writeInitialQueuedInputs(DataOutputStream canonical,
                                                 List<AgentInputCommand.Enqueue> queuedInputs) throws IOException {
        if (queuedInputs.isEmpty()) {
            return;
        }
        write(canonical, "initialQueuedInputs/v1");
        canonical.writeInt(queuedInputs.size());
        for (AgentInputCommand.Enqueue queued : queuedInputs) {
            write(canonical, queued.operationKey());
            write(canonical, queued.inputId());
            write(canonical, queued.kind().name());
            writeUserContent(canonical, queued.content());
            writeMetadata(canonical, queued.hostMetadata());
        }
    }

    /**
     * 对内部指令、Seed 和有序 MESSAGE 批次做规范编码；受理时间不影响同键重试身份。
     */
    public static String internalRequestFingerprint(AgentInternalRequest request) {
        Objects.requireNonNull(request, "request");
        return hashCanonical(canonical -> {
            canonical.writeInt(VERSION);
            writeIdentity(canonical, request.identity());
            write(canonical, "agent.internal");
            write(canonical, request.operationKey());
            writeInstruction(canonical, request.instruction());
            writeHistory(canonical, request.history());
            writeModelOverrideAndMetadata(canonical, request.modelOverride(), request.contextAttributes());
            writeInitialContextSeed(canonical, request.initialContextSeed());
            writeInitialQueuedInputs(canonical, request.initialQueuedInputs());
        });
    }

    /**
     * 将 Seed 的来源、完整消息块和摘要事实绑定到幂等键，避免同键切换冻结父上下文。
     */
    private static void writeInitialContextSeed(DataOutputStream target,
                                                java.util.Optional<InitialContextSeed> seed) throws IOException {
        if (seed.isEmpty()) {
            return;
        }
        InitialContextSeed value = seed.orElseThrow();
        write(target, "initialContextSeed/v1");
        write(target, value.sourceIdentity().tenantId());
        write(target, value.sourceIdentity().threadId());
        write(target, value.sourceIdentity().turnId());
        target.writeLong(value.sourceOrdinal());
        target.writeInt(value.messages().size());
        for (ContextMessage message : value.messages()) {
            writeContextMessage(target, message);
        }
        target.writeBoolean(value.summary().isPresent());
        if (value.summary().isPresent()) {
            writeSummaryDocument(target, value.summary().orElseThrow());
        }
    }

    /**
     * 逐块编码 Seed 消息，尤其保留 reasoning 原生负载和完整工具结果的指纹语义。
     */
    private static void writeContextMessage(DataOutputStream target, ContextMessage message) throws IOException {
        write(target, message.messageId());
        write(target, message.turnId());
        target.writeLong(message.ordinal());
        write(target, message.role().name());
        target.writeInt(message.estimatedTokens());
        target.writeInt(message.blocks().size());
        for (ContextMessage.Block block : message.blocks()) {
            if (block instanceof ContextMessage.TextBlock(String value)) {
                write(target, "text");
                write(target, value);
            } else if (block instanceof ContextMessage.AttachmentBlock(String attachmentId)) {
                write(target, "attachment");
                write(target, attachmentId);
            } else if (block instanceof ContextMessage.ReasoningBlock(
                    io.github.kongweiguang.ja.agent.domain.model.ReasoningContent content
            )) {
                write(target, "reasoning");
                write(target, content.providerId());
                write(target, content.modelId());
                write(target, content.api());
                write(target, content.upstreamModel());
                write(target, content.endpointFingerprint());
                write(target, content.wireField());
                write(target, content.nativeJson());
            } else if (block instanceof ContextMessage.ToolCallBlock(String id, String name1, String arguments)) {
                write(target, "tool_call");
                write(target, id);
                write(target, name1);
                write(target, arguments);
            } else if (block instanceof ContextMessage.ToolResultBlock(
                    String callId, String name, ContextMessage.ToolOutput output
            )) {
                write(target, "tool_result");
                write(target, callId);
                write(target, name);
                write(target, output.content());
                writeNullableString(target, output.artifactReference());
                writeNullableInteger(target, output.exitCode());
                writeNullableString(target, output.error());
                target.writeBoolean(output.promptProjection());
                target.writeInt(output.media().size());
                for (io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference reference : output.media()) {
                    write(target, reference.attachmentId());
                    write(target, reference.mediaType());
                    write(target, reference.displayName());
                    target.writeLong(reference.sizeBytes());
                }
            } else {
                throw new IllegalArgumentException("unsupported initial context block");
            }
        }
    }

    /**
     * 固定每个摘要分区顺序和退休证据，保证父背景任何变化均改变准入指纹。
     */
    private static void writeSummaryDocument(DataOutputStream target, SummaryDocument summary) throws IOException {
        writeFacts(target, "goals", summary.goals());
        writeFacts(target, "constraints", summary.constraints());
        writeFacts(target, "completedProgress", summary.completedProgress());
        writeFacts(target, "currentProgress", summary.currentProgress());
        writeFacts(target, "blockers", summary.blockers());
        writeFacts(target, "decisions", summary.decisions());
        writeFacts(target, "nextSteps", summary.nextSteps());
        writeFacts(target, "criticalFacts", summary.criticalFacts());
        writeFacts(target, "files", summary.files());
        writeFacts(target, "pendingEffects", summary.pendingEffects());
        target.writeInt(summary.retirements().size());
        for (SummaryDocument.Retirement retirement : summary.retirements()) {
            write(target, retirement.text());
            target.writeLong(retirement.sourceOrdinal());
            write(target, retirement.status().name());
        }
    }

    /**
     * 以分区标签和有序事实长度编码摘要，避免空列表边界与相邻分区混淆。
     */
    private static void writeFacts(DataOutputStream target, String category,
                                   List<SummaryDocument.Fact> facts) throws IOException {
        write(target, category);
        target.writeInt(facts.size());
        for (SummaryDocument.Fact fact : facts) {
            write(target, fact.text());
            target.writeLong(fact.sourceOrdinal());
        }
    }

    /**
     * 将压缩的历史选择和宿主路由属性纳入规范摘要，受理时间只保存为事实而不参与重试比较。
     */
    public static String compactionRequestFingerprint(AgentCompactionRequest request) {
        Objects.requireNonNull(request, "request");
        return hashCanonical(canonical -> {
            canonical.writeInt(VERSION);
            writeIdentity(canonical, request.identity());
            write(canonical, "agent.compaction");
            write(canonical, request.operationKey());
            writeHistory(canonical, request.history());
            writeModelOverrideAndMetadata(canonical, request.modelOverride(), request.contextAttributes());
        });
    }

    /**
     * 所有准入型请求沿相同字段顺序编码可选模型和宿主属性，保证 Map 顺序不影响幂等指纹。
     */
    private static void writeModelOverrideAndMetadata(DataOutputStream target,
                                                      java.util.Optional<ModelPort.ModelConfiguration> modelOverride,
                                                      java.util.Map<String, String> metadata) throws IOException {
        target.writeBoolean(modelOverride.isPresent());
        if (modelOverride.isPresent()) {
            writeModel(target, modelOverride.orElseThrow());
        }
        writeMetadata(target, metadata);
    }

    /**
     * 将历史选择各模式按同一字段顺序编码，不让 Map 顺序或对象哈希影响重试识别。
     */
    private static void writeHistory(DataOutputStream target,
                                     io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection history)
            throws IOException {
        write(target, history.mode().name());
        target.writeInt(history.visibleTurnIds().size());
        for (String turnId : history.visibleTurnIds()) {
            write(target, turnId);
        }
        target.writeBoolean(history.expectedThreadRevision() != null);
        if (history.expectedThreadRevision() != null) {
            target.writeLong(history.expectedThreadRevision());
        }
    }

    /**
     * 仅把 ContextInstruction 的稳定 ID 与正文加入摘要，绝不把它写入准入回执。
     */
    private static void writeInstruction(DataOutputStream target, ContextInstruction instruction) throws IOException {
        write(target, instruction.messageId());
        write(target, instruction.text());
    }

    /**
     * 将新执行绑定到选定模型及运行时能力，同时不持久化任何凭据。
     */
    private static String executionFingerprint(String requestFingerprint, ModelPort.ModelConfiguration model,
                                               String runtimeBindingFingerprint) {
        return hashCanonical(canonical -> {
            canonical.writeInt(VERSION);
            write(canonical, requestFingerprint);
            writeModel(canonical, model);
            write(canonical, runtimeBindingFingerprint);
        });
    }

    /**
     * 将三类新执行统一成精确模型绑定的 Store 操作身份，用户输入才额外写消息回执。
     */
    private static AgentStore.OperationIdentity prepareAdmissionOperation(AgentIdentity identity,
                                                                          String operationType, String operationKey, String requestFingerprint,
                                                                          ModelPort.ModelConfiguration model, String runtimeBindingFingerprint, boolean createsUserMessage) {
        Objects.requireNonNull(model, "model");
        if (runtimeBindingFingerprint == null || !runtimeBindingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid runtime binding fingerprint");
        }
        String scopedKey = operationKey(identity, operationKey);
        String fingerprint = executionFingerprint(requestFingerprint, model, runtimeBindingFingerprint);
        String inputMessageId = createsUserMessage ? messageId(identity, scopedKey) : null;
        return new AgentStore.OperationIdentity(scopedKey, operationType, fingerprint,
                receipt(identity, requestFingerprint, inputMessageId));
    }

    /**
     * 将模型选择与密钥仅编码进摘要输入，绝不写入操作回执或日志。
     */
    private static void writeModel(DataOutputStream canonical, ModelPort.ModelConfiguration model) throws IOException {
        write(canonical, model.providerId());
        write(canonical, model.modelId());
        write(canonical, model.configGeneration());
        write(canonical, model.api().name());
        write(canonical, model.model());
        write(canonical, model.baseUri().toASCIIString());
        write(canonical, model.apiKey());
        writeDuration(canonical, model.connectTimeout());
        writeDuration(canonical, model.requestTimeout());
        canonical.writeInt(model.inputModalities().size());
        for (ModelPort.InputModality modality : model.inputModalities().stream()
                .sorted(Comparator.comparing(ModelPort.InputModality::name)).toList())
            {
                write(canonical, modality.name());
            }
        ModelPort.GenerationOptions generation = model.generation();
        writeNullableDouble(canonical, generation.temperature());
        writeNullableDouble(canonical, generation.topP());
        writeNullableInteger(canonical, generation.maxOutputTokens());
        writeNullableString(canonical, generation.reasoningLevel());
    }

    /**
     * Engine 生成所有操作回执时均按同一顺序写入身份字段。
     */
    private static void writeIdentity(DataOutputStream canonical, AgentIdentity identity) throws IOException {
        canonical.writeInt(VERSION);
        write(canonical, identity.tenantId());
        write(canonical, identity.threadId());
        write(canonical, identity.turnId());
        write(canonical, identity.userId());
    }

    /**
     * 直接编码回答字段，避免摘要依赖区域设置或 JsonObject 遍历顺序。
     */
    private static void writeAnswer(DataOutputStream target, InteractionAnswer answer) throws IOException {
        write(target, answer.questionId());
        target.writeBoolean(answer.skipped());
        writeNullableString(target, answer.freeText());
        target.writeInt(answer.optionIds().size());
        for (String optionId : answer.optionIds()) {
            write(target, optionId);
        }
    }

    /**
     * Core 回执账本按 tenant/user/raw key 设唯一槽位；方法、Thread、turn 与正文均由 fingerprint 决定能否复用，
     * 避免以变更 method 或 identity 派生出第二个键绕开冲突检测。
     */
    private static String operationKey(AgentIdentity identity, String providedOperationKey) {
        return "op_" + hashStrings(identity.tenantId(), identity.userId(), providedOperationKey);
    }

    /**
     * 从限定后的操作键派生用户消息身份，避免随机 UUID 破坏重试稳定性。
     */
    private static String messageId(AgentIdentity identity, String scopedKey) {
        return "msg_" + hashStrings(identity.tenantId(), identity.threadId(), identity.turnId(), scopedKey);
    }

    /**
     * 构造有界且不含密钥的回执，支持请求超时后的幂等回读核对。
     */
    private static AgentStore.OperationIdentity operation(AgentIdentity identity, String operationKey,
                                                          String operationType, String fingerprint) {
        JsonObject receipt = JsonObjects.builder().putNumber("schemaVersion", 1)
                .putText("tenantId", identity.tenantId()).putText("threadId", identity.threadId())
                .putText("turnId", identity.turnId()).putText("userId", identity.userId())
                .putText("operationType", operationType).build();
        return new AgentStore.OperationIdentity(operationKey, operationType, fingerprint, receipt);
    }

    /**
     * 对准入和后续幂等操作共用的类型化字段序列求摘要。
     */
    private static String hashCanonical(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream canonical = new DataOutputStream(bytes);
            writer.write(canonical);
            canonical.flush();
            return Hashing.sha256HexBytes(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory fingerprint encoding failed", impossible);
        }
    }

    /**
     * 按类型和顺序保留每个公开用户内容块，作为不可变准入正文的一部分。
     */
    private static void writeUserContent(DataOutputStream target, UserContent content) throws IOException {
        target.writeInt(content.blocks().size());
        for (UserContentBlock block : content.blocks()) {
            if (block instanceof TextContent(String text1)) {
                write(target, "text");
                write(target, text1);
            } else if (block instanceof AttachmentContent(String attachmentId)) {
                write(target, "attachment");
                write(target, attachmentId);
            } else if (block instanceof SkillReferenceContent(String skillId)) {
                write(target, "skill");
                write(target, skillId);
            } else if (block instanceof ResourceReferenceContent(String sourceId, String resourceId, String kind)) {
                write(target, "resource");
                write(target, sourceId);
                write(target, resourceId);
                write(target, kind);
            } else {
                throw new IllegalArgumentException("unsupported user content block");
            }
        }
    }

    /**
     * 以 UTF-8 字节长度界定字符串，避免字符数导致规范字段边界含混。
     */
    private static void write(DataOutputStream target, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        target.writeInt(encoded.length);
        target.write(encoded);
    }

    /**
     * 为可空小数写入显式存在位，避免 null 与零值混淆。
     */
    private static void writeNullableDouble(DataOutputStream target, Double value) throws IOException {
        target.writeBoolean(value != null);
        if (value != null) {
            target.writeLong(Double.doubleToLongBits(value));
        }
    }

    /**
     * 为可选整数写入显式存在位。
     */
    private static void writeNullableInteger(DataOutputStream target, Integer value) throws IOException {
        target.writeBoolean(value != null);
        if (value != null) {
            target.writeInt(value);
        }
    }

    /**
     * 为可选文本写入显式存在位。
     */
    private static void writeNullableString(DataOutputStream target, String value) throws IOException {
        target.writeBoolean(value != null);
        if (value != null) {
            write(target, value);
        }
    }

    /**
     * 直接写入秒和纳秒，避免时长经区域化文本转换后精度漂移。
     */
    private static void writeDuration(DataOutputStream target, java.time.Duration duration) throws IOException {
        target.writeLong(duration.getSeconds());
        target.writeInt(duration.getNano());
    }

    /**
     * 对类型确定且带长度前缀的字段求摘要，稳定生成内部操作及消息身份。
     */
    private static String hashStrings(String... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream canonical = new DataOutputStream(bytes);
            canonical.writeInt(VERSION);
            for (String value : values) {
                write(canonical, value);
            }
            canonical.flush();
            return Hashing.sha256HexBytes(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory fingerprint encoding failed", impossible);
        }
    }

    /**
     * 仅持久化幂等准入核对所需的版本化身份回执。
     */
    private static JsonObject receipt(AgentIdentity identity, String requestFingerprint, String inputMessageId) {
        JsonObjectBuilder value = JsonObjects.builder().putNumber("schemaVersion", 1)
                .putText("tenantId", identity.tenantId()).putText("threadId", identity.threadId())
                .putText("turnId", identity.turnId()).putText("userId", identity.userId())
                .putText("requestFingerprint", requestFingerprint);
        if (inputMessageId != null) {
            value.putText("inputMessageId", inputMessageId);
        }
        return value.build();
    }

    /**
     * 在比较正文摘要前验证 Store 回执的 operation 与四元 Agent 身份。
     */
    private static JsonObject readReceiptForOperation(AgentStore.OperationReceipt receipt,
                                                      AgentIdentity identity, String operationType,
                                                      String operationKey, String requestDescription) {
        String expectedKey = operationKey(identity, operationKey);
        if (!receipt.operationKey().equals(expectedKey) || !receipt.operationType().equals(operationType)
            || !receipt.conversation().tenantId().equals(identity.tenantId())
            || !receipt.conversation().userId().equals(identity.userId())
            || !receipt.conversation().threadId().equals(identity.threadId())
            || !receipt.turnId().equals(identity.turnId())) {
            throw new IllegalArgumentException("operation key belongs to another operation");
        }
        JsonObject body = receipt.receipt();
        if (!new JsonNumber(1).equals(body.get("schemaVersion"))
            || !identity.tenantId().equals(receiptText(body, "tenantId"))
            || !identity.threadId().equals(receiptText(body, "threadId"))
            || !identity.turnId().equals(receiptText(body, "turnId"))
            || !identity.userId().equals(receiptText(body, "userId"))) {
            throw new IllegalArgumentException("operation key was already used for a different "
                                               + requestDescription);
        }
        return body;
    }

    /**
     * 读取回执中的协议字符串，错误类型不能借 JsonNode 的宽松文本转换变成有效身份。
     */
    private static String receiptText(JsonObject body, String field) {
        JsonValue value = body.get(field);
        if (!(value instanceof JsonText text)) {
            throw new IllegalStateException("invalid stored operation receipt");
        }
        return text.value();
    }

    /**
     * 提供带受检异常的编码函数，供长度前缀摘要构造使用。
     */
    @FunctionalInterface
    private interface IoWriter {
        /**
         * 将规范字段写入 Engine 所有的内存摘要输入。
         */
        void write(DataOutputStream target) throws IOException;
    }

    /**
     * 仅向内部请求适配器提供已计算摘要，普通库调用方无需自行构造。
     *
     * @param inputMessageId 输入消息标识
     * @param operation 操作
     * @param fingerprint 当前内容或配置的校验指纹
     * @param requestFingerprint 请求指纹
     */
    public record PreparedRequest(String inputMessageId,
                                  AgentStore.OperationIdentity operation,
                                  String fingerprint,
                                  String requestFingerprint) {
        /**
         * 在进入 AgentStore 准入前校验持久身份和摘要均已就绪。
         */
        public PreparedRequest {
            Objects.requireNonNull(inputMessageId, "inputMessageId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        }
    }

    /**
     * 在运行时解析后才存在的内部执行身份与不可变语义摘要。
     *
     * @param operation 操作
     * @param fingerprint 当前内容或配置的校验指纹
     * @param requestFingerprint 请求指纹
     */
    public record PreparedInternalRequest(AgentStore.OperationIdentity operation,
                                          String fingerprint,
                                          String requestFingerprint) {
        /**
         * 准入边界需要完整幂等身份，内部指令不会生成用户消息 ID。
         */
        public PreparedInternalRequest {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        }
    }

    /**
     * 准入前冻结压缩语义和模型绑定，普通调用方无需计算内容指纹。
     *
     * @param operation 操作
     * @param fingerprint 当前内容或配置的校验指纹
     * @param requestFingerprint 请求指纹
     */
    public record PreparedCompactionRequest(AgentStore.OperationIdentity operation,
                                            String fingerprint,
                                            String requestFingerprint) {
        /**
         * 保证压缩执行身份完整后才允许写 Store。
         */
        public PreparedCompactionRequest {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        }
    }

    /**
     * 将可重试消息身份与操作回执一起保存，供排队输入使用。
     *
     * @param messageId 消息标识
     * @param operation 操作
     */
    public record PreparedInput(String messageId,
                                AgentStore.OperationIdentity operation) {
        /**
         * 在委托追加事务前校验两种持久身份均已准备完成。
         */
        public PreparedInput {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(operation, "operation");
        }
    }

    /**
     * 将稳定输入 ID 与 Store 幂等操作一起返回，ACK 不依赖从可变队列反查正文。
     *
     * @param inputId 输入标识
     * @param operation 操作
     */
    public record PreparedQueueMutation(String inputId,
                                        AgentStore.OperationIdentity operation) {
        /**
         * 确保调用方使用同一身份提交并回应队列变更。
         */
        public PreparedQueueMutation {
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
