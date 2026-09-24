// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 会话 MCP 状态的只读边界，服务连接测试由设置页负责。 */
public interface ThreadMcpUseCase {
    /** 读取最近派发观测，不连接 MCP 服务。 */
    ReadResult read(String threadId);

    /** 固定观测来源词汇，使 RPC 与界面对冻结状态的解释一致。 */
    enum Source {
        /** 当前正在运行的 Turn 的冻结目录。 */ ACTIVE("active"),
        /** 同代际最近一次真实派发观测。 */ LAST_OBSERVED("last_observed"),
        /** 本会话尚未发生 MCP 目录观测。 */ UNCHECKED("unchecked"),
        /** 配置或目录已变化，需要下轮重新发现。 */ STALE("stale");

        private final String wireName;

        /** 公共拼写独立于 Java 枚举名称，避免重构改变协议。 */
        Source(String wireName) { this.wireName = wireName; }

        /** 返回稳定的 JA-RPC 拼写。 */
        public String wireName() { return wireName; }
    }

    /** 固定服务状态词汇；缺失的数量和原因由 RPC 映射器直接省略。 */
    enum State {
        /** 当前观测确认可用。 */ AVAILABLE("available"),
        /** 当前观测确认不可用。 */ UNAVAILABLE("unavailable"),
        /** 配置明确停用。 */ DISABLED("disabled"),
        /** 尚未发现服务目录。 */ NOT_DISCOVERED("not_discovered"),
        /** 当前模式不暴露 MCP。 */ NOT_EXPOSED("not_exposed"),
        /** 目录已过期，不能冒充当前可用。 */ STALE("stale");

        private final String wireName;

        /** 固定协议状态拼写，不随 Java 枚举重命名改变。 */
        State(String wireName) { this.wireName = wireName; }

        /** 返回稳定的 JA-RPC 状态拼写。 */
        public String wireName() { return wireName; }
    }

    /** 来源闭集避免把配置路径投影到会话界面。 */
    enum Scope {
        /** 用户全局配置。 */ GLOBAL,
        /** 当前受信项目配置。 */ PROJECT
    }
    /** 提示闭集避免把服务异常或配置正文带进界面。 */
    enum Notice {
        /** 运行中配置变化将在下轮生效。 */ CONFIGURATION_CHANGED,
        /** 项目未获信任，因此项目服务不加载。 */ PROJECT_UNTRUSTED,
        /** 项目配置存在错误，需在管理页修复。 */ PROJECT_CONFIG_ERROR
    }

    /** 单个 MCP 服务的脱敏状态；可选字段为空时不出现在 Wire。 */
    record Server(String serverId, String name, Scope scope, State state, Integer toolCount, String reasonCode) {
        /** 与配置名称长度一致，同时区分未知工具数与真实零工具。 */
        public Server {
            if (serverId == null || !serverId.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("invalid serverId");
            }
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || name.length() > 512 || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid MCP server name");
            }
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(scope, "scope");
            if (toolCount != null && (toolCount < 0 || toolCount > 10_000)) {
                throw new IllegalArgumentException("invalid MCP tool count");
            }
            if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("invalid MCP reason code");
            }
        }
    }

    /** 读取结果可同时表达冻结目录与下一轮配置变更，不混入设置页的探测结果。 */
    record ReadResult(String threadId, Source source, String catalogRevision,
                      Instant observedAt, List<Server> servers, List<Notice> notices) {
        /** 冻结服务状态，避免读取边界之后被调用方修改。 */
        public ReadResult {
            if (threadId == null || !threadId.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("invalid threadId");
            }
            Objects.requireNonNull(source, "source");
            if (catalogRevision != null && (catalogRevision.isBlank() || catalogRevision.length() > 256)) {
                throw new IllegalArgumentException("invalid MCP catalog revision");
            }
            servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        }
    }

    /** 稳定失败码只供 RPC 映射，不携带工作区路径或 MCP 异常细节。 */
    final class Failure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final String code;

        /** 领域失败身份与传输错误封包分离。 */
        public Failure(String code) {
            super(Objects.requireNonNull(code, "code"));
            if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("invalid MCP failure code");
            this.code = code;
        }

        /** 返回有界错误码，交由 RPC Handler 映射到协议词汇。 */
        public String code() { return code; }
    }
}
