// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.conversation;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 可从追加命令事实确定性重建的会话输入队列，不携带 Workspace 或 Ja 业务状态。
 *
 * @param turnId 本次可恢复执行的稳定标识
 * @param revision 当前修订版本
 * @param entries 条目列表
 */
public record AgentInputQueue(String turnId,
                              long revision,
                              List<Entry> entries) {
    /**
     * 队列与准入请求共用的硬上限；溢出必须拒绝整批输入，不能静默丢弃尾项。
     */
    public static final int MAX_ENTRIES = 8;

    /**
     * 固定队列版本与输入顺序；运行时共享集合不得改变已恢复的安全点候选。
     */
    public AgentInputQueue {
        turnId = ContractChecks.identifier(turnId, "turnId");
        if (revision < 0) {
            throw new IllegalArgumentException("negative input queue revision");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() > MAX_ENTRIES || entries.stream().anyMatch(Objects::isNull)
            || entries.stream().map(Entry::inputId).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("invalid input queue entries");
        }
    }

    /**
     * 以稳定身份在有序队列中定位一项；不存在返回负索引供操作规则统一拒绝。
     */
    private static int indexOf(List<Entry> entries, String inputId) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).inputId().equals(inputId)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 统一实施乐观输入版本校验，不能用队列整体版本掩盖同项上的陈旧编辑。
     */
    private static Entry current(List<Entry> entries, int index, Command command) {
        if (index < 0) {
            throw new IllegalStateException("queued input does not exist");
        }
        Entry current = entries.get(index);
        if (current.inputRevision() != command.expectedInputRevision()) {
            throw new IllegalStateException("queued input revision changed");
        }
        return current;
    }

    /**
     * 重放一个队列命令；版本不连续或目标已变化时拒绝部分应用，优先项保持同类 FIFO。
     */
    public AgentInputQueue apply(Command command) {
        Objects.requireNonNull(command, "command");
        if (command.queueRevision() != Math.addExact(revision, 1)) {
            throw new IllegalStateException("input queue revision is not contiguous");
        }
        List<Entry> next = new ArrayList<>(entries);
        int index = indexOf(next, command.inputId());
        switch (command.action()) {
            case ENQUEUE -> {
                if (index >= 0 || command.expectedInputRevision() != 0) {
                    throw new IllegalStateException("input cannot be enqueued");
                }
                if (next.size() == MAX_ENTRIES) {
                    throw new QueueFullException();
                }
                next.add(new Entry(command.inputId(), command.kind(), command.messageRecordId(),
                        Status.PENDING, null, 1, command.occurredAt()));
            }
            case UPDATE -> {
                Entry current = current(next, index, command);
                next.set(index, new Entry(current.inputId(), current.kind(), command.messageRecordId(),
                        Status.PENDING, null, current.inputRevision() + 1, current.createdAt()));
            }
            case DELETE, CONSUME -> {
                current(next, index, command);
                next.remove(index);
            }
            case PRIORITIZE -> {
                Entry current = current(next, index, command);
                if (current.kind() == Kind.MESSAGE) {
                    throw new IllegalStateException("message input cannot be reprioritized");
                }
                next.remove(index);
                int firstFollowUp = 0;
                while (firstFollowUp < next.size() && next.get(firstFollowUp).kind() == Kind.STEERING) {
                    firstFollowUp++;
                }
                next.add(firstFollowUp, new Entry(current.inputId(), Kind.STEERING, current.messageRecordId(),
                        current.status(), current.issue(), current.inputRevision() + 1, current.createdAt()));
            }
            case NEEDS_ATTENTION -> {
                Entry current = current(next, index, command);
                next.set(index, new Entry(current.inputId(), current.kind(), current.messageRecordId(),
                        Status.NEEDS_ATTENTION, command.issue(), current.inputRevision() + 1, current.createdAt()));
            }
            case RETRY -> {
                Entry current = current(next, index, command);
                next.set(index, new Entry(current.inputId(), current.kind(), current.messageRecordId(),
                        Status.PENDING, null, current.inputRevision() + 1, current.createdAt()));
            }
        }
        return new AgentInputQueue(turnId, command.queueRevision(), next);
    }

    /**
     * 按队列顺序选取当前安全点可消费的输入；模型停止后 Follow-up 与即时输入统一 FIFO，attention 阻止越过。
     */
    public Optional<Entry> next(SafePoint safePoint) {
        Objects.requireNonNull(safePoint, "safePoint");
        if (safePoint == SafePoint.MODEL_STOPPED) {
            return entries.stream().findFirst();
        }
        return entries.stream().filter(entry -> entry.kind() != Kind.FOLLOW_UP).findFirst();
    }

    /**
     * 读取安全点当前候选的修复状态，避免把问题项当普通文本送入模型。
     */
    public boolean requiresAttentionAt(SafePoint safePoint) {
        return next(safePoint).map(entry -> entry.status() == Status.NEEDS_ATTENTION).orElse(false);
    }

    /**
     * 可持久化的命令闭集，不接受由字符串猜测的动作。
     */
    public enum Action {
        /**
         * 新增一条带稳定正文记录引用的队列项。
         */
        ENQUEUE,
        /**
         * 以条目 revision CAS 替换尚未消费的正文引用。
         */
        UPDATE,
        /**
         * 删除尚未消费的输入，不把它写入模型历史。
         */
        DELETE,
        /**
         * 将 Follow-up 提升为 Steering 并排到已有 Steering 之后。
         */
        PRIORITIZE,
        /**
         * 在同一提交中复制为普通用户消息后移出队列。
         */
        CONSUME,
        /**
         * 标记候选输入暂不可用并阻止安全点越过它。
         */
        NEEDS_ATTENTION,
        /**
         * 清除已修复输入的 attention 标记，保留原队列位置。
         */
        RETRY
    }

    /**
     * 入队类别决定安全点消费时机。
     */
    public enum Kind {
        /**
         * 在模型完成或停止等任一安全点均可消费的定向输入。
         */
        STEERING,
        /**
         * 仅在模型停止的安全点消费的后续输入。
         */
        FOLLOW_UP,
        /**
         * 进入模型 USER 历史并保留当前技能选择的宿主对话消息。
         */
        MESSAGE
    }

    /**
     * 队列项在模型循环中的恢复状态。
     */
    public enum Status {
        /**
         * 正常候选，受其 Kind 对应的安全点约束。
         */
        PENDING,
        /**
         * 正文暂不可用，当前候选阻止后续输入越过。
         */
        NEEDS_ATTENTION
    }

    /**
     * 仅模型停止边界允许消费 FOLLOW_UP，其他安全点只看 STEERING。
     */
    public enum SafePoint {
        /**
         * 模型给出自然完成结果后的安全边界。
         */
        MODEL_COMPLETED,
        /**
         * 一个完整工具批次的结果全部提交后的安全边界。
         */
        TOOL_BATCH_COMPLETED,
        /**
         * 当前模型请求停止、重试或结束后的安全边界。
         */
        MODEL_STOPPED
    }

    /**
     * 队列展示与顺序消费所需的中立输入引用，不把待消费正文纳入模型历史。
     *
     * @param inputId 输入标识
     * @param kind 类型分类
     * @param messageRecordId 消息记录标识
     * @param status 当前状态分类
     * @param issue 问题
     * @param inputRevision 输入修订版本
     * @param createdAt 创建时间
     */
    public record Entry(String inputId,
                        Kind kind,
                        String messageRecordId,
                        Status status,
                        Issue issue,
                        long inputRevision,
                        Instant createdAt) {
        /**
         * 输入正文独立存为待消费事实，仅在 CONSUME 提交时复制为普通用户消息。
         */
        public Entry {
            inputId = ContractChecks.identifier(inputId, "inputId");
            Objects.requireNonNull(kind, "kind");
            messageRecordId = ContractChecks.identifier(messageRecordId, "messageRecordId");
            Objects.requireNonNull(status, "status");
            if ((status == Status.NEEDS_ATTENTION) == (issue == null)) {
                throw new IllegalArgumentException("input issue and status disagree");
            }
            if (inputRevision < 1) {
                throw new IllegalArgumentException("invalid input revision");
            }
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 工具或输入事务追加的完整队列命令，可在进程恢复后按会话记录序号重放。
     *
     * @param commandId 命令标识
     * @param action 动作
     * @param inputId 输入标识
     * @param kind 类型分类
     * @param expectedInputRevision 预期输入修订版本
     * @param queueRevision 队列修订版本
     * @param messageRecordId 消息记录标识
     * @param issue 问题
     * @param occurredAt 事件发生时间
     * @param hostMetadata 宿主元数据
     */
    public record Command(String commandId,
                          Action action,
                          String inputId,
                          Kind kind,
                          long expectedInputRevision,
                          long queueRevision,
                          String messageRecordId,
                          Issue issue,
                          Instant occurredAt,
                          Map<String, String> hostMetadata) {
        /**
         * 校验每种操作所需字段，防止编码时把缺失语义解释为默认操作。
         */
        public Command {
            commandId = ContractChecks.identifier(commandId, "commandId");
            Objects.requireNonNull(action, "action");
            inputId = ContractChecks.identifier(inputId, "inputId");
            if (expectedInputRevision < 0 || queueRevision < 1)
                {
                    throw new IllegalArgumentException("invalid queue revision");
                }
            Objects.requireNonNull(occurredAt, "occurredAt");
            hostMetadata = ContractChecks.hostMetadata(hostMetadata, 32, 16_384);
            switch (action) {
                case ENQUEUE -> {
                    Objects.requireNonNull(kind, "kind");
                    messageRecordId = ContractChecks.identifier(messageRecordId, "messageRecordId");
                    if (expectedInputRevision != 0 || issue != null)
                        {
                            throw new IllegalArgumentException("invalid enqueue command");
                        }
                }
                case UPDATE -> {
                    messageRecordId = ContractChecks.identifier(messageRecordId, "messageRecordId");
                    if (expectedInputRevision < 1 || kind != null || issue != null)
                        {
                            throw new IllegalArgumentException("invalid update command");
                        }
                }
                case NEEDS_ATTENTION -> {
                    Objects.requireNonNull(issue, "issue");
                    requireTargetRevision(expectedInputRevision, kind, messageRecordId);
                }
                case DELETE, PRIORITIZE, CONSUME, RETRY -> {
                    requireTargetRevision(expectedInputRevision, kind, messageRecordId);
                    if (issue != null) {
                        throw new IllegalArgumentException("unexpected input issue");
                    }
                }
            }
        }

        /**
         * 更新、删除、消费和优先级命令都必须绑定一个已观察到的输入版本。
         */
        private static void requireTargetRevision(long expectedRevision, Kind kind, String messageRecordId) {
            if (expectedRevision < 1 || kind != null || messageRecordId != null) {
                throw new IllegalArgumentException("invalid input mutation command");
            }
        }

    }

    /**
     * 队列已达公开容量上限；调用方可映射为容量错误而不混淆版本冲突。
     */
    public static final class QueueFullException extends IllegalStateException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 使用稳定错误类型区分容量限制与条目冲突。
         */
        public QueueFullException() {
            super("input queue is full");
        }
    }

    /**
     * 稳定错误与脱敏修复提示，避免领域队列依赖 Workspace、附件目录或 RPC 错误类型。
     *
     * @param code 结果或诊断代码
     * @param message 消息或诊断说明
     * @param retryable 是否允许重试
     */
    public record Issue(String code,
                        String message,
                        boolean retryable) {
        /**
         * 错误码有界且可扩展，正文不得携带路径或未筛选的底层异常。
         */
        public Issue {
            code = ContractChecks.identifier(code, "input issue code");
            message = ContractChecks.text(message, "input issue message", 512, false);
        }
    }
}
