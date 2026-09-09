// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.conversation.application.context.ContextException;

import java.util.Objects;

/** 为 Turn execution 中的滚动 Summary 提供严格、对称且不依赖持久层反射的 JSON 编解码。 */
public final class SummaryProgressCodec {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** 编码完整替换文档；任何序列化失败都表示内部恢复事实不可提交。 */
    public String encode(SummaryDocument document) {
        try {
            return JSON.writeValueAsString(Objects.requireNonNull(document, "document"));
        } catch (JsonProcessingException failure) {
            throw invalid("cannot encode summary Operation progress", failure);
        }
    }

    /** 解码持久滚动文档并复用领域构造器校验来源、容量和重复事实。 */
    public SummaryDocument decode(String value) {
        try {
            return JSON.readValue(Objects.requireNonNull(value, "value"), SummaryDocument.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid("cannot decode summary Operation progress", failure);
        }
    }

    /** 将损坏进度统一收敛为不可继续的 Summary failure，禁止从消息历史猜测。 */
    private static ContextException invalid(String message, Throwable cause) {
        return new ContextException(ContextException.Code.SUMMARY_FAILURE, message, cause);
    }
}
