// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

/**
 * 由原生 Adapter Factory 创建并由组合根持有的生命周期感知模型端口。
 */
public interface ModelAdapter extends ModelPort, AutoCloseable {
    /**
     * Runtime 确定性关闭时取消在途传输并释放 Executor 资源。
     */
    @Override
    void close();
}
