// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.Accepted;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.EventPageView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.EventQuery;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.MessageQuery;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.RecoveryQuery;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.ModelMetadataView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.AttachmentCapabilitiesView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.TurnStatusView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.WaitQuery;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ChatService;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ChatStreamService;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.AnswerCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.CancelCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.ChatCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.MessageView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.QueueView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.RecoveryResolveCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.WaitView;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentCapabilities;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ThreadService;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletionStage;

/**
 * 示例使用 Ja 的 Thread/Turn 术语，用户身份由服务端决定。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chats;
    private final ChatStreamService streams;
    private final ThreadService threads;
    private final JsonValueCodec payloadCodec;
    private final ModelMetadataView metadata;
    private final CurrentActorProvider actors;

    /** 借用唯一服务实例、服务端身份和启动时冻结的附件能力，不在 HTTP 层重建 Provider 规则。 */
    public ChatController(ChatService chats, ChatStreamService streams, ThreadService threads, ModelPort.ModelConfiguration model,
                          JsonValueCodec payloadCodec,
                          AttachmentCapabilities attachmentCapabilities,
                          CurrentActorProvider actors,
                          @Value("${agent.experience.enabled:true}") boolean experience) {
        this.chats = chats;
        this.streams = streams;
        this.threads = threads;
        this.payloadCodec = payloadCodec;
        this.actors = actors;
        metadata = new ModelMetadataView(model.model(), experience, AttachmentCapabilitiesView.from(attachmentCapabilities));
    }

    /**
     * 模型标签不需要执行身份，与对话接口共用 Controller，避免额外的转发层。
     */
    @PostMapping("/model")
    public ModelMetadataView metadata() {
        return metadata;
    }

    /**
     * 受理与完成分离，返回对外统一的用户、Thread 与 Turn 身份。
     */
    @PostMapping("/submit")
    public ResponseEntity<Accepted> submit(@RequestBody ChatCommand command) {
        CurrentActor actor = actors.currentActor();
        AgentIdentity identity = chats.identity(actor, command.threadId(), command.turnId());
        threads.admit(actor, command.threadId(), command.turnId(), () -> {
            chats.submit(identity, command);
            return identity;
        });
        return ResponseEntity.accepted().body(Accepted.from(identity));
    }

    /**
     * 一次 POST 完成提交与观察，先订阅草稿避免丢失首片内容。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitStream(@RequestBody ChatCommand command) {
        CurrentActor actor = actors.currentActor();
        return threads.admit(actor, command.threadId(), command.turnId(),
                () -> streams.submit(chats.identity(actor, command.threadId(), command.turnId()), command));
    }

    /**
     * 重连仅观察原 Turn，水位来自 JSON 正文，不重新提交输入。
     */
    @PostMapping(value = "/reconnect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reconnect(@RequestBody EventQuery query) {
        return streams.reconnect(identity(query.threadId(), query.turnId()), query.afterSequence());
    }

    /**
     * 公开状态保留持久事实，并分开提示宿主失去执行观察后的显式核实动作。
     */
    @PostMapping("/status")
    public TurnStatusView query(@RequestBody TurnCommand command) {
        AgentExecutionStatus status = chats.query(identity(command.threadId(), command.turnId()));
        return TurnStatusView.from(status, chats.requiresResume(status));
    }

    /**
     * 等待记录来自持久事件，回答时 Core 再核对最新修订号。
     */
    @PostMapping("/wait")
    public WaitView waitRequest(@RequestBody WaitQuery query) {
        return chats.waitRequest(identity(query.threadId(), query.turnId()), query.recordId());
    }

    /**
     * 消息读取需要完整 Turn 身份，防止客户端跨 Turn 引用记录。
     */
    @PostMapping("/message")
    public MessageView message(@RequestBody MessageQuery query) {
        return chats.message(identity(query.threadId(), query.turnId()), query.recordId(), query.usageRecordId());
    }

    /**
     * 输入排队先持久提交，模型到安全点后消费。
     */
    @PostMapping("/inputs")
    public CompletionStage<ResponseEntity<Void>> append(@RequestBody ChatCommand command) {
        return chats.append(identity(command.threadId(), command.turnId()), command)
                .thenApply(ignored -> ResponseEntity.noContent().build());
    }

    /**
     * 答案和恢复保持两个独立动作，提交答案不会隐式续跑。
     */
    @PostMapping("/answers")
    public CompletionStage<ResponseEntity<Void>> answer(@RequestBody AnswerCommand command) {
        return chats.answer(identity(command.threadId(), command.turnId()), command)
                .thenApply(ignored -> ResponseEntity.noContent().build());
    }

    /**
     * 204 仅表示取消意图已保存，实际取消仍以终态为准。
     */
    @PostMapping("/cancel")
    public CompletionStage<ResponseEntity<Void>> cancel(@RequestBody CancelCommand command) {
        return chats.cancel(identity(command.threadId(), command.turnId()), command)
                .thenApply(ignored -> ResponseEntity.noContent().build());
    }

    /**
     * 恢复复用原 Turn，已提交的工具结果不会重新执行。
     */
    @PostMapping("/resume")
    public ResponseEntity<Accepted> resume(@RequestBody TurnCommand command) {
        AgentIdentity identity = identity(command.threadId(), command.turnId());
        chats.resume(identity);
        return ResponseEntity.accepted().body(Accepted.from(identity));
    }

    /**
     * 分页和 SSE 使用同一个 Thread 水位及 JSON Gateway，响应统一映射 Ja 身份并保留 Core JSON 文本。
     */
    @PostMapping("/events")
    public EventPageView events(@RequestBody EventQuery query) {
        return EventPageView.from(chats.events(identity(query.threadId(), query.turnId()),
                query.afterSequence(), query.limit()), payloadCodec);
    }

    /**
     * 暂停和取消语义分开，暂停保留恢复资格。
     */
    @PostMapping("/pause")
    public CompletionStage<ResponseEntity<Void>> pause(@RequestBody io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.ControlCommand command) {
        return chats.pause(identity(command.threadId(), command.turnId()), command.operationKey()).thenApply(ignored -> ResponseEntity.noContent().build());
    }

    /**
     * 独立压缩执行可以用现有状态和事件接口观察到终态。
     */
    @PostMapping("/compact")
    public ResponseEntity<Accepted> compact(@RequestBody io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto.ControlCommand command) {
        return ResponseEntity.accepted().body(Accepted.from(chats.compact(identity(command.threadId(), command.turnId()), command.operationKey())));
    }

    /**
     * 队列来自持久命令重放，页面不能用本地列表冒充待执行状态。
     */
    @PostMapping("/queue")
    public QueueView queue(@RequestBody TurnCommand command) {
        return chats.queue(identity(command.threadId(), command.turnId()));
    }

    /**
     * 单项修改以 observed revision 提交，陈旧编辑明确失败。
     */
    @PostMapping("/queue/mutate")
    public CompletionStage<?> mutate(@RequestBody io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.QueueCommand command) {
        return chats.mutate(identity(command.threadId(), command.turnId()), command);
    }

    /**
     * 供应商计量按记录引用查询，不估算未知 tokens。
     */
    @PostMapping("/usage")
    public ExecutionFacts.Usage usage(@RequestBody MessageQuery command) {
        return chats.usage(identity(command.threadId(), command.turnId()), command.recordId());
    }

    /**
     * 只观察持久事件，POST 正文携带断点水位。
     */
    @PostMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventsStream(@RequestBody EventQuery query) {
        return streams.events(identity(query.threadId(), query.turnId()), query.afterSequence());
    }

    /** 每个 HTTP 请求只从宿主身份入口获取一次身份，并在异步任务创建前冻结为 AgentIdentity。 */
    private AgentIdentity identity(String threadId, String turnId) {
        CurrentActor actor = actors.currentActor();
        threads.requireOwned(actor, threadId);
        return chats.identity(actor, threadId, turnId);
    }

    /** 只查询并展示当前 Turn 尚待用户明确处理的 Core 恢复裁决。 */
    @PostMapping("/recoveries")
    public ChatService.RecoveryList recoveries(@RequestBody RecoveryQuery query) {
        return chats.recoveries(identity(query.threadId(), query.turnId()));
    }

    /** 将显式用户选择和观察到的 recovery revision 一起交给 Core 做 CAS 裁决。 */
    @PostMapping("/recoveries/resolve")
    public CompletionStage<ResponseEntity<Void>> resolveRecovery(@RequestBody RecoveryResolveCommand command) {
        AgentIdentity identity = identity(command.threadId(), command.turnId());
        return chats.resolveRecovery(identity, command).thenApply(ignored -> ResponseEntity.noContent().build());
    }
}
