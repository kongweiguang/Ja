// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * 统一持有一次 Kernel32 FFM 查找会话，确保符号句柄和调用状态共享同一生命周期。
 *
 * <p>该类型不在静态初始化阶段加载本地库，避免 GraalVM Native Image 分析期产生平台副作用。
 * 每次本地调用都在独立受限 Arena 中同步捕获 {@code GetLastError}，调用结果离开方法前已转换为
 * 纯 Java 值，不会泄漏临时内存段。</p>
 */
final class WindowsKernel32Library implements AutoCloseable {
    /**
     * captureCallState 分配所需的固定调用状态布局。
     */
    private static final MemoryLayout CAPTURE_STATE_LAYOUT = Linker.Option.captureStateLayout();
    /**
     * GetLastError 在调用状态布局中的固定偏移。
     */
    private static final long LAST_ERROR_OFFSET = CAPTURE_STATE_LAYOUT.byteOffset(
            MemoryLayout.PathElement.groupElement("GetLastError"));
    /**
     * 无本地参数且返回句柄的调用形状，额外首参数用于承载 GetLastError 状态。
     */
    private static final MethodType ADDRESS_NO_ARGUMENTS = MethodType.methodType(
            MemorySegment.class, MemorySegment.class);
    /**
     * 接收两个句柄并返回句柄的调用形状。
     */
    private static final MethodType ADDRESS_TWO_ADDRESSES = MethodType.methodType(
            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
    /**
     * 接收三个整数并返回句柄的调用形状。
     */
    private static final MethodType ADDRESS_THREE_INTS = MethodType.methodType(
            MemorySegment.class, MemorySegment.class, int.class, int.class, int.class);
    /**
     * 无本地参数且返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_NO_ARGUMENTS = MethodType.methodType(
            int.class, MemorySegment.class);
    /**
     * 接收一个句柄并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_ONE_ADDRESS = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class);
    /**
     * 接收两个句柄并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_TWO_ADDRESSES = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
    /**
     * GetProcessTimes 接收进程句柄和四个 FILETIME 输出地址，必须保留其固定 ABI 形状。
     */
    private static final MethodType INT_FIVE_ADDRESSES = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class);
    /**
     * 接收句柄和整数并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_ADDRESS_INT = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, int.class);
    /**
     * 接收句柄和两个整数并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_ADDRESS_TWO_INTS = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, int.class, int.class);
    /**
     * 接收三个句柄和整数并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_THREE_ADDRESSES_INT = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            MemorySegment.class, int.class);
    /**
     * 接收句柄、整数、句柄和整数并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_ADDRESS_INT_ADDRESS_INT = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, int.class,
            MemorySegment.class, int.class);
    /**
     * 接收两个句柄、整数和两个句柄并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_TWO_ADDRESSES_INT_TWO_ADDRESSES = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            int.class, MemorySegment.class, MemorySegment.class);
    /**
     * 接收两个句柄、整数和三个句柄并返回 Win32 整数结果的调用形状。
     */
    private static final MethodType INT_TWO_ADDRESSES_INT_THREE_ADDRESSES = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
    /**
     * CreateProcessW 的固定参数形状，禁止调用方在运行期扩展本地调用面。
     */
    private static final MethodType INT_CREATE_PROCESS = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            MemorySegment.class, MemorySegment.class, int.class, int.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
    /**
     * CreatePseudoConsole 的 COORD 按 Win64 的四字节值传递，保持显式 ABI 而非动态参数适配。
     */
    private static final MethodType INT_CREATE_PSEUDO_CONSOLE = MethodType.methodType(
            int.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class,
            int.class, MemorySegment.class);
    /**
     * 伪控制台与线程属性表的关闭函数均返回 void，不能按 Win32 BOOL 读取不存在的结果。
     */
    private static final MethodType VOID_ONE_ADDRESS = MethodType.methodType(
            void.class, MemorySegment.class, MemorySegment.class);
    /**
     * 属性表初始化固定接受表地址、数量、标志和字节数输出地址。
     */
    private static final MethodType INT_INITIALIZE_ATTRIBUTE_LIST = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, int.class, int.class, MemorySegment.class);
    /**
     * 属性更新中的 SIZE_T 使用 Win64 的 long，避免截断 ConPTY 属性值的长度。
     */
    private static final MethodType INT_UPDATE_ATTRIBUTE = MethodType.methodType(
            int.class, MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class,
            MemorySegment.class, long.class, MemorySegment.class, MemorySegment.class);

    private final Arena libraryArena = Arena.ofShared();
    private final Linker linker;
    private final SymbolLookup kernel32;
    private final Linker.Option captureLastError;
    private final Object lifecycleMonitor = new Object();
    private volatile boolean closed;
    private int activeInvocations;
    private boolean closeComplete;
    private Throwable closeFailure;

    /**
     * 仅在运行期解析固定 Kernel32 库；构造失败时先关闭部分 Arena，再抛出调用方给定的稳定错误码。
     * 受限 API 告警抑制只覆盖这里不可替代的系统库查找调用，库名不是外部输入。
     */
    @SuppressWarnings("restricted")
    WindowsKernel32Library(String unavailableCode) {
        Objects.requireNonNull(unavailableCode, "unavailableCode");
        try {
            linker = Linker.nativeLinker();
            kernel32 = SymbolLookup.libraryLookup("kernel32", libraryArena);
            captureLastError = Linker.Option.captureCallState("GetLastError");
        } catch (Throwable failure) {
            libraryArena.close();
            throw new IllegalStateException(unavailableCode, failure);
        }
    }

    /**
     * 将允许的 Kernel32 carrier 组合展开为静态 {@code invokeExact} 调用点。每个调用点都必须以
     * 原生签名声明返回 carrier，因此异构结果只能在这个私有边界汇总为 {@code Object}；分支内部仍保留
     * 精确的原生类型。Native Image 无法可靠分析由 {@code Object[]} 驱动的
     * {@code invokeWithArguments}，显式白名单同时避免任意签名进入平台适配器；新增 Win32 函数时必须在
     * 这里声明准确 ABI 形状并补充本地测试；ConPTY 的 void 关闭与 SIZE_T 参数不能沿用 BOOL 形状。
     * @author kongweiguang
     */
    private static Object invokeExact(
            MethodHandle function,
            MemorySegment state,
            Object[] arguments) throws Throwable {
        MethodType type = function.type();
        if (type.equals(ADDRESS_NO_ARGUMENTS)) {
            return (MemorySegment) function.invokeExact(state);
        }
        if (type.equals(ADDRESS_TWO_ADDRESSES)) {
            return (MemorySegment) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1]);
        }
        if (type.equals(ADDRESS_THREE_INTS)) {
            return (MemorySegment) function.invokeExact(
                    state,
                    (int) arguments[0],
                    (int) arguments[1],
                    (int) arguments[2]);
        }
        if (type.equals(INT_NO_ARGUMENTS)) {
            return (int) function.invokeExact(state);
        }
        if (type.equals(INT_ONE_ADDRESS)) {
            return (int) function.invokeExact(state, (MemorySegment) arguments[0]);
        }
        if (type.equals(INT_TWO_ADDRESSES)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1]);
        }
        if (type.equals(INT_FIVE_ADDRESSES)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1],
                    (MemorySegment) arguments[2],
                    (MemorySegment) arguments[3],
                    (MemorySegment) arguments[4]);
        }
        if (type.equals(INT_ADDRESS_INT)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (int) arguments[1]);
        }
        if (type.equals(INT_ADDRESS_TWO_INTS)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (int) arguments[1],
                    (int) arguments[2]);
        }
        if (type.equals(INT_THREE_ADDRESSES_INT)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1],
                    (MemorySegment) arguments[2],
                    (int) arguments[3]);
        }
        if (type.equals(INT_ADDRESS_INT_ADDRESS_INT)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (int) arguments[1],
                    (MemorySegment) arguments[2],
                    (int) arguments[3]);
        }
        if (type.equals(INT_TWO_ADDRESSES_INT_TWO_ADDRESSES)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1],
                    (int) arguments[2],
                    (MemorySegment) arguments[3],
                    (MemorySegment) arguments[4]);
        }
        if (type.equals(INT_TWO_ADDRESSES_INT_THREE_ADDRESSES)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1],
                    (int) arguments[2],
                    (MemorySegment) arguments[3],
                    (MemorySegment) arguments[4],
                    (MemorySegment) arguments[5]);
        }
        if (type.equals(INT_CREATE_PROCESS)) {
            return (int) function.invokeExact(
                    state,
                    (MemorySegment) arguments[0],
                    (MemorySegment) arguments[1],
                    (MemorySegment) arguments[2],
                    (MemorySegment) arguments[3],
                    (int) arguments[4],
                    (int) arguments[5],
                    (MemorySegment) arguments[6],
                    (MemorySegment) arguments[7],
                    (MemorySegment) arguments[8],
                    (MemorySegment) arguments[9]);
        }
        if (type.equals(INT_CREATE_PSEUDO_CONSOLE)) {
            return (int) function.invokeExact(state, (int) arguments[0],
                    (MemorySegment) arguments[1], (MemorySegment) arguments[2], (int) arguments[3],
                    (MemorySegment) arguments[4]);
        }
        if (type.equals(VOID_ONE_ADDRESS)) {
            function.invokeExact(state, (MemorySegment) arguments[0]);
            return null;
        }
        if (type.equals(INT_INITIALIZE_ATTRIBUTE_LIST)) {
            return (int) function.invokeExact(state, (MemorySegment) arguments[0], (int) arguments[1],
                    (int) arguments[2], (MemorySegment) arguments[3]);
        }
        if (type.equals(INT_UPDATE_ATTRIBUTE)) {
            return (int) function.invokeExact(state, (MemorySegment) arguments[0], (int) arguments[1],
                    (MemorySegment) arguments[2], (MemorySegment) arguments[3], (long) arguments[4],
                    (MemorySegment) arguments[5], (MemorySegment) arguments[6]);
        }
        throw new IllegalArgumentException("windows_native_signature_unsupported");
    }

    /**
     * 将 Win32 的空句柄表示集中到一个边界，调用方不再重复直接读取本地地址。
     */
    static boolean isNullHandle(MemorySegment handle) {
        return handle == null || handle.address() == 0;
    }

    /**
     * 只解析代码中声明的固定符号；符号名称不来自外部输入，因此不会扩大本地调用面。
     * 受限 API 告警抑制只覆盖生成固定 downcall MethodHandle 的最小方法。
     */
    @SuppressWarnings("restricted")
    MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IllegalStateException("windows_native_library_closed");
            }
            return linker.downcallHandle(kernel32.findOrThrow(symbol), descriptor, captureLastError);
        }
    }

    /**
     * 在同一个受限 Arena 中完成调用和错误码读取，避免释放调用状态后再访问本地内存。
     */
    NativeCall invoke(MethodHandle function, String operation, Object... arguments)
            throws InvocationFailure {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(operation, "operation");
        beginInvocation(operation);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CAPTURE_STATE_LAYOUT);
            Object value = invokeExact(function, state, arguments);
            return new NativeCall(value, state.get(ValueLayout.JAVA_INT, LAST_ERROR_OFFSET));
        } catch (Throwable failure) {
            throw new InvocationFailure(operation, failure);
        } finally {
            endInvocation();
        }
    }

    /**
     * 在共享 Arena 仍可用时登记一次本地调用；检查与计数必须位于同一监视器内，避免 close
     * 在检查后、downcall 取得 session 前释放底层符号。
     */
    private void beginInvocation(String operation) throws InvocationFailure {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new InvocationFailure(
                        operation, new IllegalStateException("windows_native_library_closed"));
            }
            activeInvocations++;
        }
    }

    /**
     * 归还本地调用租约并唤醒关闭方；通知只在计数归零时发出，减少并发 IO 完成时的无效竞争。
     */
    private void endInvocation() {
        synchronized (lifecycleMonitor) {
            activeInvocations--;
            if (activeInvocations == 0) {
                lifecycleMonitor.notifyAll();
            }
        }
    }

    /**
     * 阻止新查找和调用并等待现有租约结束；并发 close 也等到 Arena 实际关闭后才返回。
     * 等待不可中断但会恢复中断位，提前返回会把资源释放变成 FFM 竞态。
     */
    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (lifecycleMonitor) {
            if (!closed) {
                closed = true;
                while (activeInvocations > 0) {
                    try {
                        lifecycleMonitor.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                try {
                    libraryArena.close();
                } catch (RuntimeException | Error failure) {
                    closeFailure = failure;
                } finally {
                    closeComplete = true;
                    lifecycleMonitor.notifyAll();
                }
            } else {
                while (!closeComplete) {
                    try {
                        lifecycleMonitor.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (closeFailure instanceof RuntimeException failure) {
            throw failure;
        }
        if (closeFailure instanceof Error failure) {
            throw failure;
        }
    }

    /**
     * 在已有主失败的清理路径中吞掉次生 Arena 异常，保证调用方保留原始故障证据。
     */
    void closeQuietly() {
        try {
            close();
        } catch (RuntimeException ignored) {
            // 主失败比次生 Arena 关闭异常更能说明根因，因此这里只执行尽力释放。
        }
    }

    /**
     * 调用返回值与紧邻调用捕获的 Win32 错误码。
     *
     * @param value 当前值
     * @param error 错误信息
     */
    record NativeCall(Object value,
                      int error) {
    }

    /**
     * 仅携带稳定操作名和本地调用原因，不复制命令、路径或敏感参数。
     */
    static final class InvocationFailure extends Exception {
        /**
         * 保持跨 JVM 版本稳定的异常序列标识；异常不携带本地句柄。
         */
        private static final long serialVersionUID = 1L;
        private final String operation;

        /**
         * 保存调用点的固定操作名，使上层可以映射自己的错误契约而不解析异常文本。
         */
        InvocationFailure(String operation, Throwable cause) {
            super(operation, cause);
            this.operation = operation;
        }

        /**
         * 返回代码定义的固定操作名，不暴露本地参数。
         */
        String operation() {
            return operation;
        }
    }
}
