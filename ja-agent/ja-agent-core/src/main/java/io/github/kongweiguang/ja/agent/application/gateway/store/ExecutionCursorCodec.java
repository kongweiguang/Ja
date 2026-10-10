// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;

/**
 * 将唯一执行状态游标与具体 JSON 库隔离，供 Store-backed Loop 在恢复边界严格解码。
 */
public interface ExecutionCursorCodec {
    /**
     * 返回当前写入格式版本，准入及每次执行提交必须共同保存该版本。
     */
    int schemaVersion();

    /**
     * 编码完整状态机游标；凭据、宿主服务及未提交副作用对象不得进入游标。
     */
    JsonObject write(TurnExecutionState state);

    /**
     * 按持久版本解码游标；未知版本必须失败关闭，不能退回旧状态机。
     */
    TurnExecutionState read(int schemaVersion, JsonObject cursor);
}
