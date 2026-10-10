// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.port.out;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.Note;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 笔记持久化端口；写入只能借用 Core Store 当前事务。 */
public interface NoteRepository {
    /**
     * 将笔记、来源关联和执行记录一同写入 Core 当前 JDBC 事务，不得提交或关闭连接。
     */
    Note save(Connection connection, AgentIdentity identity, String callKey, String title, String body,
              List<String> sourceAttachmentIds, String argumentFingerprint) throws SQLException;

    /**
     * 只读核验调用身份与原始规范参数，用于 Core 对未知 Started 结果执行裁决。
     */
    Optional<Note> findForRecovery(AgentIdentity identity, String callKey);

    /**
     * 只读回收一个冻结 Turn 的所有已提交回执，用于 middleware 投影失败后的状态重建。
     */
    List<Note> listForTurn(AgentIdentity identity);

    /**
     * 按完整服务端 owner 和 Thread 返回有界结果，不能仅以客户端 threadId 隔离数据。
     */
    List<Note> list(String tenantId, String userId, String threadId);
}
