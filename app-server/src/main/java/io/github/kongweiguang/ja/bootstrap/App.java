// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import org.noear.solon.annotation.SolonMain;

/**
 * 仅作为 Solon 组合根存在；生命周期与配置归 Solon，协议和运行时图归 bootstrap 适配器。
 */
@SolonMain
public final class App {
    /**
     * 禁止绕过 Solon 生命周期直接构造入口对象，避免产生第二套启动路径。
     */
    private App() {
    }

    /**
     * 保持 JVM 与 Native Image 共用唯一稳定入口，同时把可测试的运行时装配留在 bootstrap；
     * 仅当 bootstrap 明确返回非零状态时终止进程，正常退出交由 Solon 生命周期完成。
     *
     * @param args Tauri sidecar 启动器传入的进程参数
     */
    public static void main(String[] args) {
        int exitCode = new StdioApplication().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
