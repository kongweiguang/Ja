// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

/**
 * 对话页面所需的最小模型能力视图，不暴露 Provider 连接配置。
 *
 * @param model 当前配置的上游模型名称
 * @param experience 当前服务是否启用经验工具
 * @param attachmentCapabilities 当前模型支持的附件格式与容量上限
 */
public record ModelMetadataView(String model,
                                boolean experience,
                                AttachmentCapabilitiesView attachmentCapabilities) { }
