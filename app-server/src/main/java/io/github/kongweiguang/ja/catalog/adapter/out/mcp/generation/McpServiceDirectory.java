// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久拥有一个 `{workspace, serverId, definitionRevision}` 服务目录及其可接收通知的 Session。
 */
final class McpServiceDirectory implements AutoCloseable {
    private final McpServerDefinition definition;
    private final McpRuntime runtime;
    private final AtomicInteger pins = new AtomicInteger();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造只创建懒 Runtime，不打开传输；首次 snapshot/invoke 才会建立对应服务 Session。
     */
    McpServiceDirectory(
            McpServerDefinition definition,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSessionFactory sessionFactory) {
        this.definition = definition;
        this.runtime = new McpRuntime(List.of(definition), limits, objectMapper, sessionFactory, null,
                McpDeadline.forServiceDirectory(System::nanoTime));
    }

    /**
     * 在 Provider 安全点执行该服务的有界发现；失败由 Runtime 隔离为空目录。
     */
    McpGateway.McpSnapshot snapshot() {
        requireOpen();
        return runtime.snapshot();
    }

    /**
     * Tool batch 创建后 pin 精确服务定义，配置切换只能退休而不能抢先关闭它。
     */
    void pin() {
        requireOpen();
        pins.incrementAndGet();
        if (closed.get()) {
            release();
            throw new IllegalStateException("mcp_service_directory_closed");
        }
    }

    /**
     * batch 全部结算后释放 pin；已退休服务到此才允许关闭真实传输。
     */
    void release() {
        int remaining = pins.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("mcp_service_directory_pin_underflow");
        }
        if (remaining == 0 && retired.get()) {
            close();
        }
    }

    /**
     * 配置变化只发布退休标记；是否立即关闭由安全点决定，workspace prepare 本身保持零外部 IO。
     */
    void retire(boolean closeWhenIdle) {
        retired.set(true);
        if (closeWhenIdle && pins.get() == 0) {
            close();
        }
    }

    /**
     * 精确执行仍委派给持有该 definitionRevision 的 Runtime，不允许查找其它同名服务。
     */
    McpRuntime runtime() {
        requireOpen();
        return runtime;
    }

    /**
     * 返回服务定义以构建聚合目录修订和执行期单服务快照。
     */
    McpServerDefinition definition() {
        return definition;
    }

    /**
     * 目录 owner 关闭或退休且空闲时幂等释放 Session、进程和执行器。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            runtime.close();
        }
    }

    /**
     * 已关闭目录不可重新打开；配置安全点会为新 definitionRevision 建立新 owner。
     */
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("mcp_service_directory_closed");
        }
    }
}
