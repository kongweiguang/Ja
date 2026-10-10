// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter;

import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter.out.core.AttachmentCapabilitiesFactory;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentCapabilities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 附件能力只在组合根由启动时冻结的模型配置生成，定时器负责在服务恢复后清理过期草稿。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AttachmentConfiguration {
    /** 只创建一份与上传、提交校验和模型能力页面共享的附件能力快照。 */
    @Bean
    @ConditionalOnMissingBean(AttachmentCapabilities.class)
    public AttachmentCapabilities attachmentCapabilities(ModelPort.ModelConfiguration model) {
        return AttachmentCapabilitiesFactory.fromModel(model);
    }
}
