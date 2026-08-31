// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.domain;

/**
 * 配置文档的持久化作用域；领域值不携带路径或传输层字段。
 */
public enum ConfigurationScope {
    /**
     * 当前用户共享的全局配置。
     */
    USER,

    /**
     * 绑定到已注册且受信任工作区的项目配置。
     */
    PROJECT
}
