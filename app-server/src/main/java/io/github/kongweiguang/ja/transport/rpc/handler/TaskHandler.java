// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 用户可见 Task 的严格 JA-RPC 适配；Agent Tools 直接调用 TaskUseCase，不经过本 Handler。 */
public final class TaskHandler implements RpcHandler {
    private static final Duration DEFAULT_TASK_DEADLINE = Duration.ofMinutes(30);
    private final RpcSession session;

    /** Handler 只借用连接会话，不持有 Task observation 或数据库状态。 */
    public TaskHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** 返回用户 Task API 的精确十方法闭集。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.TASK_CREATE, RpcMethod.TASK_LIST, RpcMethod.TASK_READ,
                RpcMethod.TASK_OBSERVE, RpcMethod.TASK_UNOBSERVE, RpcMethod.TASK_SEEN,
                RpcMethod.TASK_MESSAGE_SEND, RpcMethod.TASK_FOLLOWUP, RpcMethod.TASK_CANCEL,
                RpcMethod.TASK_TREE_DELETE);
    }

    /** 所有领域错误只按稳定分类映射，SQLite 消息不会进入协议。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            return CompletableFuture.completedFuture(switch (command.method()) {
                case TASK_CREATE -> create(command.params());
                case TASK_LIST -> list(command.params());
                case TASK_READ -> read(command.params());
                case TASK_OBSERVE -> observe(command.params());
                case TASK_UNOBSERVE -> unobserve(command.params());
                case TASK_SEEN -> seen(command.params());
                case TASK_MESSAGE_SEND -> message(command.params());
                case TASK_FOLLOWUP -> followUp(command.params());
                case TASK_CANCEL -> cancel(command.params());
                case TASK_TREE_DELETE -> deleteTree(command.params());
                default -> throw JaRpcException.methodNotFound();
            });
        } catch (TaskRepositoryException failure) {
            throw map(failure);
        }
    }

    /** 草稿首次发送才进入此方法，空白 Tab 不产生数据库对象。 */
    private ObjectNode create(ObjectNode params) {
        RpcParams.requireExact(params, "parentThreadId", "parentTurnId", "expectedParentRevision",
                "taskName", "content");
        String parentTurnId = params.get("parentTurnId").isNull() ? null
                : RpcParams.identifier(params, "parentTurnId", "turn_", 128);
        TaskUseCase.StartResult result = session.tasks().createSideTask(new TaskUseCase.CreateCommand(
                RpcParams.identifier(params, "parentThreadId", "thr_", 128), parentTurnId,
                RpcParams.revision(params, "expectedParentRevision"),
                RpcParams.text(params, "taskName", 96, false), RpcUserContent.parse(params.get("content")),
                DEFAULT_TASK_DEADLINE));
        return session.mapper().createObjectNode().put("accepted", true).put("turnId", result.turnId())
                .set("task", RpcResults.task(session.mapper(), result.task()));
    }

    /** 总览只读取当前根下 projection 树。 */
    private ObjectNode list(ObjectNode params) {
        RpcParams.requireExact(params, "rootThreadId");
        List<TaskModels.Summary> values = session.tasks().listTree(
                RpcParams.identifier(params, "rootThreadId", "thr_", 128));
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode items = result.putArray("items");
        values.forEach(value -> items.add(RpcResults.task(session.mapper(), value)));
        return result;
    }

    /** cursor 同时保存 Activity 与 Mailbox sequence，不用客户端时间推导分页。 */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireOnly(params, "taskThreadId", "cursor", "limit");
        if (params.has("cursor") && params.get("cursor").isNull()) throw JaRpcException.invalidParams();
        long[] cursor = cursor(RpcParams.optionalText(params, "cursor", 512));
        int limit = RpcParams.pageLimit(params);
        TaskModels.Detail detail = session.tasks().read(
                RpcParams.identifier(params, "taskThreadId", "thr_", 128), cursor[0], cursor[1], limit);
        ObjectNode result = session.mapper().createObjectNode()
                .set("task", RpcResults.task(session.mapper(), detail.task()));
        result.set("contextSeed", RpcResults.taskSeed(session.mapper(), detail.contextSeed()));
        ArrayNode activities = result.putArray("activities");
        detail.activities().forEach(value -> activities.add(RpcResults.taskActivity(session.mapper(), value)));
        ArrayNode mailbox = result.putArray("mailbox");
        detail.mailbox().forEach(value -> mailbox.add(RpcResults.taskMailbox(session.mapper(), value)));
        if (detail.activities().size() == limit || detail.mailbox().size() == limit) {
            long activity = detail.activities().isEmpty() ? cursor[0]
                    : detail.activities().getLast().sequence();
            long message = detail.mailbox().isEmpty() ? cursor[1] : detail.mailbox().getLast().sequence();
            result.put("nextCursor", "task:" + activity + ':' + message);
        } else result.putNull("nextCursor");
        return result;
    }

    /** observe 只建立进程内高频句柄，不加载 Transcript。 */
    private ObjectNode observe(ObjectNode params) {
        RpcParams.requireExact(params, "taskThreadId", "expectedTaskRevision");
        TaskUseCase.Observation value = session.observeTask(
                RpcParams.identifier(params, "taskThreadId", "thr_", 128),
                RpcParams.revision(params, "expectedTaskRevision"));
        return session.mapper().createObjectNode().put("observationId", value.observationId())
                .put("taskThreadId", value.taskThreadId()).put("revision", value.revision());
    }

    /** unobserve 只关闭显示资源，绝不调用 cancel。 */
    private ObjectNode unobserve(ObjectNode params) {
        RpcParams.requireExact(params, "observationId");
        session.unobserveTask(RpcParams.identifier(params, "observationId", "observe_", 128));
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /** seen 按服务端 sequence/CAS 推进未读边界。 */
    private ObjectNode seen(ObjectNode params) {
        RpcParams.requireExact(params, "taskThreadId", "expectedTaskRevision", "throughActivitySequence");
        TaskModels.Summary task = session.tasks().markSeen(
                RpcParams.identifier(params, "taskThreadId", "thr_", 128),
                RpcParams.revision(params, "expectedTaskRevision"),
                RpcParams.wholeNumber(params, "throughActivitySequence"));
        return accepted(task);
    }

    /** message/send 保持 QueueOnly，Handler 不提供 wake 字段。 */
    private ObjectNode message(ObjectNode params) {
        RpcParams.requireExact(params, "senderThreadId", "targetThreadId", "content", "idempotencyKey");
        TaskUseCase.MessageReceipt receipt = session.tasks().sendMessage(messageCommand(params));
        return session.mapper().createObjectNode().put("accepted", true).put("messageId", receipt.messageId())
                .put("mailboxSequence", receipt.mailboxSequence());
    }

    /** followup 是唯一会为既有 Child 启动新 Turn 的消息入口。 */
    private ObjectNode followUp(ObjectNode params) {
        RpcParams.requireExact(params, "senderThreadId", "targetThreadId", "content", "idempotencyKey",
                "expectedTaskRevision");
        TaskUseCase.FollowUpResult result = session.tasks().followUp(new TaskUseCase.FollowUpCommand(
                messageCommand(params), RpcParams.revision(params, "expectedTaskRevision"),
                DEFAULT_TASK_DEADLINE));
        return session.mapper().createObjectNode().put("accepted", true)
                .put("messageId", result.messageId())
                .put("turnId", result.turnId()).set("task", RpcResults.task(session.mapper(), result.task()));
    }

    /** cancel 返回当前权威 Task 投影，异步终态通过 task/activity 收敛。 */
    private ObjectNode cancel(ObjectNode params) {
        RpcParams.requireExact(params, "taskThreadId", "expectedTaskRevision");
        return accepted(session.tasks().cancel(RpcParams.identifier(params, "taskThreadId", "thr_", 128),
                RpcParams.revision(params, "expectedTaskRevision")));
    }

    /** 整树删除要求重复确认身份，普通 thread/delete 由存储门拒绝。 */
    private ObjectNode deleteTree(ObjectNode params) {
        RpcParams.requireExact(params, "taskThreadId", "expectedTaskRevision", "confirmTaskThreadId");
        int deleted = session.tasks().deleteTree(
                RpcParams.identifier(params, "taskThreadId", "thr_", 128),
                RpcParams.revision(params, "expectedTaskRevision"),
                RpcParams.identifier(params, "confirmTaskThreadId", "thr_", 128));
        return session.mapper().createObjectNode().put("accepted", true).put("deletedTaskCount", deleted);
    }

    /** 构造两类 Mailbox 共用的严格命令；RPC 不猜测 causal Turn。 */
    private static TaskUseCase.MessageCommand messageCommand(ObjectNode params) {
        return new TaskUseCase.MessageCommand(
                RpcParams.identifier(params, "senderThreadId", "thr_", 128),
                RpcParams.identifier(params, "targetThreadId", "thr_", 128),
                RpcUserContent.parse(params.get("content")),
                RpcParams.text(params, "idempotencyKey", 128, false), null);
    }

    /** Mutation 回执始终包含完整 Task projection。 */
    private ObjectNode accepted(TaskModels.Summary task) {
        return session.mapper().createObjectNode().put("accepted", true)
                .set("task", RpcResults.task(session.mapper(), task));
    }

    /** opaque cursor 只接受本 Handler 签发的双 sequence 格式。 */
    private static long[] cursor(String cursor) {
        if (cursor == null) return new long[]{0, 0};
        if (!cursor.matches("task:[0-9]+:[0-9]+")) throw JaRpcException.invalidParams();
        String[] parts = cursor.split(":", -1);
        try {
            long activity = Long.parseLong(parts[1]);
            long mailbox = Long.parseLong(parts[2]);
            if (activity < 0 || mailbox < 0) throw JaRpcException.invalidParams();
            return new long[]{activity, mailbox};
        } catch (NumberFormatException failure) {
            throw JaRpcException.invalidParams();
        }
    }

    /** Task Repository 闭集与公开错误目录一一对应。 */
    private static JaRpcException map(TaskRepositoryException failure) {
        JaErrorCatalog code = switch (failure.code()) {
            case NOT_FOUND -> JaErrorCatalog.TASK_NOT_FOUND;
            case RELATION_INVALID -> JaErrorCatalog.TASK_RELATION_INVALID;
            case CONTEXT_REVISION_CONFLICT, CAS_CONFLICT -> JaErrorCatalog.TASK_CONTEXT_REVISION_CONFLICT;
            case PERMISSION_DENIED -> JaErrorCatalog.TASK_PERMISSION_DENIED;
            case DEPTH_LIMIT -> JaErrorCatalog.TASK_DEPTH_LIMIT;
            case TREE_LIMIT -> JaErrorCatalog.TASK_TREE_LIMIT;
            case MAILBOX_FULL -> JaErrorCatalog.TASK_MAILBOX_FULL;
            case TREE_DELETE_REQUIRED -> JaErrorCatalog.TASK_TREE_DELETE_REQUIRED;
            case OBSERVATION_INVALID -> JaErrorCatalog.TASK_OBSERVATION_INVALID;
            case WRITE_LEASE_TIMEOUT -> JaErrorCatalog.WORKSPACE_WRITE_LEASE_TIMEOUT;
            case INVALID_STATE -> JaErrorCatalog.INVALID_STATE;
        };
        return JaRpcException.of(code, "task operation could not be completed");
    }
}
