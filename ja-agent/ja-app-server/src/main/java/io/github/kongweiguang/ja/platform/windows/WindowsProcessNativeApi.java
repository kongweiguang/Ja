// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * 封装挂起进程创建、管道 IO 与进程句柄操作，不承载接纳或进程生命周期决策。
 */
final class WindowsProcessNativeApi implements AutoCloseable {
    /**
     * WaitForSingleObject 表示对象已触发。
     */
    static final int WAIT_OBJECT_0 = 0;
    /**
     * WaitForSingleObject 表示等待超时。
     */
    static final int WAIT_TIMEOUT = 258;
    /**
     * WaitForSingleObject 的无符号失败哨兵按 Java int 保存后的值。
     */
    static final int WAIT_FAILED = -1;
    /**
     * 管道写端关闭后的 Win32 错误码。
     */
    static final int ERROR_BROKEN_PIPE = 109;
    /**
     * 管道当前无数据或已断开的 Win32 错误码。
     */
    static final int ERROR_NO_DATA = 232;
    /**
     * 幂等关闭时允许忽略的无效句柄错误码。
     */
    static final int ERROR_INVALID_HANDLE = 6;
    /**
     * CancelIoEx 使同步 ReadFile/WriteFile 结束时返回的取消码。
     */
    static final int ERROR_OPERATION_ABORTED = 995;
    /**
     * CancelIoEx 未发现 pending I/O 时的幂等成功等价值。
     */
    static final int ERROR_NOT_FOUND = 1168;
    /**
     * 64 位 Windows 中 PROCESS_INFORMATION 的结构大小。
     */
    static final int PROCESS_INFORMATION_BYTES = 24;
    /**
     * 64 位 Windows 中 STARTUPINFOW 的结构大小。
     */
    static final int STARTUP_INFO_BYTES = 104;
    /**
     * 64 位 Windows 中 STARTUPINFOEXW 的结构大小。
     */
    static final int STARTUP_INFO_EX_BYTES = 112;
    /**
     * 64 位 Windows 中 SECURITY_ATTRIBUTES 的结构大小。
     */
    static final int SECURITY_ATTRIBUTES_BYTES = 24;
    /**
     * STARTUPINFOW.cb 字段的结构偏移。
     */
    static final int STARTUP_CB_OFFSET = 0;
    /**
     * STARTUPINFOW.dwFlags 字段的结构偏移。
     */
    static final int STARTUP_FLAGS_OFFSET = 60;
    /**
     * STARTUPINFOW.hStdInput 字段的结构偏移。
     */
    static final int STARTUP_STD_INPUT_OFFSET = 80;
    /**
     * STARTUPINFOW.hStdOutput 字段的结构偏移。
     */
    static final int STARTUP_STD_OUTPUT_OFFSET = 88;
    /**
     * STARTUPINFOW.hStdError 字段的结构偏移。
     */
    static final int STARTUP_STD_ERROR_OFFSET = 96;
    /**
     * STARTUPINFOEXW.lpAttributeList 字段的结构偏移。
     */
    static final int STARTUP_ATTRIBUTE_LIST_OFFSET = 104;
    /**
     * PROCESS_INFORMATION.hProcess 字段的结构偏移。
     */
    static final int PROCESS_HANDLE_OFFSET = 0;
    /**
     * PROCESS_INFORMATION.hThread 字段的结构偏移。
     */
    static final int THREAD_HANDLE_OFFSET = 8;
    /**
     * PROCESS_INFORMATION.dwProcessId 字段的结构偏移。
     */
    static final int PROCESS_ID_OFFSET = 16;
    /**
     * CreateProcessW 挂起主线程的创建标志。
     */
    static final int CREATE_SUSPENDED = 0x0000_0004;
    /**
     * CreateProcessW 使用 Unicode 环境块的创建标志。
     */
    static final int CREATE_UNICODE_ENVIRONMENT = 0x0000_0400;
    /**
     * Tool 标准流全部重定向到管道，不继承宿主控制台；与 JDK ProcessBuilder 的重定向
     * 路径一致，避免旧版 PowerShell 等待 CI/桌面宿主的控制台状态或弹出额外窗口。
     */
    static final int CREATE_NO_WINDOW = 0x0800_0000;
    /**
     * CreateProcessW 接收 STARTUPINFOEXW 的创建标志。
     */
    static final int EXTENDED_STARTUPINFO_PRESENT = 0x0008_0000;
    /**
     * PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE 的 Win32 宏值。
     */
    static final int PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x0002_0016;
    /**
     * InitializeProcThreadAttributeList 尺寸预检的预期错误码。
     */
    static final int ERROR_INSUFFICIENT_BUFFER = 122;
    /**
     * STARTUPINFOW 启用显式标准句柄的标志。
     */
    static final int STARTF_USESTDHANDLES = 0x0000_0100;
    /**
     * SetHandleInformation 的句柄继承位。
     */
    static final int HANDLE_FLAG_INHERIT = 0x0000_0001;

    private final WindowsKernel32Library library;
    private final MethodHandle createPipe;
    private final MethodHandle setHandleInformation;
    private final MethodHandle createProcess;
    private final MethodHandle createPseudoConsole;
    private final MethodHandle closePseudoConsole;
    private final MethodHandle initializeProcThreadAttributeList;
    private final MethodHandle updateProcThreadAttribute;
    private final MethodHandle deleteProcThreadAttributeList;
    private final MethodHandle resumeThread;
    private final MethodHandle readFile;
    private final MethodHandle writeFile;
    private final MethodHandle peekNamedPipe;
    private final MethodHandle cancelIoEx;
    private final MethodHandle waitForSingleObject;
    private final MethodHandle getExitCodeProcess;
    private final MethodHandle terminateProcess;
    private final MethodHandle closeHandle;

    /**
     * 在平台检查通过后创建共享 FFM 会话并解析固定符号；部分解析失败会先释放 library Arena。
     */
    WindowsProcessNativeApi() {
        library = new WindowsKernel32Library("windows_process_native_symbols_unavailable");
        try {
            createPipe = resolve("CreatePipe", "create_pipe", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            setHandleInformation = resolve("SetHandleInformation", "set_handle_information",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            createProcess = resolve("CreateProcessW", "create_process", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            createPseudoConsole = resolve("CreatePseudoConsole", "create_pseudo_console",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            closePseudoConsole = resolve("ClosePseudoConsole", "close_pseudo_console",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            initializeProcThreadAttributeList = resolve("InitializeProcThreadAttributeList",
                    "initialize_process_attribute_list", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            updateProcThreadAttribute = resolve("UpdateProcThreadAttribute", "update_process_attribute",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            deleteProcThreadAttributeList = resolve("DeleteProcThreadAttributeList",
                    "delete_process_attribute_list", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            resumeThread = resolve("ResumeThread", "resume_thread", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS));
            readFile = resolve("ReadFile", "read_file", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            writeFile = resolve("WriteFile", "write_file", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            peekNamedPipe = resolve("PeekNamedPipe", "peek_named_pipe",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            cancelIoEx = resolve("CancelIoEx", "cancel_io_ex",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            waitForSingleObject = resolve("WaitForSingleObject", "wait_for_single_object",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            getExitCodeProcess = resolve("GetExitCodeProcess", "get_exit_code_process",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            terminateProcess = resolve("TerminateProcess", "terminate_process",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            closeHandle = resolve("CloseHandle", "close_handle", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS));
        } catch (NativeSymbolUnavailable failure) {
            library.closeQuietly();
            throw failure;
        } catch (Throwable failure) {
            library.closeQuietly();
            throw new IllegalStateException("windows_process_native_symbols_unavailable", failure);
        }
    }

    /**
     * 在调用局部 Arena 中编码以 NUL 结尾的 UTF-16LE Win32 字符串。
     */
    private static MemorySegment utf16(String value, Arena arena) {
        byte[] bytes = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment segment = arena.allocate(bytes.length, 2);
        segment.copyFrom(MemorySegment.ofArray(bytes));
        return segment;
    }

    /**
     * 为源码固定的 Kernel32 符号生成 downcall，并将单个解析失败映射为可公开的稳定分类；
     * operation 不接收外部输入，因此错误码不会泄漏 DLL、路径或系统异常文本。
     */
    private MethodHandle resolve(String symbol, String operation, FunctionDescriptor descriptor) {
        try {
            return library.downcall(symbol, descriptor);
        } catch (Throwable failure) {
            throw new NativeSymbolUnavailable(
                    "windows_process_native_" + operation + "_symbol_unavailable", failure);
        }
    }

    /**
     * 创建一对可继承管道句柄，父端继承权限由调用方随后显式关闭；系统结构的保留字段先清零，
     * 避免把未初始化的 native 内存作为 Win32 输入。
     */
    Pipe createPipe() throws WindowsFailure {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment read = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment write = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment security = arena.allocate(SECURITY_ATTRIBUTES_BYTES, 8);
            security.fill((byte) 0);
            security.set(ValueLayout.JAVA_INT, 0, SECURITY_ATTRIBUTES_BYTES);
            security.set(ValueLayout.JAVA_INT, 16, 1);
            NativeCall result = invoke(createPipe, read, write, security, 0);
            if ((int) result.value() == 0) {
                throw new WindowsFailure("create_pipe", result.error());
            }
            return new Pipe(read.get(ValueLayout.ADDRESS, 0), write.get(ValueLayout.ADDRESS, 0));
        }
    }

    /**
     * 关闭父端句柄继承，保证只有预定的子端跨越 CreateProcess 边界。
     */
    void disableInheritance(MemorySegment handle) throws WindowsFailure {
        NativeCall result = invoke(setHandleInformation, handle, HANDLE_FLAG_INHERIT, 0);
        if ((int) result.value() == 0) {
            throw new WindowsFailure("set_handle_information", result.error());
        }
    }

    /**
     * 创建可变 UTF-16 命令行并以挂起状态启动；管道 IO 不附着宿主控制台，
     * 仍由 Job 接纳后恢复主线程，控制台隔离不能绕过进程树所有权。ConPTY 显式清空标准句柄，
     * 避免 Windows 把父进程的 JA-RPC 管道复制给 Shell；控制台为子进程提供真实终端句柄。
     * STARTUPINFOW 的未指定字段和
     * PROCESS_INFORMATION 输出缓冲区均先清零，避免未初始化字节跨 Win32 边界。
     */
    ProcessCreation createSuspended(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            MemorySegment stdin,
            MemorySegment stdout,
            MemorySegment stderr) throws WindowsFailure {
        try (Arena arena = Arena.ofConfined()) {
            return createSuspendedInArena(spec, stdin, stdout, stderr, arena);
        }
    }

    /**
     * 属性列表的删除必须发生在调用方 Arena 关闭之前；创建成功只转交 Kernel 句柄，不转交临时原生内存。
     */
    private ProcessCreation createSuspendedInArena(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            MemorySegment stdin,
            MemorySegment stdout,
            MemorySegment stderr,
            Arena arena) throws WindowsFailure {
        MemorySegment pseudoConsole = MemorySegment.NULL;
        MemorySegment attributeList = MemorySegment.NULL;
        boolean attributeListInitialized = false;
        try {
            String executable = WindowsProcessLaunchPolicy.resolveExecutable(
                    spec.command().getFirst(), spec.environment());
            MemorySegment application = utf16(executable, arena);
            MemorySegment commandLine = utf16(
                    WindowsProcessLaunchPolicy.commandLine(spec.command()), arena);
            MemorySegment directory = utf16(spec.workingDirectory().toString(), arena);
            MemorySegment environmentBlock = utf16(
                    WindowsProcessLaunchPolicy.environmentBlock(spec.environment()), arena);
            MemorySegment startup = arena.allocate(
                    spec.tty() ? STARTUP_INFO_EX_BYTES : STARTUP_INFO_BYTES, 8);
            startup.fill((byte) 0);
            startup.set(ValueLayout.JAVA_INT, STARTUP_CB_OFFSET,
                    spec.tty() ? STARTUP_INFO_EX_BYTES : STARTUP_INFO_BYTES);
            int creationFlags = CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT;
            int inheritHandles = 1;
            if (spec.tty()) {
                pseudoConsole = createPseudoConsole(stdin, stdout, arena);
                MemorySegment requiredSize = arena.allocate(ValueLayout.JAVA_LONG);
                NativeCall sizeProbe = invoke(initializeProcThreadAttributeList,
                        MemorySegment.NULL, 1, 0, requiredSize);
                long listBytes = requiredSize.get(ValueLayout.JAVA_LONG, 0);
                if (listBytes <= 0 || ((int) sizeProbe.value() == 0
                        && sizeProbe.error() != ERROR_INSUFFICIENT_BUFFER)) {
                    throw new WindowsFailure("initialize_process_attribute_list", sizeProbe.error());
                }
                attributeList = arena.allocate(listBytes, ValueLayout.ADDRESS.byteAlignment());
                NativeCall initialized = invoke(initializeProcThreadAttributeList,
                        attributeList, 1, 0, requiredSize);
                if ((int) initialized.value() == 0) {
                    throw new WindowsFailure("initialize_process_attribute_list", initialized.error());
                }
                attributeListInitialized = true;
                NativeCall updated = invoke(updateProcThreadAttribute, attributeList, 0,
                        MemorySegment.ofAddress(Integer.toUnsignedLong(PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE)),
                        pseudoConsole, ValueLayout.ADDRESS.byteSize(), MemorySegment.NULL, MemorySegment.NULL);
                if ((int) updated.value() == 0) {
                    throw new WindowsFailure("update_process_attribute", updated.error());
                }
                startup.set(ValueLayout.ADDRESS, STARTUP_ATTRIBUTE_LIST_OFFSET, attributeList);
                creationFlags |= EXTENDED_STARTUPINFO_PRESENT;
                inheritHandles = 0;
                startup.set(ValueLayout.JAVA_INT, STARTUP_FLAGS_OFFSET, STARTF_USESTDHANDLES);
                startup.set(ValueLayout.ADDRESS, STARTUP_STD_INPUT_OFFSET, MemorySegment.NULL);
                startup.set(ValueLayout.ADDRESS, STARTUP_STD_OUTPUT_OFFSET, MemorySegment.NULL);
                startup.set(ValueLayout.ADDRESS, STARTUP_STD_ERROR_OFFSET, MemorySegment.NULL);
            } else {
                startup.set(ValueLayout.JAVA_INT, STARTUP_FLAGS_OFFSET, STARTF_USESTDHANDLES);
                startup.set(ValueLayout.ADDRESS, STARTUP_STD_INPUT_OFFSET, stdin);
                startup.set(ValueLayout.ADDRESS, STARTUP_STD_OUTPUT_OFFSET, stdout);
                startup.set(ValueLayout.ADDRESS, STARTUP_STD_ERROR_OFFSET, stderr);
                creationFlags |= CREATE_NO_WINDOW;
            }
            MemorySegment processInformation = arena.allocate(PROCESS_INFORMATION_BYTES, 8);
            processInformation.fill((byte) 0);
            NativeCall result = invoke(createProcess, application, commandLine,
                    MemorySegment.NULL, MemorySegment.NULL, inheritHandles, creationFlags,
                    environmentBlock, directory, startup, processInformation);
            if ((int) result.value() == 0) {
                throw new WindowsFailure("create_process", result.error());
            }
            return new ProcessCreation(
                    processInformation.get(ValueLayout.ADDRESS, PROCESS_HANDLE_OFFSET),
                    processInformation.get(ValueLayout.ADDRESS, THREAD_HANDLE_OFFSET),
                    Integer.toUnsignedLong(processInformation.get(ValueLayout.JAVA_INT, PROCESS_ID_OFFSET)),
                    pseudoConsole);
        } catch (WindowsFailure failure) {
            closePseudoConsoleQuietly(pseudoConsole);
            throw failure;
        } catch (RuntimeException failure) {
            closePseudoConsoleQuietly(pseudoConsole);
            throw failure;
        } finally {
            if (attributeListInitialized) {
                try {
                    invoke(deleteProcThreadAttributeList, attributeList);
                } catch (WindowsFailure ignored) {
                    // 创建已不再使用启动属性；清理故障不能覆盖原进程启动结果。
                }
            }
        }
    }

    /**
     * 使用进程所有者提供的输入输出管道创建固定尺寸 ConPTY，避免伪控制台另行取得宿主标准流。
     */
    private MemorySegment createPseudoConsole(MemorySegment input, MemorySegment output, Arena arena)
            throws WindowsFailure {
        MemorySegment console = arena.allocate(ValueLayout.ADDRESS);
        NativeCall result = invoke(createPseudoConsole, 120 | (30 << 16), input, output, 0, console);
        if ((int) result.value() < 0) {
            throw new WindowsFailure("create_pseudo_console", result.error());
        }
        return console.get(ValueLayout.ADDRESS, 0);
    }

    /**
     * 根进程不再需要终端输入输出后关闭 ConPTY 句柄，保持终端资源与进程生命周期一致。
     */
    void closePseudoConsole(MemorySegment pseudoConsole) throws WindowsFailure {
        if (pseudoConsole == null || pseudoConsole.address() == 0) {
            return;
        }
        invoke(closePseudoConsole, pseudoConsole);
    }

    /**
     * 进程接纳失败时尽力清理 ConPTY；清理错误不得覆盖导致启动失败的原始异常。
     */
    void closePseudoConsoleQuietly(MemorySegment pseudoConsole) {
        try {
            closePseudoConsole(pseudoConsole);
        } catch (WindowsFailure ignored) {
            // The process creation failure remains the primary result.
        }
    }

    /**
     * 仅在 Job 接纳成功后恢复主线程，并拒绝 Windows 的无效哨兵返回值。
     */
    void resumeThread(MemorySegment thread) throws WindowsFailure {
        NativeCall result = invoke(resumeThread, thread);
        if (Integer.toUnsignedLong((int) result.value()) == 0xffff_ffffL) {
            throw new WindowsFailure("resume_thread", result.error());
        }
    }

    /**
     * 在受限 Arena 中读取一个有界管道块，返回本次实际传输字节数。
     */
    int read(MemorySegment handle, byte[] bytes, int offset, int length) throws WindowsFailure {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment target = arena.allocate(length, 1);
            NativeCall result = invoke(readFile, handle, target, length, count, MemorySegment.NULL);
            if ((int) result.value() == 0) {
                throw new WindowsFailure("read_file", result.error());
            }
            int transferred = count.get(ValueLayout.JAVA_INT, 0);
            target.asByteBuffer().get(bytes, offset, transferred);
            return transferred;
        }
    }

    /**
     * 在不消费数据且不阻塞的前提下查询精确管道可读字节数；调用方只允许按该上限进入 ReadFile，
     * 从设计上消除 Native Image 无法跨线程唤醒同步 downcall 的风险。
     */
    int availableBytes(MemorySegment handle) throws WindowsFailure {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment available = arena.allocate(ValueLayout.JAVA_INT);
            NativeCall result = invoke(
                    peekNamedPipe,
                    handle,
                    MemorySegment.NULL,
                    0,
                    MemorySegment.NULL,
                    available,
                    MemorySegment.NULL);
            if ((int) result.value() == 0) {
                throw new WindowsFailure("peek_named_pipe", result.error());
            }
            return available.get(ValueLayout.JAVA_INT, 0);
        }
    }

    /**
     * 在受限 Arena 中写入一个有界管道块，返回本次实际传输字节数。
     */
    int write(MemorySegment handle, byte[] bytes, int offset, int length) throws WindowsFailure {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment source = arena.allocate(length, 1);
            source.asByteBuffer().put(bytes, offset, length);
            NativeCall result = invoke(writeFile, handle, source, length, count, MemorySegment.NULL);
            if ((int) result.value() == 0) {
                throw new WindowsFailure("write_file", result.error());
            }
            return count.get(ValueLayout.JAVA_INT, 0);
        }
    }

    /**
     * 对精确父端管道的全部 pending I/O 发出取消标记；关闭位已阻止新 I/O 登记，因此 NULL
     * OVERLAPPED 不会误伤后续操作。调用返回不代表 I/O 已结束，外层仍必须等待 reader/writer 静默。
     */
    void cancelIoEx(MemorySegment pipeHandle) throws WindowsFailure {
        if (pipeHandle == null || pipeHandle.address() == 0) {
            return;
        }
        NativeCall result = invoke(cancelIoEx, pipeHandle, MemorySegment.NULL);
        if ((int) result.value() == 0
            && result.error() != ERROR_NOT_FOUND
            && result.error() != ERROR_INVALID_HANDLE) {
            throw new WindowsFailure("cancel_io_ex", result.error());
        }
    }

    /**
     * 只等待指定进程句柄并返回 Windows wait 状态，不扫描全局进程表。
     */
    int waitFor(MemorySegment process, int milliseconds) {
        try {
            NativeCall result = invoke(waitForSingleObject, process, milliseconds);
            return (int) result.value();
        } catch (WindowsFailure failure) {
            return WAIT_FAILED;
        }
    }

    /**
     * 在进程句柄已触发后读取最终退出码，读取失败统一映射为稳定状态异常。
     */
    int exitCode(MemorySegment process) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment code = arena.allocate(ValueLayout.JAVA_INT);
            NativeCall result = invoke(getExitCodeProcess, process, code);
            if ((int) result.value() == 0) {
                throw new IllegalStateException("windows_process_exit_code_failed");
            }
            return code.get(ValueLayout.JAVA_INT, 0);
        } catch (WindowsFailure failure) {
            throw new IllegalStateException("windows_process_exit_code_failed", failure);
        }
    }

    /**
     * 只终止精确根句柄，后代清理仍由 Job Object 独占负责。
     */
    void terminate(MemorySegment process) throws WindowsFailure {
        NativeCall result = invoke(terminateProcess, process, 1);
        if ((int) result.value() == 0 && result.error() != 5) {
            throw new WindowsFailure("terminate_process", result.error());
        }
    }

    /**
     * 关闭一个本地句柄，仅保留确定性的数值错误证据。
     */
    void closeHandle(MemorySegment handle) throws WindowsFailure {
        if (handle == null || handle.address() == 0) {
            return;
        }
        NativeCall result = invoke(closeHandle, handle);
        if ((int) result.value() == 0 && result.error() != ERROR_INVALID_HANDLE) {
            throw new WindowsFailure("close_handle", result.error());
        }
    }

    /**
     * 在接纳失败路径执行幂等尽力关闭，不覆盖首个失败。
     */
    void closeQuietly(MemorySegment handle) {
        try {
            closeHandle(handle);
        } catch (WindowsFailure ignored) {
            // 首个启动或清理错误是稳定公共结果，次生关闭错误不应覆盖它。
        }
    }

    /**
     * 在关闭精确管道句柄前尽力解除其 pending I/O；次生取消错误不覆盖资源 owner 的首个清理结果。
     */
    void cancelIoExQuietly(MemorySegment pipeHandle) {
        try {
            cancelIoEx(pipeHandle);
        } catch (WindowsFailure ignored) {
            // 最终 reader/writer 静默屏障会把无法解除的同步 I/O 判定为清理失败。
        }
    }

    /**
     * 在 Process.destroy 防御路径中终止精确根进程，且不从 Process API 抛出受检异常。
     */
    void terminateQuietly(MemorySegment process) {
        try {
            terminate(process);
        } catch (WindowsFailure ignored) {
            // Job 关闭仍是进程树的权威清理边界，根进程终止失败只作为尽力操作。
        }
    }

    /**
     * 仅在所有进程和管道句柄完成转移或关闭后释放符号 Arena。
     */
    @Override
    public void close() {
        library.close();
    }

    /**
     * 调用共享 FFM 边界并映射为进程适配器的稳定失败，不复制命令或路径。
     */
    private NativeCall invoke(MethodHandle function, Object... arguments) throws WindowsFailure {
        try {
            WindowsKernel32Library.NativeCall result = library.invoke(
                    function, "native_invoke", arguments);
            return new NativeCall(result.value(), result.error());
        } catch (WindowsKernel32Library.InvocationFailure failure) {
            throw new WindowsFailure("native_invoke", 0, failure);
        }
    }

    /**
     * 标记单个固定 Win32 符号不可用，使上层能区分 Native Image 链接缺口而不暴露底层异常。
     */
    private static final class NativeSymbolUnavailable extends IllegalStateException {
        /**
         * 只接受平台适配器生成的稳定错误码，原始失败仅保留在内部 cause 链用于本地诊断。
         */
        private NativeSymbolUnavailable(String errorCode, Throwable cause) {
            super(errorCode, cause);
        }
    }

    /**
     * 在 FFM 临时状态释放前冻结本地返回值和 GetLastError。
     *
     * @param value 当前值
     * @param error 错误信息
     */
    record NativeCall(Object value,
                      int error) {
    }

    /**
     * 一对本地管道句柄，由接纳路径或返回后的进程对象独占关闭。
     *
     * @param read 读取结果
     * @param write 待提交的写入
     */
    record Pipe(MemorySegment read,
                MemorySegment write) {
    }

    /**
     * 在临时 FFM 内存释放前冻结进程、线程句柄、伪控制台所有权及进程标识。
     *
     * @param processHandle 进程句柄
     * @param threadHandle 线程句柄
     * @param processId 进程标识
     * @param pseudoConsole ConPTY 句柄；普通管道模式为 NULL
     */
    record ProcessCreation(MemorySegment processHandle,
                           MemorySegment threadHandle,
                           long processId,
                           MemorySegment pseudoConsole) {
    }

    /**
     * 有界本地失败，只携带稳定操作名和 Win32 数值错误码。
     */
    static final class WindowsFailure extends Exception {
        /**
         * 保持跨 JVM 版本稳定的异常序列标识；异常不携带本地句柄。
         */
        private static final long serialVersionUID = 1L;
        private final int error;

        /**
         * 不保存命令文本、路径或本地边界中的敏感数据。
         */
        WindowsFailure(String operation, int error) {
            this(operation, error, null);
        }

        /**
         * 保留本地 cause 供诊断，同时公共消息保持脱敏。
         */
        WindowsFailure(String operation, int error, Throwable cause) {
            super(operation, cause);
            this.error = error;
        }

        /**
         * 提供数值错误码，用于 EOF 分类和稳定诊断。
         */
        int error() {
            return error;
        }

        /**
         * 将本地失败映射为 Tool/MCP 适配器可消费的有界 IOException。
         */
        IOException asIoException(String code) {
            return new IOException(code + "_" + Integer.toUnsignedString(error), this);
        }
    }
}
