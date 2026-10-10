// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;

import java.util.Objects;

/**
 * 为 Plan revision 提供稳定正文和摘要的输出端口；应用层只依赖这个契约，
 * 具体 JSON 库留在 infrastructure adapter。
 */
public interface PlanCanonicalizer {
    /**
     * 先验证结构化定义，再返回不可拆分的 canonical JSON/hash 结果，避免调用方自行散列不同正文。
     */
    Encoded encode(PlanDefinition definition);

    /**
     * canonical JSON 与 SHA-256 的原子结果，防止正文和摘要在跨层传递时失配。
     *
     * @param json   稳定序列化后的 Plan 正文
     * @param sha256 正文对应的小写 SHA-256 摘要
     */
    record Encoded(String json,
                   String sha256) {
        /**
         * 输出端口拒绝空正文或非十六进制摘要，避免损坏值进入持久化层。
         */
        public Encoded {
            Objects.requireNonNull(json, "json");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid plan hash");
            }
        }
    }
}

