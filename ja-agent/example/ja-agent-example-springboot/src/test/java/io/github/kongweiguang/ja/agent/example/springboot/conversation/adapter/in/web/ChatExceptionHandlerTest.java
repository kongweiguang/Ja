// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定持久化完整性故障的 Spring advice 分类，不依赖破坏性数据库夹具。 */
final class ChatExceptionHandlerTest {
    /** StoreIntegrityException 必须选择完整性错误 handler 并返回 500，而非可重试状态冲突。 */
    @Test
    void storeIntegrityFailureMapsToInternalServerError() throws NoSuchMethodException {
        ExceptionHandler mapping = ChatExceptionHandler.class.getMethod("storageIntegrity")
                .getAnnotation(ExceptionHandler.class);

        assertTrue(Arrays.asList(mapping.value()).contains(AgentStore.StoreIntegrityException.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                new ChatExceptionHandler().storageIntegrity().getStatus());
    }
}
