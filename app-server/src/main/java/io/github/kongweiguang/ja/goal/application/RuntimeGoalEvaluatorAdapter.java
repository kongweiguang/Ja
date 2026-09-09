// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalEvaluatorPort;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 使用当前 Thread 模型执行单次、无 Tool、无对话历史的独立 Goal evaluator。 */
public final class RuntimeGoalEvaluatorAdapter implements GoalEvaluatorPort {
    private final ModelPort models;
    private final TurnRuntimeResolver runtimes;
    private final ConversationRepository conversations;
    private final WorkspaceUseCase workspaces;
    private final ObjectMapper json;
    private final Clock clock;

    /** adapter 复用配置 owner 的短租约，凭据不会进入 Goal 请求或持久化。 */
    public RuntimeGoalEvaluatorAdapter(ModelPort models, TurnRuntimeResolver runtimes,
            ConversationRepository conversations, WorkspaceUseCase workspaces,
            ObjectMapper json, Clock clock) {
        this.models = Objects.requireNonNull(models, "models");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 当前偏好与 intent 不一致时失败关闭，绝不隐藏切换 Provider。 */
    @Override
    @SuppressWarnings("PMD.CloseResource") // 异步租约只能在 Provider stage 终态关闭，try-with-resources 会过早释放。
    public CompletionStage<Result> evaluate(Request request) {
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Goal owner Thread is unavailable"));
        if (!thread.preferences().providerId().equals(request.providerId())
                || !thread.preferences().modelId().equals(request.modelId())) {
            throw new IllegalStateException("Goal evaluator model selection changed");
        }
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        RuntimeLease lease = runtimes.resolve(new TurnRuntimeRequest(thread.threadId(), null,
                workspace.root(), workspace.workspaceId(), request.providerId(), request.modelId(),
                thread.preferences().reasoningLevel(), thread.preferences().accessMode(),
                thread.preferences().collaborationMode(),
                io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER,
                Duration.ofMinutes(30), clock.instant()));
        String input = encodeInput(request);
        String system = "You are an independent acceptance evaluator. Use only the supplied frozen plan and evidence. "
                + "Return one JSON object with verdict, summary, and criteria; never request or call tools.";
        String revision = "prompt_" + digest(system + '\n' + input);
        StringBuilder output = new StringBuilder();
        ModelPort.ModelRequest modelRequest = new ModelPort.ModelRequest(lease.model(),
                new ModelPort.PromptPayload(system, revision),
                List.of(new ModelMessage(ModelRole.USER, List.of(new TextContent(input)))),
                List.of(), null, 1, ModelPort.RetryPolicy.SINGLE_ATTEMPT);
        CompletionStage<ModelPort.ModelOutcome> stage = models.start(modelRequest, event -> {
            if (event instanceof ModelPort.TextDelta text) output.append(text.text());
            if (event instanceof ModelPort.ToolCallReady) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("Goal evaluator attempted a Tool call"));
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }, CancellationToken.none());
        return stage.thenApply(outcome -> {
            if (outcome.finishReason() != ModelPort.FinishReason.STOP) {
                throw new IllegalStateException("Goal evaluator response is incomplete");
            }
            return decode(output.toString());
        }).whenComplete((ignored, failure) -> lease.close());
    }

    /** 输入使用 ObjectMapper 结构化编码，证据正文只含持久化安全摘要和 digest。 */
    String encodeInput(Request request) {
        try {
            ObjectNode payload = json.createObjectNode();
            payload.put("goalId", request.goalId());
            payload.put("goalDefinitionRevision", request.goalDefinitionRevision());
            payload.put("objective", request.objective());
            if (request.canonicalPlanJson() != null) {
                payload.set("plan", json.readTree(request.canonicalPlanJson()));
            }
            ArrayNode criteria = payload.putArray("criteria");
            for (GoalEvaluatorPort.Criterion criterion : request.criteria()) {
                ObjectNode item = criteria.addObject();
                item.put("criterionId", criterion.criterionId());
                item.put("description", criterion.description());
                item.put("required", criterion.required());
            }
            ArrayNode evidence = payload.putArray("evidence");
            for (GoalEvaluatorPort.EvidenceDigest digest : request.evidence()) {
                ObjectNode item = evidence.addObject();
                item.put("criterionId", digest.criterionId());
                item.put("sourceType", digest.sourceType());
                item.put("sourceId", digest.sourceId());
                item.put("summary", digest.summary());
                item.put("digest", digest.digest());
            }
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Goal evaluator input is invalid", failure);
        }
    }

    /** Provider 输出必须是无围栏的单一严格 JSON object，未知字段和缺项均拒绝。 */
    private Result decode(String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject() || root.size() != 3
                    || !root.has("verdict") || !root.has("summary") || !root.has("criteria")
                    || !root.get("criteria").isArray()) throw new IllegalArgumentException("invalid evaluator JSON");
            List<GoalModels.CriterionEvaluation> criteria = new ArrayList<>();
            for (JsonNode item : root.get("criteria")) {
                if (!item.isObject() || item.size() != 3 || !item.has("criterionId")
                        || !item.has("verdict") || !item.has("reason")) {
                    throw new IllegalArgumentException("invalid evaluator criterion");
                }
                criteria.add(new GoalModels.CriterionEvaluation(item.get("criterionId").textValue(),
                        verdict(item.get("verdict").textValue()), item.get("reason").textValue()));
            }
            return new Result(verdict(root.get("verdict").textValue()), criteria,
                    root.get("summary").textValue());
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalArgumentException("Goal evaluator output is invalid", failure);
        }
    }

    /** wire 小写 verdict 显式映射，未知值不能降级为 inconclusive。 */
    private static GoalModels.EvaluationVerdict verdict(String value) {
        return GoalModels.EvaluationVerdict.valueOf(value.toUpperCase(Locale.ROOT));
    }

    /** Prompt revision 使用完整输入 SHA-256，Provider continuation 永不跨 evaluator 输入复用。 */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
