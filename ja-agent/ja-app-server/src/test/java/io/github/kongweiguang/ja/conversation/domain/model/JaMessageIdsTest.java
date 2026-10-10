// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 Ja ID 投影边界，保证重连与重试始终落到同一个 timeline item。 */
class JaMessageIdsTest {
    /** 数据库既有合法 item ID 必须保持原值，避免断开旧 timeline 引用。 */
    @Test
    void preservesValidLegacyItemIdentity() {
        assertEquals("item_abc-123", JaMessageIds.toItemId("item_abc-123"));
    }

    /** Core 消息身份投影必须确定、满足 JA-RPC 语法且不与原 `msg_` 身份混用。 */
    @Test
    void hashesGenericMessageIdentityDeterministically() {
        String first = JaMessageIds.toItemId("msg_abc123");
        assertEquals(first, JaMessageIds.toItemId("msg_abc123"));
        assertTrue(first.matches("item_[0-9a-f]{64}"));
        assertNotEquals(first, JaMessageIds.toItemId("msg_def456"));
    }

    /** 非法或超长源身份必须失败，防止映射吞掉无效恢复事实。 */
    @Test
    void rejectsInvalidSourceIdentity() {
        assertThrows(IllegalArgumentException.class, () -> JaMessageIds.toItemId(" \t"));
        assertThrows(IllegalArgumentException.class, () -> JaMessageIds.toItemId("x\0y"));
    }
}
