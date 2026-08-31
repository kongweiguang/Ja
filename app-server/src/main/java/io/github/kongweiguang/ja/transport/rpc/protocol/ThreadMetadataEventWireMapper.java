// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;

import java.util.Locale;
import java.util.Objects;

/** 把已提交的 Thread 标题事实映射为不含 Provider 或运行配置的严格通知。 */
public final class ThreadMetadataEventWireMapper {
    private final ObjectMapper mapper;
    private final String serverInstanceId;

    /** 固定进程身份，使调用方只需补充连接级 sequence、generation 与事件时间。 */
    public ThreadMetadataEventWireMapper(ObjectMapper mapper, String serverInstanceId) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.serverInstanceId = Objects.requireNonNull(serverInstanceId, "serverInstanceId");
    }

    /** 只投影列表刷新所需字段，禁止把冻结模型、凭据或生成内容带入通知。 */
    public ObjectNode map(ThreadMetadataEvent event) {
        Objects.requireNonNull(event, "event");
        return mapper.createObjectNode()
                .put("serverInstanceId", serverInstanceId)
                .put("workspaceId", event.workspaceId())
                .put("threadId", event.threadId())
                .put("revision", event.revision())
                .put("title", event.title())
                .put("titleSource", event.titleSource().name().toLowerCase(Locale.ROOT));
    }
}
