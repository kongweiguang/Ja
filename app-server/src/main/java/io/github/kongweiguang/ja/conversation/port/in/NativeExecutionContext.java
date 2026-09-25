// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.NativeExecutionSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把已认证原生连接的私有环境限制在进程内；RPC 请求只在同步准入期间借用它，
 * 异步 Turn 必须复制 Snapshot，不能依赖请求线程或连接继续存活。
 */
public final class NativeExecutionContext {
    private static final NativeExecutionContext SHARED = new NativeExecutionContext();
    private final ConcurrentMap<String, NativeExecutionSnapshot> contexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, NativeExecutionSnapshot> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<RunOwner, RunBinding> runs = new ConcurrentHashMap<>();
    private final ThreadLocal<NativeExecutionSnapshot> current = new ThreadLocal<>();
    private final AtomicInteger sharedModeOwners = new AtomicInteger();
    private final Object sharedModeLock = new Object();

    /** 共享后台只有一个认证代理和一个内层 RPC Server，二者必须使用同一桥接实例。 */
    public static NativeExecutionContext shared() {
        return SHARED;
    }

    /** 限定实例由 shared() 发布，避免不同传输各持一份请求映射。 */
    private NativeExecutionContext() {
    }

    /**
     * 复制完整客户端环境并分配不可猜的连接内存身份；拒绝无法传给原生进程的 NUL，
     * 容量上限只防止本地错误客户端耗尽后台内存，不筛选 PATH、代理或登录变量。
     */
    public String register(Map<String, String> environment, String shell) {
        NativeExecutionSnapshot snapshot = new NativeExecutionSnapshot(environment, shell);
        String contextId = "ctx_" + UUID.randomUUID().toString().replace("-", "");
        contexts.put(contextId, snapshot);
        return contextId;
    }

    /**
     * 将代理生成的唯一内部 requestId 绑定到当前连接的不可变快照；绑定后连接释放
     * 不影响已经入队或正在分派的请求。
     */
    public void bindRequest(String requestId, String contextId) {
        String key = requireRequestId(requestId);
        NativeExecutionSnapshot snapshot = contexts.get(Objects.requireNonNull(contextId, "contextId"));
        if (snapshot == null) throw new IllegalArgumentException("native execution context is unavailable");
        if (requests.putIfAbsent(key, snapshot) != null) {
            throw new IllegalStateException("native execution request is already bound");
        }
    }

    /** 请求完成后解除代理映射，防止长期连接累积完整环境副本的引用。 */
    public void unbindRequest(String requestId) {
        requests.remove(requireRequestId(requestId));
    }

    /**
     * 内层 RPC 仅在同步 router.dispatch 范围内暴露上下文；关闭 Scope 恢复此前值，
     * 即使虚拟线程被复用或嵌套测试分派，也不会把某客户端环境交给下一请求。
     */
    public Scope enterRequest(String requestId) {
        NativeExecutionSnapshot previous = current.get();
        NativeExecutionSnapshot snapshot = requests.get(requireRequestId(requestId));
        if (snapshot == null) current.remove();
        else current.set(snapshot);
        return () -> {
            if (previous == null) current.remove();
            else current.set(previous);
        };
    }

    /** TurnService 只在同步准入读取一次，后续异步流程必须显式携带返回的快照。 */
    public Optional<NativeExecutionSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    /** 共享后台在接受请求前设置一次；私有锁避免调用方同步持有公开 singleton 监视器。 */
    public void enableSharedMode() {
        synchronized (sharedModeLock) {
            sharedModeOwners.incrementAndGet();
        }
    }

    /** 最后一个共享后台退出后在同一私有锁内清理快照，避免新 owner 与旧清理交错。 */
    public void disableSharedMode() {
        synchronized (sharedModeLock) {
            int remaining = sharedModeOwners.updateAndGet(value -> Math.max(0, value - 1));
            if (remaining == 0) {
                runs.clear();
                requests.clear();
                contexts.clear();
                current.remove();
            }
        }
    }

    /** stdio 合同仍继承启动环境，共享模式则要求新客户端显式绑定恢复操作。 */
    public boolean sharedMode() {
        return sharedModeOwners.get() > 0;
    }

    /**
     * Goal/Plan 的运行跨越多个隐藏 Turn；以聚合与 run identity 保存当前客户端快照，
     * 同一聚合换 Run 时原子替换旧值，不把环境写入 SQLite 或事件。
     */
    public void bindRun(String kind, String ownerId, String runId, NativeExecutionSnapshot snapshot) {
        runs.put(new RunOwner(kind, ownerId), new RunBinding(runId,
                Objects.requireNonNull(snapshot, "snapshot")));
    }

    /** 只有精确 runId 可读回快照，迟到 continuation 不得继承另一次执行的环境。 */
    public Optional<NativeExecutionSnapshot> findRun(String kind, String ownerId, String runId) {
        RunBinding binding = runs.get(new RunOwner(kind, ownerId));
        return binding != null && binding.runId().equals(runId)
                ? Optional.of(binding.snapshot()) : Optional.empty();
    }

    /** 暂停或终态仅移除仍匹配原 run 的上下文，避免擦掉并发重启新 Run 的环境。 */
    public void releaseRun(String kind, String ownerId, String runId) {
        RunOwner owner = new RunOwner(kind, ownerId);
        runs.computeIfPresent(owner, (ignored, binding) -> binding.runId().equals(runId) ? null : binding);
    }

    /** 关闭连接只移除未来请求的注册；已绑定请求与已准入 Turn 各自持有快照。 */
    public void release(String contextId) {
        contexts.remove(Objects.requireNonNull(contextId, "contextId"));
    }

    /** 使用短 ID 防止无界请求键被本地代理错误输入放大。 */
    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 256) {
            throw new IllegalArgumentException("invalid native execution request identity");
        }
        return requestId;
    }

    /** 聚合类型和值都只是服务端标识，不接受任意键膨胀或空身份。 */
    private record RunOwner(String kind, String ownerId) {
        /** Goal 与 Plan 独立命名空间，防止相同标识误取对方上下文。 */
        private RunOwner {
            if (!"goal".equals(kind) && !"plan".equals(kind)) {
                throw new IllegalArgumentException("invalid execution run kind");
            }
            if (ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
                throw new IllegalArgumentException("invalid execution run owner");
            }
        }
    }

    /** 值始终与唯一聚合当前 runId 成对，不能因重用目标 Thread 取得旧 Run 环境。 */
    private record RunBinding(String runId, NativeExecutionSnapshot snapshot) {
        /** 运行身份只允许非空短字符串，不承载用户输入或环境数据。 */
        private RunBinding {
            if (runId == null || runId.isBlank() || runId.length() > 128) {
                throw new IllegalArgumentException("invalid execution run identity");
            }
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** 释放 ThreadLocal 的幂等范围；调用方在 finally 或 try-with-resources 中关闭。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /** 不声明 checked 异常，使 RPC 分派原有失败类型保持不变。 */
        @Override
        void close();
    }

}
