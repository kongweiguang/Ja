// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.platform.windows.WindowsNativeProcessObservation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把已认证原生连接的私有环境限制在进程内；RPC 请求只在同步准入期间借用它，
 * 异步 Turn 必须复制 Snapshot，不能依赖请求线程或连接继续存活。
 */
public final class NativeExecutionContext {
    private static final int MAX_NATIVE_CLIENT_OWNERS = 16;
    private static final NativeExecutionContext SHARED = new NativeExecutionContext();
    private final ConcurrentMap<String, Binding> contexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Binding> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<RunOwner, RunBinding> runs = new ConcurrentHashMap<>();
    private final ThreadLocal<Binding> current = new ThreadLocal<>();
    private final AtomicInteger sharedModeOwners = new AtomicInteger();
    private final Object sharedModeLock = new Object();
    private final ConcurrentMap<String, RegisteredNativeClient> nativeClients = new ConcurrentHashMap<>();
    private final Map<ProcessIdentity, NativeProcessGroup> nativeProcesses = new java.util.HashMap<>();
    private final Object nativeClientLock = new Object();
    private WeakReference<NativeClientProcessExitListener> processExitListener = new WeakReference<>(null);

    /**
     * 限定实例由 shared() 发布，避免不同传输各持一份请求映射。
     */
    private NativeExecutionContext() {
    }

    /**
     * 共享后台只有一个认证代理和一个内层 RPC Server，二者必须使用同一桥接实例。
     */
    public static NativeExecutionContext shared() {
        return SHARED;
    }

    /**
     * 使用短 ID 防止无界请求键被本地代理错误输入放大。
     */
    private static String requireRpcRequestId(String rpcRequestId) {
        if (rpcRequestId == null || rpcRequestId.isBlank() || rpcRequestId.length() > 256) {
            throw new IllegalArgumentException("invalid native execution request identity");
        }
        return rpcRequestId;
    }

    /**
     * 复制完整客户端环境并分配不可猜的连接内存身份；拒绝无法传给原生进程的 NUL，
     * 容量上限只防止本地错误客户端耗尽后台内存，不筛选 PATH、代理或登录变量。
     */
    public String register(Map<String, String> environment, String shell,
                           Optional<NativeClientOwner> owner) {
        Binding binding = new Binding(new NativeExecutionSnapshot(environment, shell),
                Objects.requireNonNull(owner, "owner").orElse(null));
        String contextId = "ctx_" + UUID.randomUUID().toString().replace("-", "");
        contexts.put(contextId, binding);
        return contextId;
    }

    /**
     * 登记 Native Host 的唯一逻辑生命周期；PID 只用于绑定进程实例，不作为 WebSocket 对端认证。
     * @author kongweiguang
     */
    public NativeClientOwner registerNativeClient(long processId, String lifetimeId) {
        if (processId < 1 || processId > 9_007_199_254_740_991L || lifetimeId == null
                || !lifetimeId.matches("native_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid native client identity");
        }
        ProcessHandle process = null;
        WindowsNativeProcessObservation windowsProcess = null;
        if (isWindowsPlatform()) {
            windowsProcess = WindowsNativeProcessObservation.open(processId);
        } else {
            process = ProcessHandle.of(processId)
                    .filter(ProcessHandle::isAlive)
                    .orElseThrow(() -> new IllegalArgumentException("native client process is unavailable"));
        }
        Instant creationTime = windowsProcess == null
                ? process.info().startInstant()
                        .orElseThrow(() -> new IllegalArgumentException("native client process identity is unavailable"))
                : windowsProcess.creationTime();
        ProcessIdentity identity = new ProcessIdentity(processId, creationTime);
        Throwable registrationFailure = null;
        try {
            synchronized (nativeClientLock) {
                RegisteredNativeClient existing = nativeClients.get(lifetimeId);
                if (existing != null) {
                    if (!existing.identity().equals(identity)) {
                        throw new IllegalArgumentException("native client lifetime identity changed");
                    }
                    return existing;
                }
                if (windowsProcess != null && !windowsProcess.isAlive()) {
                    throw new IllegalArgumentException("native client process is exiting");
                }
                long activeOwners = nativeClients.values().stream()
                        .filter(owner -> owner.state() != NativeClientState.CLOSED && owner.processAlive())
                        .count();
                if (activeOwners >= MAX_NATIVE_CLIENT_OWNERS) {
                    throw new IllegalArgumentException("native client capacity is exhausted");
                }
                NativeProcessGroup group = nativeProcesses.get(identity);
                boolean createdGroup = false;
                if (group == null) {
                    group = new NativeProcessGroup(identity, process, windowsProcess);
                    windowsProcess = null;
                    nativeProcesses.put(identity, group);
                    createdGroup = true;
                } else if (!group.processAlive()) {
                    throw new IllegalArgumentException("native client process is exiting");
                }
                RegisteredNativeClient owner = new RegisteredNativeClient(identity, lifetimeId, group);
                group.owners().add(owner);
                nativeClients.put(lifetimeId, owner);
                if (createdGroup) {
                    try {
                        if (group.windowsProcess() == null) {
                            NativeProcessGroup observedGroup = group;
                            process.onExit().whenComplete(
                                    (ignored, failure) -> nativeProcessExited(observedGroup, failure));
                        } else {
                            startWindowsProcessWatcher(group);
                        }
                    } catch (RuntimeException | Error failure) {
                        nativeClients.remove(lifetimeId, owner);
                        group.owners().remove(owner);
                        nativeProcesses.remove(identity, group);
                        try {
                            group.close();
                        } catch (RuntimeException | Error closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        throw new IllegalArgumentException("native client process exit cannot be observed", failure);
                    }
                }
                return owner;
            }
        } catch (RuntimeException | Error failure) {
            registrationFailure = failure;
            throw failure;
        } finally {
            if (windowsProcess != null) {
                try {
                    windowsProcess.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (registrationFailure != null) {
                        registrationFailure.addSuppressed(closeFailure);
                    } else {
                        throw closeFailure;
                    }
                }
            }
        }
    }

    /**
     * 只有 Windows 走最小权限 FFM 观察链；其它平台保持 JDK ProcessHandle 的原生实现。
     * @author kongweiguang
     */
    private static boolean isWindowsPlatform() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /**
     * Windows 没有可用的跨完整性级别 ProcessHandle.onExit 时，以有限等待轮询同一持有句柄。
     * @author kongweiguang
     */
    private void startWindowsProcessWatcher(NativeProcessGroup group) {
        Thread watcher = Thread.ofVirtual()
                .name("ja-native-client-process-" + group.identity().processId())
                .unstarted(() -> watchWindowsProcess(group));
        group.startWatcher(watcher);
        watcher.start();
    }

    /**
     * watcher 只用零超时检查句柄，再让出 250 毫秒；业务存活查询和关闭不会排队等待内核超时。
     * @author kongweiguang
     */
    private void watchWindowsProcess(NativeProcessGroup group) {
        while (!group.stopped()) {
            boolean exited = false;
            try {
                exited = group.awaitExit(0);
            } catch (RuntimeException ignored) {
                if (group.stopped()) {
                    return;
                }
            }
            if (exited) {
                nativeProcessExited(group, null);
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                if (!group.stopped()) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
    }

    /**
     * 仅接受由本 registry 创建的 owner 对象；关闭后的同一对象可重放回执但不得重新打开。
     * @author kongweiguang
     */
    public boolean isRegisteredNativeClient(NativeClientOwner owner) {
        return owner instanceof RegisteredNativeClient registered
                && (nativeClients.get(registered.lifetimeId()) == registered
                        || registered.state() == NativeClientState.CLOSED);
    }

    /**
     * 请求准入同时核验生命周期状态与原进程实例，防止 PID 复用或已关闭代际继续发起业务操作。
     * @author kongweiguang
     */
    public boolean nativeClientAcceptsRequests(NativeClientOwner owner) {
        if (!(owner instanceof RegisteredNativeClient registered)) {
            return false;
        }
        return registered.state() == NativeClientState.OPEN && registered.processAlive();
    }

    /**
     * 关闭入口只切到 Closing，不释放 fence；持久清理成功后由调用方显式提交 Closed 回执。
     * @author kongweiguang
     */
    public void beginNativeClientClose(NativeClientOwner owner) {
        RegisteredNativeClient registered = requireNativeClient(owner);
        synchronized (registered) {
            if (registered.state == NativeClientState.OPEN) {
                registered.state = NativeClientState.CLOSING;
            }
        }
    }

    /**
     * 关闭回执成功后只保留当前存活进程所需的轻量 token fence；进程退出时回收整个 fence 组。
     * @author kongweiguang
     */
    public void completeNativeClientClose(NativeClientOwner owner) {
        RegisteredNativeClient registered = requireNativeClient(owner);
        synchronized (nativeClientLock) {
            synchronized (registered) {
                registered.state = NativeClientState.CLOSED;
            }
            if (registered.processExited) {
                nativeClients.remove(registered.lifetimeId(), registered);
                registered.group.owners().remove(registered);
            }
        }
    }

    /**
     * 失败关闭保持 Closing，调用相同 token 重试；不可根据 token 新建第二代 owner。
     * @author kongweiguang
     */
    public boolean nativeClientClosed(NativeClientOwner owner) {
        return owner instanceof RegisteredNativeClient registered
                && registered.state() == NativeClientState.CLOSED;
    }

    /**
     * 仅比较已注册对象的 token 以保护同连接 epoch 切换；调用方不能据此重新查找或构造 owner。
     * @author kongweiguang
     */
    public boolean nativeClientLifetimeMatches(NativeClientOwner owner, String lifetimeId) {
        return owner instanceof RegisteredNativeClient registered
                && registered.lifetimeId().equals(lifetimeId);
    }

    /**
     * 清理工作调度只在进程句柄及真实创建时刻仍匹配时认为 Native Host 存活。
     * @author kongweiguang
     */
    public boolean nativeClientProcessAlive(NativeClientOwner owner) {
        return requireNativeClient(owner).processAlive();
    }

    /**
     * 单一弱 listener 接收每个宿主进程一次的退出通知，不让进程 watcher 长期保留 Session。
     * @author kongweiguang
     */
    public void registerNativeClientProcessExitListener(NativeClientProcessExitListener listener) {
        synchronized (nativeClientLock) {
            processExitListener = new WeakReference<>(Objects.requireNonNull(listener, "listener"));
        }
    }

    /**
     * 仅撤销匹配的 listener，避免旧 runtime 关闭时清掉新 runtime 的进程退出处理器。
     * @author kongweiguang
     */
    public void releaseNativeClientProcessExitListener(NativeClientProcessExitListener listener) {
        synchronized (nativeClientLock) {
            if (processExitListener.get() == listener) {
                processExitListener = new WeakReference<>(null);
            }
        }
    }

    /**
     * 通过 API owner handle 暴露进程退出后的精确生命周期对象，不把进程或 Session 暴露给业务用例。
     * @author kongweiguang
     */
    private void nativeProcessExited(NativeProcessGroup group, Throwable failure) {
        if (failure != null && group.processAlive()) {
            return;
        }
        List<RegisteredNativeClient> owners;
        NativeClientProcessExitListener listener;
        synchronized (nativeClientLock) {
            if (!nativeProcesses.remove(group.identity(), group)) {
                return;
            }
            group.markExited();
            owners = List.copyOf(group.owners());
            for (RegisteredNativeClient owner : owners) {
                owner.markProcessExited();
            }
            listener = processExitListener.get();
            if (listener == null) {
                for (RegisteredNativeClient owner : owners) {
                    owner.markClosed();
                    nativeClients.remove(owner.lifetimeId(), owner);
                }
                group.owners().clear();
            }
        }
        try {
            group.close();
        } finally {
            if (listener != null && !owners.isEmpty()) {
                listener.processExited(List.copyOf(owners));
            }
        }
    }

    /**
     * 唯一允许读取 owner 实现私有状态的入口；context binding 保持其对象身份不变。
     * @author kongweiguang
     */
    private RegisteredNativeClient requireNativeClient(NativeClientOwner owner) {
        if (!(owner instanceof RegisteredNativeClient registered)
                || (nativeClients.get(registered.lifetimeId()) != registered
                        && registered.state() != NativeClientState.CLOSED)) {
            throw new IllegalArgumentException("native client owner is unavailable");
        }
        return registered;
    }

    /**
     * 将代理生成的唯一内部 rpcRequestId 绑定到当前连接的不可变快照；绑定后连接释放
     * 不影响已经入队或正在分派的请求。
     */
    public void bindRpcRequest(String rpcRequestId, String contextId) {
        String key = requireRpcRequestId(rpcRequestId);
        Binding binding = contexts.get(Objects.requireNonNull(contextId, "contextId"));
        if (binding == null) {
            throw new IllegalArgumentException("native execution context is unavailable");
        }
        if (requests.putIfAbsent(key, binding) != null) {
            throw new IllegalStateException("native execution request is already bound");
        }
    }

    /**
     * 请求完成后解除代理映射，防止长期连接累积完整环境副本的引用。
     */
    public void unbindRpcRequest(String rpcRequestId) {
        requests.remove(requireRpcRequestId(rpcRequestId));
    }

    /**
     * 内层 RPC 仅在同步 router.dispatch 范围内暴露上下文；关闭 Scope 恢复此前值，
     * 即使虚拟线程被复用或嵌套测试分派，也不会把某客户端环境交给下一请求。
     */
    public Scope enterRpcRequest(String rpcRequestId) {
        Binding previous = current.get();
        Binding binding = requests.get(requireRpcRequestId(rpcRequestId));
        if (binding == null) {
            current.remove();
        }
        else {
            current.set(binding);
        }
        return () -> {
            if (previous == null) {
                current.remove();
            }
            else {
                current.set(previous);
            }
        };
    }

    /**
     * TurnService 只在同步准入读取一次，后续异步流程必须显式携带返回的快照。
     */
    public Optional<NativeExecutionSnapshot> current() {
        return Optional.ofNullable(current.get()).map(Binding::snapshot);
    }

    /**
     * 任务入口只读取当前 RPC 已固定的 owner 对象，不通过生命周期字符串重新授权。
     */
    public Optional<NativeClientOwner> currentClientOwner() {
        return Optional.ofNullable(current.get()).map(Binding::clientOwner).filter(Objects::nonNull);
    }

    /**
     * 连接重注册只用于验证旧 owner 已完成关闭，不暴露或复制原生环境快照。
     */
    public Optional<NativeClientOwner> clientOwner(String contextId) {
        Binding binding = contexts.get(Objects.requireNonNull(contextId, "contextId"));
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.clientOwner());
    }

    /**
     * 共享后台在接受请求前设置一次；私有锁避免调用方同步持有公开 singleton 监视器。
     * @author kongweiguang
     */
    public void enableSharedMode() {
        synchronized (sharedModeLock) {
            sharedModeOwners.incrementAndGet();
        }
    }

    /**
     * 最后一个共享后台退出后在同一私有锁内清理快照，避免新 owner 与旧清理交错。
     * @author kongweiguang
     */
    public void disableSharedMode() {
        synchronized (sharedModeLock) {
            int remaining = sharedModeOwners.updateAndGet(value -> Math.max(0, value - 1));
            if (remaining == 0) {
                runs.clear();
                requests.clear();
                contexts.clear();
                current.remove();
                List<NativeProcessGroup> processGroups;
                synchronized (nativeClientLock) {
                    processGroups = List.copyOf(nativeProcesses.values());
                    nativeClients.clear();
                    nativeProcesses.clear();
                    processExitListener = new WeakReference<>(null);
                }
                RuntimeException closeFailure = null;
                for (NativeProcessGroup group : processGroups) {
                    try {
                        group.close();
                    } catch (RuntimeException failure) {
                        if (closeFailure == null) {
                            closeFailure = failure;
                        } else {
                            closeFailure.addSuppressed(failure);
                        }
                    }
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }
    }

    /**
     * stdio 合同仍继承启动环境，共享模式则要求新客户端显式绑定恢复操作。
     */
    public boolean sharedMode() {
        return sharedModeOwners.get() > 0;
    }

    /**
     * Plan run 跨越多个隐藏 Turn；按 Plan 与 run identity 保存客户端快照，避免不同执行串用环境。
     */
    public void bindPlanRun(String planId, String runId, NativeExecutionSnapshot snapshot) {
        runs.put(new RunOwner(planId), new RunBinding(runId,
                Objects.requireNonNull(snapshot, "snapshot")));
    }

    /**
     * 只有精确 Plan/run identity 可读回快照，迟到 Turn 不得继承另一次执行的环境。
     */
    public Optional<NativeExecutionSnapshot> findPlanRun(String planId, String runId) {
        RunBinding binding = runs.get(new RunOwner(planId));
        return binding != null && binding.runId().equals(runId)
                ? Optional.of(binding.snapshot()) : Optional.empty();
    }

    /**
     * 暂停或终态仅移除仍匹配原 Plan/run 的上下文，避免擦掉并发启动新 Run 的环境。
     */
    public void releasePlanRun(String planId, String runId) {
        RunOwner owner = new RunOwner(planId);
        runs.computeIfPresent(owner, (ignored, binding) -> binding.runId().equals(runId) ? null : binding);
    }

    /**
     * 关闭连接只移除未来请求的注册；已绑定请求与已准入 Turn 各自持有快照。
     */
    public void release(String contextId) {
        contexts.remove(Objects.requireNonNull(contextId, "contextId"));
    }

    /**
     * 同一 context/request mapping 携带不可变环境与精确生命周期对象，避免平行归属表漂移。
     * @param snapshot 本次请求冻结的宿主环境快照，不能依赖连接后续状态
     * @param clientOwner 对应的精确 Native 生命周期对象；stdio 请求没有该 owner
     * @author kongweiguang
     */
    private record Binding(NativeExecutionSnapshot snapshot,
                           NativeClientOwner clientOwner) {
        /** 快照不可空，stdio context 允许没有 Native owner。 */
        private Binding {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * 仅轻量保留 PID 与不可复用进程起始时间，禁止引用环境快照或 Session。
     * @param processId Native Host 的 PID，只用于定位进程实例而不承担对端认证
     * @param creationTime 进程真实创建时刻，用于区分系统复用后的同一 PID
     * @author kongweiguang
     */
    private record ProcessIdentity(long processId,
                                   Instant creationTime) {
        /**
         * PID 重用必须区分代际，身份键因此要求真实起始时刻。
         * @author kongweiguang
         */
        private ProcessIdentity {
            Objects.requireNonNull(creationTime, "creationTime");
        }
    }

    /** 一个 Native process 只注册一次退出 watcher，并统一释放其全部逻辑 Host token。 */
    private static final class NativeProcessGroup {
        private final ProcessIdentity identity;
        private final ProcessHandle process;
        private final WindowsNativeProcessObservation windowsProcess;
        private final Set<RegisteredNativeClient> owners = new LinkedHashSet<>();
        private volatile boolean exited;
        private volatile boolean stopped;
        private volatile Thread watcher;

        /**
         * 每个宿主进程只保留一个 watcher；独立逻辑 Host 作为各自 owner 加入此组。
         * @author kongweiguang
         */
        private NativeProcessGroup(ProcessIdentity identity, ProcessHandle process,
                                   WindowsNativeProcessObservation windowsProcess) {
            this.identity = identity;
            this.process = process;
            this.windowsProcess = windowsProcess;
        }

        /**
         * 返回完整 PID/start key，退出事件只移除对应代际。
         * @author kongweiguang
         */
        private ProcessIdentity identity() { return identity; }

        /**
         * Windows 句柄只由所属进程组持有，非 Windows 继续依赖 JDK 生命周期事件。
         * @author kongweiguang
         */
        private WindowsNativeProcessObservation windowsProcess() { return windowsProcess; }

        /**
         * 组内 token 由外层生命周期锁统一读写，不复制为第二份授权索引。
         * @author kongweiguang
         */
        private Set<RegisteredNativeClient> owners() { return owners; }

        /**
         * watcher 先登记再启动，避免共享模式关闭看不到刚创建的线程。
         * @author kongweiguang
         */
        private void startWatcher(Thread watcher) { this.watcher = watcher; }

        /**
         * 等待只对 Windows 观察器有效；JDK ProcessHandle 使用系统提供的 onExit 事件。
         * @author kongweiguang
         */
        private boolean awaitExit(int timeoutMillis) {
            return !stopped && windowsProcess != null && windowsProcess.awaitExit(timeoutMillis);
        }

        /**
         * 在锁外询问 watcher 是否已被 runtime 关闭或真实退出事件终止。
         * @author kongweiguang
         */
        private boolean stopped() { return stopped; }

        /**
         * 身份不可观测时保守视为存活；仅确知退出或代际变化才释放临时资源。
         * @author kongweiguang
         */
        private boolean processAlive() {
            if (exited || stopped) {
                return false;
            }
            if (windowsProcess != null) {
                return windowsProcess.isAlive();
            }
            if (process == null || !process.isAlive()) {
                return false;
            }
            return process.info().startInstant().map(identity.creationTime()::equals).orElse(true);
        }

        /**
         * 退出回调在组锁下移除后标记，阻断迟到请求再次把旧组当成存活。
         * @author kongweiguang
         */
        private void markExited() { exited = true; }

        /**
         * 停止 watcher 并释放精确 Windows 进程句柄；有限等待保证关闭不与未完成 downcall 竞态。
         * @author kongweiguang
         */
        private void close() {
            stopped = true;
            Thread activeWatcher = watcher;
            watcher = null;
            if (activeWatcher != null && activeWatcher != Thread.currentThread()) {
                activeWatcher.interrupt();
            }
            if (windowsProcess != null) {
                windowsProcess.close();
            }
        }
    }

    /** Native Host 的稳定 token 对象不持有环境、连接或数据库句柄。 */
    private static final class RegisteredNativeClient implements NativeClientOwner {
        private final ProcessIdentity identity;
        private final String lifetimeId;
        private final NativeProcessGroup group;
        private volatile NativeClientState state = NativeClientState.OPEN;
        private volatile boolean processExited;

        /**
         * owner 本身不持有会话快照或服务引用，生命周期 fence 与请求上下文分离。
         * @author kongweiguang
         */
        private RegisteredNativeClient(ProcessIdentity identity, String lifetimeId, NativeProcessGroup group) {
            this.identity = identity;
            this.lifetimeId = lifetimeId;
            this.group = group;
        }

        /**
         * 同 token 重连必须仍匹配原进程代际，不能只匹配 PID。
         * @author kongweiguang
         */
        private ProcessIdentity identity() { return identity; }

        /**
         * 私有生命周期值只用于精确 fence，不会沿 RPC 响应暴露。
         * @author kongweiguang
         */
        private String lifetimeId() { return lifetimeId; }

        /**
         * 快速拒绝读取使用 volatile；生命周期转移仍由 owner monitor 串行化。
         * @author kongweiguang
         */
        private NativeClientState state() { return state; }

        /**
         * 进程组的代际核验是唯一 alive 依据，避免 owner 自己复制 watcher 状态。
         * @author kongweiguang
         */
        private boolean processAlive() { return !processExited && group.processAlive(); }

        /**
         * 进程退出立即封住准入；业务清理仍须取得持久关闭回执。
         * @author kongweiguang
         */
        private void markProcessExited() {
            processExited = true;
            synchronized (this) {
                if (state == NativeClientState.OPEN) {
                    state = NativeClientState.CLOSING;
                }
            }
        }

        /**
         * 无清理 listener 或关闭链完成后永久关闭这个 token epoch。
         * @author kongweiguang
         */
        private void markClosed() {
            synchronized (this) {
                state = NativeClientState.CLOSED;
            }
        }
    }

    /** 限定单个 Native 生命周期只能前向关闭，失败清理仍保留在 CLOSING 供重试。 */
    private enum NativeClientState {
        /** 允许该精确进程代际创建临时根。 */
        OPEN,
        /** 已拒绝新请求，等待已准入操作及持久清理完成。 */
        CLOSING,
        /** 关闭已得到回执，旧 token 不得重新打开此代际。 */
        CLOSED
    }

    /** 进程退出通知只携带 owner handle，Task 应用负责现有关闭链与失败重试。 */
    @FunctionalInterface
    public interface NativeClientProcessExitListener {
        /**
         * 一个宿主进程退出时对其所有逻辑 Host 生命周期执行清理。
         * @author kongweiguang
         */
        void processExited(List<NativeClientOwner> owners);
    }

    /**
     * 释放 ThreadLocal 的幂等范围；调用方在 finally 或 try-with-resources 中关闭。
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /**
         * 不声明 checked 异常，使 RPC 分派原有失败类型保持不变。
         */
        @Override
        void close();
    }

    /**
     * 以 Plan ID 隔离运行环境，目标 Thread 相同也不能复用其它 Plan 的执行快照。
     *
     * @param planId Plan 标识
     */
    private record RunOwner(String planId) {
        /** Plan ID 必须有界且非空，避免扩大快照索引。 */
        private RunOwner {
            if (planId == null || planId.isBlank() || planId.length() > 128) {
                throw new IllegalArgumentException("invalid execution run owner");
            }
        }
    }

    /**
     * 值始终与唯一聚合当前 runId 成对，不能因重用目标 Thread 取得旧 Run 环境。
     *
     * @param runId 运行标识
     * @param snapshot 快照
     */
    private record RunBinding(String runId,
                              NativeExecutionSnapshot snapshot) {
        /**
         * 运行身份只允许非空短字符串，不承载用户输入或环境数据。
         */
        private RunBinding {
            if (runId == null || runId.isBlank() || runId.length() > 128) {
                throw new IllegalArgumentException("invalid execution run identity");
            }
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

}
