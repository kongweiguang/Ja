// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Objects;

/** 为 Handler 测试桥接 runtime 包级接缝，避免把测试专用入口提升为生产公开 API。 */
public final class RpcRuntimeTestAccess {
    /** 禁止构造无状态测试桥，所有状态仍由真实 RpcSession 与 RpcServer 持有。 */
    private RpcRuntimeTestAccess() {
    }

    /**
     * 通过 runtime 所有的 ready 状态机建立 Handler 前置条件，测试不得绕过 token 校验写字段。
     */
    public static void markReady(RpcSession session, String token) {
        Objects.requireNonNull(session, "session").ready(token);
    }

    /**
     * 只为需要确定时钟的跨包传输测试开放构造桥，生产仍使用公开的系统时钟构造入口。
     */
    public static RpcServer server(
            InputStream input, OutputStream output, SidecarConfiguration configuration, Clock clock,
            RpcServicesFactory factory, ConfigurationUseCase configurationUseCase) {
        return new RpcServer(input, output, configuration, clock, factory, configurationUseCase);
    }
}
