// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.port.in;

import io.github.kongweiguang.ja.task.domain.TaskModels;

import java.time.Instant;
import java.util.Objects;

/** Task 提交后的低频事件闭集；高频进度只有持有 observation 的订阅者能够收到。 */
public sealed interface TaskEvent permits TaskEvent.Activity, TaskEvent.Progress, TaskEvent.MailboxChanged {
    /** 所有事件都携带可重读投影所需的根、Task 和 revision，不依赖客户端本地树。 */
    Context context();

    /** 持久 Activity 与同事务 Task 投影一起发布。 */
    record Activity(Context context, TaskModels.Activity activity, TaskModels.Summary task) implements TaskEvent {
        /** 事件内容必须描述相同 Task 和 revision。 */
        public Activity {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(task, "task");
            if (!context.taskThreadId().equals(task.lineage().taskThreadId())
                    || !context.taskThreadId().equals(activity.taskThreadId())
                    || context.taskRevision() != task.projection().revision()) {
                throw new IllegalArgumentException("task activity event identity mismatch");
            }
        }
    }

    /** Progress 是可丢弃投影，只能关联一个仍有效的 observation。 */
    record Progress(Context context, String observationId, long progressRevision,
                    String safeSummary) implements TaskEvent {
        /** 不允许把 raw reasoning 或无界文本放进高频事件。 */
        public Progress {
            Objects.requireNonNull(context, "context");
            if (observationId == null || !observationId.startsWith("observe_") || observationId.length() > 128
                    || !observationId.substring("observe_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid task event identity");
            }
            if (progressRevision < 0 || safeSummary == null || safeSummary.length() > 4_096
                    || safeSummary.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid task progress event");
            }
        }
    }

    /** Mailbox 提交后仅通知 sequence 和未读计数，正文通过 task/read 获取。 */
    record MailboxChanged(Context context, long mailboxSequence, int unreadCount) implements TaskEvent {
        /** sequence 来自 SQLite，未读计数不能为负。 */
        public MailboxChanged {
            Objects.requireNonNull(context, "context");
            if (mailboxSequence < 1 || unreadCount < 0) {
                throw new IllegalArgumentException("invalid mailbox event");
            }
        }
    }

    /** 连接元数据由 RPC Session 分配；领域事件只保存可恢复的 Task 关联。 */
    record Context(String rootThreadId, String taskThreadId, long taskRevision, Instant occurredAt) {
        /** 拒绝无效身份与时间，避免 transport 猜测缺失字段。 */
        public Context {
            if (rootThreadId == null || !rootThreadId.startsWith("thr_") || rootThreadId.length() > 128
                    || !rootThreadId.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid task event identity");
            }
            if (taskThreadId == null || !taskThreadId.startsWith("thr_") || taskThreadId.length() > 128
                    || !taskThreadId.substring("thr_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("invalid task event identity");
            }
            if (taskRevision < 0) throw new IllegalArgumentException("invalid task revision");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
