// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

/**
 * Task admission 的短生命周期注册句柄；关闭时只释放本次请求的临时绑定。
 */
@FunctionalInterface
public interface TaskAdmissionRegistration extends AutoCloseable {
    /**
     * 幂等释放 admission 绑定，不让协调器暴露 Conversation 注册表实现。
     */
    @Override
    void close();
}
