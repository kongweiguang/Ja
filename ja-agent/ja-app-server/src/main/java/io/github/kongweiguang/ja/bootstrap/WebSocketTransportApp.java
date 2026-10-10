// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import org.noear.solon.annotation.SolonMain;

/**
 * 只承载进程级 FeatHTTP 监听；业务组合根可重试，不能成为 WebSocket 连接的生命周期 owner。
 */
@SolonMain
final class WebSocketTransportApp {
    /** 禁止把传输入口当成可实例化服务，连接路由由 WebSocketDaemon 唯一登记。 */
    private WebSocketTransportApp() {
    }
}
