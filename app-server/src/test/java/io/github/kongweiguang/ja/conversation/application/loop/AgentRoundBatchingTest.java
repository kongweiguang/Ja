// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;

import io.github.kongweiguang.ja.conversation.domain.model.TextContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Agent 单轮流式批处理回归集，锁定顺序、背压、取消、大小上限与关闭排空。 */
final class AgentRoundBatchingTest {
  /** 锁定同类 delta 在截止点合批，跨类型事件仍共享单调 stream sequence。 */
  @Test
  void batchesSameKindAtDeadlineAndSharesSequenceAcrossKinds() {
    ManualTimer timer = new ManualTimer();
    List<TurnEvent> events = new ArrayList<>();
    AgentRound round = round(timer, CancellationToken.none(), event -> accepted(events, event));

    assertTrue(round.onEvent(new ModelPort.TextDelta("hel")).toCompletableFuture().isDone());
    assertTrue(round.onEvent(new ModelPort.TextDelta("lo")).toCompletableFuture().isDone());
    assertTrue(events.isEmpty());
    assertEquals(StreamingDeltaBatcher.FLUSH_DELAY, timer.delay);

    timer.fire();
    TurnEvent.TextDelta text = assertInstanceOf(TurnEvent.TextDelta.class, events.getFirst());
    assertEquals("hello", text.text());
    assertEquals(1L, text.streamSeq());

    round.onEvent(new ModelPort.ReasoningSummaryDelta("public summary"));
    timer.fire();
    TurnEvent.ReasoningSummaryDelta reasoning =
        assertInstanceOf(TurnEvent.ReasoningSummaryDelta.class, events.get(1));
    assertEquals("public summary", reasoning.text());
    assertEquals(2L, reasoning.streamSeq());
    round.close();
    assertTrue(timer.closed);
  }

  /** 锁定文本与推理跨类型边界的原始顺序，避免分批发布重排内容。 */
  @Test
  void preservesTextAndReasoningOrderAcrossKindBoundary() {
    ManualTimer timer = new ManualTimer();
    List<TurnEvent> events = new ArrayList<>();
    AgentRound round = round(timer, CancellationToken.none(), event -> accepted(events, event));

    round.onEvent(new ModelPort.TextDelta("answer "));
    round.onEvent(new ModelPort.ReasoningSummaryDelta("summary"));

    assertEquals(1, events.size());
    assertEquals("answer ", assertInstanceOf(TurnEvent.TextDelta.class, events.getFirst()).text());
    timer.fire();
    assertEquals(
        "summary",
        assertInstanceOf(TurnEvent.ReasoningSummaryDelta.class, events.get(1)).text());
    assertEquals(2L, ((TurnEvent.ReasoningSummaryDelta) events.get(1)).streamSeq());
    round.close();
  }

  /** 锁定大小切分不拆断 Unicode code point，避免流式文本产生替换字符。 */
  @Test
  void keepsUnicodeCodePointsIntactAtSizeBoundary() {
    ManualTimer timer = new ManualTimer();
    List<TurnEvent> events = new ArrayList<>();
    AgentRound round = round(timer, CancellationToken.none(), event -> accepted(events, event));
    String original = "a".repeat(StreamingDeltaBatcher.MAX_BATCH_UTF8_BYTES - 1) + "\uD83D\uDE00";

    CompletionStage<Void> accepted = round.onEvent(new ModelPort.TextDelta(original));

    assertTrue(accepted.toCompletableFuture().isDone());
    assertEquals(2, events.size());
    String first = ((TurnEvent.TextDelta) events.getFirst()).text();
    String second = ((TurnEvent.TextDelta) events.get(1)).text();
    assertFalse(Character.isHighSurrogate(first.charAt(first.length() - 1)));
    assertEquals("\uD83D\uDE00", second);
    assertEquals(original, first + second);
    round.close();
  }

  /** 锁定前一批背压完成后才发布缓冲后继，防止下游顺序与容量失控。 */
  @Test
  void propagatesBackpressureBeforePublishingBufferedSuccessor() {
    ManualTimer timer = new ManualTimer();
    List<TurnEvent> events = new ArrayList<>();
    CompletableFuture<Void> firstAcceptance = new CompletableFuture<>();
    AgentRound round =
        round(
            timer,
            CancellationToken.none(),
            event -> {
              events.add(event);
              return events.size() == 1
                  ? firstAcceptance
                  : CompletableFuture.completedFuture(null);
            });

    CompletionStage<Void> first =
        round.onEvent(new ModelPort.TextDelta("a".repeat(StreamingDeltaBatcher.MAX_BATCH_UTF8_BYTES)));
    CompletionStage<Void> successor = round.onEvent(new ModelPort.TextDelta("b"));

    assertEquals(1, events.size());
    assertFalse(first.toCompletableFuture().isDone());
    assertFalse(successor.toCompletableFuture().isDone());
    firstAcceptance.complete(null);
    assertEquals(2, events.size());
    assertTrue(first.toCompletableFuture().isDone());
    assertTrue(successor.toCompletableFuture().isDone());
    assertEquals("b", ((TurnEvent.TextDelta) events.get(1)).text());
    round.close();
  }

  /** 锁定正常关闭先排空缓冲并拒绝迟到回调，避免终态后继续发布 delta。 */
  @Test
  void flushesBeforeNormalCloseAndRejectsLateCallbacks() {
    ManualTimer timer = new ManualTimer();
    List<TurnEvent> events = new ArrayList<>();
    AgentRound round = round(timer, CancellationToken.none(), event -> accepted(events, event));
    round.onEvent(new ModelPort.TextDelta("tail"));

    round.close();
    round.onEvent(new ModelPort.TextDelta("late"));
    timer.fire();

    assertEquals(1, events.size());
    assertEquals("tail", ((TurnEvent.TextDelta) events.getFirst()).text());
    assertTrue(timer.closed);
  }

  /** 锁定取消丢弃待发布与迟到回调，防止取消后的内容进入最终消息。 */
  @Test
  void cancellationDiscardsPendingAndLateCallbacks() {
    ManualTimer timer = new ManualTimer();
    ManualCancellation cancellation = new ManualCancellation();
    List<TurnEvent> events = new ArrayList<>();
    AgentRound round = round(timer, cancellation, event -> accepted(events, event));
    round.onEvent(new ModelPort.TextDelta("unpublished"));

    cancellation.cancel();
    timer.fire();
    round.onEvent(new ModelPort.TextDelta("late"));
    round.close();

    assertTrue(events.isEmpty());
    assertTrue(timer.closed);
  }

  /** 锁定关闭排空期间发生取消会丢弃后继缓冲，避免越过取消终点。 */
  @Test
  void cancellationDuringCloseDropsBufferedSuccessor() throws InterruptedException {
    ManualTimer timer = new ManualTimer();
    ManualCancellation cancellation = new ManualCancellation();
    List<TurnEvent> events = new ArrayList<>();
    CompletableFuture<Void> blockedSink = new CompletableFuture<>();
    CountDownLatch published = new CountDownLatch(1);
    AgentRound round =
        round(
            timer,
            cancellation,
            event -> {
              events.add(event);
              published.countDown();
              return blockedSink;
            });
    round.onEvent(
        new ModelPort.TextDelta("a".repeat(StreamingDeltaBatcher.MAX_BATCH_UTF8_BYTES)));
    round.onEvent(new ModelPort.TextDelta("must be discarded"));

    Thread closeThread = Thread.startVirtualThread(round::close);
    published.await();
    cancellation.cancel();

    assertTrue(closeThread.isAlive());
    assertEquals(1, events.size());
    blockedSink.complete(null);
    assertTrue(closeThread.join(Duration.ofSeconds(1)));
    assertEquals(1, events.size());
    assertTrue(timer.closed);
  }

  /** 锁定取消关闭仍传播在途 sink 失败，避免资源收口掩盖传输错误。 */
  @Test
  void cancellationClosePropagatesInflightSinkFailure() throws InterruptedException {
    ManualTimer timer = new ManualTimer();
    ManualCancellation cancellation = new ManualCancellation();
    List<TurnEvent> events = new ArrayList<>();
    CompletableFuture<Void> blockedSink = new CompletableFuture<>();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    AgentRound round =
        round(
            timer,
            cancellation,
            event -> {
              events.add(event);
              return blockedSink;
            });
    round.onEvent(
        new ModelPort.TextDelta("a".repeat(StreamingDeltaBatcher.MAX_BATCH_UTF8_BYTES)));
    round.onEvent(new ModelPort.TextDelta("must be discarded"));
    Thread closeThread =
        Thread.startVirtualThread(
            () -> {
              try {
                round.close();
              } catch (Throwable failure) {
                closeFailure.set(failure);
              }
            });

    cancellation.cancel();
    assertTrue(closeThread.isAlive());
    IllegalStateException rejection = new IllegalStateException("sink rejected batch");
    blockedSink.completeExceptionally(rejection);

    assertTrue(closeThread.join(Duration.ofSeconds(1)));
    AgentRound.DeltaDrainException typed =
        assertInstanceOf(AgentRound.DeltaDrainException.class, closeFailure.get());
    assertEquals(AgentRound.DeltaDrainException.Code.SINK_FAILURE, typed.code());
    assertEquals(rejection, typed.getCause());
    assertEquals(1, events.size());
    assertTrue(timer.closed);
  }

  /** 锁定排空超过截止时间时失败关闭，避免关闭流程无限等待下游。 */
  @Test
  void drainDeadlineFailsClosedWithoutWaitingForever() {
    ManualTimer timer = new ManualTimer();
    CompletableFuture<Void> blockedSink = new CompletableFuture<>();
    AgentRound round =
        round(timer, CancellationToken.none(), event -> blockedSink, Duration.ZERO);
    round.onEvent(
        new ModelPort.TextDelta("a".repeat(StreamingDeltaBatcher.MAX_BATCH_UTF8_BYTES)));

    AgentRound.DeltaDrainException typed =
        assertThrows(AgentRound.DeltaDrainException.class, round::close);
    assertEquals(AgentRound.DeltaDrainException.Code.TIMEOUT, typed.code());
    assertInstanceOf(java.util.concurrent.TimeoutException.class, typed.getCause());
    blockedSink.complete(null);
    assertTrue(timer.closed);
  }

  /** 锁定类型边界处 sink 失败阻止后续类型发布，保持错误后的事件前缀一致。 */
  @Test
  void kindBoundaryPropagatesSinkFailureWithoutPublishingLaterKind() {
    ManualTimer timer = new ManualTimer();
    List<TurnEvent> events = new ArrayList<>();
    IllegalStateException rejection = new IllegalStateException("sink rejected batch");
    AgentRound round =
        round(
            timer,
            CancellationToken.none(),
            event -> {
              events.add(event);
              return CompletableFuture.failedFuture(rejection);
            });
    round.onEvent(new ModelPort.TextDelta("text"));

    CompletionStage<Void> boundary =
        round.onEvent(new ModelPort.ReasoningSummaryDelta("must not publish"));

    assertThrows(RuntimeException.class, () -> boundary.toCompletableFuture().join());
    assertEquals(1, events.size());
    assertInstanceOf(TurnEvent.TextDelta.class, events.getFirst());
    AgentRound.DeltaDrainException typed =
        assertThrows(AgentRound.DeltaDrainException.class, round::close);
    assertEquals(AgentRound.DeltaDrainException.Code.SINK_FAILURE, typed.code());
    assertEquals(rejection, typed.getCause());
    assertTrue(timer.closed);
  }

  /** 锁定十万条微小 delta 最终合成一个文本块，避免碎片数量随输入线性泄漏。 */
  @Test
  void hundredThousandTinyDeltasMaterializeOneTextBlock() {
    ManualTimer timer = new ManualTimer();
    AtomicLong publishedEvents = new AtomicLong();
    AgentRound round =
        round(
            timer,
            CancellationToken.none(),
            event -> {
              publishedEvents.incrementAndGet();
              return CompletableFuture.completedFuture(null);
            });

    for (int index = 0; index < 100_000; index++) {
      round.onEvent(new ModelPort.TextDelta("x"));
    }
    round.onEvent(new ModelPort.ReasoningSummaryDelta("public summary only"));
    round.close();

    assertEquals(1, round.textBlockMaterializations());
    List<ModelContent> content = round.assistantContent();
    assertEquals(1, content.size());
    assertEquals(
        "x".repeat(100_000), assertInstanceOf(TextContent.class, content.getFirst()).text());
    assertTrue(publishedEvents.get() <= 14);
  }

  /** 锁定助手文本超过四百万字符时失败关闭，避免无界内存物化。 */
  @Test
  void assistantTextBlockAboveFourMillionCharactersFailsClosed() {
    ManualTimer timer = new ManualTimer();
    AgentRound round =
        round(
            timer,
            CancellationToken.none(),
            event -> CompletableFuture.completedFuture(null));
    String million = "x".repeat(1_000_000);
    for (int index = 0; index < 4; index++) {
      round.onEvent(new ModelPort.TextDelta(million)).toCompletableFuture().join();
    }

    RuntimeException overflow =
        assertThrows(
            RuntimeException.class,
            () -> round.onEvent(new ModelPort.TextDelta("overflow")).toCompletableFuture().join());
    assertInstanceOf(AgentLoop.LoopFailure.class, overflow.getCause());
    round.onEvent(new ModelPort.TextDelta("late and ignored")).toCompletableFuture().join();
    round.close();

    assertEquals(1, round.textBlockMaterializations());
    TextContent retained =
        assertInstanceOf(TextContent.class, round.assistantContent().getFirst());
    assertEquals(4_000_000, retained.text().length());
    assertFalse(retained.text().endsWith("late and ignored"));
    assertTrue(timer.closed);
  }

  /** 锁定文本、Tool、文本交错后关闭仍保留助手块顺序，避免 Tool 边界错位。 */
  @Test
  void textToolTextClosePreservesAssistantBlockOrder() {
    ManualTimer timer = new ManualTimer();
    AgentRound round =
        round(
            timer,
            CancellationToken.none(),
            event -> CompletableFuture.completedFuture(null));

    round.onEvent(new ModelPort.TextDelta("before"));
    round.onEvent(
        new ModelPort.ToolCallReady(
            "call_demo", "echo", JsonObjects.builder().putText("text", "value").build(), 0));
    round.onEvent(new ModelPort.TextDelta("after"));
    round.close();

    List<ModelContent> content = round.assistantContent();
    assertEquals(3, content.size());
    assertEquals("before", assertInstanceOf(TextContent.class, content.get(0)).text());
    ToolCallContent call =
        assertInstanceOf(ToolCallContent.class, content.get(1));
    assertEquals("call_demo", call.callId());
    assertEquals("echo", call.name());
    assertEquals("after", assertInstanceOf(TextContent.class, content.get(2)).text());
    assertEquals(2, round.textBlockMaterializations());
  }

  /** 使用默认成功 sink 构造一轮 Agent 流，供纯批处理行为测试复用。 */
  private static AgentRound round(
      StreamingDeltaBatcher.Timer timer,
      CancellationToken cancellation,
      TurnEventSink sink) {
    return new AgentRound(
        "turn_batch",
        cancellation,
        sink,
        () -> false,
        1,
        timer,
        new TestSequences());
  }

  /** 注入可控 sink 与时钟构造 Agent 轮次，使背压和截止时间可确定复现。 */
  private static AgentRound round(
      StreamingDeltaBatcher.Timer timer,
      CancellationToken cancellation,
      TurnEventSink sink,
      Duration drainTimeout) {
    return new AgentRound(
        "turn_batch",
        cancellation,
        sink,
        () -> false,
        1,
        timer,
        new TestSequences(),
        drainTimeout);
  }

  /** 记录事件并返回已完成阶段，作为无背压下游的基准实现。 */
  private static CompletionStage<Void> accepted(List<TurnEvent> events, TurnEvent event) {
    events.add(event);
    return CompletableFuture.completedFuture(null);
  }

  /** 手动触发的单任务定时器，用于精确控制批次截止点而不依赖真实时间。 */
  private static final class ManualTimer implements StreamingDeltaBatcher.Timer {
    private Runnable callback;
    private Duration delay;
    private boolean closed;

    /** 保存唯一待触发回调，拒绝隐式并发定时任务掩盖批处理错误。 */
    @Override
    public Task schedule(Runnable callback, Duration delay) {
      this.callback = callback;
      this.delay = delay;
      return () -> {
        if (this.callback == callback) {
          this.callback = null;
        }
      };
    }

    /** 同步触发并清除当前回调，模拟截止点只消费一次任务。 */
    private void fire() {
      Runnable current = callback;
      callback = null;
      if (current != null && !closed) {
        current.run();
      }
    }

    /** 清除未触发回调，避免夹具关闭后残留定时动作。 */
    @Override
    public void close() {
      closed = true;
      callback = null;
    }
  }

  /** 分别记录 stream sequence 与 Tool ordinal 的测试分配器，用于断言单调性。 */
  private static final class TestSequences implements AgentRound.SequenceAllocator {
    private final AtomicLong stream = new AtomicLong(1);

    /** 分配下一条全局流序号，使跨类型批次共享同一序列。 */
    @Override
    public long allocateStreamSequence() {
      return stream.getAndIncrement();
    }

    /** 连续保留指定数量的 Tool ordinal，复现生产批量分配语义。 */
    @Override
    public int allocateToolOrdinals(int count) {
      return 0;
    }
  }

  /** 可同步触发的取消 token，用于覆盖排空与回调注册竞态。 */
  private static final class ManualCancellation implements CancellationToken {
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    /** 回读夹具取消标志，确保轮次在每个发布边界都观察同一状态。 */
    @Override
    public boolean isCancellationRequested() {
      return cancelled;
    }

    /** 仅在取消后返回固定原因，便于断言原因传播不依赖外部输入。 */
    @Override
    public Optional<String> reason() {
      return cancelled ? Optional.of("test cancellation") : Optional.empty();
    }

    /** 登记可撤销回调，使测试能精确模拟取消监听器的生命周期。 */
    @Override
    public Registration onCancellation(Runnable callback) {
      callbacks.add(callback);
      return () -> callbacks.remove(callback);
    }

    /** 原子切换取消状态并调用当前监听器，复现单次取消通知。 */
    private void cancel() {
      if (cancelled) {
        return;
      }
      cancelled = true;
      List<Runnable> retained = List.copyOf(callbacks);
      callbacks.clear();
      retained.forEach(Runnable::run);
    }
  }
}
