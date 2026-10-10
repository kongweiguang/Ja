// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

/**
 * 显式的扩展组合入口；宿主按代码顺序安装能力，不扫描 classpath，也不隐式加载插件。
 */
public interface AgentExtension {
    /**
     * 返回跨注册过程稳定且唯一的扩展标识，注册表据此拒绝重复安装。
     */
    String id();

    /**
     * 在 Engine 构建前声明本扩展提供的能力；注册表成功采用整组贡献后才会对外可见。
     */
    void register(ExtensionRegistry registry);
}
