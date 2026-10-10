// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 持有当前 Java 运行代际的公开流基线；它只缓存可恢复的公开 delta，不替代 SQLite 历史。
 *
 * <p>基线在出站事件边界更新，但只有与 {@code thread/read} 同 revision 的快照才能被读取，
 * 因而数据库提交与 stdout 排队之间的短窗口只会得到 {@code null}，不会把半旧状态伪装成完整历史。</p>
 */
public final class ActiveStreamRegistry {
    /**
     * 单个 segment 的硬上限，避免一次异常 Provider delta 占满恢复响应。
     */
    public static final int MAX_SEGMENT_BYTES = 64 * 1024;
    /**
     * 一个活动 Turn 的公开基线最多保留 256 个 segment。
     */
    public static final int MAX_SEGMENTS = 256;
    /**
     * 活动基线总公开 UTF-8 bytes 上限；超限时整体失效而不是返回残缺正文。
     */
    public static final int MAX_PUBLIC_BYTES = 1_048_576;
    /**
     * 连接级活动 registry 上限与现有 Turn queue 的 admitted budget 对齐。
     */
    public static final int MAX_ACTIVE_STREAMS = 64;
    /**
     * durable event 只做有限时间窗口去重；长期历史由 SQLite 负责，不能在内存中累积。
     */
    private static final int MAX_EVENT_IDS = 256;
    private final long runtimeGeneration;
    private final java.util.Map<String, State> states = new java.util.HashMap<>();

    /**
     * 绑定唯一运行代际；重启或连接替换后旧基线不能被新 RPC 会话读取。
     */
    public ActiveStreamRegistry(long runtimeGeneration) {
        if (runtimeGeneration < 1 || runtimeGeneration > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("invalid runtime generation");
        }
        this.runtimeGeneration = runtimeGeneration;
    }

    /**
     * 仅合并同一模型请求的相邻 delta，避免迟到提交前跨请求拼接；活动段只追加当前 delta，
     * 避免每个 Provider 分片都复制和重新扫描整段正文。
     */
    private static void appendChunk(State state, SegmentKind kind, long streamSeq,
                                    String text, String modelRequestId, Instant occurredAt,
                                    boolean canMerge, int textBytes, int appendedBytes) {
        SegmentBuffer previous = state.segments.isEmpty() ? null : state.segments.getLast();
        if (canMerge) {
            previous.append(streamSeq, text, appendedBytes);
            state.totalBytes += appendedBytes;
        } else {
            state.segments.add(new SegmentBuffer(kind, streamSeq, text, occurredAt, textBytes));
            state.totalBytes += textBytes;
        }
        state.lastSegmentModelRequestId = modelRequestId;
    }

    /**
     * Java UTF-8 编码会把未配对 surrogate 各计为一个 replacement byte；拼接成代理对后是四字节，
     * 因而只在 delta 接缝处补上两字节差额，不重扫之前的正文。
     */
    private static int appendedUtf8Bytes(SegmentBuffer previous, String text, int textBytes) {
        if (text.isEmpty() || previous.text.length() == 0) {
            return textBytes;
        }
        char last = previous.text.charAt(previous.text.length() - 1);
        return Character.isHighSurrogate(last) && Character.isLowSurrogate(text.charAt(0))
                ? textBytes + 2
                : textBytes;
    }

    /**
     * 以 Wire 实际占用的 UTF-8 bytes 作为内存与响应预算，避免中文按 UTF-16 欺骗上限。
     */
    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 严格绑定相同 Thread/Turn 的 context revision。
     */
    private static void requireRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("invalid stream revision");
        }
    }

    /**
     * Turn CAS 水位是持久 fence 的内部事实，不能使用负数或从 Thread revision 推导。
     */
    private static void requireMutationVersion(long mutationVersion) {
        if (mutationVersion < 0) {
            throw new IllegalArgumentException("invalid stream mutation version");
        }
    }

    /**
     * 本处只拒绝负数；未发起首轮使用零，模型轮次数不设额外上限。
     */
    private static void requireModelRound(int modelRound) {
        if (modelRound < 0) {
            throw new IllegalArgumentException("invalid model round");
        }
    }

    /**
     * 只接受已有 JA-RPC opaque identity 词汇，避免 registry 成为第二套 ID 规则。
     */
    private static void requireIdentifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid stream identity");
        }
    }

    /**
     * 在 Turn admission 或恢复前登记 Workspace/Thread/Turn 身份及已完成模型轮次，使首个 delta 绑定同一
     * generation fence；内部 execution 的 modelRound 不受 dispatch UNKNOWN 或 Thread metadata 影响，
     * 因而可作为公开 ModelStep 是否已被观察的语义 fence。重开挂起 Turn 只推进 revision，保留
     * 尚未提交的公开游标。
     */
    public synchronized void register(String turnId, String workspaceId, String threadId, long threadRevision,
                                      long turnMutationVersion, int modelRound) {
        requireIdentifier(turnId, "turn_");
        requireIdentifier(workspaceId, "ws_");
        requireIdentifier(threadId, "thr_");
        requireRevision(threadRevision);
        requireMutationVersion(turnMutationVersion);
        requireModelRound(modelRound);
        State current = states.get(turnId);
        if (current != null && (!current.workspaceId.equals(workspaceId) || !current.threadId.equals(threadId)
            || current.generation != runtimeGeneration)) {
            throw new IllegalStateException("active stream identity changed");
        }
        advanceThreadRevision(workspaceId, threadId, threadRevision);
        if (current == null) {
            /* 恢复缓存容量不足时继续让 Turn 正常运行，read 只返回 null 而不阻塞真实执行。 */
            if (states.size() >= MAX_ACTIVE_STREAMS) {
                return;
            }
            states.put(turnId, new State(runtimeGeneration, turnId, workspaceId, threadId, threadRevision,
                    turnMutationVersion, modelRound));
            return;
        }
        current.observeRevision(threadRevision);
        current.observeMutationVersion(turnMutationVersion);
        current.observeModelRound(modelRound);
    }

    /**
     * 返回与持久快照同 revision 的完整公开基线；revision 不一致、序号断裂或预算溢出均返回空，
     * 让调用方等待后续权威 checkpoint，而不是接受残缺文本。
     */

    /**
     * admission 失败、连接取消或终态收口时释放临时内存；删除操作保持幂等，便于多个清理路径竞态。
     */
    public synchronized void abandon(String turnId) {
        if (turnId != null) {
            states.remove(turnId);
        }
    }

    /**
     * 按 Thread 释放全部临时流；删除的是 Thread 而不是某一个 Turn，不能复用按 Turn 清理的入口。
     * 删除与终态清理都保持幂等，使删除响应和迟到事件并发时不会重新暴露已删除会话的草稿。
     */
    public synchronized void abandonThread(String threadId) {
        if (threadId == null) {
            return;
        }
        requireIdentifier(threadId, "thr_");
        states.entrySet().removeIf(entry -> entry.getValue().threadId.equals(threadId));
    }

    /**
     * 在唯一 Turn 出站边界吸收事件；流式 delta 不改变数据库 revision，持久事件只推进 revision。
     * ModelStepCommitted 和 RetryStarted 清草稿但保留 Turn 全局 streamSeq；模型请求身份仅参与
     * 内部片段合并，不进入公开恢复数据。
     */
    public synchronized void observe(TurnEvent event, Instant occurredAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (event instanceof TurnEvent.TextDelta delta) {
            append(delta.turnId(), SegmentKind.ASSISTANT, delta.streamSeq(), delta.text(),
                    delta.modelRequestId(), occurredAt);
            return;
        }
        if (event instanceof TurnEvent.ReasoningSummaryDelta delta) {
            append(delta.turnId(), SegmentKind.REASONING_SUMMARY, delta.streamSeq(), delta.text(),
                    delta.modelRequestId(), occurredAt);
            return;
        }
        TurnEvent.Context context = event.context();
        if (context == null) {
            return;
        }
        /* Thread revision 是共享水位；queued/running 的任何 durable 提交都必须推进同 Thread 其它 stream。 */
        State owned = states.get(context.turnId());
        states.values().stream().filter(state -> state.threadId.equals(context.threadId()))
                .forEach(state -> state.observeRevision(context.threadRevision()));
        if (owned == null || !owned.threadId.equals(context.threadId())
            || owned.generation != runtimeGeneration) {
                return;
            }
        /* 终态属于精确 Turn；即使其 Thread revision 晚到，也必须释放对应 registry 状态。 */
        if (event instanceof TurnEvent.Terminal
            || event instanceof TurnEvent.StateChanged changed && changed.to().terminal()) {
            states.remove(context.turnId());
            return;
        }
        State state = owned;
        if (event instanceof TurnEvent.RetryStarted) {
            /* 重试通知是临时状态，但受已提交的尝试结算边界约束；重复或过期通知不能清除下一次尝试已接收的文本。 */
            if (context.turnMutationVersion() == state.mutationVersion
                && state.rememberEvent(context.eventId())) {
                state.clearSegments();
            }
            return;
        }
        if (event instanceof TurnEvent.ModelStepCommitted modelStep) {
            /* ModelStep 的 modelRound 是持久提交语义；即使其 Thread revision 较晚到，也要先
             * 收敛这个 Turn 的语义 fence，再清理已经落库的 draft。 */
            if (state.observeModelStep(context, modelStep.modelRound())) {
                state.clearSegments();
            }
            return;
        }
        if (!state.observeDurableEvent(context)) {
            return;
        }
        if (event instanceof TurnEvent.StateChanged changed) {
            state.lifecycle = changed.to();
        }
        if (event instanceof TurnEvent.InputConsumed consumed && consumed.assistantSettlement() != null) {
            /* STOP 后消费下一条输入时，AssistantSettlement 与队列迁移同一事务完成；不能保留旧 draft。 */
            if (context.turnMutationVersion() <= state.lastSettledMutationVersion) {
                return;
            }
            state.lastSettledMutationVersion = context.turnMutationVersion();
            state.clearSettledDraft();
        }
    }

    /**
     * 吸收同一 SQLite 提交但没有公开 TurnEvent 的内部边界，例如 Provider dispatch 的 UNKNOWN usage。
     * 这条进程内观察不写 wire；modelRound 与 mutation 一起更新，避免 read 在首段流期间误判为断流。
     */
    public synchronized void observeTurnCommit(String threadId, String turnId, long threadRevision,
                                               long turnMutationVersion, int modelRound,
                                               TurnEvent event, Instant occurredAt) {
        requireIdentifier(threadId, "thr_");
        requireIdentifier(turnId, "turn_");
        requireRevision(threadRevision);
        requireMutationVersion(turnMutationVersion);
        requireModelRound(modelRound);
        Objects.requireNonNull(occurredAt, "occurredAt");
        State state = states.get(turnId);
        states.values().stream().filter(candidate -> candidate.threadId.equals(threadId))
                .forEach(candidate -> candidate.observeRevision(threadRevision));
        if (state == null || !state.threadId.equals(threadId) || state.generation != runtimeGeneration) {
            return;
        }
        if (event != null) {
            /*
             * AssistantSettlement 属于“先结算旧轮次、再启动下一轮”的同一事务；先吸收
             * 持久 execution 的 modelRound，才能让 clearSettledDraft 把 cleared fence 绑定到
             * 新快照。ModelStep 则必须先走事件本身，避免通用水位抢先使真实清稿失效。
             */
            if (event instanceof TurnEvent.InputConsumed consumed
                && consumed.assistantSettlement() != null) {
                state.observeModelRound(modelRound);
            }
            observe(event, occurredAt);
            /* 事件本身先完成语义清理，再吸收同一提交回执的 modelRound；若二者不一致则 fail closed。 */
            state.observeModelRound(modelRound);
        } else {
            state.observeMutationVersion(turnMutationVersion);
            state.observeModelRound(modelRound);
        }
    }

    /**
     * 标题、偏好、压缩等 Thread 级提交不携带 Turn 事件，但仍会改变快照 revision；只推进相同
     * Workspace/Thread 的活动流基线，防止 read 将新目录版本与旧公开正文拼接。
     */
    public synchronized void observeThreadRevision(String workspaceId, String threadId, long threadRevision) {
        requireIdentifier(workspaceId, "ws_");
        requireIdentifier(threadId, "thr_");
        requireRevision(threadRevision);
        advanceThreadRevision(workspaceId, threadId, threadRevision);
    }

    /**
     * 登记与提交观察共用同一 Workspace/Thread max 推进规则，防止并发旧回执回拨活动流基线。
     */
    private void advanceThreadRevision(String workspaceId, String threadId, long threadRevision) {
        states.values().stream().filter(state -> state.workspaceId.equals(workspaceId)
                        && state.threadId.equals(threadId))
                .forEach(state -> state.observeRevision(threadRevision));
    }

    /**
     * 将 Context Compaction 的 revision 绑定到整个 Thread；即使事件带 turnId，也不能让 queued 流停在旧水位。
     */
    public synchronized void observeCompaction(ContextCompactionEvent.Context context) {
        Objects.requireNonNull(context, "context");
        /* Thread revision 由同一 SQLite owner 共享；即使压缩带 turnId，也必须推进同 Thread queued/running。 */
        observeThreadRevision(context.workspaceId(), context.threadId(), context.threadRevision());
        if (context.turnId() != null && context.turnMutationVersion() != null) {
            State state = states.get(context.turnId());
            if (state != null && state.workspaceId.equals(context.workspaceId())
                && state.threadId.equals(context.threadId())
                && state.generation == runtimeGeneration) {
                state.observeDurableBoundary(context.eventId(), context.turnMutationVersion());
            }
        }
    }

    /**
     * 仅当 Thread 目录 revision、每个活动 Turn 的 mutation 水位和已完成模型轮次都来自同一
     * SQLite 快照时发布基线；任何一个 fence 尚未由出站事件消费都返回空，避免以较新的 metadata
     * 覆盖迟到 ModelStepCommitted。preferredTurnIds 为空也不再猜测旧状态，调用方必须明确活动集合。
     */
    public synchronized Optional<Snapshot> snapshot(String threadId, long snapshotRevision,
                                                    Set<String> preferredTurnIds,
                                                    Map<String, Long> persistedMutationVersions,
                                                    Map<String, Integer> persistedModelRounds) {
        requireIdentifier(threadId, "thr_");
        requireRevision(snapshotRevision);
        Objects.requireNonNull(preferredTurnIds, "preferredTurnIds");
        Objects.requireNonNull(persistedMutationVersions, "persistedMutationVersions");
        Objects.requireNonNull(persistedModelRounds, "persistedModelRounds");
        if (preferredTurnIds.isEmpty()) {
            return Optional.empty();
        }
        List<State> candidates = states.values().stream()
                .filter(state -> state.threadId.equals(threadId) && state.generation == runtimeGeneration
                                 && state.revision == snapshotRevision
                                 && state.matchesFence(persistedMutationVersions, persistedModelRounds))
                .toList();
        if (!preferredTurnIds.isEmpty()) {
            List<State> preferred = candidates.stream()
                    .filter(state -> preferredTurnIds.contains(state.turnId)).toList();
            if (!preferred.isEmpty()) {
                candidates = preferred;
            }
        }
        if (candidates.size() > 1) {
            List<State> running = candidates.stream()
                    .filter(state -> state.lifecycle != TurnState.QUEUED).toList();
            if (running.size() == 1) {
                candidates = running;
            }
            else {
                List<State> streaming = candidates.stream().filter(state -> state.streamSeq > 0).toList();
                if (streaming.size() == 1) {
                    candidates = streaming;
                }
                else {
                    return Optional.empty();
                }
            }
        }
        State match = candidates.isEmpty() ? null : candidates.getFirst();
        if (match == null || !match.complete || match.overflow) {
            return Optional.empty();
        }
        return Optional.of(match.snapshot());
    }

    /**
     * 判断 Thread 是否仍有连接级活动 Turn；用于区分“无活动基线”和“revision 窗口尚未收敛”。
     */
    public synchronized boolean hasActive(String threadId) {
        requireIdentifier(threadId, "thr_");
        return states.values().stream().anyMatch(state -> state.generation == runtimeGeneration
                                                          && state.threadId.equals(threadId));
    }

    /**
     * 连接关闭时显式释放 registry，不能依赖 JVM 退出回收仍可能被异步 read 引用的列表。
     */
    public synchronized void clear() {
        states.clear();
    }

    /**
     * 接收公开 delta 并严格维护连续序号；内部请求身份防止相邻模型请求的文本被拼成一段。
     * 发现缺口后只保留游标，不发布不完整 segment。
     */
    private void append(String turnId, SegmentKind kind, long streamSeq, String text,
                        String modelRequestId, Instant occurredAt) {
        State state = states.get(turnId);
        if (state == null || state.generation != runtimeGeneration) {
            return;
        }
        if (streamSeq <= state.streamSeq) {
            return;
        }
        if ((state.streamSeq == 0 && streamSeq != 1)
            || (state.streamSeq > 0 && streamSeq != state.streamSeq + 1)) {
                state.complete = false;
            }
        state.streamSeq = streamSeq;
        if (!state.complete || state.overflow) {
            return;
        }
        int textBytes = utf8Bytes(text);
        SegmentBuffer previous = state.segments.isEmpty() ? null : state.segments.getLast();
        boolean adjacent = previous != null && previous.kind == kind
                           && previous.streamSeq + 1 == streamSeq
                           && modelRequestId.equals(state.lastSegmentModelRequestId);
        int appendedBytes = adjacent ? appendedUtf8Bytes(previous, text, textBytes) : textBytes;
        boolean canMerge = adjacent && previous.utf8Bytes + appendedBytes <= MAX_SEGMENT_BYTES;
        int retainedBytes = canMerge ? appendedBytes : textBytes;
        if (textBytes > MAX_SEGMENT_BYTES
            || retainedBytes > MAX_PUBLIC_BYTES - state.totalBytes
            || (!canMerge && state.segments.size() >= MAX_SEGMENTS)) {
            state.overflow = true;
            state.segments.clear();
            state.lastSegmentModelRequestId = null;
            state.totalBytes = 0;
            return;
        }
        appendChunk(state, kind, streamSeq, text, modelRequestId, occurredAt,
                canMerge, textBytes, appendedBytes);
    }

    /**
     * 公开 segment 的类型闭集；wire 名称独立于 Java enum 大小写。
     */
    public enum SegmentKind {
        /**
         * 模型向用户公开的 Assistant 文本增量。
         */
        ASSISTANT("assistant"),
        /**
         * 模型向用户公开的思考摘要增量，不包含 Provider 私有推理。
         */
        REASONING_SUMMARY("reasoningSummary");

        private final String wireName;

        /**
         * 固定 wire kind 而不依赖 enum 默认大小写，避免公开 reasoning summary 被误解为私有推理。
         */
        SegmentKind(String wireName) {
            this.wireName = wireName;
        }

        /**
         * 返回客户端 reducer 使用的公开 kind。
         */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * 由同一 revision 读取的公开 stream 基线。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param streamSeq 临时流事件序号
     * @param segments 片段列表
     */
    public record Snapshot(String turnId,
                           long streamSeq,
                           List<Segment> segments) {
        /**
         * 深拷贝 segment 列表，避免读锁释放后异步 JSON 映射观察到可变状态。
         */
        public Snapshot {
            requireIdentifier(turnId, "turn_");
            if (streamSeq < 0) {
                throw new IllegalArgumentException("streamSeq must be non-negative");
            }
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        }
    }

    /**
     * 一个或多个相邻公开 delta 的有序恢复片段。
     *
     * @param kind 类型分类
     * @param segmentStartSeq 片段起点序号
     * @param streamSeq 临时流事件序号
     * @param text 文本内容
     * @param occurredAt 事件发生时间
     */
    public record Segment(SegmentKind kind,
                          long segmentStartSeq,
                          long streamSeq,
                          String text,
                          Instant occurredAt) {
        /**
         * 限制段身份和文本边界；不允许把 Provider 私有 reasoning 混入公开基线。
         */
        public Segment {
            Objects.requireNonNull(kind, "kind");
            if (segmentStartSeq < 1 || streamSeq < segmentStartSeq) {
                throw new IllegalArgumentException("invalid stream segment sequence");
            }
            int bytes = text == null ? 0 : utf8Bytes(text);
            if (text == null || text.isEmpty() || bytes > MAX_SEGMENT_BYTES
                || text.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("invalid stream segment text");
                }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 仅供 registry 锁内追加使用；对外快照会复制成不可变 Segment，避免异步映射观察到后续写入。
     */
    private static final class SegmentBuffer {
        private final SegmentKind kind;
        private final long segmentStartSeq;
        private final StringBuilder text;
        private final Instant occurredAt;
        private long streamSeq;
        private int utf8Bytes;

        /**
         * 记录首次 delta 的边界和已编码长度，后续只增量更新当前缓冲。
         */
        private SegmentBuffer(SegmentKind kind, long streamSeq, String text, Instant occurredAt, int utf8Bytes) {
            this.kind = kind;
            this.segmentStartSeq = streamSeq;
            this.streamSeq = streamSeq;
            this.text = new StringBuilder(text);
            this.occurredAt = occurredAt;
            this.utf8Bytes = utf8Bytes;
        }

        /**
         * 追加相邻 delta；字节增量已由调用方针对接缝计算并通过 segment 上限校验。
         */
        private void append(long sequence, String value, int byteCount) {
            text.append(value);
            streamSeq = sequence;
            utf8Bytes += byteCount;
        }

        /**
         * 在锁内生成与后续追加隔离的字符串快照。
         */
        private Segment snapshot() {
            return new Segment(kind, segmentStartSeq, streamSeq, text.toString(), occurredAt);
        }
    }

    /**
     * registry 内部可变状态只在 synchronized 方法中访问，避免快照和终态清理交错。
     */
    private static final class State {
        private final long generation;
        private final String turnId;
        private final String workspaceId;
        private final String threadId;
        private final ArrayList<SegmentBuffer> segments = new ArrayList<>();
        private final Set<String> eventIds = new HashSet<>();
        private final ArrayDeque<String> eventOrder = new ArrayDeque<>();
        private long revision;
        private long mutationVersion;
        private long streamSeq;
        private TurnState lifecycle = TurnState.QUEUED;
        private boolean complete = true;
        private boolean overflow;
        private int totalBytes;
        private int modelRound;
        private int clearedModelRound;
        private String lastSegmentModelRequestId;
        private long lastSettledMutationVersion = -1;

        /**
         * 固化 admission 时的 generation fence 与第一份持久 revision。
         */
        private State(long generation, String turnId, String workspaceId, String threadId, long revision,
                      long mutationVersion, int modelRound) {
            this.generation = generation;
            this.turnId = turnId;
            this.workspaceId = workspaceId;
            this.threadId = threadId;
            this.revision = revision;
            this.mutationVersion = mutationVersion;
            this.modelRound = modelRound;
            this.clearedModelRound = modelRound;
        }

        /**
         * Thread revision 是持久事实版本而不是公开通知序号；内部事务可以合法跳号，但不能倒退。
         * 只取单调最大值，避免等待不可见事务的“缺口”让健康基线永久返回 null。
         */
        private void observeRevision(long candidate) {
            if (candidate > revision) {
                revision = candidate;
            }
        }

        /**
         * 仅接受未重复且不低于当前已消费水位的 durable event；旧 Thread revision 不影响 Turn 事件。
         * mutation 未绑定时保持 fence 未知，避免测试或错误调用把事件误当成可恢复完整基线。
         */
        private boolean observeDurableEvent(TurnEvent.Context context) {
            if (!rememberEvent(context.eventId()) || context.turnMutationVersion() < 0) {
                return false;
            }
            if (context.turnMutationVersion() < mutationVersion) {
                return false;
            }
            mutationVersion = context.turnMutationVersion();
            return true;
        }

        /**
         * 压缩没有 TurnEvent，但同一 SQLite 回执仍需推进精确 mutation fence。
         */
        private boolean observeDurableBoundary(String eventId, long candidateMutationVersion) {
            if (!rememberEvent(eventId)) {
                return false;
            }
            if (candidateMutationVersion < mutationVersion) {
                return false;
            }
            mutationVersion = candidateMutationVersion;
            return true;
        }

        /**
         * ModelStepCommitted 可能晚于更高 Thread revision 的 metadata 到达；它必须按同一 Turn 的
         * modelRound 补齐语义 fence，但不能把 mutation 水位回退。旧/重复模型请求只记水位，不清
         * 除更新后的 draft，避免迟到旧正文覆盖当前轮次。
         */
        private boolean observeModelStep(TurnEvent.Context context, int candidateModelRound) {
            if (!rememberEvent(context.eventId())) {
                return false;
            }
            if (context.turnMutationVersion() >= 0) {
                mutationVersion = Math.max(mutationVersion, context.turnMutationVersion());
            }
            modelRound = Math.max(modelRound, candidateModelRound);
            if (candidateModelRound <= clearedModelRound) {
                return false;
            }
            clearedModelRound = candidateModelRound;
            return true;
        }

        /**
         * 已提交边界只允许完成模型轮次前进，内部 checkpoint 不得伪造更高 ModelStep。
         */
        private void observeModelRound(int candidateModelRound) {
            if (candidateModelRound > modelRound) {
                modelRound = candidateModelRound;
            }
        }

        /**
         * 有界保留最近 durable event 身份；过期去重项淘汰后仍由持久 fence 防止旧事实复活。
         */
        private boolean rememberEvent(String eventId) {
            if (eventIds.contains(eventId)) {
                return false;
            }
            eventIds.add(eventId);
            eventOrder.addLast(eventId);
            if (eventOrder.size() > MAX_EVENT_IDS) {
                eventIds.remove(eventOrder.removeFirst());
            }
            return true;
        }

        /**
         * 恢复同一 Turn 时只接受权威持久水位，不允许旧 resume 响应把 CAS 水位倒退。
         */
        private void observeMutationVersion(long candidate) {
            if (candidate > mutationVersion) {
                mutationVersion = candidate;
            }
        }

        /**
         * 以持久 Turn fence 校验快照是否已越过所有未发布 durable event。
         */
        private boolean matchesFence(Map<String, Long> persistedMutationVersions,
                                     Map<String, Integer> persistedModelRounds) {
            Long persisted = persistedMutationVersions.get(turnId);
            if (persisted == null || persisted != mutationVersion) {
                return false;
            }
            Integer persistedModelRound = persistedModelRounds.get(turnId);
            return persistedModelRound != null && persistedModelRound == modelRound
                   && clearedModelRound == modelRound;
        }

        /**
         * 模型步骤已经落库，公开草稿正文可丢弃但全局游标不能回退。
         */
        private void clearSegments() {
            segments.clear();
            lastSegmentModelRequestId = null;
            totalBytes = 0;
            complete = true;
            overflow = false;
        }

        /**
         * InputConsumed 的 AssistantSettlement 也结算当前轮次，但不伪造新的 modelRound。
         */
        private void clearSettledDraft() {
            clearedModelRound = modelRound;
            clearSegments();
        }

        /**
         * 复制当前状态用于脱离锁进行 wire 映射。
         */
        private Snapshot snapshot() {
            return new Snapshot(turnId, streamSeq, segments.stream().map(SegmentBuffer::snapshot).toList());
        }
    }
}
