// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 锁定标题通知的最小公开字段，防止运行快照或旧 Profile 身份重新泄漏到 Wire。 */
final class ThreadMetadataEventWireMapperTest {
    /** 自动标题映射只携带会话列表增量刷新所需字段。 */
    @Test
    void mapsCommittedTitleWithoutRuntimeConfiguration() {
        var params = new ThreadMetadataEventWireMapper(new ObjectMapper(), "srv_test").map(
                new ThreadMetadataEvent("thr_demo", "ws_demo", 7, "迁移方案",
                        ThreadPreferences.TitleSource.AUTO));

        assertEquals("srv_test", params.path("serverInstanceId").asText());
        assertEquals("ws_demo", params.path("workspaceId").asText());
        assertEquals("thr_demo", params.path("threadId").asText());
        assertEquals(7, params.path("revision").asLong());
        assertEquals("迁移方案", params.path("title").asText());
        assertEquals("auto", params.path("titleSource").asText());
        assertEquals(6, params.size());
        assertFalse(params.has("profileId"));
        assertFalse(params.has("providerId"));
        assertFalse(params.has("modelId"));
    }

    /** admission 临时标题必须保留 PLACEHOLDER 所有权，前端才能在模型输出前接纳同一事件。 */
    @Test
    void mapsProvisionalTitleOwnership() {
        var params = new ThreadMetadataEventWireMapper(new ObjectMapper(), "srv_test").map(
                new ThreadMetadataEvent("thr_demo", "ws_demo", 1, "首问短标题",
                        ThreadPreferences.TitleSource.PLACEHOLDER));

        assertEquals("placeholder", params.path("titleSource").asText());
        assertEquals("首问短标题", params.path("title").asText());
    }
}
