// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool.ToolResult;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool.ExecutionContext;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Identity;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Page;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Snapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionFailure.Code;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 保持进程工具共享 Java 唯一注册表和冻结的宿主 Shell 配置，避免工具各自管理进程生命周期。
 */
public final class ProcessToolFactory {
    private static final int MAX_COMMAND_CHARACTERS = 32_000;
    private static final int MAX_WORKDIR_CHARACTERS = 32_768;
    private static final int MAX_INPUT_CHARACTERS = 1_048_576;
    private static final int MAX_OUTPUT_BYTES = 65_536;
    private static final int MAX_YIELD_MILLIS = 10_000;
    private static final int DEFAULT_LIST_PAGE_SIZE = 50;
    private static final int MAX_LIST_PAGE_SIZE = 200;
    private static final int MAX_TIMEOUT_MILLIS = Integer.MAX_VALUE;
    private static final long MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L;
    private static final String EXEC_DESCRIPTION = "Runs a shell command with inherited host permissions. A running "
            + "process stays attached to this Thread after the observation window. Optional tty uses a real Windows "
            + "ConPTY. Approval gates execution but does not isolate host files or network access.";
    private static final String WRITE_DESCRIPTION = "Continues an existing process session. Provide input to write, "
            + "close_input to send EOF, or omit both to wait for new output. Carry next_sequence from the previous "
            + "result to avoid repeating output; session generation is returned for recovery checks.";
    private static final String STOP_DESCRIPTION = "Stops a background process session started by exec_command. "
            + "Use its session_id; the current Thread and runtime generation are checked, and no operating-system "
            + "process ID or shell kill command is needed.";
    private static final String LIST_DESCRIPTION = "Lists retained process sessions for the current Thread only. "
            + "The bounded summaries include session_id, generation, state, and output cursor but never command text. "
            + "Prior-generation sessions are interrupted history and are never restarted automatically.";

    /**
     * 工厂仅提供静态装配入口，防止进程工具对象形成第二个生命周期所有者。
     */
    private ProcessToolFactory() {
    }

    /**
     * 返回共享同一注册表的不可变工具集合，使发现、恢复读取和控制沿用同一资源所有者。
     */
    public static List<JaTool> create(ProcessSessionUseCase processes, ShellProfile profile) {
        ProcessSessionUseCase owner = Objects.requireNonNull(processes, "processes");
        ShellProfile shell = Objects.requireNonNull(profile, "profile");
        return List.of(new ExecCommandTool(owner, shell), new WriteStdinTool(owner), new ListProcessesTool(owner),
                new StopProcessTool(owner));
    }

    /**
     * 启动单个进程会话；首轮只返回有界输出与续读游标，避免一次调用等待完整命令退出。
     */
    private static final class ExecCommandTool extends ToolSupport {
        private final ProcessSessionUseCase processes;
        private final ShellProfile profile;

        /**
         * 将启动参数契约与注册表、Shell 配置一同固定在当前 Turn 的工具实例中。
         */
        private ExecCommandTool(ProcessSessionUseCase processes, ShellProfile profile) {
            super(new ToolSpec("exec_command", EXEC_DESCRIPTION, execSchema()));
            this.processes = Objects.requireNonNull(processes, "processes");
            this.profile = Objects.requireNonNull(profile, "profile");
        }

        /**
         * Shell 可执行宿主可见副作用，因此沿用统一的用户审批边界。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.EXTERNAL;
        }

        /**
         * Shell 能修改 Workspace 外部的宿主资源，不能将其伪装成可观察的 Workspace 内变更。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.UNOBSERVABLE;
        }

        /**
         * Plan 规划阶段只读，不能启动宿主进程。
         */
        @Override
        public PlanAccess planAccess() {
            return PlanAccess.DISALLOWED;
        }

        /**
         * 启动进程仍由既有审批状态机控制，不另设授权通道。
         */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.USER_REQUIRED;
        }

        /**
         * 将可选工作目录相对当前 Workspace 解析，并用当前运行时身份启动隔离的进程会话。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken cancellationToken)
                throws IOException {
            String command = string(invocation, "cmd", MAX_COMMAND_CHARACTERS);
            String rawWorkdir = optionalString(invocation, "workdir", MAX_WORKDIR_CHARACTERS);
            if (rawWorkdir != null && rawWorkdir.isBlank()) {
                throw argument("workdir", "must be omitted or name a non-empty host directory");
            }
            Path workdir = workingDirectory(context.workspaceRoot(), rawWorkdir);
            boolean tty = booleanValue(invocation, "tty", false);
            int yieldMillis = integer(invocation, "yield_time_ms", 10_000, 0, MAX_YIELD_MILLIS);
            int timeoutMillis = optionalPositiveTimeout(invocation);
            Identity owner = identity(processes, context);
            try {
                Snapshot snapshot = processes.exec(new ProcessSessionUseCase.Launch(owner, invocation.callId(), command,
                        profile.commandLine(command), workdir, profile.environment(), tty, timeoutMillis),
                        yieldMillis, cancellationToken);
                return processResult(snapshot);
            } catch (ProcessSessionFailure failure) {
                if (failure.code() != Code.LAUNCH_FAILED) {
                    throw failure;
                }
                return new ToolResult(ToolOutcome.FAILED,
                        "The process could not be started; verify that the command and working directory are available.",
                        Optional.empty(), "process_launch_failed");
            }
        }
    }

    /**
     * 持续工具负责一次性输入或按持久游标等待输出；空等待不创建虚假的 stdin 写入回执。
     */
    private static final class WriteStdinTool extends ToolSupport {
        private final ProcessSessionUseCase processes;

        /**
         * 续接工具绑定同一 App Server 代际中的注册表，避免跨重启复用失效进程身份。
         */
        private WriteStdinTool(ProcessSessionUseCase processes) {
            super(new ToolSpec("write_stdin", WRITE_DESCRIPTION, writeSchema()));
            this.processes = Objects.requireNonNull(processes, "processes");
        }

        /**
         * 写入或关闭进程 stdin 会影响宿主进程，必须经过用户审批。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.EXTERNAL;
        }

        /**
         * 进程可能修改 Ja 无法观察的 Workspace 外路径，因此变更范围标为不可观测。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.UNOBSERVABLE;
        }

        /**
         * Plan 规划阶段只读，不能向宿主进程续写输入或控制其 stdin。
         */
        @Override
        public PlanAccess planAccess() {
            return PlanAccess.DISALLOWED;
        }

        /**
         * 向宿主进程发送输入继续复用标准审批闸门，不扩大既有授权语义。
         */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.USER_REQUIRED;
        }

        /**
         * 输出从调用方游标继续；仅非空输入或显式 EOF 才产生持久写入操作。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            String sessionId = string(invocation, "session_id", 128);
            String input = optionalString(invocation, "input", MAX_INPUT_CHARACTERS);
            boolean closeInput = booleanValue(invocation, "close_input", false);
            int yieldMillis = integer(invocation, "yield_time_ms", 1_000, 0, MAX_YIELD_MILLIS);
            long afterSequence = optionalSequence(invocation);
            Identity owner = identity(processes, context);
            if ((input == null || input.isEmpty()) && !closeInput) {
                return processResult(processes.observe(owner, sessionId, afterSequence, MAX_OUTPUT_BYTES,
                        yieldMillis, cancellationToken));
            }
            try {
                return processResult(processes.writeStdin(owner, invocation.callId(), sessionId,
                        input == null ? "" : input, closeInput, afterSequence, yieldMillis, cancellationToken));
            } catch (ProcessSessionFailure failure) {
                if (failure.code() == Code.SESSION_UNAVAILABLE) {
                    return failed("The process session is not writable in this runtime; it may be missing, expired, or "
                        + "from a prior generation. Use list_processes and a read-only wait to inspect retained "
                        + "history; interrupted processes are never restarted automatically.",
                        "process_session_not_writable");
                }
                if (failure.code() == Code.WRITE_OUTCOME_UNKNOWN) {
                    return new ToolResult(ToolOutcome.FAILED,
                        "The stdin write outcome is unknown; the input was not retried to avoid duplicate effects.",
                        Optional.empty(), "process_write_outcome_unknown");
                }
                throw failure;
            }
        }
    }

    /**
     * 仅列出当前 Thread 的有界摘要和中断历史，不暴露命令正文。
     */
    private static final class ListProcessesTool extends ToolSupport {
        private final ProcessSessionUseCase processes;

        /**
         * 发现范围来自 Java 当前执行上下文，模型不能自行指定 Workspace 或 Thread 身份。
         */
        private ListProcessesTool(ProcessSessionUseCase processes) {
            super(new ToolSpec("list_processes", LIST_DESCRIPTION, listSchema()));
            this.processes = Objects.requireNonNull(processes, "processes");
        }

        /**
         * 枚举已保留的进程事实不会启动宿主动作或修改 Workspace。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /**
         * 摘要读取不写文件，Workspace 变更边界保持为空。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /**
         * Plan 阶段可读取进程事实，但不能启动、写入或停止进程。
         */
        @Override
        public PlanAccess planAccess() {
            return PlanAccess.READ_ONLY;
        }

        /**
         * 当前 Thread 内的只读投影无宿主副作用，不重复要求用户审批。
         */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.TRUSTED_INTERNAL;
        }

        /**
         * 使用冻结的 Thread 身份和有界游标分页，模型参数不能扩大查询范围。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context,
                                  CancellationToken cancellationToken) {
            int limit = integer(invocation, "limit", DEFAULT_LIST_PAGE_SIZE, 1, MAX_LIST_PAGE_SIZE);
            String cursor = optionalString(invocation, "cursor", 256);
            Page page = processes.list(context.workspaceId(), context.threadId(), limit, cursor, null);
            return processListResult(page, processes.generation());
        }
    }

    /**
     * 只按 Java 注册表签发的不透明会话 ID 停止进程，模型不能指定任意宿主 PID。
     */
    private static final class StopProcessTool extends ToolSupport {
        private final ProcessSessionUseCase processes;

        /**
         * 停止参数只接受会话身份，并绑定当前 Turn 共用的注册表。
         */
        private StopProcessTool(ProcessSessionUseCase processes) {
            super(new ToolSpec("stop_process", STOP_DESCRIPTION, stopSchema()));
            this.processes = Objects.requireNonNull(processes, "processes");
        }

        /**
         * 停止宿主进程属于外部动作，必须经过用户审批。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.EXTERNAL;
        }

        /**
         * 进程副作用可能落在 Ja 可观测的 Workspace 边界之外，必须标记为不可观测。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.UNOBSERVABLE;
        }

        /**
         * Plan 规划阶段只读，不能终止宿主进程。
         */
        @Override
        public PlanAccess planAccess() {
            return PlanAccess.DISALLOWED;
        }

        /**
         * 模型请求终止进程仍由既有用户审批状态机裁决。
         */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.USER_REQUIRED;
        }

        /**
         * 停止前按当前 Workspace、Thread 与运行时代际解析会话，拒绝跨作用域或过时代际控制。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            String sessionId = string(invocation, "session_id", 128);
            Identity owner = identity(processes, context);
            try {
                return processResult(processes.stop(owner.scope(), sessionId));
            } catch (ProcessSessionFailure failure) {
                if (failure.code() != Code.SESSION_UNAVAILABLE) {
                    throw failure;
                }
                return failed("The process session is not stoppable in this runtime; it may be missing, expired, or "
                        + "from a prior generation. Use list_processes to inspect retained history; interrupted "
                        + "processes are never restarted automatically.", "process_session_not_stoppable");
            }
        }
    }

    /**
     * 声明完整启动参数契约，同时保留工作目录、TTY、超时和首轮等待时长的可选语义。
     */
    private static JsonObject execSchema() {
        return ToolSupport.objectSchema(Map.of(
                "cmd", ToolSupport.requiredStringProperty("Command in the configured host Shell dialect.",
                        MAX_COMMAND_CHARACTERS),
                "workdir", ToolSupport.requiredStringProperty(
                        "Optional host directory; relative paths resolve from workspaceRoot.", MAX_WORKDIR_CHARACTERS),
                "tty", ToolSupport.booleanProperty(
                        "Use a real Windows ConPTY; this is unsupported on other platforms."),
                "yield_time_ms", integerProperty("Initial wait before returning a live session.", 0,
                        MAX_YIELD_MILLIS, 10_000),
                "timeout_ms", integerProperty("Optional positive process lifetime timeout; omitted means no timeout.",
                        1, MAX_TIMEOUT_MILLIS, null)), List.of("cmd"));
    }

    /**
     * 将可选 stdin 输入与游标续读合并到同一续接工具，避免分裂进程会话控制语义。
     */
    private static JsonObject writeSchema() {
        return ToolSupport.objectSchema(Map.of(
                "session_id", ToolSupport.requiredStringProperty("Process session ID from exec_command.", 128),
                "input", ToolSupport.optionalStringProperty("Text or terminal input to deliver to stdin.",
                        MAX_INPUT_CHARACTERS),
                "close_input", ToolSupport.booleanProperty("Close stdin to send EOF."),
                "after_sequence", integerProperty("Return output after the previous next_sequence cursor.", 0,
                        MAX_SAFE_SEQUENCE, 0),
                "yield_time_ms", integerProperty("Maximum wait for new output before returning.", 0,
                        MAX_YIELD_MILLIS, 1_000)), List.of("session_id"));
    }

    /**
     * 停止操作仅接受 exec_command 返回的不透明 ID，不暴露宿主 PID 寻址能力。
     */
    private static JsonObject stopSchema() {
        return ToolSupport.objectSchema(Map.of("session_id", ToolSupport.requiredStringProperty(
                "Process session ID returned by exec_command or write_stdin.", 128)), List.of("session_id"));
    }

    /**
     * 进程发现采用有界分页，并复用持久账本签发的不透明游标。
     */
    private static JsonObject listSchema() {
        return ToolSupport.objectSchema(Map.of(
                "limit", integerProperty("Maximum retained sessions to return.", 1, MAX_LIST_PAGE_SIZE,
                        DEFAULT_LIST_PAGE_SIZE),
                "cursor", ToolSupport.optionalStringProperty("Opaque cursor from the previous page.", 256)),
                List.of());
    }

    /**
     * Schema 与执行时共用整数范围，避免模型可见约束和服务端实际校验分叉。
     */
    private static JsonObject integerProperty(String description, long minimum, long maximum, Integer defaultValue) {
        JsonObjectBuilder property = JsonObjects.builder().putText("type", "integer")
                .putText("description", description).putNumber("minimum", minimum).putNumber("maximum", maximum);
        if (defaultValue != null) {
            property.putNumber("default", defaultValue);
        }
        return property.build();
    }

    /**
     * 相对目录以当前 Workspace 为基准解析；路径解析异常只保留为内部 cause，参数边界仅暴露固定约束。
     */
    private static Path workingDirectory(Path workspaceRoot, String rawWorkdir) throws IOException {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        if (rawWorkdir == null) {
            return root;
        }
        try {
            Path requested = Path.of(rawWorkdir);
            Path resolved = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
            if (!Files.isDirectory(resolved)) {
                throw new IOException("process_working_directory_invalid");
            }
            return resolved.toAbsolutePath();
        } catch (InvalidPathException invalid) {
            ToolSupport.ArgumentFailure failure =
                    ToolSupport.argument("workdir", "must be a valid host directory path");
            failure.initCause(invalid);
            throw failure;
        }
    }

    /**
     * 工具操作绑定当前 Workspace、Thread、Turn 与唯一进程所有者代际，拒绝身份快照外控制。
     */
    private static Identity identity(ProcessSessionUseCase processes, ExecutionContext context) {
        ExecutionContext owner = Objects.requireNonNull(context, "context");
        return new Identity(owner.workspaceId(), owner.threadId(), owner.turnId(), processes.generation());
    }

    /**
     * 省略超时表示不设期限；显式值必须为正整数，避免把默认时长误作进程生命周期上限。
     */
    private static int optionalPositiveTimeout(Invocation invocation) {
        if (!invocation.arguments().members().containsKey("timeout_ms")) {
            return 0;
        }
        return ToolSupport.integer(invocation, "timeout_ms", 1, 1, MAX_TIMEOUT_MILLIS);
    }

    /**
     * 读取可选 UTF-16 输出游标，并限制在 JavaScript 可精确表示的整数范围内。
     */
    private static long optionalSequence(Invocation invocation) {
        JsonValue value = invocation.arguments().members().get("after_sequence");
        if (value == null) {
            return 0;
        }
        return sequenceNumber(value);
    }

    /**
     * 保留绝对游标的 long 精度并拒绝越界值；十进制转换失败仍映射为固定参数错误且保留内部 cause。
     */
    private static long sequenceNumber(JsonValue value) {
        if (!(value instanceof JsonNumber number)) {
            throw ToolSupport.argument("after_sequence", "must be a safe non-negative integer");
        }
        try {
            long sequence = number.value().longValueExact();
            if (sequence < 0 || sequence > MAX_SAFE_SEQUENCE) {
                throw ToolSupport.argument("after_sequence", "must be a safe non-negative integer");
            }
            return sequence;
        } catch (ArithmeticException invalid) {
            ToolSupport.ArgumentFailure failure =
                    ToolSupport.argument("after_sequence", "must be a safe non-negative integer");
            failure.initCause(invalid);
            throw failure;
        }
    }

    /**
     * 模型正文携带续读所需身份，独立产物只保存真实进程输出，避免界面把恢复句柄当作 stdout。
     * 成功结果使用 null 错误码，中断历史不暗示进程已被自动重启。
     */
    private static ToolResult processResult(Snapshot snapshot) {
        Snapshot value = Objects.requireNonNull(snapshot, "snapshot");
        JsonObjectBuilder metadata = JsonObjects.builder()
                .putText("session_id", value.sessionId())
                .putNumber("generation", value.generation())
                .putText("state", value.state())
                .putNumber("output_sequence", value.outputSequence())
                .putNumber("next_sequence", value.nextSequence())
                .putBoolean("output_truncated", value.outputTruncated());
        if (value.exitCode() != null) {
            metadata.putNumber("exit_code", value.exitCode());
        }
        String output = value.output().isEmpty() ? "(no new output)" : value.output();
        String content = output + "\n\n[process session: " + value.state() + ", session_id=" + value.sessionId()
                + ", generation=" + value.generation() + ", next_sequence=" + value.nextSequence()
                + (value.exitCode() == null ? "" : ", exit_code=" + value.exitCode())
                + ", output_truncated=" + value.outputTruncated() + "]";
        if ("interrupted".equals(value.state())) {
            content += "\nThis is retained history from a prior runtime generation; the process was not restarted. "
                    + "Use list_processes to inspect the retained state before deciding whether to launch a replacement.";
        }
        return new ToolResult(ToolOutcome.SUCCEEDED, content, Optional.of(metadata.build()), null)
                .withArtifactContent(value.output());
    }

    /**
     * 将列表页投影为可恢复的结构化句柄并遵守成功结果错误码为空的约束，不暴露命令或环境。
     */
    private static ToolResult processListResult(Page page, long currentGeneration) {
        Page result = Objects.requireNonNull(page, "page");
        List<ProcessSessionSummary> sessions = result.sessions();
        List<JsonValue> summaries = sessions.stream().map(ProcessToolFactory::processSummary)
                .map(JsonValue.class::cast).toList();
        JsonObjectBuilder structured = JsonObjects.builder()
                .putNumber("current_generation", currentGeneration)
                .put("sessions", new JsonArray(summaries));
        if (result.nextCursor() == null) {
            structured.put("next_cursor", JsonNull.INSTANCE);
        } else {
            structured.putText("next_cursor", result.nextCursor());
        }
        String content = sessions.isEmpty() ? "No process sessions are retained for this Thread."
                : sessions.stream().map(summary -> "session_id=" + summary.sessionId() + ", state=" + summary.state()
                        + ", generation=" + summary.generation() + ", output_sequence=" + summary.outputSequence())
                        .collect(java.util.stream.Collectors.joining("\n"));
        if (result.nextCursor() != null) {
            content += "\n[next_cursor: " + result.nextCursor() + "]";
        }
        content += "\n[current runtime generation: " + currentGeneration + "]";
        return new ToolResult(ToolOutcome.SUCCEEDED, content, Optional.of(structured.build()), null);
    }

    /**
     * 仅序列化持久恢复所需字段，使上下文压缩后仍可安全继续观察会话。
     */
    private static JsonObject processSummary(ProcessSessionSummary summary) {
        JsonObjectBuilder value = JsonObjects.builder()
                .putText("session_id", summary.sessionId())
                .putText("state", summary.state())
                .putNumber("generation", summary.generation())
                .putText("started_turn_id", summary.startedTurnId())
                .putText("started_call_id", summary.startedCallId())
                .putText("created_at", summary.createdAt().toString())
                .putNumber("output_sequence", summary.outputSequence())
                .putBoolean("output_truncated", summary.outputTruncated());
        if (summary.exitCode() == null) {
            value.put("exit_code", JsonNull.INSTANCE);
        } else {
            value.putNumber("exit_code", summary.exitCode());
        }
        return value.build();
    }

    /**
     * 会话已无法由当前运行时控制时返回稳定失败契约，不泄漏底层资源细节。
     */
    private static ToolResult failed(String message, String code) {
        return new ToolResult(ToolOutcome.FAILED, message, Optional.empty(), code);
    }
}
