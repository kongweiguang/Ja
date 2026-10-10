// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.identity;

/**
 * 固定演示身份，企业接入时通过自有 CurrentActorProvider Bean 替换。
 */
public final class DemoCurrentActorProvider implements CurrentActorProvider {
    private static final CurrentActor ACTOR = new CurrentActor("tenant_demo", "user_demo");

    /** 返回不可变演示身份，避免为每个请求引入独立身份状态。 */
    @Override
    public CurrentActor currentActor() {
        return ACTOR;
    }
}
