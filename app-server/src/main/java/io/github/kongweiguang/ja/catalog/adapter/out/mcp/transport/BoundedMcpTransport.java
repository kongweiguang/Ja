// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 统一有界 MCP 传输与 SDK 之间不随载体变化的生命周期边界。
 *
 * <p>HTTP 与 stdio 只负责各自的 IO、超时和资源所有权；异常回调、显式 Mapper、协议版本与关闭入口
 * 必须保持同一语义，避免两种传输在 SDK 升级后产生不同的默认行为。</p>
 */
abstract class BoundedMcpTransport implements McpClientTransport {
    protected final McpJsonMapper jsonMapper;
    protected final List<String> protocolVersions;
    private volatile Consumer<Throwable> exceptionHandler = ignored -> {
    };

    /**
     * 复制 SDK 共享输入，使具体传输不能受调用方后续修改或 ServiceLoader 默认值影响。
     */
    BoundedMcpTransport(McpJsonMapper jsonMapper, List<String> protocolVersions) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.protocolVersions = List.copyOf(protocolVersions);
    }

    /**
     * 只注册本地异常观察者，具体传输负责确保不会把远端正文或 Secret 作为异常内容上报。
     */
    @Override
    public final void setExceptionHandler(Consumer<Throwable> handler) {
        exceptionHandler = handler == null ? ignored -> {
        } : handler;
    }

    /**
     * 通过统一模板进入具体资源关闭流程，使 SDK 观察到一致的延迟执行和失败传播语义。
     */
    @Override
    public final Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::closeTransport);
    }

    /**
     * 只使用显式注入的 Jackson 2 Mapper 转换 SDK 中性值，避免运行环境改变解码实现。
     */
    @Override
    public final <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    /**
     * 返回服务定义声明的不可变版本集合，禁止 SDK 默认值扩大兼容面。
     */
    @Override
    public final List<String> protocolVersions() {
        return protocolVersions;
    }

    /**
     * 将已脱敏的传输失败交给 SDK，并保持异常观察者只能由公共入口替换。
     */
    protected final void reportTransportFailure(Throwable failure) {
        exceptionHandler.accept(failure);
    }

    /**
     * 由具体载体在自身关闭预算内取消并释放其独占资源。
     */
    protected abstract void closeTransport();
}
