// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.context;

/**
 * 持久化各工具结果进入模型上下文时采用的最小投影阶段。
 */
public enum ToolProjection {
    /**
     * 完整保留新近结果，只有首次选择时的预算允许才可采用。
     */
    FULL,
    /**
     * 保留首尾证据并省去正文中段，兼顾诊断上下文与稳定输入预算。
     */
    HEAD_TAIL,
    /**
     * 仅保留结构化产物描述，作为预算不足时不可再降级的安全下限。
     */
    ARTIFACT
}