// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

/** 用户输入问题的闭集类型；类型直接决定前端选择语义，不从展示文案猜测。 */
public enum InteractionQuestionType {
    /** 只能选择一个稳定 optionId。 */ SINGLE,
    /** 可选择多个不重复 optionId。 */ MULTIPLE,
    /** 必须提交非空自由文本。 */ TEXT
}
