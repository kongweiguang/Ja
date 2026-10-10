// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 宿主没有提供身份适配器时才注册固定演示身份。 */
@Configuration(proxyBeanMethods = false)
public class DemoIdentityConfiguration {
    /** 保持示例开箱可运行，同时允许企业认证适配器替换默认 Bean。 */
    @Bean
    @ConditionalOnMissingBean(CurrentActorProvider.class)
    public CurrentActorProvider currentActorProvider() {
        return new DemoCurrentActorProvider();
    }
}
