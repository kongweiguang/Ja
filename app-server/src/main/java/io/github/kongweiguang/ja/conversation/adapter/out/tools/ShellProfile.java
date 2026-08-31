// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 进程启动时冻结的唯一原生 Shell 契约，Turn 和 Tool 都只引用该不可变值。
 */
public record ShellProfile(OperatingSystem os, Dialect dialect, Path executable,
                           List<String> arguments, String pathStyle, Map<String, String> environment) {
    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 固化可执行路径和启动参数，禁止 Tool 调用期间重新发现或切换 Shell。
     */
    public ShellProfile {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(dialect, "dialect");
        executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        pathStyle = Objects.requireNonNull(pathStyle, "pathStyle");
        environment = ShellProcessEnvironment.validate(os, environment);
    }

    /** 返回冻结环境的独立 Map，调用方不能取得 Profile 的内部表示。 */
    @Override
    public Map<String, String> environment() {
        return Map.copyOf(environment);
    }

    /**
     * 构造精确命令行；调用方只追加原始 command，不执行语法转换或备用 Shell 重试。
     */
    public List<String> commandLine(String command) {
        java.util.ArrayList<String> commandLine = new java.util.ArrayList<>(arguments.size() + 2);
        commandLine.add(executable.toString());
        commandLine.addAll(arguments);
        commandLine.add(command);
        return List.copyOf(commandLine);
    }

    /**
     * record 默认 toString 会展开完整 PATH；这里只公开键集合，避免诊断日志意外复制执行环境。
     */
    @Override
    public String toString() {
        return "ShellProfile[os=" + os + ", dialect=" + dialect + ", executable=" + executable
                + ", arguments=" + arguments + ", pathStyle=" + pathStyle
                + ", environmentKeys=" + environment.keySet() + "]";
    }

    /**
     * 生成不可被自定义 System Prompt 替代的精简环境块，使模型和实际执行器共享同一方言。
     */
    public String executionEnvironment(Path cwd) {
        return """
                <execution_environment>
                os: %s
                shell: %s
                cwd: %s
                path_style: %s
                </execution_environment>
                Use only the declared shell dialect for shell tool calls."""
                .formatted(os.wireName, dialect.wireName, cwd.toAbsolutePath().normalize(), pathStyle);
    }

    /**
     * 根据冻结方言生成唯一 Tool 描述，避免 Provider 看到与执行器不一致的通用 Shell 文案。
     */
    public String toolDescription() {
        return switch (dialect) {
            case POWERSHELL -> "Execute PowerShell 7 commands";
            case WINDOWS_POWERSHELL -> "Execute Windows PowerShell 5.1 commands";
            case ZSH -> "Execute zsh commands";
            case BASH -> "Execute Bash commands";
        };
    }

    /**
     * 用短进程验证 Shell 真正可启动；路径缺失、不可执行或超时都只返回不可用事实。
     */
    boolean preflight() {
        if (!Files.isRegularFile(executable)) {
            return false;
        }
        Process process = null;
        try {
            String probe = dialect == Dialect.POWERSHELL || dialect == Dialect.WINDOWS_POWERSHELL
                    ? "$PSVersionTable.PSVersion.Major" : "exit 0";
            ProcessBuilder builder = new ProcessBuilder(commandLine(probe)).redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
            if (!process.waitFor(PREFLIGHT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                if (process.isAlive()) process.destroyForcibly();
                return false;
            }
            return true;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /** 操作系统闭集只用于环境注入，不作为模型输入参数。 */
    public enum OperatingSystem {
        /** Windows 11 及后续桌面环境。 */
        WINDOWS("windows"),
        /** 使用系统 zsh 的 macOS 环境。 */
        MACOS("macos"),
        /** 使用系统 Bash 的 Linux 环境。 */
        LINUX("linux");
        private final String wireName;
        /** 固定模型环境块使用的小写 wire 名称。 */
        OperatingSystem(String wireName) { this.wireName = wireName; }
        /** 向可选能力环境块暴露同一稳定平台名称，避免复制枚举映射。 */
        String wireName() { return wireName; }
    }

    /** Shell 方言闭集与产品支持矩阵一一对应。 */
    public enum Dialect {
        /** PowerShell 7 方言，不兼容 Windows PowerShell 5.1。 */
        POWERSHELL("powershell"),
        /** Windows 11 系统回退方言，只在 PowerShell 7 不可用时选用。 */
        WINDOWS_POWERSHELL("windows_powershell"),
        /** macOS 系统 zsh 方言。 */
        ZSH("zsh"),
        /** Linux 系统 Bash 方言。 */
        BASH("bash");
        private final String wireName;
        /** 固定模型环境块使用的小写 wire 名称。 */
        Dialect(String wireName) { this.wireName = wireName; }
    }
}
