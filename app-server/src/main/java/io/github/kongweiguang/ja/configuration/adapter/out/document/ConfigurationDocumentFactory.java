// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * 为严格配置文档集中提供首次生成和 revision 推进规则。
 */
final class ConfigurationDocumentFactory {
    static final int CURRENT_SCHEMA_VERSION = 4;
    private final ObjectMapper mapper;

    /**
     * 复用 composition root 提供的 mapper，避免产生另一套 JSON 配置。
     */
    ConfigurationDocumentFactory(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 创建不会隐式落盘的最小严格文档，首次读取仍保持 missing 语义。
     */
    ObjectNode createEmpty() {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", CURRENT_SCHEMA_VERSION);
        root.put("config_revision", 0);
        root.put("default_access_mode", "full_access");
        root.putArray("providers");
        root.putArray("mcp_servers");
        root.putArray("skills");
        return root;
    }

    /**
     * 在发布前推进 revision，并忽略调用方提供的回退值。
     *
     * <p>达到 long 上限时回到 1，CAS 身份仍由内容摘要提供，因此 revision 只承担文档内
     * 可读顺序，不被误用成并发令牌。</p>
     */
    void advanceRevision(ObjectNode next, ObjectNode previous) {
        Objects.requireNonNull(next, "next");
        if (!next.has("schema_version")) {
            next.put("schema_version", CURRENT_SCHEMA_VERSION);
        }
        long revision = previous == null ? 0L : previous.path("config_revision").asLong(0L);
        next.put("config_revision", revision == Long.MAX_VALUE ? 1L : revision + 1L);
    }
}
