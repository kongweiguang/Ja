// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionSummary;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 提供模型工具与本地 RPC 共用的进程会话用例，原生句柄只由当前运行时 owner 持有。
 */
public interface ProcessSessionUseCase extends DeadlineCloseable {
    /**
     * 返回服务实例绑定的进程代际，供调用方拒绝陈旧控制请求。
     *
     * @return 当前进程代际
     */
    long generation();

    /**
     * 按稳定工具调用身份至多启动一次，并等待指定的首轮输出窗口。
     *
     * @param request 已冻结的宿主命令与运行环境
     * @param yieldMillis 首轮输出最长等待时间
     * @param cancellationToken 当前 Turn 的取消信号
     * @return 含最新输出游标的进程快照
     */
    Snapshot exec(Launch request, int yieldMillis, CancellationToken cancellationToken);

    /**
     * 按操作键至多写入一次 stdin，并仅等待本次请求的输出窗口。
     *
     * @param caller 发起操作的 Workspace、Thread、Turn 与代际身份
     * @param callId 本次模型工具调用的稳定标识
     * @param sessionId 已启动进程会话标识
     * @param input 要送入进程标准输入的内容
     * @param closeInput 是否在写入后关闭输入流
     * @param afterSequence 客户端最后消费的绝对输出游标
     * @param yieldMillis 等待新输出的最长时间
     * @param cancellationToken 当前 Turn 的取消信号
     * @return 含最新输出窗口的进程快照
     */
    Snapshot writeStdin(Identity caller, String callId, String sessionId, String input, boolean closeInput,
                        long afterSequence, int yieldMillis, CancellationToken cancellationToken);

    /**
     * 只等待输出，不为纯轮询创建 stdin 写入回执或副作用记录。
     *
     * @param caller 发起观察的 Workspace、Thread、Turn 与代际身份
     * @param sessionId 已启动进程会话标识
     * @param afterSequence 客户端最后消费的绝对输出游标
     * @param maxBytes 本次最多返回的 UTF-8 输出字节数
     * @param yieldMillis 等待新输出的最长时间
     * @param cancellationToken 当前 Turn 的取消信号
     * @return 含最新输出窗口的进程快照
     */
    Snapshot observe(Identity caller, String sessionId, long afterSequence, int maxBytes, int yieldMillis,
                     CancellationToken cancellationToken);

    /**
     * 查询单个 Workspace 与 Thread 下的稳定分页摘要，不返回命令或输出正文。
     *
     * @param workspaceId 进程所属工作区标识
     * @param threadId 进程所属线程标识
     * @param limit 本页最多返回的摘要数
     * @param cursor 上一页返回的不透明游标，首屏为空
     * @param startedCallId 可选的启动工具调用筛选
     * @return 不含进程正文的会话摘要页
     */
    Page list(String workspaceId, String threadId, int limit, String cursor, String startedCallId);

    /**
     * 读取准确属于给定 Workspace 与 Thread 的有界输出。
     *
     * @param scope 已校验的进程权限范围
     * @param sessionId 已启动进程会话标识
     * @param afterSequence 客户端最后消费的绝对输出游标
     * @param maxBytes 本次最多返回的 UTF-8 输出字节数
     * @return 当前可见的进程快照
     */
    Snapshot read(ProcessSessionScope scope, String sessionId, long afterSequence, int maxBytes);

    /**
     * 使用调用方提供的操作键提交 stdin 写入，未知副作用结果不得自动重放。
     *
     * @param scope 已校验的进程权限范围
     * @param sessionId 已启动进程会话标识
     * @param operationKey 本次写入的幂等键
     * @param input 要送入进程标准输入的内容
     * @param closeInput 是否在写入后关闭输入流
     * @param afterSequence 客户端最后消费的绝对输出游标
     * @param maxBytes 本次最多返回的 UTF-8 输出字节数
     * @return 当前可见的进程快照
     */
    Snapshot write(ProcessSessionScope scope, String sessionId, String operationKey, String input,
                   boolean closeInput, long afterSequence, int maxBytes);

    /**
     * 只允许停止拥有相同工作区、线程和运行时代际的活动会话。
     *
     * @param scope 已校验的进程权限范围
     * @param sessionId 已启动进程会话标识
     * @return 停止状态确认快照，不重复返回已消费输出
     */
    Snapshot stop(ProcessSessionScope scope, String sessionId);

    /**
     * 取消当前 Turn 已准入的进程，不影响其它 Turn 留存的后台会话。
     *
     * @param owner 被取消 Turn 的完整进程所有权身份
     */
    void cancelTurn(Identity owner);

    /**
     * 在线程生命周期提交后关闭该 Thread 所拥有的全部进程。
     *
     * @param scope 已校验的进程权限范围
     */
    void closeThread(ProcessSessionScope scope);

    /**
     * 线程恢复可用后重新开放新进程准入，不恢复此前的原生进程。
     *
     * @param scope 已校验的进程权限范围
     */
    void reopenThread(ProcessSessionScope scope);

    /**
     * 绑定启动进程的 Workspace、Thread、Turn 与服务代际。
     *
     * @param workspaceId 所属工作区标识
     * @param threadId 所属线程标识
     * @param turnId 启动进程的 Turn 标识
     * @param generation 启动进程的服务代际
     */
    record Identity(String workspaceId,
                    String threadId,
                    String turnId,
                    long generation) {
        /**
         * 拒绝不完整或超范围身份，避免资源在进入注册表前失去取消与清理归属。
         */
        public Identity {
            workspaceId = identifier(workspaceId, "workspaceId");
            threadId = identifier(threadId, "threadId");
            turnId = identifier(turnId, "turnId");
            generation = requireGeneration(generation);
        }

        /**
         * 去掉 Turn 身份生成供 RPC 与线程清理使用的权限范围。
         *
         * @return 对应 Workspace、Thread 与代际范围
         */
        public ProcessSessionScope scope() {
            return new ProcessSessionScope(workspaceId, threadId, generation);
        }
    }

    /**
     * 保存单次输出窗口与不可变进程事实，便于工具与 RPC 共用同一读取语义。
     *
     * @param sessionId 进程会话标识
     * @param workspaceId 所属工作区标识
     * @param threadId 所属线程标识
     * @param startedTurnId 启动进程的 Turn 标识
     * @param startedCallId 启动进程的工具调用标识
     * @param generation 进程所属服务代际
     * @param state 进程生命周期状态
     * @param createdAt 会话开始时间
     * @param output 本次返回的 UTF-8 文本窗口
     * @param outputSequence 已产生输出的绝对字符序号
     * @param nextSequence 下一次读取应使用的绝对字符序号
     * @param outputTruncated 是否有早于窗口的输出已丢弃
     * @param exitCode 进程退出码，活动进程为空
     */
    record Snapshot(String sessionId,
                    String workspaceId,
                    String threadId,
                    String startedTurnId,
                    String startedCallId,
                    long generation,
                    String state,
                    Instant createdAt,
                    String output,
                    long outputSequence,
                    long nextSequence,
                    boolean outputTruncated,
                    Integer exitCode) {
        /**
         * 校验共享会话事实和读取游标，防止不一致快照越过服务边界。
         */
        public Snapshot {
            ProcessSessionSummary summary = new ProcessSessionSummary(sessionId, workspaceId, threadId,
                    startedTurnId, startedCallId, generation, state, outputSequence, outputTruncated,
                    exitCode, createdAt);
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(output, "output");
            if (nextSequence < 0 || nextSequence > outputSequence) {
                throw new IllegalArgumentException("invalid output cursor");
            }
        }

        /**
         * 只保留列表摘要字段，避免把单次输出正文复制到分页结果。
         *
         * @return 不含本次输出正文的会话摘要
         */
        public ProcessSessionSummary summary() {
            return new ProcessSessionSummary(sessionId, workspaceId, threadId, startedTurnId, startedCallId,
                    generation, state, outputSequence, outputTruncated, exitCode, createdAt);
        }
    }

    /**
     * 面向用例调用方的会话列表页，游标只表示续页位置，不解释底层排序键。
     *
     * @param sessions 当前页的进程会话摘要
     * @param nextCursor 下一页不透明游标，没有后续页时为空
     */
    record Page(List<ProcessSessionSummary> sessions,
                String nextCursor) {
        /**
         * 在持久化事务结束前复制摘要列表，避免调用方观察到可变集合。
         */
        public Page {
            sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        }
    }

    /**
     * 固定一次进程准入所需的 argv、工作目录、宿主环境与生命周期约束。
     *
     * @param owner 启动 Turn 的完整进程所有权身份
     * @param callId 稳定模型工具调用标识
     * @param command 供用户审阅与持久化的命令文本
     * @param commandLine 交给宿主的 argv 列表
     * @param workingDirectory 绝对工作目录
     * @param environment 从宿主继承并固定的进程环境
     * @param tty 是否申请伪终端
     * @param timeoutMillis 进程生命周期上限，零表示不额外限定时长
     */
    record Launch(Identity owner,
                  String callId,
                  String command,
                  List<String> commandLine,
                  Path workingDirectory,
                  Map<String, String> environment,
                  boolean tty,
                  long timeoutMillis) {
        /**
         * 在分配原生资源前冻结集合输入并验证超时，避免调用方后续修改影响已准入请求。
         */
        public Launch {
            Objects.requireNonNull(owner, "owner");
            callId = identifier(callId, "callId");
            command = boundedText(command, 32_000, "command");
            commandLine = List.copyOf(Objects.requireNonNull(commandLine, "commandLine"));
            if (commandLine.isEmpty()) {
                throw new IllegalArgumentException("command line is empty");
            }
            workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                    .toAbsolutePath().normalize();
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
            if (timeoutMillis < 0 || timeoutMillis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid process lifetime timeout");
            }
        }
    }

    /**
     * 限制跨进程持久身份的长度和控制字符，防止异常记录破坏后续审计与回读。
     *
     * @param value 待检查的身份值
     * @param field 错误诊断使用的字段名
     * @return 验证通过的身份值
     */
    private static String identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 将运行时代际限制在 JavaScript 可精确表示的正整数范围内。
     *
     * @param value 待检查的运行时代际
     * @return 验证通过的运行时代际
     */
    private static long requireGeneration(long value) {
        if (value < 1 || value > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("invalid process generation");
        }
        return value;
    }

    /**
     * 保持命令元数据有限且不含 NUL，避免持久事实被错误文本污染。
     *
     * @param value 待检查的命令文本
     * @param maxCharacters 最大字符数
     * @param field 错误诊断使用的字段名
     * @return 验证通过的命令文本
     */
    private static String boundedText(String value, int maxCharacters, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxCharacters || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
