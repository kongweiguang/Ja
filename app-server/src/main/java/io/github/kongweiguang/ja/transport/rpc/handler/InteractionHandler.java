// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.port.in.InteractionUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.InteractionWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 问答使用短请求提交，等待用户的生命周期留在持久 Turn，不占用 RPC in-flight 名额。 */
public final class InteractionHandler implements RpcHandler {
    private final RpcSession session;
    private final InteractionWireMapper wire;

    /** Handler 只适配输入与已提交快照，不负责恢复调度或保存第二份答案。 */
    public InteractionHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.wire = new InteractionWireMapper(session.mapper());
    }

    /** 观察与回答拥有独立协议身份，普通 turn/input 消息不能伪造结构化提交。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.INTERACTION_READ, RpcMethod.INTERACTION_OBSERVE,
                RpcMethod.INTERACTION_UNOBSERVE, RpcMethod.INTERACTION_DRAFT_SAVE,
                RpcMethod.INTERACTION_RESPOND, RpcMethod.INTERACTION_CANCEL);
    }

    /** 只有后端 ACK 的状态进入返回值；客户端重试沿用原幂等键。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            ObjectNode result = switch (command.method()) {
                case INTERACTION_READ -> read(command.params());
                case INTERACTION_OBSERVE -> observe(command.params());
                case INTERACTION_UNOBSERVE -> unobserve(command.params());
                case INTERACTION_DRAFT_SAVE -> saveDraft(command.params());
                case INTERACTION_RESPOND -> respond(command.params());
                case INTERACTION_CANCEL -> cancel(command.params());
                default -> throw JaRpcException.methodNotFound();
            };
            return CompletableFuture.completedFuture(result);
        } catch (InteractionUseCase.InteractionException failure) {
            JaErrorCatalog code = switch (failure.code()) {
                case NOT_FOUND -> JaErrorCatalog.INTERACTION_NOT_FOUND;
                case REVISION_CONFLICT -> JaErrorCatalog.INTERACTION_REVISION_CONFLICT;
                case INVALID_STATE -> JaErrorCatalog.INTERACTION_INVALID_STATE;
                case INVALID -> JaErrorCatalog.INTERACTION_INVALID;
                case UNAVAILABLE -> JaErrorCatalog.STORAGE_UNAVAILABLE;
            };
            throw JaRpcException.of(code, "问答状态已变化，请重新查看当前问题");
        } catch (IllegalArgumentException invalid) {
            throw JaRpcException.of(JaErrorCatalog.INTERACTION_INVALID, "回答不符合问题约束");
        }
    }

    /** 未指定请求时只恢复当前 Thread 的活动问答，不扫描其它会话。 */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "requestId");
        String threadId = threadId(params);
        String requestId = params.has("requestId") ? requestId(params) : null;
        return snapshot(threadId, requestId);
    }

    /** 由连接先订阅再读取水位，事件先于 ACK 到达也可以去重对账。 */
    private ObjectNode observe(ObjectNode params) {
        RpcParams.requireExact(params, "threadId");
        return session.observeInteraction(threadId(params));
    }

    /** 只释放当前连接的观察，切换会话不会丢弃未回答问题。 */
    private ObjectNode unobserve(ObjectNode params) {
        RpcParams.requireExact(params, "observationId");
        session.unobserveInteraction(RpcParams.identifier(params, "observationId", "observe_", 128));
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /** 草稿允许不完整答案，最终问题约束在 respond 的同一事务中重新校验。 */
    private ObjectNode saveDraft(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "requestId", "expectedDraftRevision",
                "idempotencyKey", "answers", "page", "collapsed");
        String threadId = threadId(params);
        String requestId = requestId(params);
        int page = RpcParams.integer(params, "page");
        if (page > 2) throw JaRpcException.invalidParams();
        session.interactions().saveDraft(threadId, requestId, answers(params), page,
                bool(params, "collapsed"), RpcParams.revision(params, "expectedDraftRevision"),
                idempotencyKey(params), session.clock().instant());
        return snapshot(threadId, requestId);
    }

    /** 回答只结算原请求，不接受用户端提供的 Turn、Run 或执行恢复游标。 */
    private ObjectNode respond(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "requestId", "expectedRevision", "idempotencyKey", "answers");
        String threadId = threadId(params);
        String requestId = requestId(params);
        var pending = session.interactions().read(threadId, requestId)
                .flatMap(io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot::request)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.INTERACTION_NOT_FOUND, "问题不存在"));
        var thread = session.threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "会话不存在"));
        if (pending.status() == InteractionStatus.PENDING) {
            // 终态回答重试只回读原结果，不重新注册已经完成的 Turn 路由或复活旧通知上下文。
            session.restoreTurnNotificationContext(pending.turnId(), thread.thread().workspaceId(),
                    threadId, thread.thread().revision());
        }
        session.interactions().respond(threadId, requestId, RpcParams.revision(params, "expectedRevision"),
                answers(params), idempotencyKey(params), session.clock().instant(), session.eventSink());
        return snapshot(threadId, requestId);
    }

    /** 对外只允许明确取消；被新需求替代是内核 steering 的事实，不能由 Renderer 冒充。 */
    private ObjectNode cancel(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "requestId", "expectedRevision", "idempotencyKey");
        String threadId = threadId(params);
        String requestId = requestId(params);
        session.interactions().cancel(threadId, requestId, RpcParams.revision(params, "expectedRevision"),
                InteractionStatus.CANCELLED, idempotencyKey(params), session.clock().instant());
        return snapshot(threadId, requestId);
    }

    /** 每次查询复核 Thread 归属，猜中其它会话 requestId 仍得到不可见结果。 */
    private ObjectNode snapshot(String threadId, String requestId) {
        var interaction = session.interactions().read(threadId, requestId)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.INTERACTION_NOT_FOUND, "问题不存在"));
        return wire.snapshot(interaction);
    }

    /** Thread 必须真实存在，不能把不存在的身份注册成无限增长的观察条目。 */
    private String threadId(ObjectNode params) {
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 128);
        if (session.threads().readThread(threadId, null, 1).isEmpty()) {
            throw JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "会话不存在");
        }
        return threadId;
    }

    /** 不接受路径或用户标签替代内部请求身份。 */
    private static String requestId(ObjectNode params) {
        return RpcParams.identifier(params, "requestId", "interaction_", 128);
    }

    /** 所有 mutation 使用有界调用身份，响应丢失时可以安全复用。 */
    private static String idempotencyKey(ObjectNode params) {
        return RpcParams.text(params, "idempotencyKey", 256, false);
    }

    /** 显式布尔避免把字符串 false 强制转换成选中状态。 */
    private static boolean bool(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw JaRpcException.invalidParams();
        return value.booleanValue();
    }

    /** 严格解析有界答案结构，选项是否属于原问题仍由领域聚合验证。 */
    private static List<InteractionAnswer> answers(ObjectNode params) {
        if (!(params.get("answers") instanceof ArrayNode array) || array.size() > 3) {
            throw JaRpcException.invalidParams();
        }
        List<InteractionAnswer> answers = new ArrayList<>();
        for (JsonNode value : array) {
            if (!(value instanceof ObjectNode answer)) throw JaRpcException.invalidParams();
            RpcParams.requireExact(answer, "questionId", "optionIds", "freeText", "skipped");
            if (!(answer.get("optionIds") instanceof ArrayNode ids) || ids.size() > 32) {
                throw JaRpcException.invalidParams();
            }
            List<String> optionIds = new ArrayList<>();
            for (JsonNode id : ids) {
                if (!id.isTextual()) throw JaRpcException.invalidParams();
                optionIds.add(RpcParams.identifier(id.textValue(), "option_", 128));
            }
            answers.add(new InteractionAnswer(RpcParams.identifier(answer, "questionId", "question_", 128),
                    optionIds, answer.get("freeText").isNull() ? null
                            : RpcParams.text(answer, "freeText", 16_000, true), bool(answer, "skipped")));
        }
        return List.copyOf(answers);
    }
}
