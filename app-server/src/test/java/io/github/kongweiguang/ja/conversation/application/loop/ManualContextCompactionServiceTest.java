// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证手动压缩在任何 Provider、MCP 或 Workspace 副作用前执行空闲态与 CAS 门禁。 */
final class ManualContextCompactionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC);

    /** 非终态 Turn 必须稳定返回 THREAD_BUSY，且不触碰 Checkpoint 或运行时解析。 */
    @Test
    void rejectsBusyThreadBeforeExternalWork() {
        ConversationRepository.TurnSnapshot turn = new ConversationRepository.TurnSnapshot(
                "thr_test", "turn_test", TurnState.RUNNING, runtime(),
                CLOCK.instant(), CLOCK.instant(), null, 7, 1);
        ManualContextCompactionService service = service(snapshot(7, List.of(turn)));
        ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                () -> service.compact(new ContextCompactionUseCase.Command("thr_test", 7), event -> {
                    throw new AssertionError("busy Thread must not publish compaction lifecycle");
                }, CancellationToken.none()));
        assertEquals(ContextCompactionUseCase.Code.THREAD_BUSY, failure.code());
    }

    /** 陈旧 expected revision 必须先于 busy 判断返回 CONFLICT，调用方需重新读取后再决定入口。 */
    @Test
    void rejectsRevisionConflictBeforeExternalWork() {
        ManualContextCompactionService service = service(snapshot(8, List.of()));
        ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                () -> service.compact(new ContextCompactionUseCase.Command("thr_test", 7), event -> {
                    throw new AssertionError("conflict must not publish compaction lifecycle");
                }, CancellationToken.none()));
        assertEquals(ContextCompactionUseCase.Code.CONFLICT, failure.code());
    }

    /** 缺失 Thread 使用稳定 NOT_FOUND，不尝试从配置或 Workspace 猜测身份。 */
    @Test
    void rejectsMissingThreadBeforeExternalWork() {
        ManualContextCompactionService service = service(null);
        ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                () -> service.compact(new ContextCompactionUseCase.Command("thr_test", 0), event -> {
                    throw new AssertionError("missing Thread must not publish lifecycle");
                }, CancellationToken.none()));
        assertEquals(ContextCompactionUseCase.Code.THREAD_NOT_FOUND, failure.code());
    }

    /** 已取消连接不得读取 Thread 或触发任何外部资源，且公开稳定 CANCELLED。 */
    @Test
    void rejectsPreCancelledRequestBeforeExternalWork() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("runtime_closed");
        ManualContextCompactionService service = service(snapshot(7, List.of()));

        ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                () -> service.compact(new ContextCompactionUseCase.Command("thr_test", 7), event -> {
                    throw new AssertionError("cancelled request must not publish lifecycle");
                }, cancellation));

        assertEquals(ContextCompactionUseCase.Code.CANCELLED, failure.code());
    }

    /** 构造最小权威 Thread 快照；早期门禁测试不伪造任何历史消息。 */
    private static ConversationRepository.ThreadSnapshot snapshot(
            long revision, List<ConversationRepository.TurnSnapshot> turns) {
        return new ConversationRepository.ThreadSnapshot("thr_test", "ws_test", "title", preferences(),
                revision, turns, List.of(), CLOCK.instant(), CLOCK.instant());
    }

    /** 固定下一轮 Provider/Model 偏好，使早期门禁夹具不依赖配置 Owner。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 固定既有 Turn 的独立运行事实，避免测试借当前 Thread 偏好解释历史。 */
    private static TurnRuntimeSnapshot runtime() {
        return new TurnRuntimeSnapshot("provider_test", "model_test", "openai", "openai_responses",
                "test-model", "medium", AccessMode.APPROVAL_REQUIRED, "cfg_test");
    }

    /** 非目标端口全部使用拒绝代理，任何越过早期门禁的调用都会使测试失败。 */
    private static ManualContextCompactionService service(ConversationRepository.ThreadSnapshot snapshot) {
        ConversationRepository repository = new EarlyRepository(snapshot);
        CheckpointStore checkpoints = unsupported(CheckpointStore.class);
        SummaryModel.Factory summaryModels = binding -> prompt -> {
            throw new AssertionError("summary model must not be bound");
        };
        return new ManualContextCompactionService(repository, checkpoints,
                unsupported(WorkspaceUseCase.class), unsupported(TurnRuntimeResolver.class),
                new ContextOrchestratorFactory(checkpoints, CLOCK, summaryModels),
                unsupported(ModelPort.class), unsupported(JsonValueCodec.class), CLOCK);
    }

    /** 用动态拒绝代理缩小 fixture，接口新增方法时不会静默获得成功默认值。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("unexpected call: " + method.getName()); });
    }

    /** 只允许 readThread 的存储夹具，所有 mutation 与关闭都保持显式可见。 */
    private static final class EarlyRepository implements ConversationRepository {
        private final ConversationRepository.ThreadSnapshot snapshot;

        /** 固定本次读取结果，null 表达不存在而不是异常。 */
        private EarlyRepository(ConversationRepository.ThreadSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /** 返回唯一测试 Thread，不接受其它身份。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId) {
            return "thr_test".equals(threadId) ? Optional.ofNullable(snapshot) : Optional.empty();
        }

        /** 早期门禁测试不创建 Thread。 */
        @Override public ThreadSnapshot createThread(ThreadDefinition thread) { throw unsupported(); }
        /** 早期门禁测试不接纳 Turn。 */
        @Override public AdmissionReceipt admit(TurnAdmission admission) { throw unsupported(); }
        /** 早期门禁测试不提交事实。 */
        @Override public CommitReceipt commit(CommitRequest request) { throw unsupported(); }
        /** 早期门禁测试不提交取消 Tool batch。 */
        @Override public CommitReceipt commitCancellationToolBatch(CancellationToolBatchCommit request) { throw unsupported(); }
        /** 早期门禁测试不提交终态。 */
        @Override public CommitReceipt commitTerminal(TerminalCommit request) { throw unsupported(); }
        /** 早期门禁测试不声明取消。 */
        @Override public CancellationClaim claimCancellation(String threadId, String turnId, long revision,
                                                              String reason, Instant occurredAt) { throw unsupported(); }
        /** 早期门禁测试不按 Turn 查找。 */
        @Override public Optional<TurnSnapshot> findTurn(String threadId, String turnId) { throw unsupported(); }
        /** fixture 不拥有资源，关闭保持幂等无副作用。 */
        @Override public void close() { }

        /** 生成统一拒绝，避免每个无关端口携带不同假语义。 */
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("operation is outside early gate fixture");
        }
    }
}
