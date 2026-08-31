// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 保护持久化边界只保存附件 identity，绝不落库请求期 Base64。 */
final class PersistenceCodecTest {
    /** 原生载荷只能存在于冻结 Provider 请求，任何历史写入都必须 fail closed。 */
    @Test
    void rejectsRequestScopedNativeAttachmentPayload() {
        ModelMessage message = new ModelMessage(ModelRole.USER, List.of(
                new NativeAttachmentContent("att_12345678", NativeAttachmentContent.Kind.IMAGE,
                        "image.png", "image/png", 1, "AQ==")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PersistenceCodec(new ObjectMapper()).writeMessage(message));

        assertEquals("native attachment payload is request-scoped and cannot be persisted",
                failure.getMessage());
    }
}
