// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持有单代 Runtime 唯一的 OkHttp Dispatcher、连接池、请求 Executor 和 Deadline Scheduler。
 * Adapter 只借用带冻结超时的 Client 视图，本对象始终是共享网络资源唯一生命周期所有者。
 */
public final class ModelTransport implements AutoCloseable {
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    private final Dispatcher dispatcher;
    private final ConnectionPool connectionPool;
    private final OkHttpClient client;
    private final ExecutorService requests;
    private final ScheduledExecutorService deadlines;
    private final ProviderCircuitBreaker circuits;
    private final Set<RequestController> activeControllers =
            ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private final Map<ModelPort.ModelRequest, ProviderRequestEnvelope> envelopes = new IdentityHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> closeCompletion =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 创建唯一 Dispatcher/Pool，并禁用 OkHttp 隐式重试，使 Adapter 的提交前策略成为
     * 唯一重试决策点。
     */
    public ModelTransport() {
        this(Clock.systemUTC());
    }

    /** 使用组合根时钟创建共享熔断器，Executor 与连接池仍保持唯一所有权。 */
    public ModelTransport(Clock clock) {
        requests = Executors.newVirtualThreadPerTaskExecutor();
        deadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ja-model-deadline");
            thread.setDaemon(true);
            return thread;
        });
        dispatcher = new Dispatcher(Executors.newVirtualThreadPerTaskExecutor());
        dispatcher.setMaxRequests(64);
        dispatcher.setMaxRequestsPerHost(16);
        connectionPool = new ConnectionPool(16, 5, TimeUnit.MINUTES);
        client = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .addNetworkInterceptor(ModelTransport::preserveAdapterRetryOwnership)
                .build();
        circuits = new ProviderCircuitBreaker(Objects.requireNonNull(clock, "clock"));
    }

    /**
     * OkHttp 会把带 {@code Retry-After: 0} 的 503 在应用重试循环之外自动重放；把该提示改写为
     * 等价的已过期 HTTP-date，既让 Provider 映射仍得到零等待，也保证每个 Adapter attempt 只产生
     * 一次真实 HTTP exchange。否则自动标题的 {@code SINGLE_ATTEMPT} 仍会暗中发出第二个请求。
     */
    private static Response preserveAdapterRetryOwnership(Interceptor.Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        String retryAfter = response.header("Retry-After");
        if (response.code() == 503 && retryAfter != null && "0".equals(retryAfter.trim())) {
            return response.newBuilder()
                    .header("Retry-After", "Thu, 01 Jan 1970 00:00:00 GMT")
                    .build();
        }
        return response;
    }

    /**
     * 在保留共享 Dispatcher 和 Pool 的同时派生短生命周期请求视图。连接建立受 connectTimeout
     * 约束，而读写和完整交换受 requestTimeout 约束，允许首个 SSE token 晚于连接预算到达；
     * Adapter 的单调 Deadline 仍提供同一 Turn 的最终边界。
     */
    OkHttpClient clientFor(ModelPort.ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        ensureOpen();
        return client.newBuilder()
                .connectTimeout(configuration.connectTimeout())
                .readTimeout(configuration.requestTimeout())
                .writeTimeout(configuration.requestTimeout())
                .callTimeout(configuration.requestTimeout())
                .build();
    }

    /**
     * 返回用于阻塞式流协调器的共享虚拟线程 Executor。
     */
    public ExecutorService requestExecutor() {
        ensureOpen();
        return requests;
    }

    /**
     * 返回所有 Turn Deadline 共用的 Scheduler。
     */
    public ScheduledExecutorService deadlineExecutor() {
        ensureOpen();
        return deadlines;
    }

    /** 在共享传输上取得 Provider 操作许可，使 count、send 与 summary 使用同一治理状态。 */
    public ProviderCircuitBreaker.Permit acquireCircuit(
            ModelPort.ModelConfiguration configuration, ProviderCircuitBreaker.Operation operation) {
        ensureOpen();
        return circuits.acquire(configuration, operation);
    }

    /**
     * 与传输关闭原子竞争地接纳 Controller；竞争失败者在打开 Provider 请求前停止，
     * 防止 close 后出现迟到请求。
     */
    public void register(RequestController controller) {
        Objects.requireNonNull(controller, "controller");
        synchronized (lifecycle) {
            if (closed.get()) {
                controller.shutdown();
                throw new IllegalStateException("model transport is closed");
            }
            activeControllers.add(controller);
        }
    }

    /**
     * 从共享关闭屏障移除已完成 Turn，不保留请求状态。
     */
    public void unregister(RequestController controller) {
        activeControllers.remove(controller);
    }

    /**
     * 按 ModelRequest 对象身份只构造一次 Provider envelope；值相等但来源不同的请求不得共享
     * 计量结果，避免跨 Turn 或跨凭据代际复用。
     */
    public ProviderRequestEnvelope envelope(
            ModelPort.ModelRequest request, Supplier<ProviderRequestEnvelope> factory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(factory, "factory");
        synchronized (lifecycle) {
            ensureOpen();
            return envelopes.computeIfAbsent(request, ignored -> Objects.requireNonNull(factory.get(), "envelope"));
        }
    }

    /**
     * 最终发送完成或计量失败后移除请求身份，防止完整 Prompt 在共享传输生命周期内滞留。
     */
    public void releaseEnvelope(ModelPort.ModelRequest request) {
        synchronized (lifecycle) {
            envelopes.remove(Objects.requireNonNull(request, "request"));
        }
    }

    /**
     * 停止 Executor 前关闭全部活动流，随后驱逐空闲连接并汇合全部自有 Worker。
     * 一旦跨过关闭门禁，任何 Adapter 都不能重新打开 Call。
     */
    @Override
    public void close() {
        closeAt(deadlineAfter(SHUTDOWN_WAIT.toNanos()));
    }

    /**
     * 在调用方绝对 Deadline 内停止全部自有 Provider 资源，并共享关闭结果，避免每个 Executor
     * 或重复 close 调用方各自启动独立等待。
     */
    public void closeAt(long shutdownDeadlineNanos) {
        CompletableFuture<Void> completion = closeCompletion.get();
        boolean owner = false;
        if (completion == null) {
            CompletableFuture<Void> candidate = new CompletableFuture<>();
            if (closeCompletion.compareAndSet(null, candidate)) {
                completion = candidate;
                owner = true;
            } else {
                completion = closeCompletion.get();
            }
        }
        if (owner) {
            try {
                closeOwned(shutdownDeadlineNanos);
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }
        awaitClose(completion, shutdownDeadlineNanos);
    }

    /**
     * 先取消活动 Call，再按同一剩余预算汇合各 Executor。
     */
    private void closeOwned(long shutdownDeadlineNanos) {
        Set<RequestController> snapshot;
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
            snapshot = Set.copyOf(activeControllers);
            envelopes.clear();
        }
        snapshot.forEach(RequestController::shutdown);
        dispatcher.cancelAll();
        deadlines.shutdownNow();
        requests.shutdownNow();
        dispatcher.executorService().shutdownNow();
        connectionPool.evictAll();
        awaitTermination(deadlines, shutdownDeadlineNanos);
        awaitTermination(requests, shutdownDeadlineNanos);
        awaitTermination(dispatcher.executorService(), shutdownDeadlineNanos);
        connectionPool.evictAll();
    }

    /**
     * 共享生命周期进入终态后拒绝新的 Adapter 工作。
     */
    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("model transport is closed");
    }

    /**
     * 有界等待并汇合自有 Executor，避免 Runtime shutdown 无限挂起。
     */
    private static void awaitTermination(ExecutorService executor, long shutdownDeadlineNanos) {
        try {
            long remaining = shutdownDeadlineNanos - System.nanoTime();
            if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("model transport executor did not stop");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing model transport", exception);
        }
    }

    /**
     * 等待唯一传输关闭所有者，且不刷新 shutdown 预算。
     */
    private static void awaitClose(CompletableFuture<Void> completion, long shutdownDeadlineNanos) {
        long remaining = shutdownDeadlineNanos - System.nanoTime();
        if (remaining <= 0 && !completion.isDone()) {
            throw new IllegalStateException("model transport close deadline expired");
        }
        try {
            completion.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing model transport", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("model transport close deadline expired", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("model transport close failed", cause);
        }
    }

    /**
     * 创建饱和计算的独立关闭 Deadline，不影响嵌套进程 shutdown。
     */
    private static long deadlineAfter(long budgetNanos) {
        long now = System.nanoTime();
        return now >= Long.MAX_VALUE - budgetNanos ? Long.MAX_VALUE : now + budgetNanos;
    }
}
