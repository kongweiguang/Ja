// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.error;

/**
 * 准入和恢复预检共用的确定性终答约束错误；只携带固定分类与文案，不保存用户 Schema。
 */
public final class OutputSchemaException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    /** 私有构造确保宿主只能取得已定义的安全分类，不能借此把任意消息公开到协议或历史。 */
    private OutputSchemaException(String code, String safeMessage) {
        super(safeMessage, null, false, false);
        this.code = code;
    }

    /** 宿主和执行会话使用相同分类，避免准入失败退化成无法诊断的参数错误。 */
    public String code() {
        return code;
    }

    /** 非法或非对象根 Schema 必须在供应商请求前拒绝，原文不进入诊断。 */
    public static OutputSchemaException invalid() {
        return new OutputSchemaException("OUTPUT_SCHEMA_INVALID",
                "Output JSON Schema is invalid or its root must permit a JSON object.");
    }

    /** 能力未声明或 Codec 不支持时确定性拒绝，不作为可重试传输故障。 */
    public static OutputSchemaException unsupported() {
        return new OutputSchemaException("OUTPUT_SCHEMA_UNSUPPORTED",
                "Selected model does not support structured output.");
    }
}
