// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription.PollResult;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.ChatCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.EventView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * 对话观察者单独管理 SSE 资源，连接关闭不会取消执行。
 */
@Service
public class ChatStreamService {
    private final AgentEngine engine;
    private final ChatService chats;
    private final JsonValueCodec payloadCodec;

    /**
     * 订阅共用同一 Engine 和组合根提供的 JSON Gateway，保持执行循环与事件编码策略唯一。
     */
    public ChatStreamService(AgentEngine engine, ChatService chats, JsonValueCodec payloadCodec) {
        this.engine = engine;
        this.chats = chats;
        this.payloadCodec = payloadCodec;
    }

    /**
     * 显式投影公开流事件，供应商原生内容不进入 SSE。
     */
    private static void sendDraft(SseEmitter emitter, TurnEvent event) throws IOException {
        switch (event) {
            case TurnEvent.TextDelta delta ->
                    emitter.send(SseEmitter.event().name("text-delta").data(Map.of("modelRequestId", delta.context().modelRequestId(), "text", delta.text())));
            case TurnEvent.ReasoningSummaryDelta delta ->
                    emitter.send(SseEmitter.event().name("reasoning-delta").data(Map.of("modelRequestId", delta.context().modelRequestId(), "text", delta.text())));
            case TurnEvent.Reset reset ->
                    emitter.send(SseEmitter.event().name("reset").data(Map.of("modelRequestId", reset.context().modelRequestId(), "reason", reset.reasonCode())));
        }
    }

    /**
     * 先订阅再准入，确保首片草稿不会在 HTTP 建连期间丢失。
     */
    public SseEmitter submit(AgentIdentity identity, ChatCommand command) {
        AgentStreamSubscription drafts = engine.subscribeStream(identity, 128);
        try {
            chats.submit(identity, command);
            return open(identity, drafts, 0);
        } catch (RuntimeException | Error failure) {
            drafts.close();
            throw failure;
        }
    }

    /**
     * 重连只补读原执行，不提交用户正文也不隐式恢复。
     */
    public SseEmitter reconnect(AgentIdentity identity, long cursor) {
        chats.query(identity);
        if (cursor < 0) {
            throw new IllegalArgumentException("negative event cursor");
        }
        return open(identity, engine.subscribeStream(identity, 128), cursor);
    }

    /**
     * 完成回调只释放当前观察者，执行和连接池生命周期仍由 Spring 管理。
     */
    private SseEmitter open(AgentIdentity identity, AgentStreamSubscription drafts, long cursor) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(drafts::close);
        emitter.onTimeout(drafts::close);
        emitter.onError(ignored -> drafts.close());
        try {
            Thread.ofVirtual().name("chat-" + identity.turnId()).start(() -> publish(identity, drafts, emitter, cursor));
        } catch (RuntimeException | Error failure) {
            drafts.close();
            throw failure;
        }
        return emitter;
    }

    /**
     * 临时流与持久水位分别发送；终态再排空，结果未知或重启后结束观察并要求用户核实，不自动续跑。
     */
    private void publish(AgentIdentity identity, AgentStreamSubscription drafts, SseEmitter emitter, long initialCursor) {
        try (drafts) {
            emitter.send(SseEmitter.event().name("accepted").data(Map.of("identity", TurnIdentity.from(identity))));
            long cursor = initialCursor;
            long heartbeatAt = System.nanoTime();
            long nextReadAt = heartbeatAt;
            boolean terminal = false;
            while (drafts.state() != AgentStreamSubscription.State.CLOSED) {
                long now = System.nanoTime();
                long untilRead = Math.max(0L, nextReadAt - now);
                Duration pollTime = Duration.ofNanos(Math.min(untilRead, Duration.ofMillis(250).toNanos()));
                PollResult draft = drafts.poll(pollTime);
                if (draft.state() == AgentStreamSubscription.State.RESYNC_REQUIRED) {
                    emitter.send(SseEmitter.event().name("resync").data(Map.of("reason", "draft-overflow")));
                    emitter.complete();
                    return;
                }
                if (draft.event().isPresent()) {
                    sendDraft(emitter, draft.event().orElseThrow());
                }
                now = System.nanoTime();
                if (now >= nextReadAt) {
                    AgentEventPage page = sendPage(identity, cursor, emitter);
                    cursor = page.nextSequence();
                    nextReadAt = now + Duration.ofMillis(250).toNanos();
                    if (page.hasMore()) {
                        continue;
                    }
                    if (terminal) {
                        emitter.complete();
                        return;
                    }
                    AgentExecutionStatus status = chats.query(identity);
                    terminal = TurnState.valueOf(status.state()).terminal();
                    if (chats.requiresResume(status)) {
                        sendRecoveryRequired(emitter);
                        return;
                    }
                }
                if (now - heartbeatAt > Duration.ofSeconds(15).toNanos()) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    heartbeatAt = now;
                }
            }
            emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(interrupted);
        } catch (IOException | RuntimeException failure) {
            emitter.completeWithError(failure);
        }
    }

    /**
     * Watch 所有权移交观察线程并由回调兜底释放；不能在返回 SSE 时就关闭订阅。
     */
    @SuppressWarnings("PMD.CloseResource")
    public SseEmitter events(AgentIdentity identity, long cursor) {
        chats.query(identity);
        AgentEventWatch watch = engine.watchEvents(identity, cursor);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(watch::close);
        emitter.onTimeout(watch::close);
        emitter.onError(ignored -> watch.close());
        try {
            Thread.ofVirtual().name("chat-events-" + identity.turnId()).start(() -> publishEvents(identity, cursor, watch, emitter));
        } catch (RuntimeException | Error failure) {
            watch.close();
            throw failure;
        }
        return emitter;
    }

    /**
     * 持久订阅等待真实通知；无宿主句柄时通知显式核实，终态确认后再排空避免提交与查询交错。
     */
    private void publishEvents(AgentIdentity identity, long initialCursor, AgentEventWatch watch, SseEmitter emitter) {
        try (watch) {
            long cursor = initialCursor;
            boolean terminal = false;
            while (!watch.isClosed()) {
                AgentEventPage page = sendPage(identity, cursor, emitter);
                cursor = page.nextSequence();
                if (page.hasMore()) {
                    continue;
                }
                if (terminal) {
                    emitter.complete();
                    return;
                }
                AgentExecutionStatus status = chats.query(identity);
                terminal = TurnState.valueOf(status.state()).terminal();
                if (chats.requiresResume(status)) {
                    sendRecoveryRequired(emitter);
                    return;
                }
                if (terminal) {
                    continue;
                }
                if (watch.awaitNext(cursor, Duration.ofSeconds(15)).isEmpty()) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                }
            }
            emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(interrupted);
        } catch (IOException | RuntimeException failure) {
            emitter.completeWithError(failure);
        }
    }

    /** 这是宿主观察事件而非持久终态，无 SSE id；后续动作仍以 status、Core 租约和恢复事实为准。 */
    private static void sendRecoveryRequired(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().name("recovery-required").data(Map.of("reason", "execution-unobserved")));
        emitter.complete();
    }

    /**
     * 只有持久事件获得 SSE id，草稿不能参与断点水位；投影继续输出 Core JSON 文本以维持 HTTP/SSE 信封合同。
     */
    private AgentEventPage sendPage(AgentIdentity identity, long cursor, SseEmitter emitter) throws IOException {
        AgentEventPage page = chats.events(identity, cursor, 256);
        for (AgentStore.PersistedEvent event : page.events()) {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("agent-event")
                    .data(EventView.from(event, payloadCodec)));
        }
        return page;
    }
}
