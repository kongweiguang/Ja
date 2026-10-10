// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.model.summary;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 来源 ordinal 不能截断后引用另一条有效历史事实。 */
final class SummaryDocumentCodecTest {
    /** 大于 long 的数字即使低位为正，也不能恢复为有效的来源 ordinal。 */
    @Test
    void rejectsOverflowedSourceOrdinals() {
        BigInteger overflow = new BigInteger("18446744073709551617");
        ObjectNode facts = SummaryDocumentCodec.encode(SummaryDocument.empty());
        facts.withArray("goals").addObject().put("text", "source").put("sourceOrdinal", overflow);
        ObjectNode retirements = SummaryDocumentCodec.encode(SummaryDocument.empty());
        retirements.withArray("retirements").addObject().put("text", "source")
                .put("sourceOrdinal", overflow).put("status", "resolved");

        assertThrows(ContextException.class, () -> SummaryDocumentCodec.decode(facts.toString()));
        assertThrows(ContextException.class, () -> SummaryDocumentCodec.decode(retirements.toString()));
    }
}
