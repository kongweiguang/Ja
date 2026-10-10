// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionSummary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定义进程会话持久事实的出站端口；原生进程句柄仍只属于活动运行时。
 */
public interface ProcessSessionLedger {
    /**
     * 启动前插入 starting 事实；相同模型调用重试时返回先前记录。
     *
     * @param entry 待创建的不可变启动身份
     * @return 已持久化或已存在的事实
     */
    Entry createStarting(Entry entry);

    /**
     * 按 Thread、Turn 与模型调用身份查找启动事实，供启动重试执行幂等判定。
     *
     * @param threadId 所属线程标识
     * @param turnId 所属 Turn 标识
     * @param callId 工具调用标识
     * @return 匹配的事实，不存在时为空
     */
    Optional<Entry> findByCall(String threadId, String turnId, String callId);

    /**
     * 按完整会话身份读取事实，不扩大工作区或线程范围。
     *
     * @param sessionId 进程会话标识
     * @param workspaceId 所属工作区标识
     * @param threadId 所属线程标识
     * @return 匹配的事实，不存在时为空
     */
    Optional<Entry> find(String sessionId, String workspaceId, String threadId);

    /**
     * 按稳定键集顺序分页，并在限制条数前应用可选的来源调用筛选。
     *
     * @param scope 已校验的工作区、线程及代际范围
     * @param limit 请求的最大页长
     * @param cursor 持久化格式不透明的续页游标
     * @param startedCallId 可选的启动工具调用筛选
     * @return 仅含安全摘要字段的分页结果
     */
    Page list(ProcessSessionScope scope, int limit, String cursor, String startedCallId);

    /**
     * 查询一个准确 Workspace 和 Thread 下仍可能持有原生进程的事实。
     *
     * @param workspaceId 所属工作区标识
     * @param threadId 所属线程标识
     * @return 活动会话事实
     */
    List<Entry> active(String workspaceId, String threadId);

    /**
     * 替换可变状态，同时保留已写入的启动身份。
     *
     * @param entry 完整的新事实
     */
    void update(Entry entry);

    /**
     * 在 stdin 交付前原子占用操作键，区分首次、已应用和结果未知的写入。
     *
     * @param sessionId 进程会话标识
     * @param operationKey 客户端操作幂等键
     * @param requestFingerprint 本次输入及游标的摘要
     * @param createdAt 本次请求时间
     * @return 当前操作键的持久状态
     */
    WriteClaim claimWrite(String sessionId, String operationKey, String requestFingerprint, Instant createdAt);

    /**
     * 关闭写入回执，防止已交付或结果未知的输入被重新发送。
     *
     * @param sessionId 进程会话标识
     * @param operationKey 客户端操作幂等键
     * @param state 写入的最终持久状态
     * @param completedAt 本次状态关闭时间
     */
    void resolveWrite(String sessionId, String operationKey, WriteClaim state, Instant completedAt);

    /**
     * 将所有旧运行时代际中的活动事实标记为 interrupted，不重放原命令。
     *
     * @param generation 新运行时代际
     * @param endedAt 旧活动事实结束时间
     */
    void interruptOtherGenerations(long generation, Instant endedAt);

    /**
     * 表示 stdin 操作键的持久状态；claimed 或 unknown 都不能安全重放。
     */
    enum WriteClaim {
        /** 当前调用已取得操作键，可以尝试一次 stdin 交付。 */
        CLAIMED,
        /** 输入已确认交付，之后的重试不得再次发送。 */
        APPLIED,
        /** 输入可能已经送达，但无法证明结果，必须停止自动推进。 */
        UNKNOWN,
        /** 另一个写入者尚未发布该操作键的应用结果。 */
        PENDING
    }

    /**
     * 表示不可变的账本行；输出仅保存保留后缀，序号仍使用绝对字符偏移。
     *
     * @param sessionId 进程会话标识
     * @param workspaceId 所属工作区标识
     * @param threadId 所属线程标识
     * @param startedTurnId 启动进程的 Turn 标识
     * @param startedCallId 启动进程的工具调用标识
     * @param generation 进程所属服务代际
     * @param command 已审计的原始命令文本
     * @param state 持久化的进程生命周期状态
     * @param output 已保留的输出后缀
     * @param outputSequence 已产生输出的绝对字符序号
     * @param exitCode 进程退出码，活动会话为空
     * @param createdAt 会话开始时间
     * @param endedAt 会话结束时间，活动会话为空
     */
    record Entry(String sessionId,
                 String workspaceId,
                 String threadId,
                 String startedTurnId,
                 String startedCallId,
                 long generation,
                 String command,
                 String state,
                 String output,
                 long outputSequence,
                 Integer exitCode,
                 Instant createdAt,
                 Instant endedAt) {
        /**
         * 拒绝持久化身份、状态或输出游标不完整的事实，避免损坏内容成为可控制会话。
         */
        public Entry {
            if (sessionId == null || !sessionId.matches("proc_[0-9a-f]{32}")
                    || workspaceId == null || !workspaceId.matches("ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                    || threadId == null || !threadId.matches("thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                    || startedTurnId == null || !startedTurnId.matches("turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                    || startedCallId == null || !startedCallId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("invalid process session identity");
            }
            if (generation < 1 || state == null
                    || !List.of("starting", "running", "stopping", "exited", "failed", "interrupted")
                    .contains(state)
                    || output == null || outputSequence < output.length() || command == null
                    || command.isBlank() || command.length() > 32_000 || command.indexOf('\0') >= 0
                    || createdAt == null || (endedAt != null && endedAt.isBefore(createdAt))) {
                throw new IllegalArgumentException("invalid process session facts");
            }
        }

        /**
         * 根据总序号与保留后缀长度推导旧输出是否已被裁剪，不额外维护易漂移状态。
         *
         * @return 存在已丢弃的早期输出时为 true
         */
        public boolean outputTruncated() {
            return outputSequence > output.length();
        }

        /**
         * 投影列表值时隐藏命令和输出，并把内部启动或失败态映射到稳定摘要状态。
         *
         * @return 可跨端口传递的进程会话摘要
         */
        public ProcessSessionSummary summary() {
            String projectedState = switch (state) {
                case "starting" -> "running";
                case "failed" -> "exited";
                default -> state;
            };
            return new ProcessSessionSummary(sessionId, workspaceId, threadId, startedTurnId,
                    startedCallId, generation, projectedState, outputSequence, outputTruncated(), exitCode,
                    createdAt);
        }

        /**
         * 生成共享不可变身份下的新运行事实，供注册表提交状态变化时避免逐字段复制遗漏。
         *
         * @param nextState 新生命周期状态
         * @param nextOutput 新保留输出后缀
         * @param nextSequence 新绝对输出序号
         * @param nextExitCode 新退出码
         * @param nextEndedAt 新结束时间
         * @return 保留启动身份的新账本行
         */
        public Entry withRuntime(String nextState, String nextOutput, long nextSequence,
                                 Integer nextExitCode, Instant nextEndedAt) {
            return new Entry(sessionId, workspaceId, threadId, startedTurnId, startedCallId, generation,
                    command, nextState, nextOutput, nextSequence, nextExitCode, createdAt, nextEndedAt);
        }
    }

    /**
     * 隔离持久化分页结构与用例响应结构，分页游标始终由账本实现解释。
     *
     * @param sessions 当前页的进程摘要
     * @param nextCursor 下一页的不透明账本游标
     */
    record Page(List<ProcessSessionSummary> sessions,
                String nextCursor) {
        /**
         * 在 JDBC 会话关闭前复制数据，保证页对象不再依赖持久化读取期。
         */
        public Page {
            sessions = List.copyOf(sessions);
        }
    }
}
