// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.infrastructure.runtime.ConfigurationJaRequestBindingSource;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 穿过真实偏好映射与 admission 校验，防止独立审查只在默认权限会话中可用。 */
final class ReviewServiceTest {
    /** 只读审查改变本次目录约束，但不能写回会话的权限或规划模式。 */
    @ParameterizedTest
    @MethodSource("threadPreferences")
    void admitsReadOnlyReviewWithoutChangingThreadPreferences(AccessMode access, CollaborationMode mode) {
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                access, mode, ThreadPreferences.TitleSource.AUTO);
        ThreadSummary thread = new ThreadSummary("thr_review", "ws_review", "Review", "project", preferences,
                ThreadSummary.Status.ACTIVE, false, null, true, 8, now, now);
        ThreadSnapshot snapshot = new ThreadSnapshot(thread, List.of(), List.of(), null, null, null);
        Workspace workspace = new Workspace("ws_review", Path.of(".").toAbsolutePath(), "Review",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, 0);
        AtomicReference<TurnStartRequest> submitted = new AtomicReference<>();
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, arguments) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(snapshot);
            }
            throw new AssertionError("review must not mutate Thread preferences: " + method.getName());
        });
        WorkspaceUseCase workspaces = proxy(WorkspaceUseCase.class, (method, arguments) -> {
            if ("requireOpenWorkspace".equals(method.getName())) {
                return workspace;
            }
            throw new AssertionError("review must only resolve its workspace");
        });
        TurnUseCase turns = proxy(TurnUseCase.class, (method, arguments) -> {
            if ("readClientOperation".equals(method.getName())) {
                return Optional.empty();
            }
            if ("start".equals(method.getName())) {
                TurnStartRequest start = (TurnStartRequest) arguments[0];
                submitted.set(start);
                verifyAdmissionPreferences(start, preferences);
                return new TurnUseCase.Accepted(start.threadId(), start.turnId(), 9, false,
                        new CompletableFuture<>());
            }
            throw new AssertionError("review must use ordinary Turn admission");
        });
        AttachmentUseCase attachments = proxy(AttachmentUseCase.class, (method, arguments) -> {
            throw new AssertionError("snapshot bytes remain owned by Turn admission");
        });
        ReviewService service = new ReviewService(threads, workspaces, turns, attachments, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));

        ReviewUseCase.Accepted accepted = service.start(new ReviewUseCase.StartRequest("thr_review", "turn_review",
                8, "att_snapshot"), ConversationTurnEventSink.noop(), "op_" + "a".repeat(32), "b".repeat(64));

        assertEquals("turn_review", accepted.turnId());
        assertEquals(AccessMode.APPROVAL_REQUIRED, submitted.get().accessMode());
        assertEquals(CollaborationMode.DEFAULT, submitted.get().collaborationMode());
        assertEquals("att_snapshot", submitted.get().reviewContext().orElseThrow().snapshotAttachmentId());
        assertEquals(preferences, snapshot.thread().preferences());
    }

    /** 原会话两种权限及两种协作模式都应允许用户显式发起独立的只读审查。 */
    private static Stream<Arguments> threadPreferences() {
        return Arrays.stream(AccessMode.values()).flatMap(access -> Arrays.stream(CollaborationMode.values())
                .map(mode -> Arguments.of(access, mode)));
    }

    /** 真实 mapper 与 binding 必须通过偏好守卫；工作区哨兵阻止测试触发实际 IO 或执行。 */
    private static void verifyAdmissionPreferences(TurnStartRequest start, ThreadPreferences stored) {
        AgentRequest mapped = JaTurnRequestMapper.user(start, start.content(), TurnOrigin.USER,
                HistorySelection.explicit(List.of(), 8), Map.of("ja.method", "turn/start"));
        ConversationProjectionRepository projections = new ConversationProjectionRepository() {
            /** 读取不变的持久偏好，避免用审查覆盖值伪造源会话。 */
            @Override
            public Optional<ThreadProjection> readThread(String threadId) {
                return Optional.of(new ThreadProjection(threadId, start.workspaceId(), stored, 8, List.of()));
            }

            /** 尚未提交的新审查不能有来源 Turn 投影。 */
            @Override
            public Optional<TurnOwnerProjection> readTurn(String turnId) {
                return Optional.empty();
            }
        };
        ConfigurationJaRequestBindingSource source = new ConfigurationJaRequestBindingSource(projections,
                workspaceId -> { throw new WorkspaceResolved(); },
                identity -> { throw new AssertionError("workspace sentinel must stop resolution"); },
                new JaAdmissionParticipants(),
                facts -> { throw new AssertionError("test must not open a transaction"); },
                facts -> Optional.empty());
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, mapped.identity(),
                Optional.empty(), mapped.contextAttributes(), CancellationToken.none());
        assertThrows(WorkspaceResolved.class, () -> source.bind(request, Optional.empty()));
    }

    /** 严格窄代理使未声明的写操作立即失败，不为测试引入生产 façade 或新 mocking 依赖。 */
    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, arguments) -> handler.apply(method, arguments)));
    }

    /** 仅作为通过全部 admission 偏好守卫后的无 IO 哨兵，不模拟业务成功。 */
    private static final class WorkspaceResolved extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
