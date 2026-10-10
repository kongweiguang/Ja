// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools.process;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger.Entry;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger.WriteClaim;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionFailure.Code;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.platform.windows.WindowsJobObject;
import io.github.kongweiguang.ja.platform.windows.WindowsProcessLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 独占管理原生进程句柄、持久进程事实及 Thread 级清理，避免资源生命周期分散到调用方。
 */
public final class ProcessSessionRegistry implements ProcessSessionUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessSessionRegistry.class);
    private static final int OUTPUT_RETAINED_CHARACTERS = 262_144;
    private static final int MAX_OUTPUT_BYTES_PER_READ = 65_536;
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final long DEFAULT_CLOSE_MILLIS = 10_000;
    private static final long STOP_GRACE_MILLIS = 1_500;
    private static final long MONITOR_POLL_MILLIS = 50;
    private final long generation;
    private final ProcessSessionLedger ledger;
    private final Clock clock;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Object> callLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> threadLocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> threadEpochs = new ConcurrentHashMap<>();
    private final java.util.Set<String> closedThreads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 发布新代际注册表前先恢复旧进程事实，确保历史状态不会被误认为仍可控制。
     */
    public ProcessSessionRegistry(long generation, ProcessSessionLedger ledger, Clock clock) {
        if (generation < 1 || generation > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("invalid process generation");
        }
        this.generation = generation;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ledger.interruptOtherGenerations(generation, clock.instant());
    }

    /**
     * 返回装配时冻结的代际，使 RPC 与 Tool 绑定使用同一资源围栏。
     */
    @Override
    public long generation() {
        return generation;
    }

    /**
     * 启动原生进程前先持久化 starting 记录；本次调用仅等待请求的首轮观察窗口。
     */
    @Override
    public Snapshot exec(Launch request, int yieldMillis, CancellationToken cancellationToken) {
        Launch launch = Objects.requireNonNull(request, "request");
        CancellationToken cancellation = Objects.requireNonNull(cancellationToken, "cancellationToken");
        validateYield(yieldMillis);
        requireOpen();
        requireCurrent(launch.owner());
        cancellation.throwIfCancellationRequested();

        String threadKey = threadKey(launch.owner().workspaceId(), launch.owner().threadId());
        if (closedThreads.contains(threadKey)) {
            throw new IllegalStateException("process thread is closed");
        }
        AtomicLong threadEpoch = threadEpochs.computeIfAbsent(threadKey, ignored -> new AtomicLong());
        long admittedEpoch = threadEpoch.get();
        Object threadLock = threadLocks.computeIfAbsent(threadKey, ignored -> new Object());
        String callKey = callKey(launch.owner(), launch.callId());
        Object callLock = callLocks.computeIfAbsent(callKey, ignored -> new Object());
        Running admitted;
        synchronized (threadLock) {
            if (closedThreads.contains(threadKey) || closed.get() || threadEpoch.get() != admittedEpoch) {
                throw new IllegalStateException("process registry is closed");
            }
            synchronized (callLock) {
                Optional<Entry> existing = ledger.findByCall(launch.owner().threadId(), launch.owner().turnId(),
                        launch.callId());
                if (existing.isPresent()) {
                    Entry prior = existing.orElseThrow();
                    if (!prior.workspaceId().equals(launch.owner().workspaceId())) {
                        throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
                    }
                    if (prior.state().equals("failed")) {
                        throw new ProcessSessionFailure(Code.LAUNCH_FAILED, new IOException("process launch failed"));
                    }
                    Running active = running.get(prior.sessionId());
                    return active == null ? snapshot(prior, 0, MAX_OUTPUT_BYTES_PER_READ, generation)
                            : snapshot(active.entry, 0, MAX_OUTPUT_BYTES_PER_READ, generation);
                }

                Instant createdAt = clock.instant();
                Entry starting = new Entry(newSessionId(), launch.owner().workspaceId(), launch.owner().threadId(),
                        launch.owner().turnId(), launch.callId(), generation, launch.command(), "starting", "", 0,
                        null, createdAt, null);
                Entry persisted = ledger.createStarting(starting);
                if (!persisted.sessionId().equals(starting.sessionId())) {
                    if (persisted.state().equals("failed")) {
                        throw new ProcessSessionFailure(Code.LAUNCH_FAILED, new IOException("process launch failed"));
                    }
                    Running active = running.get(persisted.sessionId());
                    return active == null ? snapshot(persisted, 0, MAX_OUTPUT_BYTES_PER_READ, generation)
                            : snapshot(active.entry, 0, MAX_OUTPUT_BYTES_PER_READ, generation);
                }
                admitted = launch(persisted, launch);
            }
        }

        CancellationToken.Registration registration = cancellation.onCancellation(
                () -> terminate(admitted, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MILLIS)));
        try {
            awaitYield(admitted, yieldMillis, cancellation);
            return snapshot(admitted.entry, 0, MAX_OUTPUT_BYTES_PER_READ, generation);
        } finally {
            registration.close();
        }
    }

    /**
     * stdin 写入以幂等回执约束副作用；本 Turn 取消不会误停更早 Turn 已启动的会话。
     */
    @Override
    public Snapshot writeStdin(Identity caller, String callId, String sessionId, String input, boolean closeInput,
                               long afterSequence, int yieldMillis, CancellationToken cancellationToken) {
        Identity owner = Objects.requireNonNull(caller, "caller");
        String safeCallId = identifier(callId, "callId");
        String payload = Objects.requireNonNull(input, "input");
        CancellationToken cancellation = Objects.requireNonNull(cancellationToken, "cancellationToken");
        validateYield(yieldMillis);
        validateInput(payload);
        validateRead(afterSequence, MAX_OUTPUT_BYTES_PER_READ);
        requireOpen();
        requireCurrent(owner);
        Entry entry = requireEntry(owner.scope(), sessionId);
        if (entry.generation() != generation || !isLive(entry.state())) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        Running process = running.get(entry.sessionId());
        if (process == null) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        String key = toolOperationKey(safeCallId);
        String fingerprint = fingerprint(payload, closeInput);
        synchronized (process.lock) {
            WriteClaim claim = ledger.claimWrite(entry.sessionId(), key, fingerprint, clock.instant());
            if (claim == WriteClaim.APPLIED) {
                return snapshot(process.entry, afterSequence, MAX_OUTPUT_BYTES_PER_READ, generation);
            }
            if (claim != WriteClaim.CLAIMED) {
                markUnknownAndStop(process, key);
                throw new ProcessSessionFailure(Code.WRITE_OUTCOME_UNKNOWN);
            }
            try {
                deliver(process, payload, closeInput);
                ledger.resolveWrite(entry.sessionId(), key, WriteClaim.APPLIED, clock.instant());
            } catch (IOException | RuntimeException failure) {
                resolveUnknownQuietly(process, key, failure);
                terminate(process, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MILLIS));
                throw new ProcessSessionFailure(Code.WRITE_OUTCOME_UNKNOWN, failure);
            }
        }
        if (!cancellation.isCancellationRequested()) {
            awaitYield(process, yieldMillis, cancellation);
        }
        return snapshot(process.entry, afterSequence, MAX_OUTPUT_BYTES_PER_READ, generation);
    }

    /**
     * 等待输出或进程结束时只观察会话条件，不为无输入等待伪造 stdin 持久回执。
     */
    @Override
    public Snapshot observe(Identity caller, String sessionId, long afterSequence, int maxBytes, int yieldMillis,
                            CancellationToken cancellationToken) {
        Identity owner = Objects.requireNonNull(caller, "caller");
        CancellationToken cancellation = Objects.requireNonNull(cancellationToken, "cancellationToken");
        validateYield(yieldMillis);
        requireOpen();
        requireCurrent(owner);
        Snapshot initial = read(owner.scope(), sessionId, afterSequence, maxBytes);
        if (yieldMillis == 0 || !initial.output().isEmpty() || !isLive(initial.state())) {
            return initial;
        }
        Running process = running.get(initial.sessionId());
        if (process == null) {
            return read(owner.scope(), sessionId, afterSequence, maxBytes);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(yieldMillis);
        synchronized (process.lock) {
            while (isLive(process.entry.state()) && process.entry.outputSequence() <= afterSequence
                    && !cancellation.isCancellationRequested()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(process.lock,
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(MONITOR_POLL_MILLIS)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return snapshot(process.entry, afterSequence, maxBytes, generation);
        }
    }

    /**
     * 按当前 Workspace、Thread 和代际返回安全摘要；游标交由账本解释，避免运行时依赖数据库适配器。
     */
    @Override
    public Page list(String workspaceId, String threadId, int limit, String cursor, String startedCallId) {
        String workspace = Objects.requireNonNull(workspaceId, "workspaceId");
        String thread = Objects.requireNonNull(threadId, "threadId");
        validatePageLimit(limit);
        if (startedCallId != null) {
            identifier(startedCallId, "startedCallId");
        }
        ProcessSessionLedger.Page page = ledger.list(
                new ProcessSessionScope(workspace, thread, generation), limit, cursor, startedCallId);
        return new Page(page.sessions(), page.nextCursor());
    }

    /**
     * 输出按 UTF-8 字节数限量而游标保持绝对 UTF-16 偏移，避免截断补充字符。
     */
    @Override
    public Snapshot read(ProcessSessionScope scope, String sessionId, long afterSequence, int maxBytes) {
        ProcessSessionScope caller = Objects.requireNonNull(scope, "scope");
        requireCurrent(caller);
        validateRead(afterSequence, maxBytes);
        Entry entry = requireEntry(caller, sessionId);
        if (entry.state().equals("failed")) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        Running active = running.get(entry.sessionId());
        Entry current = active == null ? entry : active.entry;
        return snapshot(current, afterSequence, maxBytes, generation);
    }

    /**
     * stdin 副作用前先占用操作键；结果不确定时拒绝重试，避免重复发送输入。
     */
    @Override
    public Snapshot write(ProcessSessionScope scope, String sessionId, String operationKey, String input, boolean closeInput,
                          long afterSequence, int maxBytes) {
        ProcessSessionScope caller = Objects.requireNonNull(scope, "scope");
        requireCurrent(caller);
        validateRead(afterSequence, maxBytes);
        validateOperationKey(operationKey);
        String payload = Objects.requireNonNull(input, "input");
        validateInput(payload);
        Entry entry = requireEntry(caller, sessionId);
        if (entry.generation() != generation || !isLive(entry.state())) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        Running process = running.get(entry.sessionId());
        if (process == null) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        String fingerprint = fingerprint(payload, closeInput);
        synchronized (process.lock) {
            WriteClaim claim = ledger.claimWrite(entry.sessionId(), rpcOperationKey(operationKey), fingerprint,
                    clock.instant());
            if (claim == WriteClaim.APPLIED) {
                return snapshot(process.entry, afterSequence, maxBytes, generation);
            }
            if (claim != WriteClaim.CLAIMED) {
                markUnknownAndStop(process, rpcOperationKey(operationKey));
                throw new ProcessSessionFailure(Code.WRITE_OUTCOME_UNKNOWN);
            }
            try {
                deliver(process, payload, closeInput);
                ledger.resolveWrite(entry.sessionId(), rpcOperationKey(operationKey), WriteClaim.APPLIED,
                        clock.instant());
            } catch (IOException | RuntimeException failure) {
                resolveUnknownQuietly(process, rpcOperationKey(operationKey), failure);
                terminate(process, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MILLIS));
                throw new ProcessSessionFailure(Code.WRITE_OUTCOME_UNKNOWN, failure);
            }
            return snapshot(process.entry, afterSequence, maxBytes, generation);
        }
    }

    /**
     * 仅停止当前代际的活动会话；停止响应不重复携带已有输出，游标仍由读取接口推进。
     */
    @Override
    public Snapshot stop(ProcessSessionScope scope, String sessionId) {
        ProcessSessionScope caller = Objects.requireNonNull(scope, "scope");
        requireCurrent(caller);
        Entry entry = requireEntry(caller, sessionId);
        if (entry.generation() != generation) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        if (entry.state().equals("failed")) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        Running process = running.get(entry.sessionId());
        if (process == null) {
            return stopSnapshot(entry);
        }
        terminate(process, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        return stopSnapshot(process.entry);
    }

    /**
     * 取消仅终止身份完全匹配的 Turn 所启动进程，之前 Turn 的后台会话保持可用。
     */
    @Override
    public void cancelTurn(Identity owner) {
        Identity turn = Objects.requireNonNull(owner, "owner");
        requireCurrent(turn);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Running> matches = running.values().stream()
                .filter(value -> value.entry.workspaceId().equals(turn.workspaceId())
                        && value.entry.threadId().equals(turn.threadId())
                        && value.entry.startedTurnId().equals(turn.turnId())
                        && value.entry.generation() == turn.generation())
                .toList();
        matches.forEach(value -> terminate(value, deadline));
    }

    /**
     * 先阻止该 Thread 的后续启动，再停止其活动进程，避免关闭与并发接纳交错。
     */
    @Override
    public void closeThread(ProcessSessionScope scope) {
        ProcessSessionScope caller = Objects.requireNonNull(scope, "scope");
        requireCurrent(caller);
        String key = threadKey(caller.workspaceId(), caller.threadId());
        Object lock = threadLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (closedThreads.add(key)) {
                threadEpochs.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            }
            List<Running> matches = running.values().stream()
                    .filter(value -> value.entry.workspaceId().equals(caller.workspaceId())
                            && value.entry.threadId().equals(caller.threadId()))
                    .toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            matches.forEach(value -> terminate(value, deadline));
        }
        threadLocks.remove(key, lock);
    }

    /**
     * 恢复后解除 Thread 启动围栏，但历史进程仍记录为中断，不据此尝试重启。
     */
    @Override
    public void reopenThread(ProcessSessionScope scope) {
        ProcessSessionScope caller = Objects.requireNonNull(scope, "scope");
        requireCurrent(caller);
        String key = threadKey(caller.workspaceId(), caller.threadId());
        Object lock = threadLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (closedThreads.remove(key)) {
                threadEpochs.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
            }
        }
    }

    /**
     * 所有进程共用一个关闭期限，避免逐个等待放大关服时长并确保释放原生所有权。
     */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Running process : List.copyOf(running.values())) {
            terminate(process, shutdownDeadlineNanos);
        }
    }

    /**
     * 直接生命周期调用沿用统一的有界关闭窗口，防止默认清理永久等待。
     */
    @Override
    public void close() {
        closeAt(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEFAULT_CLOSE_MILLIS));
    }

    /**
     * 账本记录持久化后才启动；成功时资源转交 Running，失败时集中回收，不能用方法级自动关闭截断后台进程。
     */
    @SuppressWarnings("PMD.CloseResource")
    private Running launch(Entry starting, Launch request) {
        WindowsJobObject job = null;
        Process process = null;
        try {
            Path cwd = request.workingDirectory().toAbsolutePath().normalize();
            if (!Files.isDirectory(cwd)) {
                throw new IOException("process_working_directory_invalid");
            }
            if (WindowsJobObject.isSupported()) {
                job = WindowsJobObject.create();
                process = WindowsProcessLauncher.launch(
                        request.commandLine(), cwd, request.environment(), job, request.tty());
            } else {
                if (request.tty()) {
                    throw new IOException("process_tty_unsupported");
                }
                ProcessBuilder builder = new ProcessBuilder(request.commandLine()).directory(cwd.toFile());
                Map<String, String> environment = builder.environment();
                environment.clear();
                environment.putAll(request.environment());
                process = builder.start();
            }
            Running session = new Running(starting.withRuntime("running", "", 0, null, null), process, job,
                    request.timeoutMillis());
            ledger.update(session.entry);
            running.put(starting.sessionId(), session);
            session.stdoutReader = Thread.startVirtualThread(
                    () -> drain(session, session.process.getInputStream(), false));
            session.stderrReader = Thread.startVirtualThread(
                    () -> drain(session, session.process.getErrorStream(), true));
            Thread.startVirtualThread(() -> monitor(session));
            return session;
        } catch (IOException | RuntimeException failure) {
            closeFailedLaunch(process, job, failure);
            Entry failed = starting.withRuntime("failed", starting.output(), starting.outputSequence(), null,
                    clock.instant());
            try {
                ledger.update(failed);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            throw new ProcessSessionFailure(Code.LAUNCH_FAILED, failure);
        }
    }

    /**
     * 监视进程退出及可选生命周期期限；未指定期限时不人为限制后台运行时长。
     */
    private void monitor(Running session) {
        long deadline = session.timeoutMillis == 0 ? Long.MAX_VALUE
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(session.timeoutMillis);
        try {
            while (session.process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    session.timedOut.set(true);
                    terminate(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                    break;
                }
                long waitMillis = deadline == Long.MAX_VALUE ? MONITOR_POLL_MILLIS
                        : Math.max(1, Math.min(MONITOR_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
                session.process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
            }
            complete(session);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminate(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            complete(session);
        } catch (RuntimeException failure) {
            LOGGER.warn("Process monitor failed sessionId={} type={}", session.entry.sessionId(),
                    failure.getClass().getName());
            terminate(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            complete(session);
        }
    }

    /**
     * 先排空输出并释放平台句柄，再在同一锁内发布退出事实并唤醒观察者，避免完成通知早于资源收口。
     */
    private void complete(Running session) {
        if (!session.completing.compareAndSet(false, true)) {
            return;
        }
        try {
            WindowsProcessLauncher.finishPseudoConsole(session.process);
        } catch (RuntimeException failure) {
            LOGGER.warn("Process pseudoconsole close failed sessionId={} type={}",
                    session.entry.sessionId(), failure.getClass().getName());
        }
        awaitReader(session.stdoutReader, session, false);
        awaitReader(session.stderrReader, session, true);
        Integer exitCode = null;
        try {
            exitCode = session.process.exitValue();
        } catch (IllegalThreadStateException ignored) {
            session.process.destroyForcibly();
        }
        closeProcess(session);
        synchronized (session.lock) {
            Entry prior = session.entry;
            Entry ended = prior.withRuntime(prior.state().equals("failed") ? "failed" : "exited",
                    prior.output(), prior.outputSequence(), exitCode, clock.instant());
            try {
                ledger.update(ended);
                session.entry = ended;
            } catch (RuntimeException persistenceFailure) {
                LOGGER.error("Process exit could not be persisted sessionId={} type={}",
                        prior.sessionId(), persistenceFailure.getClass().getName());
            }
            session.lock.notifyAll();
        }
        running.remove(session.entry.sessionId(), session);
        session.finished.complete(null);
    }

    /**
     * 仅保留有界输出后缀，且每个解码片段先持久化再公开对应游标。
     */
    private void appendOutput(Running session, String output) {
        if (output.isEmpty()) {
            return;
        }
        synchronized (session.lock) {
            Entry prior = session.entry;
            if (!isLive(prior.state())) {
                return;
            }
            String combined = trimOutput(prior.output() + output);
            long sequence = prior.outputSequence() + output.length();
            Entry next = prior.withRuntime(prior.state(), combined, sequence, prior.exitCode(), prior.endedAt());
            ledger.update(next);
            session.entry = next;
            session.lock.notifyAll();
        }
    }

    /**
     * 以 UTF-8 读取原生流并跨缓冲区保留拆分的代理对，避免字符边界损坏。
     */
    private void drain(Running session, InputStream stream, boolean stderr) {
        char pendingHigh = 0;
        boolean stderrLabel = false;
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8_192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                String chunk = new String(buffer, 0, count);
                if (pendingHigh != 0) {
                    chunk = pendingHigh + chunk;
                    pendingHigh = 0;
                }
                if (!chunk.isEmpty() && Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))) {
                    pendingHigh = chunk.charAt(chunk.length() - 1);
                    chunk = chunk.substring(0, chunk.length() - 1);
                }
                if (chunk.isEmpty()) {
                    continue;
                }
                chunk = chunk.replace('\0', '\uFFFD');
                if (stderr && !stderrLabel) {
                    chunk = "[stderr]\n" + chunk;
                    stderrLabel = true;
                }
                appendOutput(session, chunk);
            }
            if (pendingHigh != 0) {
                appendOutput(session, "\uFFFD");
            }
        } catch (IOException failure) {
            if (!session.completing.get() && session.process.isAlive()) {
                LOGGER.warn("Process output reader failed sessionId={} stream={} type={}",
                        session.entry.sessionId(), stderr ? "stderr" : "stdout", failure.getClass().getName());
                terminate(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            }
        }
    }

    /**
     * 在有界工具观察窗口内等待监视器或读取器更新，超时只结束等待，不终止后台进程。
     */
    private void awaitYield(Running session, int yieldMillis, CancellationToken cancellation) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(yieldMillis);
        synchronized (session.lock) {
            while (isLive(session.entry.state()) && !cancellation.isCancellationRequested()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(session.lock,
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(MONITOR_POLL_MILLIS)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 终止前持久化 stopping，并在同一期限内等待监视器完成输出与退出账本，避免原生退出后提前返回。
     */
    private void terminate(Running session, long deadlineNanos) {
        if (session.finished.isDone()) {
            return;
        }
        synchronized (session.lock) {
            if (!session.entry.state().equals("stopping") && isLive(session.entry.state())) {
                Entry stopping = session.entry.withRuntime("stopping", session.entry.output(),
                        session.entry.outputSequence(), session.entry.exitCode(), null);
                try {
                    ledger.update(stopping);
                    session.entry = stopping;
                } catch (RuntimeException persistenceFailure) {
                    LOGGER.error("Process stopping fact could not be persisted sessionId={} type={}",
                            session.entry.sessionId(), persistenceFailure.getClass().getName());
                }
            }
        }
        if (session.terminating.compareAndSet(false, true)) {
            terminateNativeTree(session);
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0) {
            try {
                session.process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (session.process.isAlive()) {
            session.process.destroyForcibly();
        }
        if (!session.process.isAlive() && !session.finished.isDone()) {
            complete(session);
        }
        remaining = deadlineNanos - System.nanoTime();
        if (remaining > 0 && !session.finished.isDone()) {
            try {
                session.finished.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unresolved) {
                // 到期仍保留真实 stopping 状态，由后续读取核实，不伪造已完成的退出事实。
                LOGGER.debug("Process completion remains unresolved sessionId={} type={}",
                        session.entry.sessionId(), unresolved.getClass().getName());
            }
        }
    }

    /**
     * Windows 依赖 Job 所有权，POSIX 使用已捕获的后代集合，停止时不按任意 PID 扩大目标。
     */
    private static void terminateNativeTree(Running session) {
        if (session.job != null) {
            try {
                session.job.terminate();
            } catch (IOException failure) {
                session.process.destroyForcibly();
            }
            return;
        }
        List<ProcessHandle> descendants = session.process.toHandle().descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        session.process.destroy();
    }

    /**
     * 每个已认领输入最多发送一次；显式 Ctrl+C 字符转为停止请求，不伪装成普通 stdin 内容。
     */
    private void deliver(Running session, String input, boolean closeInput) throws IOException {
        if (input.equals("\u0003")) {
            if (session.terminating.compareAndSet(false, true)) {
                terminateNativeTree(session);
            }
        } else if (!input.isEmpty()) {
            if (session.inputClosed.get()) {
                throw new IOException("process_stdin_closed");
            }
            session.process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            session.process.getOutputStream().flush();
        }
        if (closeInput && session.inputClosed.compareAndSet(false, true)) {
            session.process.getOutputStream().close();
        }
    }

    /**
     * 写入结果含糊时先停止会话并记录 unknown，再把不确定事实返回调用方。
     */
    private void markUnknownAndStop(Running session, String operationKey) {
        try {
            ledger.resolveWrite(session.entry.sessionId(), operationKey, WriteClaim.UNKNOWN, clock.instant());
        } catch (RuntimeException resolutionFailure) {
            LOGGER.error("Process write uncertainty could not be persisted sessionId={} type={}",
                    session.entry.sessionId(), resolutionFailure.getClass().getName());
        }
        terminate(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    /**
     * 写入失败时将回执定为 unknown；原始异常仅留在本地诊断，不进入稳定外部结果。
     */
    private void resolveUnknownQuietly(Running session, String operationKey, Throwable original) {
        try {
            ledger.resolveWrite(session.entry.sessionId(), operationKey, WriteClaim.UNKNOWN, clock.instant());
        } catch (RuntimeException resolutionFailure) {
            original.addSuppressed(resolutionFailure);
        }
    }

    /**
     * 等待读取器排空并确认进程树退出后才关闭其流视图，避免截掉末尾输出。
     */
    private void awaitReader(Thread reader, Running session, boolean stderr) {
        if (reader == null) {
            return;
        }
        try {
            reader.join(STOP_GRACE_MILLIS);
            if (reader.isAlive()) {
                closeStream(session, stderr);
                reader.join(STOP_GRACE_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeStream(session, stderr);
        }
    }

    /**
     * 有界排空后关闭单个父端读取器，避免子进程继承的句柄无限拖延完成。
     */
    private void closeStream(Running session, boolean stderr) {
        try {
            (stderr ? session.process.getErrorStream() : session.process.getInputStream()).close();
        } catch (IOException failure) {
            LOGGER.debug("Process stream close failed sessionId={} stream={}",
                    session.entry.sessionId(), stderr ? "stderr" : "stdout");
        }
    }

    /**
     * 读取器不再依赖资源后再关闭 stdin、Windows 管道和 Job 句柄。
     */
    private void closeProcess(Running session) {
        try {
            session.process.getOutputStream().close();
        } catch (IOException failure) {
            LOGGER.debug("Process stdin close failed sessionId={}", session.entry.sessionId());
        }
        if (session.job != null) {
            try {
                session.job.closeAfterRootExit();
            } catch (IOException failure) {
                LOGGER.error("Process Job close failed sessionId={} type={}", session.entry.sessionId(),
                        failure.getClass().getName());
            }
            try {
                WindowsProcessLauncher.close(session.process);
            } catch (IOException failure) {
                LOGGER.error("Process handle close failed sessionId={} type={}", session.entry.sessionId(),
                        failure.getClass().getName());
            }
        }
    }

    /**
     * 启动未完成时回收已取得资源，并将清理异常附加到主故障以保留根因。
     */
    private static void closeFailedLaunch(Process process, WindowsJobObject job, Exception failure) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        if (job != null) {
            try {
                job.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (process != null && WindowsJobObject.isSupported()) {
            try {
                WindowsProcessLauncher.close(process);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 读取并校验持久会话的作用域；跨 Thread 身份不匹配统一表现为不存在。
     */
    private Entry requireEntry(ProcessSessionScope scope, String sessionId) {
        if (sessionId == null || !sessionId.matches("proc_[0-9a-f]{32}")) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
        return ledger.find(sessionId, scope.workspaceId(), scope.threadId())
                .orElseThrow(() -> new ProcessSessionFailure(Code.SESSION_UNAVAILABLE));
    }

    /**
     * 按 UTF-8 字节预算生成输出窗口，同时维持绝对 Java UTF-16 游标语义。
     */
    private static Snapshot snapshot(Entry original, long afterSequence, int maxBytes, long callerGeneration) {
        Entry entry = original;
        if (entry.generation() != callerGeneration && isLive(entry.state())) {
            entry = entry.withRuntime("interrupted", entry.output(), entry.outputSequence(), entry.exitCode(),
                    entry.endedAt());
        }
        long sequence = entry.outputSequence();
        if (afterSequence > sequence) {
            throw new IllegalArgumentException("output cursor is ahead of the process");
        }
        String retained = entry.output();
        long baseSequence = sequence - retained.length();
        long actualStart = Math.max(afterSequence, baseSequence);
        int offset = Math.toIntExact(actualStart - baseSequence);
        if (offset > 0 && offset < retained.length() && Character.isLowSurrogate(retained.charAt(offset))
                && Character.isHighSurrogate(retained.charAt(offset - 1))) {
            offset++;
            actualStart++;
        }
        StringBuilder output = new StringBuilder();
        int usedBytes = 0;
        int consumedChars = 0;
        while (offset + consumedChars < retained.length()) {
            int index = offset + consumedChars;
            int codePoint = retained.codePointAt(index);
            int chars = Character.charCount(codePoint);
            int bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + bytes > maxBytes) {
                break;
            }
            output.appendCodePoint(codePoint);
            usedBytes += bytes;
            consumedChars += chars;
        }
        String state = projectState(entry.generation(), entry.state(), callerGeneration);
        return new Snapshot(entry.sessionId(), entry.workspaceId(), entry.threadId(), entry.startedTurnId(),
                entry.startedCallId(), entry.generation(), state, entry.createdAt(), output.toString(), sequence,
                actualStart + consumedChars, afterSequence < baseSequence || entry.outputTruncated(), entry.exitCode());
    }

    /**
     * 停止结果反映真实状态与游标，不重放输出正文以免覆盖后续读取进度。
     */
    private static Snapshot stopSnapshot(Entry entry) {
        String state = projectState(entry.generation(), entry.state(), entry.generation());
        if (!state.equals("stopping") && !state.equals("exited")) {
            state = "exited";
        }
        return new Snapshot(entry.sessionId(), entry.workspaceId(), entry.threadId(), entry.startedTurnId(),
                entry.startedCallId(), entry.generation(), state, entry.createdAt(), "", entry.outputSequence(),
                entry.outputSequence(), false, entry.exitCode());
    }

    /**
     * 将内部临时或失败状态投影到封闭协议枚举，同时保留账本中的真实事实。
     */
    private static String projectState(long ownerGeneration, String state, long callerGeneration) {
        if (ownerGeneration != callerGeneration && isLive(state)) {
            return "interrupted";
        }
        return switch (state) {
            case "starting" -> "running";
            case "failed" -> "exited";
            default -> state;
        };
    }

    /**
     * 限制保留输出大小，并将裁剪边界移过未配对代理项以保持 UTF-16 字符完整。
     */
    private static String trimOutput(String output) {
        if (output.length() <= OUTPUT_RETAINED_CHARACTERS) {
            return output;
        }
        int start = output.length() - OUTPUT_RETAINED_CHARACTERS;
        if (Character.isLowSurrogate(output.charAt(start)) && Character.isHighSurrogate(output.charAt(start - 1))) {
            start++;
        }
        return output.substring(start);
    }

    /**
     * 读取量不得超过 v2 线协议预算，游标也不得超出当前输出绝对序列。
     */
    private static void validateRead(long afterSequence, int maxBytes) {
        if (afterSequence < 0 || maxBytes < 1 || maxBytes > MAX_OUTPUT_BYTES_PER_READ) {
            throw new IllegalArgumentException("invalid process output read window");
        }
    }

    /**
     * 在占用操作键之前按 UTF-8 字节预算拒绝过大输入，避免留下无效幂等记录。
     */
    private static void validateInput(String input) {
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("process input exceeds the request limit");
        }
    }

    /**
     * 首轮及后续等待都限制在 RPC/Tool 可响应预算内，超时不等同于终止进程。
     */
    private static void validateYield(int yieldMillis) {
        if (yieldMillis < 0 || yieldMillis > 10_000) {
            throw new IllegalArgumentException("invalid process yield window");
        }
    }

    /**
     * 在生成查询或分配结果列表前拒绝超大分页请求，限制数据库与内存开销。
     */
    private static void validatePageLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("invalid process session page size");
        }
    }

    /**
     * 持久化幂等查询前先校验 JSON-RPC 操作键形状，避免非法标识进入账本。
     */
    private static void validateOperationKey(String operationKey) {
        if (operationKey == null || !operationKey.matches("op_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid process operation key");
        }
    }

    /**
     * 每个请求必须携带当前进程所有者代际，不能信任会话记录中的旧代际字段。
     */
    private void requireCurrent(ProcessSessionScope scope) {
        if (scope.generation() != generation) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
    }

    /**
     * Tool Turn 必须属于当前运行时，才允许启动或取消原生进程资源。
     */
    private void requireCurrent(Identity owner) {
        if (owner.generation() != generation) {
            throw new ProcessSessionFailure(Code.SESSION_UNAVAILABLE);
        }
    }

    /**
     * 关闭开始后拒绝新的启动接纳，防止已回收的所有权重新进入注册表。
     */
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("process registry is closed");
        }
    }

    /**
     * stdin 操作仅接受活动状态；starting、stopping、exited、failed 和 interrupted 均不可写。
     */
    private static boolean isLive(String state) {
        return state.equals("starting") || state.equals("running") || state.equals("stopping");
    }

    /**
     * 生成进程专属持久身份，不复用 AgentStore 请求标识以免跨领域碰撞。
     */
    private static String newSessionId() {
        return "proc_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 为内存生命周期键明确编码 Workspace 与 Thread，避免直接拼接产生歧义。
     */
    private static String threadKey(String workspaceId, String threadId) {
        return workspaceId.length() + ":" + workspaceId + threadId;
    }

    /**
     * 将 Turn 与调用 ID 组合为接纳锁键，使同一幂等调用串行执行。
     */
    private static String callKey(Identity owner, String callId) {
        return threadKey(owner.workspaceId(), owner.threadId()) + owner.generation() + ":"
                + owner.turnId() + ":" + callId;
    }

    /**
     * 校验公开调用标识但不改写 Provider 生成的原始身份字节。
     */
    private static String identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 在共用写入回执账本中区分 Tool call ID 与 RPC operation ID，防止命名空间碰撞。
     */
    private static String toolOperationKey(String callId) {
        return "op_" + digest("tool:" + callId).substring(0, 32);
    }

    /**
     * 外部 RPC 幂等键持久化前加上进程写入命名空间，避免与 Tool 写入键冲突。
     */
    private static String rpcOperationKey(String operationKey) {
        return "op_" + digest("rpc:" + operationKey).substring(0, 32);
    }

    /**
     * 对完整 stdin 操作计算指纹，阻止同一幂等键被重用为不同副作用。
     */
    private static String fingerprint(String input, boolean closeInput) {
        return digest((closeInput ? "1\0" : "0\0") + input);
    }

    /**
     * 以稳定的小写 SHA-256 摘要认领操作，不把原始输入字节持久保留在回执中。
     */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 聚合进程及读取器，通过完成 Future 统一等待监视器收口，无需额外保存其线程句柄。
     */
    private static final class Running {
        private final Object lock = new Object();
        private final Process process;
        private final WindowsJobObject job;
        private final long timeoutMillis;
        private final AtomicBoolean terminating = new AtomicBoolean();
        private final AtomicBoolean completing = new AtomicBoolean();
        private final AtomicBoolean inputClosed = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile Entry entry;
        private volatile Thread stdoutReader;
        private volatile Thread stderrReader;

        /**
         * 接管已启动进程及可选 Job，后续资源清理只由此运行记录负责。
         */
        private Running(Entry entry, Process process, WindowsJobObject job, long timeoutMillis) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.process = Objects.requireNonNull(process, "process");
            this.job = job;
            this.timeoutMillis = timeoutMillis;
        }
    }
}
