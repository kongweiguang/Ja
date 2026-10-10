// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.identity;

/**
 * HTTP adapter 获取服务端当前身份的替换点；异步执行必须使用准入时冻结的 AgentIdentity。
 */
public interface CurrentActorProvider {
    /** 在请求边界解析一次身份，调用方应将返回值显式传入业务服务。 */
    CurrentActor currentActor();
}
