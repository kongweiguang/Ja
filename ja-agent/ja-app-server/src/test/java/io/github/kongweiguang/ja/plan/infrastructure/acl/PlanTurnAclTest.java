// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.acl;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.plan.application.gateway.PlanConversationPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTurnPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanWorkspacePort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 ACL 不改写 Conversation 的权威 TurnResult。 */
final class PlanTurnAclTest {
    private static final String THREAD_ID = "thr_plan_acl";
    private static final String TURN_ID = "turn_plan_acl";
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    /** 启动路径对所有真实终态都原样透传，由执行 Adapter 决定推进与停止语义。 */
    @Test
    void startContinuationPreservesAuthoritativeTerminalResult() {
        for (TurnState state : new TurnState[]{TurnState.COMPLETED, TurnState.FAILED, TurnState.CANCELLED}) {
            TurnResult expected = result(state);

            TurnResult actual = start(expected).completion().toCompletableFuture().join();

            assertSame(expected, actual);
        }
    }

    /** 恢复回调收到原 completion identity，避免 ACL 包装后丢失权威 Turn 终态或异常诊断。 */
    @Test
    void resumeContinuationPreservesOriginalCompletionStage() {
        AtomicReference<Consumer<CompletionStage<TurnResult>>> conversationContinuation = new AtomicReference<>();
        TurnUseCase turns = (TurnUseCase) java.lang.reflect.Proxy.newProxyInstance(
                TurnUseCase.class.getClassLoader(), new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("registerResumeContinuation")) {
                        conversationContinuation.set(asContinuation(arguments[1]));
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        AtomicReference<CompletionStage<TurnResult>> received = new AtomicReference<>();
        new PlanTurnAcl(turns).registerResumeContinuation(TURN_ID, received::set);
        CompletionStage<TurnResult> original = new CompletableFuture<>();

        assertNotNull(conversationContinuation.get());
        conversationContinuation.get().accept(original);

        assertSame(original, received.get());
    }

    /** Stop 跨 ACL 保留取消回执的持久终态屏障，供 Plan owner 等待 ledger 结算。 */
    @Test
    void cancelReturnsAuthoritativeCompletionStage() {
        CompletionStage<TurnResult> original = new CompletableFuture<>();
        TurnUseCase turns = (TurnUseCase) java.lang.reflect.Proxy.newProxyInstance(
                TurnUseCase.class.getClassLoader(), new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("cancel")) {
                        return new TurnUseCase.CancelResult(true, TURN_ID, TurnState.CANCELLED, 2, original);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        CompletionStage<TurnResult> actual = new PlanTurnAcl(turns).cancel(TURN_ID);

        assertSame(original, actual);
    }

    /** 沿真实 Conversation 投影、Plan request 工厂与 ACL 验证 nullable 模型默认和显式推理选择均原样透传。 */
    @Test
    void planExecutionPreservesNullableAndExplicitReasoningLevel() {
        for (String reasoningLevel : new String[]{null, "high"}) {
            InternalTurnStartRequest request = startWithReasoningLevel(reasoningLevel);

            assertEquals(reasoningLevel, request.reasoningLevel());
            assertEquals(TurnOrigin.PLAN_EXECUTION, request.origin());
            assertEquals(0, request.initialTurnMutationVersion());
            assertEquals("planrev_plan_acl", request.planRevisionId());
            assertEquals("run_plan_acl", request.runId());
        }
    }

    /** 推理级别可缺省，但 Thread identity 仍是 Plan admission 的必需边界。 */
    @Test
    void startRequestStillRequiresThreadIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new PlanTurnPort.StartRequest(
                " ", TURN_ID, "ws_plan_acl", Path.of("."), "provider_plan_acl", "model_plan_acl", null,
                "FULL_ACCESS", "PLAN", 1, NOW, "planrev_plan_acl", "run_plan_acl"));
        assertThrows(IllegalArgumentException.class, () -> new PlanConversationPort.ThreadBinding(
                " ", "ws_plan_acl", 1, "provider_plan_acl", "model_plan_acl", null, "FULL_ACCESS", "PLAN"));
    }

    /** 非空非法级别仍交由 Conversation 请求边界拒绝，不在 Plan 端口复制运行时校验规则。 */
    @Test
    void conversationAdmissionStillRejectsUnsupportedReasoningLevel() {
        PlanConversationPort.ThreadBinding thread = new PlanConversationPort.ThreadBinding(THREAD_ID,
                "ws_plan_acl", 1, "provider_plan_acl", "model_plan_acl", "unsupported", "FULL_ACCESS", "PLAN");
        PlanTurnPort.StartRequest request = PlanTurnPort.StartRequest.planExecution(thread,
                new PlanWorkspacePort.Binding("ws_plan_acl", Path.of(".")), TURN_ID, NOW,
                "planrev_plan_acl", "run_plan_acl");
        AtomicReference<Boolean> reachedTurnService = new AtomicReference<>(false);
        TurnUseCase turns = (TurnUseCase) Proxy.newProxyInstance(TurnUseCase.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("startContinuation")) {
                        reachedTurnService.set(true);
                        return new TurnUseCase.Accepted(THREAD_ID, TURN_ID, 1, false,
                                CompletableFuture.completedFuture(result(TurnState.COMPLETED)));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        assertThrows(IllegalArgumentException.class, () -> new PlanTurnAcl(turns).startContinuation(request,
                "instruction", ConversationTurnEventSink.noop(), null));
        assertFalse(reachedTurnService.get());
    }

    /** 启动路径以固定 Conversation accepted result 驱动 ACL，不伪造 Plan 侧终态转换。 */
    private static PlanTurnPort.Accepted start(TurnResult turnResult) {
        TurnUseCase turns = (TurnUseCase) java.lang.reflect.Proxy.newProxyInstance(
                TurnUseCase.class.getClassLoader(), new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("startContinuation")) {
                        return new TurnUseCase.Accepted(THREAD_ID, TURN_ID, 1, false,
                                CompletableFuture.completedFuture(turnResult));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        PlanTurnPort.StartRequest request = new PlanTurnPort.StartRequest(THREAD_ID, TURN_ID,
                "ws_plan_acl", Path.of("."), "provider_plan_acl", "model_plan_acl", "medium",
                "FULL_ACCESS", "PLAN", 1, NOW, "planrev_plan_acl", "run_plan_acl");
        return new PlanTurnAcl(turns).startContinuation(request, "instruction",
                ConversationTurnEventSink.noop(), null);
    }

    /** 用合法 ThreadPreferences 构造真实 PlanConversationAcl 投影，并捕获已验证的 Conversation 内部请求。 */
    private static InternalTurnStartRequest startWithReasoningLevel(String reasoningLevel) {
        ThreadPreferences preferences = new ThreadPreferences("provider_plan_acl", "model_plan_acl", reasoningLevel,
                AccessMode.FULL_ACCESS, CollaborationMode.PLAN, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadProjection projection = new ThreadProjection(THREAD_ID, "ws_plan_acl", preferences, 1, List.of());
        ConversationProjectionRepository conversations = (ConversationProjectionRepository) Proxy.newProxyInstance(
                ConversationProjectionRepository.class.getClassLoader(),
                new Class<?>[]{ConversationProjectionRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readThread")) {
                        return Optional.of(projection);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        PlanConversationPort.ThreadBinding thread = new PlanConversationAcl(conversations).readThread(THREAD_ID);
        PlanTurnPort.StartRequest request = PlanTurnPort.StartRequest.planExecution(thread,
                new PlanWorkspacePort.Binding("ws_plan_acl", Path.of(".")), TURN_ID, NOW,
                "planrev_plan_acl", "run_plan_acl");
        AtomicReference<InternalTurnStartRequest> admitted = new AtomicReference<>();
        TurnUseCase turns = (TurnUseCase) Proxy.newProxyInstance(TurnUseCase.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("startContinuation")) {
                        admitted.set((InternalTurnStartRequest) arguments[0]);
                        return new TurnUseCase.Accepted(THREAD_ID, TURN_ID, 1, false,
                                CompletableFuture.completedFuture(result(TurnState.COMPLETED)));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        new PlanTurnAcl(turns).startContinuation(request, "instruction", ConversationTurnEventSink.noop(), null);
        InternalTurnStartRequest admittedRequest = admitted.get();
        assertNotNull(admittedRequest, "Conversation admission must be reached");
        return admittedRequest;
    }

    /** JDK Proxy 在运行期擦除泛型后，仅在此测试边界恢复已知 continuation 类型。 */
    @SuppressWarnings("unchecked")
    private static Consumer<CompletionStage<TurnResult>> asContinuation(Object value) {
        return (Consumer<CompletionStage<TurnResult>>) value;
    }

    /** 用领域合法的终态投影驱动 ACL，避免用字符串或伪造中间状态代替真实结果。 */
    private static TurnResult result(TurnState state) {
        TurnEvent.Context context = new TurnEvent.Context("evt_plan_acl", THREAD_ID, TURN_ID, 1, 0, NOW);
        TurnEvent.Terminal terminal = switch (state) {
            case COMPLETED -> new TurnEvent.Terminal(context, state, "done", null, null,
                    new TurnEvent.FinalMessage("item_plan_acl", "done"), null, TurnChangeSet.emptyComplete());
            case FAILED -> new TurnEvent.Terminal(context, state, "failed", "INTERNAL_ERROR",
                    "provider failed", null, null, null);
            case CANCELLED -> new TurnEvent.Terminal(context, state, "cancelled", null, null,
                    null, null, null);
            default -> throw new IllegalArgumentException("terminal TurnState required");
        };
        return new TurnResult(state, terminal.summary(), terminal);
    }
}
