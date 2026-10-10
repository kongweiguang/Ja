// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.InitialContextSeedCodec;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Task 列中的空继承快照表示与 Core typed seed 格式边界。 */
final class TaskInitialContextSeedCodecTest {
    private final TestJsonValueCodec json = new TestJsonValueCodec();
    private final TaskInitialContextSeedCodec codec = new TaskInitialContextSeedCodec(json);

    /** 空父历史也必须以非空、有版本的文档持久化，满足 EFFECTIVE_CONTEXT 列约束。 */
    @Test
    void storesEmptyContextAsExplicitVersionedEnvelope() {
        String encoded = codec.encode(Optional.empty());

        assertEquals(1, ((JsonNumber) json.decodeObject(encoded).get("schemaVersion")).value().intValueExact());
        assertEquals("empty", ((JsonText) json.decodeObject(encoded).get("kind")).value());
        assertTrue(codec.decode(encoded).isEmpty());
    }

    /** 有效背景仍由 Core codec 完整编码，Task envelope 只增加可选值的显式判别。 */
    @Test
    void roundTripsCoreSeedWithoutChangingItsTypedPayload() {
        InitialContextSeed seed = seed();

        String encoded = codec.encode(Optional.of(seed));

        assertEquals("seed", ((JsonText) json.decodeObject(encoded).get("kind")).value());
        assertEquals(Optional.of(seed), codec.decode(encoded));
    }

    /** 旧的裸 Core JSON 与带额外字段的空 envelope 都失败关闭，避免双格式和静默数据丢失。 */
    @Test
    void rejectsUnwrappedCorePayloadAndUnknownEnvelopeFields() {
        InitialContextSeedCodec coreCodec = new InitialContextSeedCodec(json);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(coreCodec.encode(seed())));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                "{\"schemaVersion\":1,\"kind\":\"empty\",\"seed\":null}"));
        assertFalse(codec.decode(codec.encode(Optional.empty())).isPresent());
    }

    /** 使用一条真实用户上下文消息，固定 typed seed 的身份、水位和正文断言。 */
    private static InitialContextSeed seed() {
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                "ja.local", "thr_seed_parent", "turn_seed_parent"), 1,
                List.of(ContextMessage.text("msg_seed_parent", "turn_seed_parent", 1,
                        ContextMessage.Role.USER, "父会话背景", 2)), Optional.empty());
    }
}
