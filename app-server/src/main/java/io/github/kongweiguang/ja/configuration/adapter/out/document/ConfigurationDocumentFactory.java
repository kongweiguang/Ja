// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * 为严格配置文档集中提供首次生成和 revision 推进规则。
 */
final class ConfigurationDocumentFactory {
    static final int CURRENT_SCHEMA_VERSION = 1;
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
        root.putObject("interaction").put("clarification_enabled", true);
        root.putNull("default_provider_id");
        root.putNull("default_model_id");
        root.putNull("default_reasoning_level");
        root.putObject("subagents").put("enabled", true).putNull("provider_id").putNull("model_id")
                .putNull("reasoning_level");
        root.putArray("providers");
        root.putArray("mcp_servers");
        root.putArray("skills");
        return root;
    }

    /**
     * 在发布前只推进 revision；schema 与其它当前字段必须由调用方显式提供并通过严格校验。
     *
     * <p>达到 long 上限时回到 1，CAS 身份仍由内容摘要提供，因此 revision 只承担文档内
     * 可读顺序，不被误用成并发令牌。</p>
     */
    void advanceRevision(ObjectNode next, ObjectNode previous) {
        Objects.requireNonNull(next, "next");
        long revision = 0L;
        if (previous != null) {
            JsonNode value = previous.get("config_revision");
            if (value == null || !value.isIntegralNumber() || value.longValue() < 0) {
                throw new IllegalArgumentException("previous configuration revision is invalid");
            }
            revision = value.longValue();
        }
        next.put("config_revision", revision == Long.MAX_VALUE ? 1L : revision + 1L);
    }
}
